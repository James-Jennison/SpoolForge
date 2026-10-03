"""Normalized Amazon catalog candidates from a replaceable server-side provider."""

from __future__ import annotations

from datetime import datetime, timezone
import json
import re
import urllib.parse
import urllib.request

RAINFOREST_ENDPOINT = "https://api.rainforestapi.com/request"
AMAZON_DOMAIN = "amazon.com"
TEXT_FIELDS = ("brand", "product", "material", "color", "sku")


def valid_gtin(value: object) -> bool:
    digits = re.sub(r"\D", "", str(value or ""))
    if len(digits) not in {8, 12, 13, 14}:
        return False
    body, check = digits[:-1], int(digits[-1])
    total = sum(int(char) * (3 if (len(body) - index) % 2 else 1) for index, char in enumerate(body))
    return (10 - total % 10) % 10 == check


def gtin_variants(value: object) -> list[str]:
    """Prefer the printed-length retail code over a GTIN-14 zero-padded form."""
    digits = re.sub(r"\D", "", str(value or ""))
    values = []
    for length in (12, 13, 8, 14):
        candidate = digits[-length:]
        if len(digits) >= length and valid_gtin(candidate) and candidate not in values:
            values.append(candidate)
    return values


def search_terms(evidence: dict) -> str:
    parts: list[str] = []
    for key in TEXT_FIELDS:
        value = str(evidence.get(key) or "").strip()
        if value and value.casefold() not in {part.casefold() for part in parts}:
            parts.append(value)
    diameter = evidence.get("diameter_mm")
    mass = evidence.get("net_weight_g")
    if isinstance(diameter, (int, float)) and diameter > 0:
        parts.append(f"{diameter:g}mm")
    if isinstance(mass, int) and mass > 0:
        parts.append(f"{mass}g")
    for code in evidence.get("other_codes") or []:
        value = str(code.get("value") if isinstance(code, dict) else "").strip()
        if value and value.casefold() not in {part.casefold() for part in parts}:
            parts.append(value)
    return " ".join(parts)[:240]


def _request(parameters: dict, api_key: str, opener=urllib.request.urlopen) -> dict:
    query = urllib.parse.urlencode({"api_key": api_key, "amazon_domain": AMAZON_DOMAIN, **parameters})
    request = urllib.request.Request(f"{RAINFOREST_ENDPOINT}?{query}", headers={"Accept": "application/json"})
    with opener(request, timeout=45) as response:
        return json.load(response)


def _candidate(product: dict, reason: str, retrieved_at: str) -> dict | None:
    asin = str(product.get("asin") or "").strip().upper()
    title = str(product.get("title") or "").strip()
    if not re.fullmatch(r"[A-Z0-9]{10}", asin) or not title:
        return None
    image = product.get("image")
    if isinstance(image, dict):
        image = image.get("link")
    if not image and isinstance(product.get("main_image"), dict):
        image = product["main_image"].get("link")
    return {
        "asin": asin,
        "title": title,
        "brand": str(product.get("brand") or product.get("manufacturer") or "").strip() or None,
        "image_url": str(image).strip() if image else None,
        "amazon_url": f"https://www.amazon.com/dp/{asin}",
        "match_reasons": [reason],
        "provenance": {
            "source": "Amazon marketplace via Rainforest API",
            "marketplace": AMAZON_DOMAIN,
            "retrieved_at": retrieved_at,
        },
    }


def lookup(evidence: dict, api_key: str, opener=urllib.request.urlopen, retrieved_at: str | None = None) -> dict:
    """Return review-only candidates. No returned field is approved for automatic merge."""
    retrieved_at = retrieved_at or datetime.now(timezone.utc).isoformat()
    gtins = gtin_variants(evidence.get("gtin"))
    if gtins:
        gtin = gtins[0]
        raw = _request({"type": "product", "gtin": gtin}, api_key, opener)
        candidate = _candidate(raw.get("product") or {}, f"Amazon product resolved from exact GTIN {gtin}", retrieved_at)
        if candidate:
            return {"provider": "rainforest", "query_kind": "gtin", "candidates": [candidate]}

        # Amazon search often indexes the printed EAN/UPC even when the provider's
        # GTIN-to-ASIN mapping table has no entry for it.
        raw = _request({"type": "search", "search_term": gtin, "number_of_results": 5, "exclude_sponsored": "true"}, api_key, opener)
        candidates = [item for product in (raw.get("search_results") or [])[:5]
                      if (item := _candidate(product, f"Amazon search matched exact printed GTIN {gtin}", retrieved_at))]
        if candidates:
            return {"provider": "rainforest", "query_kind": "gtin_keyword", "candidates": candidates}

    terms = search_terms(evidence)
    if not terms:
        return {"provider": "rainforest", "query_kind": "none", "candidates": []}
    raw = _request({"type": "search", "search_term": terms, "number_of_results": 5, "exclude_sponsored": "true"}, api_key, opener)
    candidates = []
    for product in (raw.get("search_results") or [])[:5]:
        candidate = _candidate(product, f"Amazon keyword candidate for: {terms}", retrieved_at)
        if candidate:
            candidates.append(candidate)
    return {"provider": "rainforest", "query_kind": "keywords", "candidates": candidates}
