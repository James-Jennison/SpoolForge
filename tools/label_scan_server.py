#!/usr/bin/env python3
"""Loopback-only development service for SpoolForge label extraction.

The API key remains on the workstation. Requests and images are never logged or stored.
"""

from __future__ import annotations

import argparse
import base64
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
import struct
import threading
import time
from pathlib import Path
import urllib.request

from openai_label_smoke import PHOTO_ROLES, PROMPT, field_schema, load_key, schema
from amazon_catalog import lookup as lookup_amazon_catalog

MAX_REQUEST = 9_000_000
MODEL = "gpt-5.6-terra"
COMMUNITY_DATABASE = Path("research/sources/spoolmandb/compiled.json")


class ResultCache:
    def __init__(self, limit: int = 32):
        self.limit = limit
        self.values = {}
        self.lock = threading.Lock()

    def get_or_compute(self, key: str, compute):
        return self.get_or_compute_with_status(key, compute)[0]

    def get_or_compute_with_status(self, key: str, compute):
        with self.lock:
            if key in self.values:
                return self.values[key], True
            value = compute()
            if len(self.values) >= self.limit:
                self.values.pop(next(iter(self.values)))
            self.values[key] = value
            return value, False


def service_schema() -> dict:
    result = deepcopy(schema())
    result["properties"]["color_hex"] = field_schema({"type": "string", "pattern": "^[0-9A-Fa-f]{6}$"})
    required = result["required"]
    required.insert(required.index("other_codes"), "color_hex")
    return result


def _claim_value(extracted: dict, name: str):
    claim = extracted.get(name) or {}
    return claim.get("value")


def enrich_missing_mass(extracted: dict, database_path: Path = COMMUNITY_DATABASE) -> None:
    """Fill mass only when a strong local catalog match has one unanimous value."""
    if _claim_value(extracted, "net_weight_g") is not None or not database_path.is_file():
        return
    brand = str(_claim_value(extracted, "label_brand") or "").lower().replace(" 3d", "")
    material = str(_claim_value(extracted, "material") or "").upper()
    color = str(_claim_value(extracted, "color") or "").lower().replace("gray", "grey")
    diameter = _claim_value(extracted, "diameter_mm")
    nozzle = [_claim_value(extracted, "nozzle_min_c"), _claim_value(extracted, "nozzle_max_c")]
    bed = [_claim_value(extracted, "bed_min_c"), _claim_value(extracted, "bed_max_c")]
    if not brand or not material or diameter is None or not color or None in nozzle or None in bed:
        return
    candidates = []
    for row in json.loads(database_path.read_text(encoding="utf-8")):
        row_name = str(row.get("name") or "").lower().replace("gray", "grey")
        row_brand = str(row.get("manufacturer") or "").lower().replace(" 3d", "")
        if (
            (brand in row_brand or row_brand in brand)
            and row.get("material") == material
            and row.get("diameter") == diameter
            and color in row_name
            and row.get("extruder_temp_range") == nozzle
            and row.get("bed_temp_range") == bed
            and isinstance(row.get("weight"), int)
        ):
            candidates.append(row)
    weights = {row["weight"] for row in candidates}
    if candidates and len(weights) == 1:
        weight = weights.pop()
        extracted["net_weight_g"] = {
            "value": weight,
            "confidence": 0.8,
            "evidence": f"SpoolmanDB-Community candidate consensus ({len(candidates)} matching rows)",
            "basis": "catalog",
        }
        extracted["needs_user_review"].append(f"Mass {weight} g was supplied by SpoolmanDB-Community candidate consensus because it is not printed on the label; confirm the package size.")


def image_request_id(images: list[tuple[str, str, bytes]], decoded_codes: list[dict] | None = None) -> str:
    digest = hashlib.sha256()
    for role, _mime_type, decoded in images:
        digest.update(role.encode("utf-8")); digest.update(b"\0"); digest.update(struct.pack(">I", len(decoded))); digest.update(decoded)
    normalized_codes = sorted(
        (
            str(code.get("format") or ""), str(code.get("value") or ""),
            str(code.get("kind") or ""), str(code.get("photo_role") or ""),
        )
        for code in (decoded_codes or []) if isinstance(code, dict)
    )
    for values in normalized_codes:
        for value in values:
            digest.update(value.encode("utf-8")); digest.update(b"\0")
    return digest.hexdigest()


def extract_label(images: list[tuple[str, str, str]], decoded_codes: list[dict], api_key: str, opener=urllib.request.urlopen) -> dict:
    codes_text = json.dumps(decoded_codes, ensure_ascii=True)[:8_000]
    prompt = (
        PROMPT
        + " Estimate color_hex from the visibly represented filament color when possible; return six hexadecimal digits without #."
        + " Local ZXing decoded these symbols independently. Treat them as code evidence, keep marketplace IDs and QR payloads distinct, and do not convert them into GTINs unless their format and check digit prove that classification: "
        + codes_text
    )
    content = [{"type": "input_text", "text": prompt}]
    for role, mime_type, image_base64 in images:
        content.append({"type": "input_text", "text": f"Photo role: {role}"})
        content.append({"type": "input_image", "image_url": f"data:{mime_type};base64,{image_base64}", "detail": "high"})
    body = {
        "model": MODEL,
        "store": False,
        "reasoning": {"effort": "none"},
        "input": [{"role": "user", "content": content}],
        "text": {"format": {"type": "json_schema", "name": "filament_label", "strict": True, "schema": service_schema()}},
    }
    request = urllib.request.Request(
        "https://api.openai.com/v1/responses",
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        method="POST",
    )
    with opener(request, timeout=100) as response:
        raw = json.load(response)
    output_text = "".join(
        content.get("text", "") for item in raw.get("output", []) for content in item.get("content", [])
        if content.get("type") == "output_text"
    )
    if not output_text:
        raise RuntimeError("The AI provider returned no structured label result")
    extracted = json.loads(output_text)
    enrich_missing_mass(extracted)
    return {
        "provider": "openai",
        "model": raw.get("model") or MODEL,
        "fields": {key: value for key, value in extracted.items() if key not in {"other_codes", "needs_user_review"}},
        "other_codes": extracted["other_codes"],
        "needs_user_review": extracted["needs_user_review"],
    }


class LabelHandler(BaseHTTPRequestHandler):
    server_version = "SpoolForgeLabelService/0.1"

    def log_message(self, format: str, *args) -> None:
        # Keep development output useful without recording paths, request bodies, or image data.
        print(f"label-service {self.command} {self.path} -> {args[1] if len(args) > 1 else '-'}", flush=True)

    def send_json(self, status: int, value: dict) -> None:
        data = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if self.path == "/healthz":
            self.send_json(200, {"status": "ready", "provider": "openai", "model": MODEL, "amazon_catalog": "ready" if self.server.amazon_api_key else "not_configured"})
        else:
            self.send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        if self.path == "/v1/amazon-catalog/search":
            self.handle_amazon_catalog(); return
        if self.path != "/v1/label-scan":
            self.send_json(404, {"error": "Not found"}); return
        try:
            request_started = time.perf_counter()
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_REQUEST:
                raise ValueError("Request must be between 1 byte and 9 MB")
            body = json.loads(self.rfile.read(length))
            image_values = body.get("images")
            request_id = body.get("request_id")
            codes = body.get("decoded_codes", [])
            if not isinstance(image_values, list) or len(image_values) != 1 or not isinstance(codes, list) or not isinstance(request_id, str):
                raise ValueError("Invalid label scan request")
            images = []
            for index, value in enumerate(image_values):
                if not isinstance(value, dict) or value.get("role") != PHOTO_ROLES[index] or value.get("mime_type") not in {"image/jpeg", "image/png", "image/webp"} or not isinstance(value.get("image_base64"), str):
                    raise ValueError("Label photos must use the expected ordered roles")
                decoded = base64.b64decode(value["image_base64"], validate=True)
                if not decoded or len(decoded) > 6_000_000:
                    raise ValueError("Each label photo must be 6 MB or smaller")
                images.append((value["role"], value["mime_type"], decoded, value["image_base64"]))
            decoded_at = time.perf_counter()
            if request_id != image_request_id([(role, mime, decoded) for role, mime, decoded, _encoded in images], codes):
                raise ValueError("Label scan request identifier does not match the images and decoded codes")
            provider_ms = 0
            def compute():
                nonlocal provider_ms
                provider_started = time.perf_counter()
                value = extract_label([(role, mime, encoded) for role, mime, _decoded, encoded in images], codes[:32], self.server.api_key)
                provider_ms = round((time.perf_counter() - provider_started) * 1000)
                return value
            result, cache_hit = self.server.result_cache.get_or_compute_with_status(request_id, compute)
            total_ms = round((time.perf_counter() - request_started) * 1000)
            decode_ms = round((decoded_at - request_started) * 1000)
            print(
                f"label-service timing images={len(images)} image_bytes={sum(len(item[2]) for item in images)} decode_ms={decode_ms} "
                f"provider_ms={provider_ms} total_ms={total_ms} cache_hit={str(cache_hit).lower()}",
                flush=True,
            )
            self.send_json(200, result)
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
        except Exception as exc:
            # Provider bodies can contain sensitive request metadata; return only the exception class.
            print(f"label-service provider failure: {type(exc).__name__}", flush=True)
            self.send_json(502, {"error": "AI label analysis failed on the development service"})

    def handle_amazon_catalog(self) -> None:
        if not self.server.amazon_api_key:
            self.send_json(503, {"error": "Amazon catalog provider is not configured"}); return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 64_000:
                raise ValueError("Amazon lookup request must be between 1 byte and 64 KB")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("Amazon lookup request must be an object")
            fingerprint = hashlib.sha256(json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
            result = self.server.amazon_result_cache.get_or_compute(
                fingerprint, lambda: lookup_amazon_catalog(body, self.server.amazon_api_key)
            )
            self.send_json(200, result)
        except (ValueError, json.JSONDecodeError) as exc:
            self.send_json(400, {"error": str(exc)})
        except Exception as exc:
            print(f"amazon-catalog provider failure: {type(exc).__name__}", flush=True)
            self.send_json(502, {"error": "Amazon catalog lookup failed on the development service"})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", type=Path, default=Path(".env.local"))
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    api_key = load_key(args.env, "OPENAI_API_KEY")
    try:
        amazon_api_key = load_key(args.env, "RAINFOREST_API_KEY")
    except RuntimeError:
        amazon_api_key = None
    server = ThreadingHTTPServer(("127.0.0.1", args.port), LabelHandler)
    server.api_key = api_key
    server.amazon_api_key = amazon_api_key
    server.result_cache = ResultCache()
    server.amazon_result_cache = ResultCache()
    print(f"label-service ready on 127.0.0.1:{args.port} provider=openai model={MODEL}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
