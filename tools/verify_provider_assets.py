#!/usr/bin/env python3
"""Verify bundled provider artifacts without network access."""

import gzip
import hashlib
import json
import sqlite3
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    manifest = json.loads((ASSETS / "community-catalog-manifest.json").read_text())
    assert manifest["format"] == "filamajig-community-sqlite-v1"
    source = (ROOT / "research/sources/spoolmandb/compiled.json").read_bytes()
    license_body = (ROOT / "research/sources/spoolmandb/LICENSE").read_bytes()
    compressed = (ASSETS / "community-catalog.sqlite.gzip").read_bytes()
    bundled_license = (ASSETS / "COMMUNITY-LICENSE.txt").read_bytes()
    assert sha256(source) == manifest["source_sha256"]
    assert sha256(license_body) == manifest["license_sha256"]
    assert bundled_license == license_body
    assert sha256(compressed) == manifest["compressed_sha256"]
    raw = gzip.decompress(compressed)
    assert len(raw) == manifest["uncompressed_bytes"]
    assert sha256(raw) == manifest["sha256"]
    with tempfile.NamedTemporaryFile() as temporary:
        temporary.write(raw); temporary.flush()
        db = sqlite3.connect(f"file:{temporary.name}?mode=ro", uri=True)
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert db.execute("SELECT COUNT(*) FROM records").fetchone()[0] == manifest["records"]
        assert db.execute("SELECT COUNT(*) FROM identifiers").fetchone()[0] == manifest["identifiers"]
        assert db.execute("SELECT COUNT(*) FROM records_fts").fetchone()[0] == manifest["records"]
        assert [row[1] for row in db.execute("PRAGMA table_info(records)").fetchall()][-3:] == ["identifiers_json", "source_pointer", "record_sha256"]
        ambiguous = db.execute("SELECT COUNT(*) FROM identifiers WHERE scheme='GTIN' AND normalized_value='07340002119380'").fetchone()[0]
        assert ambiguous == 4
        assert db.execute("SELECT COUNT(*) FROM identifiers WHERE scheme='SKU' AND normalized_value='33102'").fetchone()[0] > 0
        assert db.execute("SELECT COUNT(*) FROM records_fts WHERE records_fts MATCH '\"Gizmo\"* \"Dorks\"* \"HIPS\"* \"White\"*'").fetchone()[0] > 0
        db.close()
    print(json.dumps({"status": "PASS", "records": manifest["records"], "identifiers": manifest["identifiers"], "ambiguous_fixture_candidates": ambiguous}, sort_keys=True))


if __name__ == "__main__":
    main()
