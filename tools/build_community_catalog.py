#!/usr/bin/env python3
"""Build the deterministic offline SpoolmanDB Community provider sidecar."""

from __future__ import annotations

import gzip
import hashlib
import json
import sqlite3
import tempfile
from collections import Counter
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "research/sources/spoolmandb/compiled.json"
LICENSE = ROOT / "research/sources/spoolmandb/LICENSE"
OUTPUT = ROOT / "app/src/main/assets/community-catalog.sqlite.gzip"
MANIFEST = ROOT / "app/src/main/assets/community-catalog-manifest.json"
REPORT = ROOT / "work/providers/BARCODE_COVERAGE.md"


def compact(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def decimal(value: object) -> str:
    if value is None:
        return ""
    return format(Decimal(str(value)).normalize(), "f")


def integer(value: object) -> int | None:
    if value is None:
        return None
    number = Decimal(str(value))
    return int(number) if number == number.to_integral_value() else None


def bounds(row: dict, stem: str) -> tuple[int | None, int | None]:
    value = row.get(stem + "_range")
    if isinstance(value, list) and len(value) == 2:
        return integer(value[0]), integer(value[1])
    point = integer(row.get(stem))
    return point, point


def identifiers(row: dict) -> list[tuple[str, str]]:
    found: set[tuple[str, str]] = set()
    for field, scheme in (("codes", "SKU"), ("eans", "GTIN"), ("eans_refill", "GTIN")):
        for raw in row.get(field) or []:
            value = str(raw).strip()
            if value and (scheme != "GTIN" or normalize_gtin(value) is not None):
                found.add((scheme, value))
    return sorted(found)


def normalize_gtin(value: str) -> str | None:
    if len(value) not in (8, 12, 13, 14) or not value.isdigit() or set(value) == {"0"}:
        return None
    total = sum(int(digit) * (3 if index % 2 == 0 else 1) for index, digit in enumerate(value[-2::-1])) + int(value[-1])
    return value.zfill(14) if total % 10 == 0 else None


def main() -> None:
    source_bytes = SOURCE.read_bytes()
    license_bytes = LICENSE.read_bytes()
    rows = json.loads(source_bytes)
    if not isinstance(rows, list):
        raise ValueError("compiled catalog must be a JSON array")
    if len({str(row.get("id")) for row in rows}) != len(rows):
        raise ValueError("community record IDs must be unique")
    source_hash = hashlib.sha256(source_bytes).hexdigest()
    license_hash = hashlib.sha256(license_bytes).hexdigest()
    revision = "artifact:" + source_hash
    rejected_gtins = sorted({str(value) for row in rows for field in ("eans", "eans_refill") for value in (row.get(field) or []) if normalize_gtin(str(value).strip()) is None})

    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "community.sqlite"
        db = sqlite3.connect(path)
        db.executescript(
            """
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            PRAGMA user_version=1;
            CREATE TABLE records (
              record_id TEXT PRIMARY KEY, manufacturer TEXT NOT NULL, product TEXT NOT NULL,
              material TEXT NOT NULL, color_hex TEXT NOT NULL, additional_color_hexes TEXT NOT NULL,
              diameter_mm TEXT NOT NULL, filament_mass_g INTEGER NOT NULL, spool_mass_g INTEGER,
              nozzle_min_c INTEGER, nozzle_max_c INTEGER, bed_min_c INTEGER, bed_max_c INTEGER,
              identifiers_json TEXT NOT NULL, source_pointer TEXT NOT NULL, record_sha256 TEXT NOT NULL
            ) WITHOUT ROWID;
            CREATE TABLE identifiers (
              scheme TEXT NOT NULL, normalized_value TEXT NOT NULL, record_id TEXT NOT NULL,
              PRIMARY KEY (scheme, normalized_value, record_id)
            ) WITHOUT ROWID;
            CREATE INDEX identifiers_record ON identifiers(record_id);
            CREATE VIRTUAL TABLE records_fts USING fts4(record_id, manufacturer, product, material, identifier_text, tokenize=unicode61);
            """
        )
        normalized: list[tuple] = []
        id_rows: list[tuple[str, str, str]] = []
        for position, row in enumerate(rows):
            if not isinstance(row, dict):
                raise ValueError(f"record {position} is not an object")
            record_id = str(row.get("id") or "").strip()
            manufacturer = str(row.get("manufacturer") or "").strip()
            product = str(row.get("name") or "").strip()
            material = str(row.get("material") or "").strip()
            diameter_mm = decimal(row.get("diameter"))
            mass = integer(row.get("weight"))
            if not all((record_id, manufacturer, product, material, diameter_mm)) or mass is None or mass <= 0:
                raise ValueError(f"required normalized field missing for {record_id or position}")
            colors = [str(value).removeprefix("#").upper() for value in (row.get("color_hexes") or [])]
            color_hex = str(row.get("color_hex") or (colors[0] if colors else "")).removeprefix("#").upper()
            ordered_colors = list(dict.fromkeys(([color_hex] if color_hex else []) + colors))
            additional = ",".join(ordered_colors[1:])
            nozzle_min, nozzle_max = bounds(row, "extruder_temp")
            bed_min, bed_max = bounds(row, "bed_temp")
            ids = identifiers(row)
            pointer = f"spoolmandb/compiled.json#/{position}"
            normalized.append((record_id, manufacturer, product, material, color_hex, additional, diameter_mm,
                               mass, integer(row.get("spool_weight")), nozzle_min, nozzle_max, bed_min, bed_max,
                               compact([{"scheme": scheme, "value": value} for scheme, value in ids]), pointer,
                               hashlib.sha256(compact(row).encode()).hexdigest()))
            for scheme, value in ids:
                normalized_value = value.upper() if scheme == "SKU" else normalize_gtin(value)
                assert normalized_value is not None
                id_rows.append((scheme, normalized_value, record_id))

        normalized.sort(key=lambda value: value[0])
        id_rows = sorted(set(id_rows))
        db.executemany("INSERT INTO records VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", normalized)
        db.executemany("INSERT INTO identifiers VALUES (?,?,?)", id_rows)
        identifier_text = {}
        for scheme, value, record_id in id_rows:
            identifier_text.setdefault(record_id, []).append(value)
        db.executemany(
            "INSERT INTO records_fts(record_id,manufacturer,product,material,identifier_text) VALUES (?,?,?,?,?)",
            ((row[0], row[1], row[2], row[3], " ".join(identifier_text.get(row[0], []))) for row in normalized),
        )
        db.commit()
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("generated community database failed integrity check")
        db.execute("VACUUM")
        db.close()
        raw = path.read_bytes()

    compressed = gzip.compress(raw, mtime=0)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(compressed)
    (OUTPUT.parent / "COMMUNITY-LICENSE.txt").write_bytes(license_bytes)
    manifest = {
        "format": "filamajig-community-sqlite-v1",
        "source": "Icezaza2543/SpoolmanDB-Community",
        "source_url": "https://icezaza2543.github.io/SpoolmanDB-Community/filaments.json",
        "revision": revision,
        "source_sha256": source_hash,
        "license_sha256": license_hash,
        "license": "MIT",
        "records": len(normalized),
        "identifiers": len(id_rows),
        "rejected_gtins": len(rejected_gtins),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "compressed_sha256": hashlib.sha256(compressed).hexdigest(),
        "uncompressed_bytes": len(raw),
    }
    MANIFEST.write_text(compact(manifest) + "\n")

    total = len(rows)
    def has_valid_gtin(row: dict) -> bool:
        return any(normalize_gtin(str(value).strip()) for field in ("eans", "eans_refill") for value in (row.get(field) or []))
    with_gtin = sum(has_valid_gtin(row) for row in rows)
    with_sku = sum(bool(row.get("codes")) for row in rows)
    materials = Counter(str(row.get("material") or "Unknown") for row in rows)
    material_gtin = Counter(str(row.get("material") or "Unknown") for row in rows if has_valid_gtin(row))
    brands = Counter(str(row.get("manufacturer") or "Unknown") for row in rows)
    brand_gtin = Counter(str(row.get("manufacturer") or "Unknown") for row in rows if has_valid_gtin(row))
    lines = [
        "# Offline barcode coverage report", "",
        f"Source artifact: `{revision}`", "",
        "This measures identifier availability in the retained Community package-variant snapshot. "
        "A row without a GTIN is a deterministic exact-barcode lookup miss for this provider; it is not a camera-decoder failure or an estimate of the whole retail market.", "",
        f"- Package variants: {total:,}",
        f"- Variants with at least one GTIN/EAN: {with_gtin:,} ({with_gtin / total:.2%})",
        f"- Exact-barcode misses: {total - with_gtin:,} ({1 - with_gtin / total:.2%})",
        f"- Variants with at least one manufacturer code/SKU: {with_sku:,} ({with_sku / total:.2%})", "",
        "## Material strata", "", "| Material | Variants | With GTIN | Exact-barcode miss rate |", "|---|---:|---:|---:|",
    ]
    for material, count in sorted(materials.items(), key=lambda item: (-item[1], item[0]))[:20]:
        hits = material_gtin[material]
        lines.append(f"| {material} | {count:,} | {hits:,} | {1 - hits / count:.2%} |")
    lines += ["", "## Manufacturer strata", "", "| Manufacturer | Variants | With GTIN | Exact-barcode miss rate |", "|---|---:|---:|---:|"]
    for brand, count in sorted(brands.items(), key=lambda item: (-item[1], item[0]))[:20]:
        hits = brand_gtin[brand]
        lines.append(f"| {brand} | {count:,} | {hits:,} | {1 - hits / count:.2%} |")
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text("\n".join(lines) + "\n")
    print(compact(manifest))


if __name__ == "__main__":
    main()
