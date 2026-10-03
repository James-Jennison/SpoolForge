#!/usr/bin/env python3
"""Run all missing provider/case pairs in a label benchmark manifest."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys


def safe_name(value: str) -> str:
    return "".join(char if char.isalnum() or char in "-." else "-" for char in value.casefold())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--env", type=Path, default=Path(".env.local"))
    parser.add_argument("--provider", choices=("openai", "xai", "gemini", "claude"))
    args = parser.parse_args()
    manifest_path = args.manifest.resolve()
    base = manifest_path.parent
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    completed = {(run["case"], run["provider"], run["model"]) for run in manifest.get("runs", [])}

    failures = []
    for case in manifest["cases"]:
        for candidate in manifest["candidates"]:
            if args.provider and candidate["provider"] != args.provider:
                continue
            identity = (case["id"], candidate["provider"], candidate["model"])
            if identity in completed:
                print(f"SKIP {case['id']} / {candidate['model']}", flush=True)
                continue
            result_name = f"results/{safe_name(case['id'])}--{safe_name(candidate['model'])}.json"
            result_path = base / result_name
            result_path.parent.mkdir(parents=True, exist_ok=True)
            print(f"START {case['id']} / {candidate['model']}", flush=True)
            try:
                subprocess.run([
                    sys.executable,
                    str(Path(__file__).with_name("openai_label_smoke.py")),
                    str(base / case["image"]),
                    "--env", str(args.env),
                    "--provider", candidate["provider"],
                    "--model", candidate["model"],
                    "--output", str(result_path),
                ], check=True)
            except subprocess.CalledProcessError:
                failures.append(identity)
                print(f"FAILED {case['id']} / {candidate['model']}; continuing", flush=True)
                continue
            json.loads(result_path.read_text(encoding="utf-8"))
            manifest.setdefault("runs", []).append({
                "case": case["id"],
                "provider": candidate["provider"],
                "model": candidate["model"],
                "result": result_name,
            })
            manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            completed.add(identity)
            print(f"COMPLETE {case['id']} / {candidate['model']}", flush=True)

    if failures:
        print(f"INCOMPLETE missing_pairs={len(failures)}", flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
