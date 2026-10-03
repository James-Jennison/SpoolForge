import io
import json
from pathlib import Path
import sys
import unittest
import urllib.parse

sys.path.insert(0, str(Path(__file__).resolve().parent))
from amazon_catalog import gtin_variants, lookup, search_terms, valid_gtin


class FakeResponse:
    def __init__(self, value): self.value = value
    def __enter__(self): return io.BytesIO(json.dumps(self.value).encode())
    def __exit__(self, *_): return False


class AmazonCatalogTest(unittest.TestCase):
    def test_gtin_validation(self):
        self.assertTrue(valid_gtin("6938936717461"))
        self.assertTrue(valid_gtin("887503120752"))
        self.assertFalse(valid_gtin("6938936717462"))
        self.assertFalse(valid_gtin("X004QSH4ZH"))
        self.assertEqual(["6938936717461", "06938936717461"], gtin_variants("06938936717461"))
        self.assertEqual(["887503120752", "0887503120752", "00887503120752"], gtin_variants("00887503120752"))

    def test_exact_gtin_returns_review_candidate_without_exposing_key(self):
        seen = {}
        def opener(request, timeout):
            seen.update(urllib.parse.parse_qsl(urllib.parse.urlsplit(request.full_url).query))
            self.assertEqual(45, timeout)
            return FakeResponse({"product": {"asin": "B012345678", "title": "Panchroma PLA Starlight Purple-Red", "brand": "Polymaker", "main_image": {"link": "https://example.invalid/image.jpg"}}})
        result = lookup({"gtin": "6938936717461"}, "secret-for-test", opener, "2026-09-07T00:00:00Z")
        self.assertEqual("product", seen["type"])
        self.assertEqual("6938936717461", seen["gtin"])
        self.assertEqual("gtin", result["query_kind"])
        self.assertEqual("B012345678", result["candidates"][0]["asin"])
        self.assertNotIn("secret-for-test", json.dumps(result))

    def test_failed_gtin_mapping_searches_the_printed_code(self):
        requests = []
        responses = [
            {"request_info": {"success": False}},
            {"search_results": [{"asin": "B0ABCDEF12", "title": "ANYCUBIC PLA Magenta 1.75mm 1kg", "image": "https://example.invalid/a.jpg"}]},
        ]
        def opener(request, timeout):
            requests.append(dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(request.full_url).query)))
            return FakeResponse(responses.pop(0))
        result = lookup({"gtin":"06938936717461", "brand":"ANYCUBIC", "material":"PLA", "color":"Magenta", "diameter_mm":1.75, "net_weight_g":1000, "sku":"AHPLMG-107", "other_codes":[{"value":"X004LDC21N"}]}, "key", opener, "now")
        self.assertEqual(["product", "search"], [item["type"] for item in requests])
        self.assertEqual("6938936717461", requests[1]["search_term"])
        self.assertEqual("gtin_keyword", result["query_kind"])
        self.assertEqual(1, len(result["candidates"]))

    def test_without_gtin_uses_bounded_label_terms_and_codes(self):
        seen = {}
        def opener(request, timeout):
            seen.update(urllib.parse.parse_qsl(urllib.parse.urlsplit(request.full_url).query))
            return FakeResponse({"search_results": []})
        result = lookup({"brand":"ANYCUBIC", "material":"PLA", "color":"Magenta", "diameter_mm":1.75, "net_weight_g":1000, "sku":"AHPLMG-107", "other_codes":[{"value":"X004LDC21N"}]}, "key", opener, "now")
        self.assertEqual("search", seen["type"])
        self.assertIn("AHPLMG-107", seen["search_term"])
        self.assertIn("X004LDC21N", seen["search_term"])
        self.assertEqual("keywords", result["query_kind"])

    def test_empty_evidence_does_not_call_provider(self):
        result = lookup({}, "key", lambda *_: self.fail("provider called"), "now")
        self.assertEqual([], result["candidates"])
        self.assertEqual("none", result["query_kind"])


if __name__ == "__main__": unittest.main()
