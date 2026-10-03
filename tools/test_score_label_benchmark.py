#!/usr/bin/env python3

import unittest

from tools.score_label_benchmark import canonical_gtin, contains_term, exact


class ScoreLabelBenchmarkTest(unittest.TestCase):
    def test_product_terms_match_whole_tokens(self):
        self.assertTrue(contains_term("1.75mm HIPS filament", "HIPS"))
        self.assertTrue(contains_term("Fire-Engine Red", "fire engine"))
        self.assertFalse(contains_term("chips filament", "HIPS"))
        self.assertFalse(contains_term("player", "PLA"))

    def test_gtin_forms_compare_canonically(self):
        self.assertEqual("00887503120752", canonical_gtin("887503120752"))
        self.assertTrue(exact("0887503120752", "887503120752", "gtin"))


if __name__ == "__main__":
    unittest.main()
