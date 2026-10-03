#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
evidence = ROOT / "work" / "m6"
identification = json.loads((evidence / "device-identification-m6-acceptance.json").read_text())
rotation = json.loads((evidence / "device-rotation-m6-acceptance.json").read_text())

assert identification["model"] == "motorola razr 2023"
assert identification["app_version"] == "0.6.0-m6"
assert identification["photo_count"] == 1
assert identification["local_qr_codes"] >= 1
assert identification["ai_under_one_minute"] is True
assert 0 < identification["ai_label_to_review_ms"] < 60_000
assert identification["manual_obscure_filament"] == "Gizmo Dorks HIPS White"
assert identification["ai_fields"] == {
    "brand": "MARSWORK",
    "material": "PLA",
    "color": "Cyan",
    "transmission_distance": "2.7",
    "diameter_mm": "1.75",
    "weight_g": "1000",
}

assert rotation == {
    "model": "motorola razr 2023",
    "app_version": "0.6.0-m6",
    "landscape_same_activity": True,
    "portrait_same_activity": True,
    "configuration_recreation": False,
}

print("M6 device evidence: PASS")
