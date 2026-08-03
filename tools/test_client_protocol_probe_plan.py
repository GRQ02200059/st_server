import copy
from collections import Counter
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.client_protocol_probe_plan import (
    ProbePlanError,
    build_auto_probe_batch,
    main,
    validate_probe_plan,
)


_MISSING = object()


def inventory(version, commands):
    return {
        "clientVersion": version,
        "commands": [
            {
                "id": command_id,
                "names": [names] if isinstance(names, str) else names,
            }
            for command_id, names in commands
        ],
    }


def row(
    command_id,
    name,
    classification,
    auto_probe=False,
    probe_payload=_MISSING,
):
    result = {
        "id": command_id,
        "names": [name],
        "classification": classification,
        "requestShapes": ["[]"],
        "autoProbe": auto_probe,
        "evidence": ["Example.cs:1"],
        "reason": "test evidence",
    }
    if probe_payload is not _MISSING:
        result["probePayload"] = probe_payload
    return result


class ProbePlanTest(unittest.TestCase):
    def test_cli_exports_validated_batch(self):
        with tempfile.TemporaryDirectory() as raw_root:
            root = Path(raw_root)
            baseline_path = root / "baseline.json"
            current_path = root / "current.json"
            manifest_path = root / "manifest.json"
            output_path = root / "batch.json"
            baseline_path.write_text(
                json.dumps(inventory("9.2.2", [(1, "OLD")])),
                encoding="utf-8",
            )
            current_path.write_text(
                json.dumps(
                    inventory(
                        "9.2.4",
                        [(1, "OLD"), (42, "QUERY")],
                    )
                ),
                encoding="utf-8",
            )
            manifest_path.write_text(
                json.dumps(
                    {
                        "clientVersion": "9.2.4",
                        "baselineVersion": "9.2.2",
                        "commands": [
                            row(
                                42,
                                "QUERY",
                                "READ_ONLY_STATIC",
                                auto_probe=True,
                                probe_payload=None,
                            ),
                        ],
                    }
                ),
                encoding="utf-8",
            )

            exit_code = main(
                [
                    "--baseline-inventory",
                    str(baseline_path),
                    "--current-inventory",
                    str(current_path),
                    "--manifest",
                    str(manifest_path),
                    "--output",
                    str(output_path),
                ]
            )

            output_text = output_path.read_text(encoding="utf-8")
            self.assertEqual(0, exit_code)
            self.assertEqual(
                [{"cmd": 42, "payload": None}],
                json.loads(output_text),
            )
            self.assertTrue(output_text.endswith("\n"))

    def test_real_manifest_covers_added_ids_and_exports_only_safe_literals(self):
        root = Path(__file__).resolve().parent.parent
        baseline = json.loads(
            (
                root
                / "src/main/resources/protocol/"
                "client-9.2.2-command-inventory.json"
            ).read_text(encoding="utf-8")
        )
        current = json.loads(
            (
                root
                / "src/main/resources/protocol/"
                "client-9.2.4-command-inventory.json"
            ).read_text(encoding="utf-8")
        )
        manifest = json.loads(
            (
                root
                / "tools/protocol/"
                "client-9.2.4-added-command-probes.json"
            ).read_text(encoding="utf-8")
        )

        rows = validate_probe_plan(manifest, baseline, current)

        self.assertEqual(63, len(rows))
        self.assertEqual(
            {
                "MUTATING": 38,
                "READ_ONLY_CONTEXTUAL": 6,
                "READ_ONLY_STATIC": 7,
                "SERVER_PUSH": 1,
                "SESSION_CONTROL": 2,
                "UNRESOLVED": 9,
            },
            dict(
                sorted(
                    Counter(
                        row["classification"]
                        for row in rows
                    ).items()
                )
            ),
        )
        self.assertEqual(
            [3_720, 3_913, 4_383, 4_385, 4_386, 6_148, 6_156],
            [
                item["cmd"]
                for item in build_auto_probe_batch(rows)
            ],
        )

    def test_validate_accepts_exact_delta_in_manifest_order(self):
        rows = validate_probe_plan(
            manifest={
                "clientVersion": "9.2.4",
                "baselineVersion": "9.2.2",
                "commands": [
                    row(
                        42,
                        "QUERY",
                        "READ_ONLY_STATIC",
                        auto_probe=True,
                        probe_payload=None,
                    ),
                    row(43, "WRITE", "MUTATING"),
                ],
            },
            baseline_inventory=inventory("9.2.2", [(1, "OLD")]),
            current_inventory=inventory(
                "9.2.4",
                [(1, "OLD"), (42, "QUERY"), (43, "WRITE")],
            ),
        )

        self.assertEqual([42, 43], [item["id"] for item in rows])
        self.assertEqual(
            [{"cmd": 42, "payload": None}],
            build_auto_probe_batch(rows),
        )

    def test_rejects_invalid_manifest_rows(self):
        baseline = inventory("9.2.2", [(1, "OLD")])
        current = inventory("9.2.4", [(1, "OLD"), (42, "QUERY")])
        valid = {
            "clientVersion": "9.2.4",
            "baselineVersion": "9.2.2",
            "commands": [
                row(
                    42,
                    "QUERY",
                    "READ_ONLY_STATIC",
                    auto_probe=True,
                    probe_payload=[],
                ),
            ],
        }

        cases = {}

        def add_case(name, mutate):
            manifest = copy.deepcopy(valid)
            mutate(manifest)
            cases[name] = manifest

        add_case(
            "client version mismatch",
            lambda value: value.update(clientVersion="9.9.9"),
        )
        add_case(
            "baseline version mismatch",
            lambda value: value.update(baselineVersion="9.9.9"),
        )
        add_case(
            "missing delta id",
            lambda value: value.update(commands=[]),
        )
        add_case(
            "extra id",
            lambda value: value["commands"].append(
                row(99, "EXTRA", "MUTATING"),
            ),
        )
        add_case(
            "duplicate id",
            lambda value: value["commands"].append(
                copy.deepcopy(value["commands"][0]),
            ),
        )
        add_case(
            "name mismatch",
            lambda value: value["commands"][0].update(names=["WRONG"]),
        )
        add_case(
            "unknown classification",
            lambda value: value["commands"][0].update(
                classification="MAYBE",
            ),
        )
        add_case(
            "empty request shapes",
            lambda value: value["commands"][0].update(requestShapes=[]),
        )
        add_case(
            "empty evidence",
            lambda value: value["commands"][0].update(evidence=[]),
        )
        add_case(
            "empty reason",
            lambda value: value["commands"][0].update(reason=""),
        )
        add_case(
            "non boolean auto probe",
            lambda value: value["commands"][0].update(autoProbe=1),
        )

        def make_mutating_auto_probe(value):
            command = value["commands"][0]
            command["classification"] = "MUTATING"
            command.pop("probePayload")

        add_case("unsafe automatic row", make_mutating_auto_probe)
        add_case(
            "static row missing payload",
            lambda value: value["commands"][0].pop("probePayload"),
        )
        add_case(
            "payload on non-static row",
            lambda value: value["commands"][0].update(
                classification="MUTATING",
                autoProbe=False,
            ),
        )

        for name, manifest in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(ProbePlanError):
                    validate_probe_plan(manifest, baseline, current)

    def test_rejects_invalid_ids_and_duplicate_inventory_ids(self):
        valid_manifest = {
            "clientVersion": "9.2.4",
            "baselineVersion": "9.2.2",
            "commands": [row(42, "QUERY", "MUTATING")],
        }
        baseline = inventory("9.2.2", [(1, "OLD")])
        current = inventory("9.2.4", [(1, "OLD"), (42, "QUERY")])

        boolean_id = copy.deepcopy(valid_manifest)
        boolean_id["commands"][0]["id"] = True
        with self.assertRaises(ProbePlanError):
            validate_probe_plan(boolean_id, baseline, current)

        duplicate_current = inventory(
            "9.2.4",
            [(1, "OLD"), (42, "QUERY"), (42, "QUERY")],
        )
        with self.assertRaises(ProbePlanError):
            validate_probe_plan(valid_manifest, baseline, duplicate_current)

    def test_rejects_unsorted_manifest_ids(self):
        manifest = {
            "clientVersion": "9.2.4",
            "baselineVersion": "9.2.2",
            "commands": [
                row(43, "WRITE", "MUTATING"),
                row(
                    42,
                    "QUERY",
                    "READ_ONLY_STATIC",
                    probe_payload=[],
                ),
            ],
        }
        baseline = inventory("9.2.2", [(1, "OLD")])
        current = inventory(
            "9.2.4",
            [(1, "OLD"), (42, "QUERY"), (43, "WRITE")],
        )

        with self.assertRaises(ProbePlanError):
            validate_probe_plan(manifest, baseline, current)

    def test_rejects_non_object_inventories_as_probe_plan_errors(self):
        manifest = {
            "clientVersion": "9.2.4",
            "baselineVersion": "9.2.2",
            "commands": [],
        }

        with self.assertRaises(ProbePlanError):
            validate_probe_plan(
                manifest,
                [],
                inventory("9.2.4", []),
            )
        with self.assertRaises(ProbePlanError):
            validate_probe_plan(
                manifest,
                inventory("9.2.2", []),
                [],
            )


if __name__ == "__main__":
    unittest.main()
