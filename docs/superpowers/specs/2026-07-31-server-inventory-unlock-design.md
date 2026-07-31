# 纯服务端库藏全解锁设计

## 目标

让全新的、未安装任何客户端注入补丁的客户端在登录私服后，通过 99991 的
`UserInitTable` 获得完整库藏：

- 所有有效正常武器各一把，满级并带最高可用红色词条。
- 最高 `advance=1` 鸿级词条武器额外 50 把。
- 每个实际存在的普通 `level_type` 词条档位额外 10 把。
- 当前客户端配置中的 111 种 `Tcfg_item` 道具各 5 个。

## 非目标

- 不修改客户端 DLL、运行时内存或 PipeBridge。
- 不实现每一种道具的使用命令和活动逻辑。
- 不将赠送的武器、道具写入玩家 JSON 存档；它们是每次登录重新生成的固定库藏。

## 数据来源

服务端随发行包携带客户端 cfg-5 兼容配置：

- `client-config/tb_cfg_gear.bin`
- `client-config/tb_cfg_gear_feature.bin`
- 新增 `client-config/tb_cfg_item.bin`

`InventoryCatalog` 使用已有的 MemoryPack 读取规则解析三张表。它只接受：

- 武器：`gear_id > 0`、`gear_type > 0`、`is_defective == 0`、`tag == 0`。
- 词条：`skill` 或 `policy` 非空。
- 道具：当前 `tb_cfg_item.bin` 中的全部 111 行。

## 武器规则

### 基础武器

每个有效武器配置生成一条 `Tb_gear`：

- `state=2`，表示已拥有。
- `heroid_u=0`，表示未装备。
- `level=5`（一阶）或 `10`（高阶），与对应 phase 的满级规则一致。
- 词条优先选相同 `gear_type` 的最高等级红词条
  (`advance=0 && level_type=30`)；若该类型没有红词条，使用当前配置中最高等级红词条。

### 鸿级和档位副本

额外副本保持已拥有、满级和未装备：

- 鸿级：从全部 `advance=1` 词条中选最高 `level` 的词条，生成 50 把。
- 普通档位：按 `level_type` 分组，从每组中选最高 `level` 的词条，每组生成 10 把。
- 副本的武器外形优先选择词条相同 `gear_type` 的武器；缺失时使用首个有效武器作为外形兜底。

使用稳定虚拟 UID，避免和真实服务器生成的装备冲突：

- 基础武器：`800000000 + gear_id`
- 鸿级副本：`840100001..840100050`
- 普通档位副本：`841000000 + level_type * 100 + copyIndex`

## 道具规则

每个 `Tcfg_item` 生成一条 `Tb_user_item`：

- `item_id` 为配置 ID。
- `userid` 为当前玩家。
- `repo_type` 取配置值，确保按原生库藏分类显示。
- `item_num=5`。
- `valid_time=0`，永久有效。
- `season_item=0`、`get_time=0`、`last_get_time=0`。
- 主键使用 `1900000000 + rowIndex`，避免与服务端正常道具记录冲突。

活动或服务器专属道具也会显示。点击使用后若对应服务端命令尚未实现，客户端可能收到普通兜底响应；重新登录会恢复为 5 个。

## 登录流程

`UserInitTableBuilder.build()` 在创建其它核心表时调用 `InventoryCatalog`：

1. 加入 `Tb_gear` 全部生成行。
2. 加入 `Tb_user_item` 111 条道具行。
3. 不在 `addEmptyTables()` 中再覆盖这两张表。

所有数据随登录快照同步，因此干净客户端无需任何本地注入即可读取库藏。

## 验证

新增自动化测试覆盖：

- 配置解析能发现有效武器、鸿级词条、红词条和全部普通词条档位。
- 基础武器均为已拥有、满级并带红色 feature。
- 鸿级副本正好 50 条，普通档位每组正好 10 条。
- `Tb_user_item` 正好 111 条，每条数量为 5、永久有效、分类匹配配置。
- 99991 快照包含完整且字段类型正确的 `Tb_gear` 和 `Tb_user_item`。

最终运行相关单测、完整 Gradle 测试和 `installDist`。若现有无关测试基线失败，单独报告失败项与原因。
