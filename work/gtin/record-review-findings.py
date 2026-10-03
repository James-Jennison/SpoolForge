import json, subprocess
from pathlib import Path

base = Path("work/gtin")
sets = [
    ("import", "review-cycle-2ca7f46e3193", "import-deepseek.json"),
    ("android", "review-cycle-2cba3cb75876", "android-deepseek.json"),
    ("ui", "review-cycle-da0678d34a11", "ui-deepseek.json"),
]
decisions = {
    ("import", 1): ("CONFIRMED", "Community snapshot currently has no dual-scope row, but the identity collision was real for a valid future row. Community package identity now includes scope and the oracle asserts spool and refill identities differ.", "tools/build_gtin_index.py; tools/verify_gtin_index.py; work/gtin/bundle-verification-final.log"),
    ("import", 2): ("CONFIRMED", "Community record pointers now retain the spoolmandb subdirectory and the independent oracle verifies every Community pointer prefix.", "tools/build_gtin_index.py; tools/verify_gtin_index.py; work/gtin/bundle-verification-final.log"),
    ("android", 1): ("CONFIRMED", "CONFIRMED WITH NARROWER SCOPE: full hashing is measurable work, but actual target p95 is 95.45 ms and no Owner latency gate is exceeded. Retaining content verification protects the offline index; slower-device performance remains observable rather than weakening integrity.", "work/gtin/device-gtin-acceptance-final.json"),
    ("android", 2): ("CONFIRMED", "CONFIRMED WITH NARROWER SCOPE: immutable barcodeEvidence already retained original values and origins while current field authorship became Edited locally. The dialog now exposes both layers separately and regression coverage verifies the original origin survives.", "app/src/main/kotlin/net/jamesjennison/spoolio/MainActivity.kt; app/src/test/kotlin/net/jamesjennison/spoolio/GtinIndexTest.kt; work/gtin/camera-zxing-build.log"),
    ("android", 3): ("REJECTED", "DISPUTED: the app-private sidecar and temporary file share one Android/Linux directory, where rename replaces the destination atomically. Both host regression and actual Razr instrumentation now corrupt, replace, validate the SQLite header, and recover known results; delete-first fallback would reduce crash safety.", "app/src/test/kotlin/net/jamesjennison/spoolio/GtinIndexTest.kt; app/src/androidTest/kotlin/net/jamesjennison/spoolio/DeviceGtinAcceptanceTest.kt; work/gtin/device-instrumentation-final.log"),
    ("ui", 1): ("CONFIRMED", "The test claim was too weak. It now creates a fresh index instance, verifies known candidates after corruption, and checks the SQLite header and size; Razr instrumentation independently repeats the repair.", "app/src/test/kotlin/net/jamesjennison/spoolio/GtinIndexTest.kt; work/gtin/device-instrumentation-final.log"),
    ("ui", 2): ("CONFIRMED", "All optional provenance objects, arrays, and displayed keys now use safe opt access with Unavailable fallbacks, so older or partial stored evidence does not crash composition.", "app/src/main/kotlin/net/jamesjennison/spoolio/MainActivity.kt; work/gtin/camera-zxing-build.log"),
}

def normalized(raw):
    return {str(k).lower().replace("_", " "): v for k, v in raw.items()}

already_recorded = {
    "spoolio-gtin-import-deepseek-1", "spoolio-gtin-import-deepseek-2",
    "spoolio-gtin-android-deepseek-1", "spoolio-gtin-android-deepseek-2",
    "spoolio-gtin-android-deepseek-3",
}

for phase, cycle, filename in sets:
    envelope = json.loads((base / filename).read_text())
    response = envelope["response"]["response"]
    findings = response.get("findings", [])
    for number, original in enumerate(findings, 1):
        f = normalized(original)
        confidence = str(f.get("confidence", "MEDIUM")).upper()
        if confidence.replace(".", "", 1).isdigit():
            confidence = "HIGH" if float(confidence) >= .8 else "MEDIUM" if float(confidence) >= .6 else "LOW"
        finding_id = f"spoolio-gtin-{phase}-deepseek-{number}"
        if finding_id in already_recorded: continue
        finding = {
            "finding_id": finding_id,
            "change_id": "spoolio-gtin-20260907",
            "cycle_id": cycle,
            "reviewer": "deepseek",
            "severity": str(f["severity"]).upper(),
            "category": f["area"],
            "summary": f["finding"],
            "claim": f["consequence"],
            "evidence": {"source": f["evidence"], "response_sha256": envelope["response_sha256"]},
            "affected_components": ["app", "core", "tools"],
            "suggested_remediation": f["required correction"],
            "suggested_validation": f["acceptance test"],
            "reviewer_confidence": confidence,
        }
        disposition, reason, evidence = decisions[(phase, number)]
        decision = {"finding_id": finding_id, "disposition": disposition, "reason": reason, "evidence_reference": evidence, "resolved": True}
        for suffix, payload, command in [("finding", finding, "finding"), ("disposition", decision, "disposition-finding")]:
            path = base / f"{finding_id}-{suffix}.json"
            path.write_text(json.dumps(payload, indent=2) + "\n")
            result = subprocess.run(["reviewer", command, str(path)], text=True, capture_output=True)
            if result.returncode: raise SystemExit(result.stdout + result.stderr)
        print(finding_id, disposition)
