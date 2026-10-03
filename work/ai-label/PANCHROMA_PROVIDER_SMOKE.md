# Filament label provider smoke comparison

Date: 2026-09-07

This is one-image smoke evidence, not a provider selection benchmark. Each model
received the same Panchroma package photograph, extraction instructions, and
logical output contract. Claude required a flatter transport schema because its
structured-output compiler rejected the repeated nested schema; the result was
normalized back to the common field structure.

## Ground truth visible in the photograph

- Printed label brand: Panchroma
- Manufacturer: not visibly printed
- Product: Panchroma PLA Gradient, Starlight Purple-Red
- Material: PLA
- Color: Starlight Purple-Red
- Diameter: 1.75 mm
- Net weight: 1,000 g
- GTIN/EAN: 6938936717461
- SKU: CA09019
- Marketplace identifier: X004QSH4ZH, an Amazon FNSKU-style identifier
- No nozzle, bed, or lot values are visible

## Observed results

| Model | Strong result | Material limitation |
|---|---|---|
| GPT-5.6 Terra | All core identity/package values; correct missing fields | Product value omitted Purple-Red; marketplace code remained generic |
| Grok 4.6 | All core identity/package values; complete product reconstruction | Marketplace code remained generic; absence confidence used a different convention |
| Gemini 3.8 Flash | All core values except the complete product suffix; uniquely classified FNSKU correctly | Product value omitted Starlight Purple-Red; absence confidence used a different convention |
| GPT-5.6 Sol | All core identity/package values; complete product reconstruction | Marketplace code remained generic; no material improvement over Grok on this image |
| Claude Sonnet 5 | Most core values and all missing temperatures | Color omitted Starlight; marketplace code was incorrectly described as a likely partial ASIN |

The corrected contract separates `label_brand` from `manufacturer`. This avoids
turning the visibly printed Panchroma identity into an unsupported Polymaker or
Bambu Lab manufacturer claim. Parent manufacturer resolution belongs to catalog
evidence keyed by GTIN, SKU, or a verified product-family relationship.

## Initial interpretation

No provider wins from one image. Gemini showed the strongest identifier-type
understanding. Sol and Grok produced the most complete product value. Terra was
close to those results at lower cost than Sol. Claude was weakest on the two
fields that differed across providers.

A provider decision requires a stratified set of real package photographs with
brands, retail barcodes, QR codes, marketplace overlays, small temperature text,
glare, rotated labels, and absent fields. Score exact values, correct omissions,
identifier type, unsupported inference, latency, and measured request cost.

The deterministic one-case score is recorded in `BENCHMARK_SCORE.md`. It keeps
exact structured fields, product-term coverage, identifier classification, and
unsupported claims separate so one aggregate percentage cannot conceal the type
of error.
