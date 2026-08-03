import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.client_protocol_inventory import (
    build_inventory,
    load_capture_index,
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

    def test_scan_includes_game_unqualified_calls_and_excludes_noise(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            game_source = root / "Game.UI" / "Panel.cs"
            game_source.parent.mkdir()
            game_source.write_text(
                """class Panel {
    void Go() {
        Send(51, null);
        Send<List<List<int>>>(52, null);
        AddObserver(53, OnPacket);
        Send(NetCommandDef.SYMBOLIC_REQUEST, null);
        AddObserver(NetCommandDef.SYMBOLIC_PUSH, OnPacket);
        Send(dynamicCmd, null);
    }
    protected void Send(int cmd, object data) {}
}
""",
                encoding="utf-8",
            )
            plain_game_source = root / "Game" / "Plain.cs"
            plain_game_source.parent.mkdir()
            plain_game_source.write_text(
                "class Plain {\n"
                "    void Go() {\n"
                "        Send(56, null);\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )
            noise_source = root / "Safaia" / "Noise.cs"
            noise_source.parent.mkdir()
            noise_source.write_text(
                "class Noise {\n"
                "    void Go() {\n"
                "        Send(51, null);\n"
                "        AddObserver(53, OnPacket);\n"
                "    }\n"
                "}\n",
                encoding="utf-8",
            )

            found = scan_client_sources(
                root,
                {
                    51: ["NUMERIC_REQUEST"],
                    52: ["NESTED_REQUEST"],
                    53: ["NUMERIC_PUSH"],
                    54: ["SYMBOLIC_REQUEST"],
                    55: ["SYMBOLIC_PUSH"],
                    56: ["PLAIN_GAME_REQUEST"],
                },
            )

        self.assertEqual(
            ["Game.UI/Panel.cs:3"],
            found[51]["requestSources"],
        )
        self.assertEqual(
            ["Game.UI/Panel.cs:4"],
            found[52]["requestSources"],
        )
        self.assertEqual(
            ["Game.UI/Panel.cs:5"],
            found[53]["receiveSources"],
        )
        self.assertEqual(
            ["Game.UI/Panel.cs:6"],
            found[54]["requestSources"],
        )
        self.assertEqual(
            ["Game.UI/Panel.cs:7"],
            found[55]["receiveSources"],
        )
        self.assertEqual(
            ["Game/Plain.cs:3"],
            found[56]["requestSources"],
        )
        self.assertEqual([], found["unresolvedRequestSources"])

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
            client_version="9.2.2",
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

    def test_inventory_uses_explicit_client_version(self):
        inventory = build_inventory(
            constants={42: ["REQUEST"]},
            discovered={
                42: {"requestSources": ["A.cs:1"], "receiveSources": []},
                "unresolvedRequestSources": [],
            },
            capture_index={},
            client_version="9.2.4",
        )

        self.assertEqual("9.2.4", inventory["clientVersion"])

    def test_missing_capture_index_is_an_empty_version_scoped_capture(self):
        self.assertEqual({}, load_capture_index(None))


if __name__ == "__main__":
    unittest.main()
