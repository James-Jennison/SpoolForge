#!/usr/bin/env python3
"""Build a reproducible Icecat coverage trial without using Icecat data."""

from __future__ import annotations

import csv
import gzip
import hashlib
import json
import re
import sqlite3
import tempfile
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

from bs4 import BeautifulSoup


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "work" / "gtin"
INDEX = ROOT / "app" / "src" / "main" / "assets" / "gtin-index.sqlite.gzip"
BASE = "https://www.3dprima.com"
BRANDS = {
    "3dxtech": "3DXTech",
    "anycubic": "Anycubic",
    "bambu-lab": "Bambu Lab",
    "copymaster3d": "Copymaster3D",
    "creality-3d": "Creality 3D",
    "elegoo": "ELEGOO",
    "esun": "eSun",
    "flashforge": "Flashforge",
    "ninjatek": "NinjaTek",
    "panchroma": "Panchroma",
    "polymaker": "Polymaker",
    "primacreator": "PrimaCreator",
    "prusa": "Prusa",
    "sunlu": "SUNLU",
    "ultimaker": "UltiMaker",
    "xyzprinting": "XYZPrinting",
    "zortrax": "Zortrax",
}


def normalize_gtin(raw: str) -> str | None:
    value = re.sub(r"\D", "", raw)
    if len(value) not in (8, 12, 13, 14):
        return None
    digits = [int(x) for x in value]
    total = sum(d * (3 if (len(digits) - i) % 2 == 0 else 1) for i, d in enumerate(digits[:-1]))
    if (10 - total % 10) % 10 != digits[-1]:
        return None
    return value.zfill(14)


def material(title: str) -> str:
    upper = title.upper()
    for name in ("PETG", "PLA", "ABS", "ASA", "TPU", "TPE", "PA", "NYLON", "PC", "PVA", "HIPS", "PP"):
        if re.search(rf"(?<![A-Z]){name}(?![A-Z])", upper):
            return name
    return "OTHER"


def read_index() -> tuple[set[str], list[dict]]:
    with gzip.open(INDEX, "rb") as source, tempfile.NamedTemporaryFile(delete=False) as target:
        target.write(source.read())
        db_path = Path(target.name)
    try:
        with sqlite3.connect(db_path) as db:
            rows = db.execute(
                "SELECT gtin14, source, package_id, values_json FROM candidates ORDER BY gtin14, source, package_id"
            ).fetchall()
    finally:
        db_path.unlink(missing_ok=True)
    existing = {row[0] for row in rows}
    decoded = []
    seen = set()
    for gtin14, source, package_id, values_json in rows:
        if gtin14 in seen:
            continue
        seen.add(gtin14)
        values = json.loads(values_json)
        decoded.append(
            {
                "gtin14": gtin14,
                "gtin": gtin14.lstrip("0") or "0",
                "brand": str(values.get("brand") or "Unknown"),
                "product_code": str(values.get("article_number") or values.get("sku") or ""),
                "title": " ".join(str(values.get(k) or "") for k in ("product", "material", "color_name")).strip(),
                "source": source,
                "package_id": package_id,
            }
        )
    return existing, decoded


def fetch_external(existing: set[str]) -> list[dict]:
    found: dict[str, dict] = {}
    headers = {"User-Agent": "Mozilla/5.0 FilamajigCoverageResearch/1.0"}
    for slug, brand in BRANDS.items():
        prior_page_gtins: set[str] = set()
        for page in range(1, 4):
            url = f"{BASE}/filament-resin/manufacturer/{slug}" + (f"?page={page}" if page > 1 else "")
            try:
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=20) as response:
                    soup = BeautifulSoup(response.read(), "html.parser")
            except Exception as exc:
                print(f"external {brand} page {page}: {type(exc).__name__}", flush=True)
                break
            page_gtins = set()
            for node in soup.select(".product-ean"):
                match = re.search(r"([0-9]{8,14})", node.get_text(" ", strip=True))
                if not match:
                    continue
                gtin14 = normalize_gtin(match.group(1))
                info = node.find_parent(class_="product-info")
                link = info.select_one("a.product-name[href]") if info else None
                if not gtin14 or not link:
                    continue
                title = " ".join(link.get_text(" ", strip=True).split())
                if "resin" in title.lower() and not re.search(r"filament|\b(?:pla|petg|abs|asa|tpu|tpe|nylon|pa|pc|pva|hips|pp)\b", title, re.I):
                    continue
                page_gtins.add(gtin14)
                if gtin14 in existing or gtin14 in found:
                    continue
                found[gtin14] = {
                    "cohort": "external_miss",
                    "brand": brand,
                    "product_code": "",
                    "gtin": match.group(1),
                    "gtin14": gtin14,
                    "title": title,
                    "material": material(title),
                    "evidence_url": urllib.parse.urljoin(BASE, link["href"]),
                    "catalog_page": url,
                    "evidence_source": "3D Prima public catalog",
                }
            print(f"external {brand} page {page}: {len(page_gtins)} identifiers", flush=True)
            if not page_gtins or page_gtins == prior_page_gtins:
                break
            prior_page_gtins = page_gtins
            time.sleep(0.35)
    return list(found.values())


def round_robin(rows: list[dict], count: int, per_brand: int) -> list[dict]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        groups[row["brand"].casefold()].append(row)
    for values in groups.values():
        values.sort(key=lambda x: (x.get("material", ""), x["gtin14"]))
    chosen = []
    for offset in range(per_brand):
        for key in sorted(groups):
            if offset < len(groups[key]):
                chosen.append(groups[key][offset])
                if len(chosen) == count:
                    return chosen
    return chosen


def stratified(rows: list[dict], count: int, quotas: dict[str, int], per_brand: int) -> list[dict]:
    """Select material quotas while rotating brands and enforcing a brand cap."""
    chosen: list[dict] = []
    chosen_gtins: set[str] = set()
    brand_counts: dict[str, int] = defaultdict(int)

    def add_material(material_name: str, wanted: int) -> None:
        groups: dict[str, list[dict]] = defaultdict(list)
        for row in rows:
            if row["material"] == material_name:
                groups[row["brand"].casefold()].append(row)
        for values in groups.values():
            values.sort(key=lambda x: x["gtin14"])
        added = 0
        offset = 0
        while added < wanted and groups:
            progressed = False
            for key in sorted(groups):
                values = groups[key]
                if offset >= len(values) or brand_counts[key] >= per_brand:
                    continue
                row = values[offset]
                if row["gtin14"] not in chosen_gtins:
                    chosen.append(row)
                    chosen_gtins.add(row["gtin14"])
                    brand_counts[key] += 1
                    added += 1
                    progressed = True
                    if added == wanted or len(chosen) == count:
                        return
            if not progressed and all(offset + 1 >= len(v) for v in groups.values()):
                return
            offset += 1

    for material_name, wanted in quotas.items():
        if len(chosen) == count:
            break
        add_material(material_name, min(wanted, count - len(chosen)))

    # Fill shortages without letting one brand dominate.
    for row in round_robin(rows, len(rows), per_brand):
        key = row["brand"].casefold()
        if row["gtin14"] in chosen_gtins or brand_counts[key] >= per_brand:
            continue
        chosen.append(row)
        chosen_gtins.add(row["gtin14"])
        brand_counts[key] += 1
        if len(chosen) == count:
            break
    return chosen


def main() -> None:
    existing, candidates = read_index()
    print(f"shipped unique GTINs: {len(existing)}", flush=True)
    external_all = fetch_external(existing)
    (OUT / "icecat-independent-candidates.json").write_text(json.dumps(external_all, indent=2) + "\n")
    quotas = {
        "PLA": 25, "PETG": 20, "ABS": 10, "ASA": 8, "TPU": 7, "TPE": 5,
        "PA": 5, "NYLON": 4, "PC": 4, "PVA": 3, "HIPS": 3, "PP": 3, "OTHER": 3,
    }
    external = stratified(external_all, 100, quotas, 10)
    if len(external) < 100:
        raise SystemExit(f"Only {len(external)} independent misses available; need 100")

    controls_all = []
    for row in candidates:
        row = dict(row)
        row.update(
            cohort="filamajig_control",
            material=material(row["title"]),
            evidence_url="",
            catalog_page="",
            evidence_source=f"Filamajig shipped index ({row['source']})",
        )
        controls_all.append(row)
    controls = stratified(controls_all, 100, quotas, 5)
    if len(controls) < 100:
        raise SystemExit(f"Only {len(controls)} controls available; need 100")

    selected = external + controls
    for number, row in enumerate(selected, 1):
        row["trial_id"] = f"ICAT-{number:03d}"

    upload = OUT / "icecat-stratified-200-upload.csv"
    with upload.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["brand", "product_code", "gtin", "trial_id"])
        writer.writeheader()
        writer.writerows({key: row.get(key, "") for key in writer.fieldnames} for row in selected)

    manifest = OUT / "icecat-stratified-200-manifest.json"
    manifest.write_text(json.dumps({"schema_version": 1, "generated_on": "2026-09-07", "rows": selected}, indent=2) + "\n")

    summary = {
        "rows": len(selected),
        "cohorts": {name: sum(r["cohort"] == name for r in selected) for name in ("external_miss", "filamajig_control")},
        "brands": len({r["brand"].casefold() for r in selected}),
        "external_brands": sorted({r["brand"] for r in external}),
        "control_brands": sorted({r["brand"] for r in controls}),
        "materials": {name: sum(r["material"] == name for r in selected) for name in sorted({r["material"] for r in selected})},
        "upload_sha256": hashlib.sha256(upload.read_bytes()).hexdigest(),
        "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
    }
    (OUT / "icecat-stratified-200-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
