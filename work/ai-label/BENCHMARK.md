# Filamajig label extraction benchmark

This benchmark compares providers against facts reviewed directly from real
filament package photographs. Every provider receives the same source image and
logical extraction contract. Provider-specific transport formats are normalized
before scoring.

## Add a case

1. Copy the original photograph into `fixtures/` without resizing or enhancing it.
2. Add a case to `benchmark-manifest.json` containing only facts visibly supported
   by the photograph. Use `null` for fields that are not visible.
3. List distinctive product-name terms separately because word order and
   punctuation may vary without changing the meaning.
4. Record secondary identifiers with their reviewed type, such as `FNSKU`, `ASIN`,
   `QR`, or `lot`.
5. Run every candidate model against the same image and add each sanitized result
   to `runs`.

Manufacturer and label brand are separate. A parent company inferred from product
knowledge is not image ground truth. Catalog enrichment may add it later with its
own provenance.

## Run the scorer

```bash
python3 tools/run_label_benchmark.py work/ai-label/benchmark-manifest.json

python3 tools/score_label_benchmark.py \
  work/ai-label/benchmark-manifest.json \
  --output work/ai-label/benchmark-score.json \
  --markdown work/ai-label/BENCHMARK_SCORE.md
```

The scorer reports exact structured fields, reviewed product-name term coverage,
identifier classification, and unsupported non-null claims. Latency and request
cost will be added once multiple real cases make those measurements meaningful.

## Selection threshold

Do not select a production provider from the current one-image smoke test. The
first useful comparison should contain at least 10-20 packages across brands,
materials, label layouts, code types, lighting, focus, glare, and field absence.
Keep a small holdout set out of prompt changes so improvements are not tuned only
to known labels.
