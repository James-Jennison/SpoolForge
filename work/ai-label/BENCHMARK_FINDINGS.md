# Six-label benchmark findings

Date: 2026-09-07

## Evidence status

The benchmark contains six original filament-package photographs and 30 completed
provider/image runs. Terra, Grok, Gemini, Sol, and Claude all have full six-image
coverage. A transient Gemini quota response interrupted the first batch, but the
resumable runner retained completed work and the remaining calls succeeded after
the account's paid quota became active.

The Gemini runner uses Google's current Interactions API at
`https://generativelanguage.googleapis.com/v1beta/interactions`. Google's current
API reference documents that endpoint, multimodal image input, and schema-bound
JSON through `response_format`; six live results were produced through this path.

## Current result

GPT-5.6 Terra is the provisional semantic-extraction baseline. Across all six
images it returned 74/77 exact structured fields, 23/25 reviewed product terms,
and two unsupported non-null claims. Grok reconstructed every product term but
returned more unsupported structured claims. Gemini led exact structured fields
at 75/77 and identifier classification at 4/5, but reconstructed only 8/25 product
terms. Sol and Claude did not exceed Terra on the combined structured-field and
product evidence.

This is not a production provider selection. Six labels are enough to expose
failure modes, but not enough to establish accuracy across the filament market.

## Code handling result

ZXing 3.5.4 decoded every visible machine-readable symbol that was evaluated:

| Label | Symbol | Decoded value |
|---|---|---|
| Panchroma | QR | `6938936717461` |
| Panchroma | Code 128 | `X004QSH4ZH` |
| Gizmo Dorks | UPC-A | `887503120752` |
| Kingroon | QR | `https://qr12.cn/CRDd80` |
| Anycubic | Code 128 | `X004LDC21N` |
| 3DHoJor | Code 128 | `X00444MZE3` |
| Flashforge | Code 128 | `X0037RJ695` |

Gemini correctly classified all four Amazon FNSKU values but omitted the Kingroon
QR URL. No other model correctly classified any of the five reviewed secondary
identifier types; they variously called Amazon label values ASINs, generic
marketplace identifiers, or manufacturer SKUs.

## Proposed prototype pipeline

1. Preserve the captured still image as the common input for deterministic and AI
   extraction.
2. Decode all supported linear and QR symbols locally with ZXing, retaining value,
   symbology, bounding box, and source-image identity.
3. Normalize valid UPC/EAN values to GTIN-14 for lookup while retaining the printed
   representation.
4. Keep non-GTIN Code 128 values and QR payloads distinct. Treat `X00...` values as
   Amazon-label candidates until catalog evidence establishes their role.
5. Send the image to the selected vision provider for brand, product, material,
   color, dimensions, weight, and temperature extraction. Decoded code facts may
   be supplied as context, but the model cannot replace the deterministic values.
6. Merge only provenance-compatible claims and require user review for conflicting,
   inferred, or low-confidence values.

This pipeline avoids asking a vision model to recover information that the phone's
barcode decoder can read exactly, while preserving AI for the unstructured label
content that deterministic OCR and barcode lookup do not resolve.

The combined pipeline has not yet been measured end to end. The current evidence
separately establishes image-only provider behavior and deterministic code-decoding
behavior. Prototype acceptance must verify their merge, provenance precedence,
conflict handling, and user-review path on the same fixtures.
