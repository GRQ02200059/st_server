#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path

from build_all_season_card_pack_patch import (
    build_merged_tables,
    patch_npk,
    parse_card_extract_table,
    parse_card_prob_table,
    read_npk_entry,
)


class AllSeasonCardPackPatchTest(unittest.TestCase):
    def test_merged_tables_are_valid_memorypack_tables(self) -> None:
        project_root = Path(__file__).resolve().parents[2]
        table_root = (
            project_root
            / "stzb_9.2.2_out_branch_9.1.1776213"
            / "assets/npk_extracted_all/others/res/csharp/data/tcfg"
        )

        with tempfile.TemporaryDirectory() as directory:
            extract_path, prob_path = build_merged_tables(table_root, Path(directory))
            extract_rows = parse_card_extract_table(extract_path.read_bytes())
            prob_values = parse_card_prob_table(prob_path.read_bytes())

        self.assertEqual(274, len(extract_rows))
        self.assertEqual(252, len({value // 1_000_000 for value in prob_values}))
        self.assertIn(2004, {row.ints[0] for row in extract_rows})

    def test_patched_npk_round_trips_both_merged_tables(self) -> None:
        project_root = Path(__file__).resolve().parents[2]
        client_root = project_root / "stzb_9.2.2_out_branch_9.1.1776213"
        table_root = client_root / "assets/npk_extracted_all/others/res/csharp/data/tcfg"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            extract_path, prob_path = build_merged_tables(table_root, output)
            patched_npk = output / "others-all-season-card-packs.npk"
            patch_npk(
                client_root / "assets/others.npk",
                patched_npk,
                extract_path,
                prob_path,
            )

            self.assertEqual(prob_path.read_bytes(), read_npk_entry(patched_npk, 2308))
            self.assertEqual(extract_path.read_bytes(), read_npk_entry(patched_npk, 5390))


if __name__ == "__main__":
    unittest.main()
