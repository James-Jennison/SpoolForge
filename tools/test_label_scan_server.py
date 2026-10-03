import io
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from label_scan_server import ResultCache, enrich_missing_mass, extract_label, image_request_id, service_schema


class FakeResponse:
    def __init__(self, value): self.value = value
    def __enter__(self): return io.BytesIO(json.dumps(self.value).encode())
    def __exit__(self, *_): return False


class LabelScanServerTest(unittest.TestCase):
    def test_schema_requires_color_hex(self):
        value = service_schema()
        self.assertIn("color_hex", value["required"])
        self.assertEqual("^[0-9A-Fa-f]{6}$", value["properties"]["color_hex"]["properties"]["value"]["anyOf"][0]["pattern"])

    def test_returns_sanitized_fields_and_does_not_expose_key(self):
        extracted = {name: {"value": None, "confidence": 1, "evidence": None, "basis": "absent"} for name in service_schema()["properties"] if name not in {"other_codes", "needs_user_review"}}
        extracted["label_brand"] = {"value": "ACME", "confidence": .9, "evidence": "ACME", "basis": "printed"}
        extracted["other_codes"] = []
        extracted["needs_user_review"] = ["Confirm color"]
        raw = {"model": "gpt-5.6-terra", "output": [{"content": [{"type": "output_text", "text": json.dumps(extracted)}]}]}
        seen = {}
        def opener(request, timeout):
            seen["authorization"] = request.headers["Authorization"]
            seen["body"] = json.loads(request.data)
            return FakeResponse(raw)
        result = extract_label([("profile_label", "image/jpeg", "YWJj")], [], "secret-for-test", opener)
        self.assertEqual("ACME", result["fields"]["label_brand"]["value"])
        self.assertNotIn("secret-for-test", json.dumps(result))
        self.assertEqual("Bearer secret-for-test", seen["authorization"])
        self.assertFalse(seen["body"]["store"])
        content = seen["body"]["input"][0]["content"]
        self.assertEqual("Photo role: profile_label", content[1]["text"])

    def test_multi_photo_request_id_is_order_and_role_bound(self):
        one = image_request_id([("profile_label", "image/jpeg", b"a"), ("package_identifiers", "image/jpeg", b"b")])
        reordered = image_request_id([("profile_label", "image/jpeg", b"b"), ("package_identifiers", "image/jpeg", b"a")])
        renamed = image_request_id([("profile_label", "image/jpeg", b"a"), ("spool_color", "image/jpeg", b"b")])
        self.assertNotEqual(one, reordered)
        self.assertNotEqual(one, renamed)

    def test_multi_photo_request_id_is_decoded_code_bound(self):
        images = [("profile_label", "image/jpeg", b"a")]
        first = image_request_id(images, [{"format":"UPC_A", "value":"027680274484", "kind":"GTIN", "photo_role":"profile_label"}])
        second = image_request_id(images, [{"format":"QR_CODE", "value":"profile", "kind":"QR payload", "photo_role":"profile_label"}])
        self.assertNotEqual(first, second)

    def test_missing_mass_uses_only_unanimous_strong_catalog_candidates(self):
        import tempfile
        extracted = {
            "label_brand": {"value": "KINGROON 3D"}, "material": {"value": "PETG"}, "color": {"value": "Gray"},
            "diameter_mm": {"value": 1.75}, "nozzle_min_c": {"value": 230}, "nozzle_max_c": {"value": 250},
            "bed_min_c": {"value": 70}, "bed_max_c": {"value": 90}, "net_weight_g": {"value": None}, "needs_user_review": [],
        }
        rows = [
            {"manufacturer":"Kingroon","name":"Kingroon PETG Grey","material":"PETG","diameter":1.75,"extruder_temp_range":[230,250],"bed_temp_range":[70,90],"weight":1000},
            {"manufacturer":"Kingroon","name":"PETG Gray","material":"PETG","diameter":1.75,"extruder_temp_range":[230,250],"bed_temp_range":[70,90],"weight":1000},
        ]
        with tempfile.NamedTemporaryFile("w", suffix=".json") as database:
            json.dump(rows, database); database.flush()
            enrich_missing_mass(extracted, __import__('pathlib').Path(database.name))
        self.assertEqual(1000, extracted["net_weight_g"]["value"])
        self.assertEqual("catalog", extracted["net_weight_g"]["basis"])
        self.assertIn("confirm the package size", extracted["needs_user_review"][0])

    def test_retry_cache_computes_same_image_only_once(self):
        calls = []
        cache = ResultCache(limit=2)
        def compute(): calls.append(1); return {"ok": True}
        self.assertEqual({"ok": True}, cache.get_or_compute("image-hash", compute))
        self.assertEqual({"ok": True}, cache.get_or_compute("image-hash", compute))
        self.assertEqual(1, len(calls))

    def test_cache_reports_hit_without_recomputing(self):
        cache = ResultCache()
        first, first_hit = cache.get_or_compute_with_status("image-hash", lambda: {"ok": True})
        second, second_hit = cache.get_or_compute_with_status("image-hash", lambda: self.fail("cache recomputed"))
        self.assertEqual(first, second)
        self.assertFalse(first_hit)
        self.assertTrue(second_hit)


if __name__ == "__main__": unittest.main()
