# Open Icecat coverage trial

Date: 2026-09-07

## Purpose

Evaluate whether the free Open Icecat catalog materially fills known GTIN gaps in Filamajig NFC's retained OFD, OpenPrintTag, and SpoolmanDB Community index.

## Method

Five public product identifiers known to be absent from the shipped Filamajig GTIN index were submitted through Icecat's authenticated product-feed coverage analyzer. Matching used all three supplied columns: manufacturer brand, manufacturer product code, and GTIN.

| Brand | Manufacturer product code | GTIN | Filamajig candidates before trial |
|---|---|---:|---:|
| Polymaker | CA09019 | 6938936717461 | 0 |
| SUNLU | SLU-24156 | 6933582308674 | 0 |
| NinjaTek | 3DNF0517510 | 0662345234160 | 0 |
| UltiMaker | 1609 | 8718836374555 | 0 |
| Verbatim | 55017 | 023942550174 | 0 |

## Results

Icecat reported:

- 5 submitted products.
- 2 products matched Icecat overall (40%).
- 1 product was available through free Open Icecat.
- 1 matched product was not authorized for this free account.
- 3 products were skipped.
- 0 duplicates.

The generated Personal Index File contains one accessible record:

| Brand | Product code | Identifier | Access outcome |
|---|---|---|---|
| Verbatim | 55017 | 0023942550174 / 023942550174 | Open Icecat record available |

The skipped-product report contains Polymaker, SUNLU, and NinjaTek. Because those are the three skipped inputs, and Verbatim is the accessible Open record, the remaining overall match is UltiMaker and is the record reported as unauthorized. This last association is a deterministic inference from the two Icecat output files and aggregate counts; the restricted record itself was not downloaded.

The accessible Verbatim XML path requires Icecat account authentication: an unauthenticated HEAD request returned HTTP 401. No credentials, cookies, access keys, or tokens were retained in these artifacts.

## Conclusion

Open Icecat free access adds verified value but does not materially solve the tested filament-identifier gap by itself: it contributed one distributable candidate from five known misses. It should be treated as a supplemental source, subject to a final license/fair-use review and confirmation that app-bundled offline redistribution is permitted. Full Icecat could add the tested UltiMaker record, but the trial provides no evidence that a paid subscription would cover the three skipped brands.

Before implementing an importer, obtain a credential-safe automated access route and run a larger stratified sample. The sample should cover the highest-volume zero-identifier brands and regional GTIN variants. Preserve Icecat as field-level provenance, and never infer material, color, diameter, mass, or print temperatures from an identifier-only match.

## Artifacts

- `icecat-coverage-trial.csv` SHA-256 `e4a88065cd5db4a491c9b7a5ae0a8192ec6925a68ec0825020330e4fb214d6a5`
- `icecat-trial-open-match.csv` SHA-256 `30275f36c5444419995d20eb64cd80da6ccd00cd7247859dfd387d22f731ac46`
- `icecat-trial-skipped.csv` SHA-256 `9b420fb468c02cb6fd9e2b0ce31abf0b8f9b93b98ab1918adfb79e545e8d0c4e`
