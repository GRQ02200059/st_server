#!/usr/bin/env python3
from __future__ import annotations

import argparse
import dataclasses
import json
import struct
import subprocess
import tempfile
from pathlib import Path


@dataclasses.dataclass(frozen=True)
class ExtractRow:
    key: int
    ints: tuple[int, ...]
    bytes_: bytes
    arrays: tuple[object, ...]
    strings: tuple[str | None, ...]


def read_i32(data: bytes, offset: int) -> tuple[int, int]:
    return struct.unpack_from("<i", data, offset)[0], offset + 4


def read_memorypack_string(data: bytes, offset: int) -> tuple[str | None, int]:
    length, offset = read_i32(data, offset)
    if length == -1:
        return None, offset
    if length >= 0:
        size = length * 2
        return data[offset : offset + size].decode("utf-16le"), offset + size
    size = length ^ -1
    _, offset = read_i32(data, offset)
    return data[offset : offset + size].decode("utf-8"), offset + size


def write_memorypack_string(value: str | None) -> bytes:
    if value is None:
        return struct.pack("<i", -1)
    encoded = value.encode("utf-16le")
    return struct.pack("<i", len(value)) + encoded


def read_table_header(data: bytes) -> tuple[list[str | None], list[int], int]:
    string_table_length, offset = read_i32(data, 0)
    string_table_end = offset + string_table_length
    string_count, offset = read_i32(data, offset)
    strings: list[str | None] = []
    for _ in range(max(string_count, 0)):
        value, offset = read_memorypack_string(data, offset)
        strings.append(value)
    if offset != string_table_end or data[offset] != 2:
        raise ValueError("invalid MemoryPack table header")
    offset += 1
    key_count, offset = read_i32(data, offset)
    keys = list(struct.unpack_from(f"<{key_count}i", data, offset))
    offset += key_count * 4
    value_count, offset = read_i32(data, offset)
    if value_count != key_count:
        raise ValueError("MemoryPack key/value count mismatch")
    return strings, keys, offset


def write_table_header(strings: list[str | None], keys: list[int]) -> bytes:
    string_body = struct.pack("<i", len(strings)) + b"".join(
        write_memorypack_string(value) for value in strings
    )
    return (
        struct.pack("<i", len(string_body))
        + string_body
        + b"\x02"
        + struct.pack("<i", len(keys))
        + struct.pack(f"<{len(keys)}i", *keys)
        + struct.pack("<i", len(keys))
    )


def read_int_array(data: bytes, offset: int) -> tuple[tuple[int, ...] | None, int]:
    length, offset = read_i32(data, offset)
    if length == -1:
        return None, offset
    values = tuple(struct.unpack_from(f"<{length}i", data, offset))
    return values, offset + length * 4


def write_int_array(values: tuple[int, ...] | None) -> bytes:
    if values is None:
        return struct.pack("<i", -1)
    return struct.pack("<i", len(values)) + struct.pack(f"<{len(values)}i", *values)


def read_nested_int_array(data: bytes, offset: int) -> tuple[tuple[tuple[int, ...] | None, ...] | None, int]:
    length, offset = read_i32(data, offset)
    if length == -1:
        return None, offset
    values = []
    for _ in range(length):
        value, offset = read_int_array(data, offset)
        values.append(value)
    return tuple(values), offset


def write_nested_int_array(values: tuple[tuple[int, ...] | None, ...] | None) -> bytes:
    if values is None:
        return struct.pack("<i", -1)
    return struct.pack("<i", len(values)) + b"".join(write_int_array(value) for value in values)


def parse_card_extract_table(data: bytes) -> list[ExtractRow]:
    string_table, keys, offset = read_table_header(data)
    rows = []
    for key in keys:
        if data[offset] != 62:
            raise ValueError("invalid Tcfg_card_extract object header")
        offset += 1
        ints = struct.unpack_from("<27i", data, offset)
        offset += 27 * 4
        bytes_ = data[offset : offset + 17]
        offset += 17
        first_array, offset = read_int_array(data, offset)
        arrays: list[object] = [first_array]
        for _ in range(3):
            value, offset = read_nested_int_array(data, offset)
            arrays.append(value)
        string_indices = struct.unpack_from("<14i", data, offset)
        offset += 14 * 4
        strings = tuple(None if index == -1 else string_table[index] for index in string_indices)
        rows.append(ExtractRow(key, ints, bytes_, tuple(arrays), strings))
    if offset != len(data):
        raise ValueError("trailing bytes in Tcfg_card_extract")
    return rows


def encode_card_extract_table(rows: list[ExtractRow]) -> bytes:
    strings: list[str | None] = []
    string_indices: dict[str | None, int] = {}
    for row in rows:
        for value in row.strings:
            if value is not None and value not in string_indices:
                string_indices[value] = len(strings)
                strings.append(value)

    values = []
    for row in rows:
        body = [b"\x3e", struct.pack("<27i", *row.ints), row.bytes_]
        body.append(write_int_array(row.arrays[0]))
        body.extend(write_nested_int_array(value) for value in row.arrays[1:])
        body.append(
            struct.pack(
                "<14i",
                *[-1 if value is None else string_indices[value] for value in row.strings],
            )
        )
        values.append(b"".join(body))
    keys = [row.key for row in rows]
    return write_table_header(strings, keys) + b"".join(values)


def parse_card_prob_table(data: bytes) -> list[int]:
    _, keys, offset = read_table_header(data)
    values = []
    for _ in keys:
        if data[offset] != 1:
            raise ValueError("invalid Tcfg_card_prob object header")
        offset += 1
        value, offset = read_i32(data, offset)
        values.append(value)
    if offset != len(data):
        raise ValueError("trailing bytes in Tcfg_card_prob")
    return values


def encode_card_prob_table(values: list[int]) -> bytes:
    return (
        write_table_header([], values)
        + b"".join(b"\x01" + struct.pack("<i", value) for value in values)
    )


def season_sort_key(path: Path) -> tuple[bool, int]:
    suffix = path.stem.removeprefix("tb_cfg_card_extract").removeprefix("_")
    return path.name != "tb_cfg_card_extract.bin", int(suffix or 0)


def build_merged_tables(table_root: Path, output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    rows_by_pack: dict[int, ExtractRow] = {}
    probability_values: dict[int, None] = {}

    for extract_path in sorted(
        (table_root / "default").glob("tb_cfg_card_extract*.bin"),
        key=season_sort_key,
    ):
        suffix = extract_path.stem.removeprefix("tb_cfg_card_extract")
        prob_path = table_root / f"tb_cfg_card_prob{suffix}.bin"
        for row in parse_card_extract_table(extract_path.read_bytes()):
            rows_by_pack.setdefault(row.ints[0], row)
        if prob_path.exists():
            for value in parse_card_prob_table(prob_path.read_bytes()):
                probability_values.setdefault(value, None)

    rows = sorted(rows_by_pack.values(), key=lambda row: (row.ints[16], row.ints[0]))
    values = list(probability_values)
    extract_output = output_dir / "tb_cfg_card_extract.bin"
    prob_output = output_dir / "tb_cfg_card_prob.bin"
    extract_output.write_bytes(encode_card_extract_table(rows))
    prob_output.write_bytes(encode_card_prob_table(values))
    return extract_output, prob_output


def patch_npk(
    source_npk: Path,
    output_npk: Path,
    extract_table: Path,
    prob_table: Path,
) -> None:
    data = source_npk.read_bytes()
    if data[:4] != b"NXPK":
        raise ValueError("not an NXPK package")
    entry_count = struct.unpack_from("<I", data, 4)[0]
    index_offset = struct.unpack_from("<I", data, 20)[0]
    replacements = {
        2308: prob_table,
        5390: extract_table,
    }

    with tempfile.TemporaryDirectory(prefix="stzb-card-pack-zstd-") as directory:
        compressed: dict[int, bytes] = {}
        for index, table in replacements.items():
            output = Path(directory) / f"{index}.zst"
            subprocess.run(
                ["zstd", "-q", "-f", "-o", str(output), str(table)],
                check=True,
            )
            compressed[index] = output.read_bytes()

    payload = bytearray(data[:24])
    entries = []
    for index in range(entry_count):
        entry_offset = index_offset + index * 28
        entry = bytearray(data[entry_offset : entry_offset + 28])
        sign, source_offset, compressed_length, original_length = struct.unpack_from(
            "<IIII", entry, 0
        )
        if index in compressed:
            body = compressed[index]
            expected_sign = 0x2BBEF4EE if index == 2308 else 0x679EF13A
            if sign != expected_sign:
                raise ValueError(f"unexpected target entry sign at {index}")
            original_length = replacements[index].stat().st_size
            struct.pack_into("<Q", entry, 16, 0)
            struct.pack_into("<H", entry, 24, 3)
        else:
            body = data[source_offset : source_offset + compressed_length]
        while len(payload) % 4:
            payload.append(0)
        target_offset = len(payload)
        payload.extend(body)
        struct.pack_into("<III", entry, 4, target_offset, len(body), original_length)
        entries.append(bytes(entry))

    while len(payload) % 4:
        payload.append(0)
    new_index_offset = len(payload)
    payload.extend(b"".join(entries))
    struct.pack_into("<I", payload, 20, new_index_offset)
    output_npk.parent.mkdir(parents=True, exist_ok=True)
    output_npk.write_bytes(payload)


def read_npk_entry(npk: Path, index: int) -> bytes:
    data = npk.read_bytes()
    entry_count = struct.unpack_from("<I", data, 4)[0]
    index_offset = struct.unpack_from("<I", data, 20)[0]
    if not 0 <= index < entry_count:
        raise IndexError(index)
    entry_offset = index_offset + index * 28
    source_offset, compressed_length, original_length = struct.unpack_from(
        "<III", data, entry_offset + 4
    )
    compression = struct.unpack_from("<H", data, entry_offset + 24)[0]
    raw = data[source_offset : source_offset + compressed_length]
    if compression == 0:
        return raw
    if compression != 3:
        raise ValueError(f"unsupported NXPK compression: {compression}")
    with tempfile.TemporaryDirectory(prefix="stzb-card-pack-unzstd-") as directory:
        source = Path(directory) / "entry.zst"
        output = Path(directory) / "entry.bin"
        source.write_bytes(raw)
        subprocess.run(
            ["zstd", "-q", "-d", "-f", "-o", str(output), str(source)],
            check=True,
        )
        body = output.read_bytes()
    if len(body) != original_length:
        raise ValueError("NXPK decompressed length mismatch")
    return body


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--client-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    client_root = args.client_root.resolve()
    table_root = client_root / "assets/npk_extracted_all/others/res/csharp/data/tcfg"
    tables_dir = args.output.resolve().parent / "all-season-card-pack-tables"
    extract_table, prob_table = build_merged_tables(table_root, tables_dir)
    patch_npk(
        client_root / "assets/others.npk",
        args.output.resolve(),
        extract_table,
        prob_table,
    )
    report = {
        "source": str(client_root / "assets/others.npk"),
        "output": str(args.output.resolve()),
        "card_extract_rows": len(parse_card_extract_table(extract_table.read_bytes())),
        "card_prob_rows": len(parse_card_prob_table(prob_table.read_bytes())),
    }
    args.output.with_suffix(".json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
