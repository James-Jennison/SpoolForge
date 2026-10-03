#!/usr/bin/env python3
"""Run a single, sanitized multi-provider filament-label extraction smoke test."""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

PHOTO_ROLES = ["profile_label"]


def load_key(path: Path, variable: str) -> str:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{variable}="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise RuntimeError(f"{variable} is missing")


def field_schema(value_schema: dict) -> dict:
    return {
        "type": "object",
        "properties": {
            "value": {"anyOf": [value_schema, {"type": "null"}]},
            "confidence": {
                "type": "number",
                "minimum": 0,
                "maximum": 1,
                "description": (
                    "Confidence that this claim is correct. When value is null and basis is absent, "
                    "this is confidence that the field is not visible in the image."
                ),
            },
            "evidence": {"type": ["string", "null"]},
            "basis": {"type": "string", "enum": ["printed", "barcode", "visual_estimate", "absent"]},
            "photo_roles": {"type": "array", "items": {"type": "string", "enum": PHOTO_ROLES}},
        },
        "required": ["value", "confidence", "evidence", "basis", "photo_roles"],
        "additionalProperties": False,
    }


def schema() -> dict:
    text = {"type": "string"}
    integer = {"type": "integer"}
    fields = {
        "label_brand": field_schema(text),
        "manufacturer": field_schema(text),
        "product": field_schema(text),
        "material": field_schema(text),
        "color": field_schema(text),
        "diameter_mm": field_schema({"type": "number"}),
        "net_weight_g": field_schema(integer),
        "nozzle_min_c": field_schema(integer),
        "nozzle_max_c": field_schema(integer),
        "bed_min_c": field_schema(integer),
        "bed_max_c": field_schema(integer),
        "transmission_distance": field_schema({"type": "number"}),
        "gtin": field_schema(text),
        "sku": field_schema(text),
        "lot": field_schema(text),
    }
    return {
        "type": "object",
        "properties": {
            **fields,
            "other_codes": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "value": {"type": "string"},
                        "kind": {"type": "string"},
                        "evidence": {"type": "string"},
                    },
                    "required": ["value", "kind", "evidence"],
                    "additionalProperties": False,
                },
            },
            "needs_user_review": {"type": "array", "items": {"type": "string"}},
        },
        "required": [*fields, "other_codes", "needs_user_review"],
        "additionalProperties": False,
    }


PROMPT = (
    "Extract only filament product facts visible in this package image. "
    "Never supply general material knowledge or infer temperatures that are not printed. "
    "label_brand is the brand or product-family name visibly printed on the package. "
    "product must preserve the complete visible product or variant wording, including descriptive phrases; "
    "do not replace a specific printed product phrase with only the material plus the word Filament. "
    "manufacturer is the company name only when that company name or logo is visibly printed; "
    "do not infer a parent company from product-family knowledge or packaging style. "
    "Keep retail GTIN, manufacturer SKU, marketplace identifiers, QR data, and lot numbers distinct. "
    "transmission_distance is the numeric HueForge TD value only when TD or transmission distance is visibly printed; do not confuse it with diameter. "
    "For every field, photo_roles must contain profile_label when the photo visibly supports the claim; use an empty array for absent fields. "
    "Use null with basis absent when a field is not visible. "
    "Confidence always means confidence that the claim is correct; for a null absent claim, "
    "it means confidence that the field is not visible."
)


def without_numeric_constraints(value):
    if isinstance(value, dict):
        return {key: without_numeric_constraints(item) for key, item in value.items() if key not in {"minimum", "maximum"}}
    if isinstance(value, list):
        return [without_numeric_constraints(item) for item in value]
    return value


def provider_retry_seconds(exc: urllib.error.HTTPError, error: str, attempt: int) -> float:
    retry_after = exc.headers.get("Retry-After") if exc.headers else None
    if retry_after:
        try:
            return min(59.0, max(1.0, float(retry_after)))
        except ValueError:
            pass
    match = re.search(r"retry in\s+([0-9]+(?:\.[0-9]+)?)s", error, re.IGNORECASE)
    if match:
        return min(59.0, max(1.0, float(match.group(1)) + 1.0))
    return float(2 ** attempt)


def claude_schema() -> dict:
    fields = {
        "label_brand": {"type": ["string", "null"]},
        "manufacturer": {"type": ["string", "null"]},
        "product": {"type": ["string", "null"]},
        "material": {"type": ["string", "null"]},
        "color": {"type": ["string", "null"]},
        "diameter_mm": {"type": ["number", "null"]},
        "net_weight_g": {"type": ["integer", "null"]},
        "nozzle_min_c": {"type": ["integer", "null"]},
        "nozzle_max_c": {"type": ["integer", "null"]},
        "bed_min_c": {"type": ["integer", "null"]},
        "bed_max_c": {"type": ["integer", "null"]},
        "transmission_distance": {"type": ["number", "null"]},
        "gtin": {"type": ["string", "null"]},
        "sku": {"type": ["string", "null"]},
        "lot": {"type": ["string", "null"]},
    }
    return {
        "type": "object",
        "properties": {
            "values": {"type": "object", "properties": fields, "required": list(fields), "additionalProperties": False},
            "claims": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "field": {"type": "string", "enum": list(fields)},
                        "confidence": {
                            "type": "number",
                            "description": (
                                "Confidence that the claim is correct, including confidence that a null field is absent."
                            ),
                        },
                        "evidence": {"type": "string"},
                        "basis": {"type": "string", "enum": ["printed", "barcode", "visual_estimate", "absent"]},
                        "photo_roles": {"type": "array", "items": {"type": "string", "enum": PHOTO_ROLES}},
                    },
                    "required": ["field", "confidence", "evidence", "basis", "photo_roles"],
                    "additionalProperties": False,
                },
            },
            "other_codes": schema()["properties"]["other_codes"],
            "needs_user_review": schema()["properties"]["needs_user_review"],
        },
        "required": ["values", "claims", "other_codes", "needs_user_review"],
        "additionalProperties": False,
    }


def normalize_claude(value: dict) -> dict:
    claims = {claim["field"]: claim for claim in value["claims"]}
    extracted = {}
    for field, field_value in value["values"].items():
        claim = claims.get(field)
        absent = field_value is None
        extracted[field] = {
            "value": field_value,
            "confidence": claim.get("confidence", 1.0 if absent else 0.0) if claim else (1.0 if absent else 0.0),
            "evidence": (claim.get("evidence") or None) if claim else None,
            "basis": claim.get("basis", "absent" if absent else "visual_estimate") if claim else ("absent" if absent else "visual_estimate"),
            "photo_roles": claim.get("photo_roles", []) if claim else [],
        }
    extracted["other_codes"] = value["other_codes"]
    extracted["needs_user_review"] = value["needs_user_review"]
    return extracted


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--env", type=Path, default=Path(".env.local"))
    parser.add_argument("--provider", choices=("openai", "xai", "gemini", "claude"), default="openai")
    parser.add_argument("--model", default="gpt-5.6-luna")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    key_variable = {
        "openai": "OPENAI_API_KEY",
        "xai": "XAI_API_KEY",
        "gemini": "GEMINI_API_KEY",
        "claude": "CLAUDE_API_KEY",
    }[args.provider]
    api_key = load_key(args.env, key_variable)
    mime = mimetypes.guess_type(args.image.name)[0] or "image/jpeg"
    image_data = base64.b64encode(args.image.read_bytes()).decode("ascii")
    if args.provider in {"openai", "xai"}:
        body = {
            "model": args.model,
            "store": False,
            "reasoning": {"effort": "none" if args.provider == "openai" else "low"},
            "input": [{
                "role": "user",
                "content": [
                    {"type": "input_text", "text": PROMPT},
                    {"type": "input_image", "image_url": f"data:{mime};base64,{image_data}", "detail": "high"},
                ],
            }],
            "text": {"format": {"type": "json_schema", "name": "filament_label", "strict": True, "schema": schema()}},
        }
        url = "https://api.openai.com/v1/responses" if args.provider == "openai" else "https://api.x.ai/v1/responses"
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
    elif args.provider == "gemini":
        body = {
            "model": args.model,
            "input": [
                {"type": "image", "data": image_data, "mime_type": mime},
                {"type": "text", "text": PROMPT},
            ],
            "response_format": {"type": "text", "mime_type": "application/json", "schema": schema()},
        }
        url = "https://generativelanguage.googleapis.com/v1beta/interactions"
        headers = {"x-goog-api-key": api_key, "Content-Type": "application/json"}
    else:
        body = {
            "model": args.model,
            "max_tokens": 4096,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": mime, "data": image_data}},
                    {"type": "text", "text": PROMPT + " Return exactly one claims entry for every field in values; use an empty evidence string when absent."},
                ],
            }],
            "output_config": {"format": {"type": "json_schema", "schema": claude_schema()}},
        }
        url = "https://api.anthropic.com/v1/messages"
        headers = {"x-api-key": api_key, "anthropic-version": "2023-06-01", "Content-Type": "application/json"}
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    raw = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                raw = json.load(response)
            break
        except urllib.error.HTTPError as exc:
            error = exc.read().decode("utf-8", errors="replace")
            if args.provider == "gemini" and exc.code in {429, 500, 502, 503} and attempt < 2:
                time.sleep(provider_retry_seconds(exc, error, attempt))
                continue
            raise RuntimeError(f"{args.provider} request failed with HTTP {exc.code}: {error[:800]}") from None
    assert raw is not None
    if args.provider in {"openai", "xai"}:
        output_text = "".join(
            content.get("text", "") for item in raw.get("output", []) for content in item.get("content", [])
            if content.get("type") == "output_text"
        )
    elif args.provider == "gemini":
        output_text = "".join(
            content.get("text", "") for step in raw.get("steps", []) if step.get("type") == "model_output"
            for content in step.get("content", []) if content.get("type") == "text"
        ) or raw.get("output_text", "")
    else:
        output_text = "".join(content.get("text", "") for content in raw.get("content", []) if content.get("type") == "text")
    extracted = json.loads(output_text)
    if args.provider == "claude":
        extracted = normalize_claude(extracted)
    sanitized = {
        "provider": args.provider,
        "model": raw.get("model"),
        "status": raw.get("status") or "completed",
        "usage": raw.get("usage"),
        "extracted": extracted,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(sanitized, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": sanitized["status"], "model": sanitized["model"], "output": str(args.output)}))


if __name__ == "__main__":
    main()
