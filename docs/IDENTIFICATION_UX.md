# Identification and UX hardening

SpoolForge keeps three independent identification paths. A user can scan one label or QR with AI, perform an exact offline GTIN lookup, or create/review records manually including bulk CSV/TSV input. AI is optional and a failed network request never blocks the offline catalog, retail-code lookup, or manual editor.

## One-photo scan lifecycle

The camera decodes visible retail and QR symbols locally while framing the still. After capture, the JPEG is copied into the app-private files directory before analysis begins. The activity handles orientation and screen-size changes in place; Compose changes the camera layout and CameraX receives the new target rotation. The analysis coroutine remains ViewModel-owned, and the full-screen transition shows a changing stage, attempt number, and elapsed time.

If Android terminates the process, the network request itself cannot continue. The next app process restores the private photo and offers **Retry analysis** or **Discard photo**. A retry derives the same idempotency key from the image and deterministic code evidence. Connection failures receive one bounded retry; HTTP errors are returned without a blind retry. The user must still review every extracted field before saving.

The pending photo is deleted when the scan is discarded or the review closes. Android backup is disabled for the application. The debug prototype sends the captured photo and locally decoded code evidence to the configured OpenAI Responses endpoint. A distributable build still requires a production credential boundary; the embedded debug key is not a release design.

## Bulk input

**Bulk add CSV** accepts comma- or tab-separated text up to 256 KB and 100 data rows. Required headers are `brand`, `material`, and `color`. Optional headers are `product`, `color_hex`, `diameter_mm`, `weight_g`, `nozzle_min_c`, `nozzle_max_c`, `bed_min_c`, `bed_max_c`, `gtin`, `sku`, and `transmission_distance`.

The parser enforces unique supported headers, balanced/positioned quotes, the exact field count, a 4,096-character field limit, and valid GTIN check digits. Missing diameter and weight receive the product defaults of 1.75 mm and 1,000 g with explicit assumed-default provenance. Every row opens in the existing editor and must be reviewed before it is saved.

## 2023 Razr acceptance

Acceptance was run on `motorola razr 2023`, Android API 36, with debug version `0.6.0-m6`.

| Pipeline | Device time | Result boundary |
|---|---:|---|
| Local QR/code decode from one MarsWork image | 5,399.01 ms | Code evidence ready; excludes camera framing |
| OpenAI label analysis | 8,905.44 ms | Editable record ready; excludes human review |
| Cold exact offline GTIN lookup | 199.82 ms | Candidate list ready |
| Obscure manual CSV preparation | 2.20 ms | Gizmo Dorks HIPS White editor seed ready |

The AI result retained the visibly printed MARSWORK brand, PLA material, Cyan color and HueForge TD 2.7. The absent diameter and weight were visibly sourced as the 1.75 mm and 1,000 g defaults. The total measured local decode plus AI work was under twelve seconds and the AI portion passed the one-minute acceptance threshold.

Device tests also verified that:

- `MainActivity` remained the same instance while moving to landscape and back to portrait.
- A simulated transport interruption made exactly two attempts with the same request identity and returned an actionable internet-connection error.
- A process-interrupted private photo restored into the explicit retry/discard state.
- The one-photo MarsWork image contained a QR code that decoded locally before AI analysis.

These timings are controlled pipeline comparisons, not complete human task times. They exclude aiming the camera, reviewing fields, correcting uncertain values, saving a record, and writing an NFC tag. Network and provider latency will vary.
