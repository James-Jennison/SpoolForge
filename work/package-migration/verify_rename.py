from pathlib import Path
import json, sqlite3, subprocess

root = Path(__file__).resolve().parents[2]
new_id = "net.jamesjennison.filamajignfc"
old_id = "net.jamesjennison.spoolio"

gradle = (root / "app/build.gradle.kts").read_text()
assert f'namespace = "{new_id}"' in gradle
assert f'applicationId = "{new_id}"' in gradle
strings = (root / "app/src/main/res/values/strings.xml").read_text()
assert ">Filamajig NFC<" in strings
assert ">Tap it. Tag it. Print it.<" in strings
assert ">Filamajig NFC: Spool Tagger<" in strings
assert 'rootProject.name = "Filamajig NFC"' in (root / "settings.gradle.kts").read_text()

active = [root / "app/src", root / "core/src", root / "catalog-tool/src"]
for tree in active:
    for path in tree.rglob("*"):
        if path.is_file() and path.suffix in {".kt", ".xml"}:
            assert old_id not in path.read_text(), path

build_tools = sorted((Path.home() / "Android/Sdk/build-tools").iterdir())[-1]
badging = subprocess.check_output(
    [str(build_tools / "aapt2"), "dump", "badging", str(root / "app/build/outputs/apk/debug/app-debug.apk")],
    text=True,
)
assert f"package: name='{new_id}'" in badging
assert "application-label:'Filamajig NFC'" in badging
# The approved direct label-analysis prototype uses HTTPS from the debug APK.
# Package identity verification must not retain the older offline-only assertion.
assert "android.permission.INTERNET" in badging

audit = root / "work/package-migration/destination-audit"
db = sqlite3.connect(audit / "user.db")
assert db.execute("pragma integrity_check").fetchone()[0] == "ok"
assert db.execute("select count(*) from custom_records").fetchone()[0] == 2
assert db.execute("select count(*) from recents").fetchone()[0] == 4
db.close()
for name in ("nfc-read-diagnostics.xml", "nfc-write-journal.xml"):
    assert (audit / name).read_bytes() == (root / "work/package-migration" / name).read_bytes()

ui = json.loads((root / "work/m1/host-evidence/filamajig-post-instrumentation.json").read_text())
texts = {node.get("text", "") for node in ui}
assert "Filamajig NFC" in texts and "Tap it. Tag it. Print it." in texts
assert "Unresolved history (1)" in texts
instrumentation = (root / "work/package-migration/renamed-device-instrumentation.log").read_text()
assert "OK (1 test)" in instrumentation
device = json.loads((root / "work/package-migration/renamed-device-gtin-acceptance.json").read_text())
assert device["model"] == "motorola razr 2023" and device["candidates"] == 4 and device["corrupt_sidecar_repaired"] is True
print("Filamajig identity, APK boundary, migrated user state, and renamed-device instrumentation: PASS")
