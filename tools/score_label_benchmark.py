#!/usr/bin/env python3
"""Score normalized filament-label provider results against reviewed fixtures."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def normalized(value):
    if isinstance(value, str):
        return re.sub(r"[^a-z0-9]+", " ", value.casefold()).strip()
    return value


def contains_term(text, term) -> bool:
    normalized_text = normalized(text or "")
    normalized_term = normalized(term or "")
    return bool(normalized_term) and f" {normalized_term} " in f" {normalized_text} "


def canonical_gtin(value):
    if not isinstance(value, str):
        return value
    digits = re.sub(r"\D", "", value)
    if 8 <= len(digits) <= 14:
        return digits.zfill(14)
    return normalized(value)


def exact(actual, expected, field=None) -> bool:
    if field == "gtin":
        return canonical_gtin(actual) == canonical_gtin(expected)
    if isinstance(expected, str):
        return normalized(actual) == normalized(expected)
    if isinstance(expected, (int, float)) and not isinstance(expected, bool):
        return isinstance(actual, (int, float)) and abs(float(actual) - float(expected)) < 1e-9
    return actual is expected


def score_run(case: dict, result: dict) -> dict:
    extracted = result["extracted"]
    field_checks = {}
    unsupported = []
    for field, expected in case["expected"].items():
        actual = extracted[field]["value"]
        passed = exact(actual, expected, field)
        field_checks[field] = {"pass": passed, "expected": expected, "actual": actual}
        if expected is None and actual is not None:
            unsupported.append(field)

    product = extracted["product"]["value"] or ""
    terms = case["product_terms"]
    product_hits = [term for term in terms if contains_term(product, term)]

    actual_codes = {item["value"]: item for item in extracted["other_codes"]}
    code_checks = []
    for expected_code in case["other_codes"]:
        actual = actual_codes.get(expected_code["value"])
        kind = normalized(actual["kind"]) if actual else ""
        expected_kind = normalized(expected_code["kind"])
        code_checks.append({
            "value": expected_code["value"],
            "present": actual is not None,
            "kind_pass": actual is not None and expected_kind == kind,
            "actual_kind": actual["kind"] if actual else None,
        })

    exact_passes = sum(check["pass"] for check in field_checks.values())
    kind_passes = sum(check["kind_pass"] for check in code_checks)
    return {
        "exact_fields": exact_passes,
        "exact_fields_total": len(field_checks),
        "product_terms": len(product_hits),
        "product_terms_total": len(terms),
        "identifier_kinds": kind_passes,
        "identifier_kinds_total": len(code_checks),
        "unsupported_non_null": unsupported,
        "field_checks": field_checks,
        "code_checks": code_checks,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    base = args.manifest.parent
    cases = {case["id"]: case for case in manifest["cases"]}
    scored = []
    for run in manifest["runs"]:
        result = json.loads((base / run["result"]).read_text(encoding="utf-8"))
        scored.append({**run, "score": score_run(cases[run["case"]], result)})
    by_model = {}
    for run in scored:
        totals = by_model.setdefault(run["model"], {
            "provider": run["provider"],
            "cases": 0,
            "exact_fields": 0,
            "exact_fields_total": 0,
            "product_terms": 0,
            "product_terms_total": 0,
            "identifier_kinds": 0,
            "identifier_kinds_total": 0,
            "unsupported_non_null": 0,
        })
        score = run["score"]
        totals["cases"] += 1
        for metric in ("exact_fields", "exact_fields_total", "product_terms", "product_terms_total",
                       "identifier_kinds", "identifier_kinds_total"):
            totals[metric] += score[metric]
        totals["unsupported_non_null"] += len(score["unsupported_non_null"])

    completed = {(run["case"], run["provider"], run["model"]) for run in manifest["runs"]}
    missing_runs = [
        {"case": case["id"], **candidate}
        for case in manifest["cases"]
        for candidate in manifest.get("candidates", [])
        if (case["id"], candidate["provider"], candidate["model"]) not in completed
    ]
    payload = {
        "version": 1,
        "case_count": len(cases),
        "run_count": len(scored),
        "missing_runs": missing_runs,
        "runs": scored,
        "models": by_model,
    }
    args.output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    if args.markdown:
        lines = [
            "# Filament label benchmark scores",
            "",
            f"Reviewed image cases: {len(cases)}",
            "",
            "| Provider | Model | Cases | Exact fields | Product terms | Identifier types | Unsupported values |",
            "|---|---|---:|---:|---:|---:|---:|",
        ]
        for model, totals in by_model.items():
            lines.append(
                f"| {totals['provider']} | {model} | {totals['cases']}/{len(cases)} | "
                f"{totals['exact_fields']}/{totals['exact_fields_total']} | "
                f"{totals['product_terms']}/{totals['product_terms_total']} | "
                f"{totals['identifier_kinds']}/{totals['identifier_kinds_total']} | "
                f"{totals['unsupported_non_null']} |"
            )
        lines.extend([
            "",
            "Exact fields exclude the free-form product name, which is scored by reviewed product terms. "
            "Unsupported values count non-null claims where the reviewed value is absent.",
            "",
            "These results are comparative evidence only. A production choice requires a varied set of real labels.",
        ])
        if missing_runs:
            lines.extend([
                "",
                f"Incomplete provider/case pairs: {len(missing_runs)}. Models with partial coverage are not directly comparable to full-coverage models.",
            ])
        args.markdown.write_text("\n".join(lines) + "\n", encoding="utf-8")
    for run in scored:
        score = run["score"]
        print(
            f"{run['model']}: fields={score['exact_fields']}/{score['exact_fields_total']} "
            f"product_terms={score['product_terms']}/{score['product_terms_total']} "
            f"identifier_kind={score['identifier_kinds']}/{score['identifier_kinds_total']} "
            f"unsupported={len(score['unsupported_non_null'])}"
        )


if __name__ == "__main__":
    main()
