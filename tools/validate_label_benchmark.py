#!/usr/bin/env python3
"""Validate retained label benchmark structure and provenance without API access."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import py_compile
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "work" / "ai-label"
FIELDS = {
    "label_brand", "manufacturer", "product", "material", "color", "diameter_mm",
    "net_weight_g", "nozzle_min_c", "nozzle_max_c", "bed_min_c", "bed_max_c",
    "gtin", "sku", "lot",
}


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> None:
    for script in ("openai_label_smoke.py", "run_label_benchmark.py", "score_label_benchmark.py"):
        py_compile.compile(str(ROOT / "tools" / script), doraise=True)
    subprocess.run(
        [sys.executable, "-m", "unittest", "tools.test_score_label_benchmark"],
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
    )

    manifest = load(BASE / "benchmark-manifest.json")
    cases = manifest["cases"]
    candidates = manifest["candidates"]
    runs = manifest["runs"]
    assert len({case["id"] for case in cases}) == len(cases)
    assert len({(item["provider"], item["model"]) for item in candidates}) == len(candidates)
    assert len({(run["case"], run["provider"], run["model"]) for run in runs}) == len(runs)
    case_ids = {case["id"] for case in cases}
    candidate_ids = {(item["provider"], item["model"]) for item in candidates}

    for case in cases:
        image = BASE / case["image"]
        assert image.is_file() and image.stat().st_size > 0
        assert hashlib.sha256(image.read_bytes()).hexdigest() == case["source_sha256"]
        assert set(case["expected"]) <= FIELDS
        assert "label_brand" in case["expected"]

    for run in runs:
        assert run["case"] in case_ids
        assert (run["provider"], run["model"]) in candidate_ids
        result = load(BASE / run["result"])
        assert set(result) <= {"provider", "model", "status", "usage", "extracted"}
        if run.get("legacy_result_format"):
            assert "provider" not in result
            assert not run["result"].startswith("results/")
            assert result.get("status") in {None, "completed"}
        else:
            assert result["provider"] == run["provider"]
            assert result["status"] == "completed"
        extracted = result["extracted"]
        assert set(extracted) == FIELDS | {"other_codes", "needs_user_review"}
        for field in FIELDS:
            assert set(extracted[field]) == {"value", "confidence", "evidence", "basis"}
            assert 0 <= extracted[field]["confidence"] <= 1

    score = load(BASE / "benchmark-score.json")
    assert score["case_count"] == len(cases)
    assert score["run_count"] == len(runs)
    completed = {(run["case"], run["provider"], run["model"]) for run in runs}
    missing = {
        (case["id"], item["provider"], item["model"])
        for case in cases for item in candidates
        if (case["id"], item["provider"], item["model"]) not in completed
    }
    assert missing == {(item["case"], item["provider"], item["model"]) for item in score["missing_runs"]}

    retained = [*BASE.glob("*.json"), *BASE.glob("*.md"), *BASE.glob("results/*.json"), *ROOT.glob("tools/*label*.py")]
    openai_prefix = b"s" + b"k-"
    google_prefix = b"AI" + b"za"
    xai_prefix = b"x" + b"ai-"
    secret_pattern = re.compile(
        rb"(?:" + re.escape(openai_prefix) + rb"(?:proj-)?|" + re.escape(google_prefix)
        + rb"[0-9A-Za-z_-]{20,}|" + re.escape(xai_prefix) + rb"[0-9A-Za-z_-]{20,})"
    )
    for path in retained:
        assert not secret_pattern.search(path.read_bytes()), f"credential-like material in {path}"

    env = ROOT / ".env.local"
    if env.exists():
        assert os.stat(env).st_mode & 0o777 == 0o600
        assert os.system(f"git -C '{ROOT}' check-ignore -q .env.local") == 0

    print(f"PASS cases={len(cases)} completed_runs={len(runs)} missing_runs={len(missing)}")


if __name__ == "__main__":
    main()
