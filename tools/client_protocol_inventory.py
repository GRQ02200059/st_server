#!/usr/bin/env python3
"""Generate a deterministic command inventory from versioned client evidence."""

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Optional


CONST_RE = re.compile(r"public\s+const\s+int\s+([A-Z0-9_]+)\s*=\s*(\d+)\s*;")
SEND_RE = re.compile(
    r"\.Send(?:<[^\r\n]+?>)?[ \t]*\([ \t]*(?:NetCommandDef\.)?"
    r"([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
)
RAW_SEND_RE = re.compile(
    r"SendRawObject\s*\(\s*(?:NetCommandDef\.)?"
    r"([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
)
OBSERVER_RE = re.compile(
    r"\.AddObserver\s*\(\s*(?:NetCommandDef\.)?"
    r"([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
)
UNQUALIFIED_SEND_RE = re.compile(
    r"^[ \t]*Send(?:<[^\r\n]+?>)?[ \t]*\([ \t]*"
    r"(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
    re.MULTILINE,
)
UNQUALIFIED_OBSERVER_RE = re.compile(
    r"^[ \t]*AddObserver[ \t]*\([ \t]*"
    r"(?:NetCommandDef\.)?([A-Za-z_][A-Za-z0-9_]*|\d+)\b",
    re.MULTILINE,
)


def parse_command_constants(text):
    """Return command id to sorted constant-name lists."""
    names = defaultdict(list)
    for name, number in CONST_RE.findall(text):
        names[int(number)].append(name)
    return {
        command_id: sorted(values)
        for command_id, values in sorted(names.items())
    }


def _line_number(text, offset):
    return text.count("\n", 0, offset) + 1


def _source_ref(root, path, text, offset):
    return f"{path.relative_to(root).as_posix()}:{_line_number(text, offset)}"


def _resolve_command(token, names_by_id):
    if token.isdigit():
        return int(token)
    for command_id, names in names_by_id.items():
        if token in names:
            return command_id
    return None


def _is_game_source(root, path):
    top = path.relative_to(root).parts[0]
    return top == "Game" or top.startswith("Game.")


def scan_client_sources(root, names_by_id):
    """Discover literal/symbolic request and receive command sites."""
    root = Path(root)
    requests = defaultdict(set)
    receives = defaultdict(set)
    unresolved_requests = set()

    for path in sorted(root.rglob("*.cs")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern in (SEND_RE, RAW_SEND_RE):
            for match in pattern.finditer(text):
                command_id = _resolve_command(match.group(1), names_by_id)
                source_ref = _source_ref(root, path, text, match.start())
                if command_id is None:
                    unresolved_requests.add(source_ref)
                else:
                    requests[command_id].add(source_ref)
        for match in OBSERVER_RE.finditer(text):
            command_id = _resolve_command(match.group(1), names_by_id)
            if command_id is not None:
                receives[command_id].add(_source_ref(root, path, text, match.start()))
        if _is_game_source(root, path):
            for pattern, target in (
                (UNQUALIFIED_SEND_RE, requests),
                (UNQUALIFIED_OBSERVER_RE, receives),
            ):
                for match in pattern.finditer(text):
                    command_id = _resolve_command(match.group(1), names_by_id)
                    if command_id is None:
                        continue
                    target[command_id].add(
                        _source_ref(root, path, text, match.start()),
                    )

    discovered = {
        command_id: {
            "requestSources": sorted(requests[command_id]),
            "receiveSources": sorted(receives[command_id]),
        }
        for command_id in sorted(set(requests) | set(receives))
    }
    discovered["unresolvedRequestSources"] = sorted(unresolved_requests)
    return discovered


def _capture_ids(capture_index):
    ids = set()
    for command_id in capture_index:
        try:
            ids.add(int(command_id))
        except (TypeError, ValueError):
            continue
    return ids


def load_capture_index(path: Optional[Path]) -> dict:
    if path is None:
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def build_inventory(constants, discovered, capture_index, client_version):
    """Merge constant, source, and same-version capture evidence."""
    all_ids = set(constants)
    all_ids.update(
        command_id
        for command_id in discovered
        if isinstance(command_id, int)
    )
    all_ids.update(_capture_ids(capture_index))

    commands = []
    for command_id in sorted(all_ids):
        sources = discovered.get(command_id, {})
        captures = capture_index.get(
            str(command_id),
            capture_index.get(command_id, {}),
        )
        commands.append(
            {
                "id": command_id,
                "names": constants.get(command_id, []),
                "requestSources": sources.get("requestSources", []),
                "receiveSources": sources.get("receiveSources", []),
                "captureSendCount": int(captures.get("send", 0)),
                "captureReceiveCount": int(captures.get("recv", 0)),
            }
        )

    return {
        "clientVersion": client_version,
        "commands": commands,
        "unresolvedRequestSources": discovered.get(
            "unresolvedRequestSources",
            [],
        ),
    }


def main():
    parser = argparse.ArgumentParser(
        description="Generate the versioned client protocol command inventory.",
    )
    parser.add_argument("--client-version", required=True)
    parser.add_argument("--client-root", type=Path, required=True)
    parser.add_argument("--capture-index", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    constants_file = (
        args.client_root / "Game.Network" / "Tenth.Network" / "NetCommandDef.cs"
    )
    constants = parse_command_constants(
        constants_file.read_text(encoding="utf-8"),
    )
    captures = load_capture_index(args.capture_index)
    inventory = build_inventory(
        constants,
        scan_client_sources(args.client_root, constants),
        captures,
        client_version=args.client_version,
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(
            inventory,
            ensure_ascii=False,
            sort_keys=True,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(
        "commands="
        f"{len(inventory['commands'])} "
        f"unresolved_request_sites="
        f"{len(inventory['unresolvedRequestSources'])} "
        f"output={args.output}",
    )


if __name__ == "__main__":
    main()
