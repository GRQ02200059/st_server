#!/usr/bin/env python3
"""Validate versioned command probe plans and build safe probe batches."""

import argparse
import json
from pathlib import Path


ALLOWED_CLASSIFICATIONS = frozenset(
    {
        "READ_ONLY_STATIC",
        "READ_ONLY_CONTEXTUAL",
        "MUTATING",
        "SESSION_CONTROL",
        "SERVER_PUSH",
        "UNRESOLVED",
    }
)


class ProbePlanError(ValueError):
    """Raised when a probe manifest does not match its inventory evidence."""


def _inventory_by_id(inventory, label):
    if not isinstance(inventory, dict):
        raise ProbePlanError(f"{label} inventory must be an object")
    commands = inventory.get("commands")
    if not isinstance(commands, list):
        raise ProbePlanError(f"{label} inventory commands must be a list")

    by_id = {}
    for command in commands:
        if not isinstance(command, dict):
            raise ProbePlanError(f"{label} inventory command must be an object")
        command_id = command.get("id")
        if not isinstance(command_id, int) or isinstance(command_id, bool):
            raise ProbePlanError(
                f"{label} inventory command id must be an integer",
            )
        if command_id in by_id:
            raise ProbePlanError(
                f"{label} inventory has duplicate command id {command_id}",
            )
        by_id[command_id] = command
    return by_id


def _require_non_empty_string_list(command_id, row, field):
    value = row.get(field)
    if (
        not isinstance(value, list)
        or not value
        or any(not isinstance(item, str) or not item.strip() for item in value)
    ):
        raise ProbePlanError(
            f"command {command_id} {field} must be a non-empty string list",
        )


def validate_probe_plan(manifest, baseline_inventory, current_inventory):
    """Validate manifest provenance and return rows sorted by command ID."""
    if not isinstance(manifest, dict):
        raise ProbePlanError("probe manifest must be an object")

    baseline_by_id = _inventory_by_id(
        baseline_inventory,
        "baseline",
    )
    current_by_id = _inventory_by_id(current_inventory, "current")
    baseline_version = baseline_inventory.get("clientVersion")
    current_version = current_inventory.get("clientVersion")
    if manifest.get("baselineVersion") != baseline_version:
        raise ProbePlanError(
            "probe baseline version does not match baseline inventory",
        )
    if manifest.get("clientVersion") != current_version:
        raise ProbePlanError(
            "probe client version does not match current inventory",
        )

    expected_ids = set(current_by_id) - set(baseline_by_id)

    commands = manifest.get("commands")
    if not isinstance(commands, list):
        raise ProbePlanError("probe manifest commands must be a list")

    manifest_by_id = {}
    for row in commands:
        if not isinstance(row, dict):
            raise ProbePlanError("probe manifest command must be an object")
        command_id = row.get("id")
        if not isinstance(command_id, int) or isinstance(command_id, bool):
            raise ProbePlanError("probe manifest command id must be an integer")
        if command_id in manifest_by_id:
            raise ProbePlanError(
                f"probe manifest has duplicate command id {command_id}",
            )
        manifest_by_id[command_id] = row

    actual_ids = set(manifest_by_id)
    if actual_ids != expected_ids:
        missing = sorted(expected_ids - actual_ids)
        extra = sorted(actual_ids - expected_ids)
        raise ProbePlanError(
            f"probe manifest delta mismatch: missing={missing} extra={extra}",
        )

    validated = []
    for command_id in sorted(actual_ids):
        row = manifest_by_id[command_id]
        expected_names = current_by_id[command_id].get("names")
        if row.get("names") != expected_names:
            raise ProbePlanError(
                f"command {command_id} names do not match current inventory",
            )

        classification = row.get("classification")
        if classification not in ALLOWED_CLASSIFICATIONS:
            raise ProbePlanError(
                f"command {command_id} has invalid classification",
            )
        _require_non_empty_string_list(command_id, row, "requestShapes")
        _require_non_empty_string_list(command_id, row, "evidence")

        reason = row.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            raise ProbePlanError(
                f"command {command_id} reason must be a non-empty string",
            )

        auto_probe = row.get("autoProbe")
        if not isinstance(auto_probe, bool):
            raise ProbePlanError(
                f"command {command_id} autoProbe must be a boolean",
            )

        has_payload = "probePayload" in row
        if classification == "READ_ONLY_STATIC" and not has_payload:
            raise ProbePlanError(
                f"command {command_id} static query has no probePayload",
            )
        if classification != "READ_ONLY_STATIC" and has_payload:
            raise ProbePlanError(
                f"command {command_id} non-static row has probePayload",
            )
        if auto_probe and classification != "READ_ONLY_STATIC":
            raise ProbePlanError(
                f"command {command_id} unsafe classification cannot auto-probe",
            )

        validated.append(row)
    return validated


def build_auto_probe_batch(validated_commands):
    """Return capture_send.py rows for explicitly approved static queries."""
    return [
        {
            "cmd": row["id"],
            "payload": row["probePayload"],
        }
        for row in validated_commands
        if row["autoProbe"]
    ]


def load_json(path):
    """Load one UTF-8 JSON object from disk."""
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=(
            "Validate a versioned command probe plan and export safe probes."
        ),
    )
    parser.add_argument(
        "--baseline-inventory",
        type=Path,
        required=True,
    )
    parser.add_argument(
        "--current-inventory",
        type=Path,
        required=True,
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)

    rows = validate_probe_plan(
        load_json(args.manifest),
        load_json(args.baseline_inventory),
        load_json(args.current_inventory),
    )
    batch = build_auto_probe_batch(rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(
            batch,
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(
        f"validated={len(rows)} "
        f"auto_probes={len(batch)} "
        f"output={args.output}",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
