import unittest

from build_community_catalog import normalize_gtin


class CommunityCatalogBuilderTest(unittest.TestCase):
    def test_gtin_normalization_accepts_supported_valid_lengths(self):
        self.assertEqual("07340002119380", normalize_gtin("7340002119380"))
        self.assertEqual("00036000291452", normalize_gtin("036000291452"))

    def test_gtin_normalization_rejects_malformed_identifiers(self):
        for value in ("", "12345678", "0000000000000", "X004QSH4ZH", "1234"):
            self.assertIsNone(normalize_gtin(value))


if __name__ == "__main__":
    unittest.main()
