# 从 NPK 提取 C# 代码：交给 AI 的执行手册

## 目标与边界

目标是从一个**已获授权分析的 Android APK** 中取得 `assets/assembly.npk`，解出其中的 Mono/.NET DLL，并批量反编译为可搜索的 C# 源码。

本文针对本项目已验证的 NeoX `NXPK` 格式。输出目录应当可复现，建议保留原始 APK、原始 DLL、修复后的 DLL 和反编译 C# 四层产物；不要覆盖原始文件。

不包含修改客户端、绕过校验、重新打包或发布客户端的步骤。

## 预期输入与输出

输入：

```text
client.apk
```

输出：

```text
client_out/
  assets/assembly.npk             # APK 内原始包
  assembly_raw/                   # NXPK 解压出的原始二进制
  assembly_extracted/             # 识别并按程序集名命名的 DLL
  assembly_fixed/                 # 可选，修复元数据后的 DLL
  decompiled/                     # ILSpy 导出的 C#
```

本项目当前版本的对应位置：

```text
/Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/assembly.npk
/Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/assembly_extracted
/Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/assembly_fixed
/Users/bytedance/stzb/stzb_9.2.2_out_branch_9.1.1776213/assets/decompiled
```

当前版本的实测规模：

- `assembly.npk`：约 32 MB，共 36 个条目。
- 命名后的托管程序集：34 个；其余条目是程序集校验/版本元数据。
- 反编译 C# 文件：约 16,745 个。

## 给 AI 的任务指令

将以下文本和 APK 路径一起交给执行 AI：

```text
请在获得授权的前提下，完成 Android APK 内 NeoX assembly.npk 到 C# 源码的离线提取。

要求：
1. 不修改 APK、不重打包、不连接任何线上服务。
2. 保留每一阶段的独立输出目录：APK 解包、NPK 原始条目、命名 DLL、修复 DLL（如需要）、反编译源码。
3. NXPK 目录项按 28 字节解析：
   - 文件头 magic 为 "NXPK"
   - uint32 LE @ 0x04 为条目数量
   - uint32 LE @ 0x14 为索引起点
   - 每条索引：sign:uint32, offset:uint32, compressedLen:uint32,
     originalLen:uint32, 保留 8 字节, compression:uint16 @ entry+24, 保留 2 字节
   - compression=0 为原文；compression=3 为 Zstandard。
4. 先解出所有条目，再识别 PE/Mono 托管程序集；不能假设资源清单包含 DLL 明文路径。
5. 用程序集元数据中的 Assembly Name 作为 DLL 文件名；名称重复时保留 sign 后缀。
6. 优先使用 ILSpy/ilspycmd 批量导出 C#。若发现“metadata token / invalid method body / type load”等反编译错误，
   原始 DLL 不得覆盖；将问题 DLL 复制到 assembly_fixed 后单独修复，并记录具体改动、输入 SHA-256 和输出 SHA-256。
7. 完成后报告：NXPK 条目数、成功解压数、识别出的托管 DLL 数、反编译 C# 文件数、失败文件及原因。
```

## 1. 从 APK 取出 `assembly.npk`

只需要资源文件时，直接解压 APK 最稳妥：

```bash
APK=/absolute/path/client.apk
OUT=/absolute/path/client_out

rm -rf "$OUT"
mkdir -p "$OUT"
unzip -q "$APK" -d "$OUT"
test -f "$OUT/assets/assembly.npk"
file "$OUT/assets/assembly.npk"
```

预期：

```text
.../assets/assembly.npk: data
```

如还需要查看 AndroidManifest 或 resources，可额外使用：

```bash
apktool d -f "$APK" -o "${OUT}_apktool"
```

`apktool` 不是提取 `assembly.npk` 的必要依赖。对本任务，`unzip` 的资源字节更直接。

## 2. 安装离线工具

Python 依赖仅需 Zstandard：

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install zstandard
```

安装 ILSpy 命令行版：

```bash
dotnet tool install --global ilspycmd
ilspycmd --version
```

如果目标环境没有 `dotnet`，也可以使用 ILSpy GUI 手动导出；批量导出时仍建议使用 `ilspycmd`。

## 3. 解压 NXPK 全部条目

不要先依赖文件名。`assembly.npk` 内通常只保存哈希签名，外部资源清单未必列出程序集真实路径。

在工作目录创建 `dump_nxpk.py`：

```python
#!/usr/bin/env python3
import argparse
import json
import struct
from pathlib import Path

import zstandard as zstd


ENTRY_SIZE = 28


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("npk", type=Path)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()

    data = args.npk.read_bytes()
    if data[:4] != b"NXPK":
        raise SystemExit("not an NXPK package")

    count = struct.unpack_from("<I", data, 0x04)[0]
    index_offset = struct.unpack_from("<I", data, 0x14)[0]
    if index_offset + count * ENTRY_SIZE > len(data):
        raise SystemExit("NXPK index exceeds file size")

    args.out.mkdir(parents=True, exist_ok=True)
    decompressor = zstd.ZstdDecompressor()
    report = []

    for index in range(count):
        base = index_offset + index * ENTRY_SIZE
        sign, offset, compressed_len, original_len = struct.unpack_from(
            "<IIII", data, base
        )
        compression = struct.unpack_from("<H", data, base + 24)[0]
        if offset + compressed_len > len(data):
            report.append({"index": index, "sign": f"{sign:08x}", "error": "range"})
            continue

        raw = data[offset:offset + compressed_len]
        try:
            if compression == 0:
                body = raw
            elif compression == 3:
                body = decompressor.decompress(raw, max_output_size=max(original_len, 1))
            else:
                raise ValueError(f"unsupported compression={compression}")
        except Exception as error:
            report.append({
                "index": index,
                "sign": f"{sign:08x}",
                "error": str(error),
            })
            continue

        if len(body) != original_len:
            report.append({
                "index": index,
                "sign": f"{sign:08x}",
                "error": f"length {len(body)} != {original_len}",
            })
            continue

        (args.out / f"{index:05d}_{sign:08x}.bin").write_bytes(body)
        report.append({
            "index": index,
            "sign": f"{sign:08x}",
            "compression": compression,
            "size": len(body),
            "ok": True,
        })

    (args.out / "_nxpk_report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    failed = sum("error" in item for item in report)
    print(f"entries={count} extracted={count - failed} failed={failed}")


if __name__ == "__main__":
    main()
```

执行：

```bash
python3 dump_nxpk.py \
  "$OUT/assets/assembly.npk" \
  "$OUT/assembly_raw"
```

验收：

```bash
find "$OUT/assembly_raw" -name '*.bin' | wc -l
cat "$OUT/assembly_raw/_nxpk_report.json" | head
```

每个成功条目的解压长度必须等于 NXPK 索引中的 `originalLen`。长度不一致通常意味着索引偏移、压缩算法或端序判断有误，不能继续进入反编译。

## 4. 识别并命名托管 DLL

先根据 PE 头筛选候选文件：

```bash
mkdir -p "$OUT/assembly_candidates"
find "$OUT/assembly_raw" -name '*.bin' -print0 |
  while IFS= read -r -d '' file; do
    if file "$file" | grep -q 'Mono/.Net assembly'; then
      cp "$file" "$OUT/assembly_candidates/$(basename "$file").dll"
    fi
  done
```

然后从 .NET Assembly 元数据读取程序集名。最简单的可靠方式是在 .NET 环境执行以下 C# 小工具：

```csharp
// Program.cs
using System.Reflection;

foreach (var path in args)
{
    try
    {
        var name = AssemblyName.GetAssemblyName(path).Name;
        Console.WriteLine($"{path}\t{name}");
    }
    catch
    {
        // 非托管文件或损坏元数据，保持静默并由调用方记录。
    }
}
```

创建并运行：

```bash
mkdir -p "$OUT/assembly_name_reader"
cd "$OUT/assembly_name_reader"
dotnet new console --force
# 用上面的内容替换 Program.cs
dotnet run -- ../assembly_candidates/*.dll
```

将每行的第二列作为最终文件名，例如：

```text
00013_abcd1234.dll    Game.Network
```

应复制为：

```text
assembly_extracted/Game.Network.dll
```

命名规则：

- `AssemblyName.Name` 正常且唯一：`<name>.dll`。
- 同名不同条目：`<name>__<sign>.dll`。
- 元数据无法读取：保留原始 `<index>_<sign>.dll`，记录到失败清单，不要猜测名称。

本项目的成功样例包括：

```text
Game.Network.dll
Game.Data.dll
Game.Data.Tb.dll
Game.Data.GamePlay.dll
Game.UI.GamePlay.dll
Game.Map.dll
Engine.Native.Patch.dll
MonoLoader.dll
```

## 5. 可选：修复反编译器无法读取的程序集

有些 NeoX/Mono 程序集的 PE 或 metadata 表存在非标准填充，表现为：

- ILSpy 无法加载模块。
- 部分类型报 metadata token 无效。
- 批量反编译过程中异常退出。
- 导出的 C# 大面积缺失，而其他 DLL 正常。

处理原则：

1. 原始 DLL 永远保留在 `assembly_extracted/`。
2. 仅将失败 DLL 复制到 `assembly_fixed/` 后操作。
3. 每个修复操作都记录：工具版本、字节偏移、旧值、新值、修复前后 SHA-256。
4. 修复后先用 `file` 和 `AssemblyName.GetAssemblyName` 验收，再重新跑 ILSpy。
5. 不要为了消除反编译错误而删方法体、改 IL 或跳过类型；目标是修复容器/元数据可读性，而非改变逻辑。

建议记录格式：

```text
input:  assembly_extracted/Game.Network.dll
output: assembly_fixed/Game.Network.dll
reason: ILSpy metadata parse failed
change: <具体 PE/metadata 修复说明>
sha256-before: ...
sha256-after:  ...
verify: AssemblyName readable; ilspycmd exit=0
```

如果所有程序集可被 `ilspycmd` 正常处理，可跳过这一层，直接将 `assembly_extracted/` 作为反编译输入。

## 6. 使用 ILSpy 批量导出 C#

先单个 DLL 验证反编译器：

```bash
ilspycmd \
  -p \
  -o "$OUT/decompiled_smoke/Game.Network" \
  "$OUT/assembly_fixed/Game.Network.dll"
```

`-p` 生成项目结构，便于 IDE 打开。若没有 `assembly_fixed/Game.Network.dll`，替换为 `assembly_extracted/Game.Network.dll`。

批量导出时，按 DLL 分目录，避免不同程序集内同名文件互相覆盖：

```bash
INPUT="$OUT/assembly_fixed"
test -d "$INPUT" || INPUT="$OUT/assembly_extracted"

mkdir -p "$OUT/decompiled"
for dll in "$INPUT"/*.dll; do
  name="$(basename "$dll" .dll)"
  echo "decompile: $name"
  ilspycmd -p -o "$OUT/decompiled/$name" "$dll" ||
    echo "$dll" >> "$OUT/decompile_failed.txt"
done
```

批量验收：

```bash
find "$OUT/decompiled" -name '*.cs' | wc -l
test ! -s "$OUT/decompile_failed.txt" && echo "all assemblies decompiled"
rg -n 'class NetManager|class GameConfig|class DbNotify' "$OUT/decompiled"
```

对于本项目，以下源码命中可作为端到端检查点：

```text
Game.Network/Tenth.Network/NetManager.cs
Game.Core/Tenth/GameConfig.cs
Game.Data/Tenth.Data/DbNotify.cs
```

## 7. 本项目已有的按路径提取器

仓库中的 [`tools/extract_npk_paths.py`](file:///Users/bytedance/stzb/tools/extract_npk_paths.py) 用于**已知资源路径**的 NPK 提取：

- 路径签名：MurmurHash3 x86_32。
- seed：`0x9747B28C`。
- 计算前将 `/` 替换为 `\`，并在路径以 `res/` 开头时去掉此前缀。
- 支持原文（`zflag=0`）和 Zstandard（`zflag=3`）。

它适用于 `others.npk` 等有资源清单可映射的包。`assembly.npk` 推荐采用本文第 3 节的“全索引枚举 + PE 元数据命名”，因为程序集路径未必出现在可用清单中。

## 常见失败与排查

| 现象 | 优先检查 |
| --- | --- |
| `not an NXPK package` | 输入是否为 APK 解出的 `assets/assembly.npk`；不要把 APK 或其他 NPK 当作输入。 |
| 所有条目长度不匹配 | 确认索引起点为 `0x14`、目录项为 28 字节、字段按 little-endian 读取。 |
| `unsupported compression` | 记录 `compression` 值和条目 sign；不要把未知格式当作 zstd 解。 |
| 解压成功但没有 DLL | 使用 `file` 检查条目；确认分析对象确实是 `assembly.npk`。 |
| DLL 名称读取失败 | 保留原文件，确认 `MZ`/CLI header；问题可能是 metadata 非标准或 DLL 并非托管程序集。 |
| ILSpy 只导出部分类型 | 先隔离到单个 DLL；比较原始与 fixed 的 SHA-256；检查 stderr 和 `decompile_failed.txt`。 |
| C# 中存在 `Unknown result type` | 这是 IL 反编译信息不足的常见产物，不等于解包失败。保留 ILSpy 输出，并用调用点、字段类型和 IL 交叉验证。 |

## 最终交付清单

执行 AI 完成后必须交付：

1. NPK 格式说明和实际解析参数。
2. 可重复执行的解压脚本。
3. `assembly_raw/_nxpk_report.json`。
4. 命名 DLL 清单，以及每个 DLL 的 SHA-256。
5. 如存在修复，逐个 DLL 的修复记录和前后 SHA-256。
6. `decompiled/` C# 目录与失败清单。
7. 统计数字：索引条目数、解压成功数、托管 DLL 数、C# 文件数、未解决失败项。
