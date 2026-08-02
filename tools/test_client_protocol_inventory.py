import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from client_protocol_inventory import (
    build_inventory,
    parse_command_constants,
    scan_client_sources,
)


class ClientProtocolInventoryTests(unittest.TestCase):
    def test_constants_merge_duplicate_numeric_ids_without_losing_names(self):
        constants = parse_command_constants(
            """
            public const int FIRST = 7;
            public const int SECOND = 7;
            public const int THIRD = 8;
            """
        )

        self.assertEqual({7: ["FIRST", "SECOND"], 8: ["THIRD"]}, constants)

    def test_scan_classifies_literal_send_and_observer_sites(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "Game.UI" / "Example.cs"
            source.parent.mkdir()
            source.write_text(
                """
                public class Example {
                    void Go() {
                        NetManager.GetInstance().Send(41, new object[0]);
                        NetManager.GetInstance().Send<object[]>(42, new object[0]);
                        NetManager.GetInstance().Send<List<object>>(44, new List<object>());
                        NetManager.GetInstance().Send<List<List<int>>>(45, new List<List<int>>());
                        NetManager.GetInstance().Send<Dictionary<string, object>>(46, new Dictionary<string, object>());
                        NetManager.GetInstance().Send<object[]>(dynamicCmd, new object[0]);
                        NetManager.GetInstance().Send<List<object>>(nestedDynamicCmd, new List<object>());
                        NetManager.GetInstance().SendRawObject(47, new object());
                        NetObserver.GetInstance().AddObserver(43, OnPacket, 0);
                        NetManager.GetInstance().Send<object[]>
                            (48, new object[0]);
                        NetManager.GetInstance().Send<List<object>>(49, new List<object>()); NetManager.GetInstance().Send<List<List<int>>>(50, new List<List<int>>());
                    }
                }
                """,
                encoding="utf-8",
            )

            found = scan_client_sources(
                root,
                {
                    41: ["PLAIN_REQUEST"],
                    42: ["SIMPLE_GENERIC_REQUEST"],
                    43: ["PUSH"],
                    44: ["NESTED_GENERIC_REQUEST"],
                    45: ["DEEP_NESTED_GENERIC_REQUEST"],
                    46: ["DICTIONARY_REQUEST"],
                    47: ["RAW_REQUEST"],
                    48: ["MULTILINE_REQUEST"],
                    49: ["FIRST_SAME_LINE_REQUEST"],
                    50: ["SECOND_SAME_LINE_REQUEST"],
                },
            )

        expected_requests = {
            41: ["Game.UI/Example.cs:4"],
            42: ["Game.UI/Example.cs:5"],
            44: ["Game.UI/Example.cs:6"],
            45: ["Game.UI/Example.cs:7"],
            46: ["Game.UI/Example.cs:8"],
            47: ["Game.UI/Example.cs:11"],
            49: ["Game.UI/Example.cs:15"],
            50: ["Game.UI/Example.cs:15"],
        }
        for command_id, sources in expected_requests.items():
            with self.subTest(command_id=command_id):
                self.assertEqual(
                    sources,
                    found.get(command_id, {}).get("requestSources", []),
                )
        self.assertEqual(["Game.UI/Example.cs:12"], found[43]["receiveSources"])
        self.assertNotIn(48, found)
        self.assertEqual(
            ["Game.UI/Example.cs:10", "Game.UI/Example.cs:9"],
            found["unresolvedRequestSources"],
        )

    def test_inventory_contains_constants_sources_and_capture_counts_in_id_order(self):
        discovered = {
            42: {"requestSources": ["A.cs:1"], "receiveSources": []},
            43: {"requestSources": [], "receiveSources": ["B.cs:2"]},
            "unresolvedRequestSources": ["C.cs:3"],
        }
        inventory = build_inventory(
            constants={42: ["REQUEST"], 43: ["PUSH"]},
            discovered=discovered,
            capture_index={"42": {"send": 2, "recv": 1}},
        )

        self.assertEqual("9.2.2", inventory["clientVersion"])
        self.assertEqual([42, 43], [row["id"] for row in inventory["commands"]])
        self.assertEqual(2, inventory["commands"][0]["captureSendCount"])
        self.assertEqual(1, inventory["commands"][0]["captureReceiveCount"])
        self.assertEqual(["C.cs:3"], inventory["unresolvedRequestSources"])
        self.assertEqual(
            json.dumps(inventory, ensure_ascii=False, sort_keys=True),
            json.dumps(inventory, ensure_ascii=False, sort_keys=True),
        )


if __name__ == "__main__":
    unittest.main()
