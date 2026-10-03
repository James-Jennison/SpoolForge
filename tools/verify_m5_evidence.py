#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path


root = Path(__file__).resolve().parents[1]
evidence = json.loads((root / "work/open-ecosystem-m5/device-acceptance.json").read_text())
content = json.loads((root / "work/open-ecosystem-m5/data-content-preservation.json").read_text())

assert evidence["milestone"] == "M5 open ecosystem adapters"
assert evidence["app_version"] == "0.5.0-m5"
assert evidence["device"] == {"model": "motorola razr 2023", "transport": "wireless-adb", "android_api": 36}
assert evidence["host"]["python_tests"] == 16
assert evidence["host"]["core_tests"] == 61
assert evidence["host"]["app_tests"] == 57
assert evidence["host"]["lint"] == "PASS"
assert evidence["device_tests"]["tests"] == 6
assert evidence["device_tests"]["failures"] == 0
assert evidence["device_tests"]["external_adapter_test"] == "PASS"
assert evidence["visible_ui"]["home"] == "PASS"
assert content["all_tables_identical"] is True
assert all(table["identical"] for table in content["tables"].values())

app_apk = root / "app/build/outputs/apk/debug/app-debug.apk"
test_apk = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
assert hashlib.sha256(app_apk.read_bytes()).hexdigest() == evidence["installed_apk_sha256"]
assert hashlib.sha256(test_apk.read_bytes()).hexdigest() == evidence["test_apk_sha256"]

print("M5 host, artifact, device adapter, UI, and content-preservation evidence: PASS")
