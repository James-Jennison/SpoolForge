# ChatGPT plan model benchmark

Date: 2026-10-03. Device: Motorola Razr 2026.

Every model the connected ChatGPT plan listed was run against the six reviewed label fixtures in
`fixtures/`, converted to JPEG, through the app's own request path (`DeviceChatGptModelBenchmark`):
local ZXing code decoding, the app prompt, the label JSON schema as strict structured output, and
`POST /v1/responses` with `store: false` and `stream: true`, charged to the plan. Scoring uses
`tools/score_label_benchmark.py` and the ground truth in `benchmark-manifest.json`. Raw output is in
`chatgpt-plan-benchmark-results.json`.

| Model | Completed | Exact fields | Product terms | Identifier types | Unsupported values | Median time | Slowest |
|---|---:|---:|---:|---:|---:|---:|---:|
| gpt-6-astra | 6/6 | 72/77 | 24/25 | 0/5 | 5 | 43.7 s | 50.1 s |
| gpt-5.6-sol | 6/6 | 72/77 | 25/25 | 0/5 | 4 | 36.7 s | 39.9 s |
| **gpt-5.6-terra** | 6/6 | 72/77 | 25/25 | 0/5 | 2 | 14.4 s | 16.1 s |
| gpt-5.6-luna | 6/6 | 71/77 | 25/25 | 0/5 | 4 | 21.6 s | 27.9 s |
| gpt-5.5 | 6/6 | 71/77 | 22/25 | 0/5 | 3 | 22.6 s | 25.4 s |

All 30 requests accepted image input and strict structured output on the first attempt; no usage
limit was reported.

**Selection: `gpt-5.6-terra`.** It ties for the most exact fields, reconstructs every product term,
makes the fewest unsupported claims, and is two to three times faster than the others. Its five
misses were the brand on three labels (`Zew` for 3DHoJor, `TING` for FLASHFORGE, and `Panchroma™ PLA`
with the material appended), a manufacturer claim on the ANYCUBIC label, and a GTIN claim on the
KINGROON label. Brand therefore still needs the user's review, which the app already requires.

Limits: six labels and one run per pair. Timings include the phone's network and vary between runs.
This is comparative evidence for choosing among the plan's models, not a measure of accuracy across
the filament market. Re-run the benchmark when the plan's model list changes.
