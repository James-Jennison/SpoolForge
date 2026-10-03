# Offline catalog providers

SpoolForge treats catalogs as evidence providers rather than canonical truth. OFD, local records, the merged exact-GTIN index, and SpoolmanDB Community implement the same `CatalogProvider` boundary and return canonical `FilamentProfile` candidates with source references on every populated field.

## Matching and conflict rules

Candidates are ordered deterministically by:

1. exact GTIN/EAN/UPC or manufacturer-code/SKU match;
2. exact structured-field match;
3. partial structured-field match;
4. text match.

The ranker never combines or deletes candidates. When candidates sharing an identifier disagree on brand, product, material, color, diameter, or nominal mass, each candidate carries the same explicit set of conflicting fields for the UI and persistence layer to preserve.

The bundled Community catalog is a secondary provider. Its 53,355 compiled package variants remain distinct, including different diameter, mass, spool, and refill variants. The provider uses the compiled record ID as its stable external identity and an `artifact:<sha256>` revision because the retained compiled artifact, rather than an unproven repository commit, is the build input.

## Offline rebuild and verification

Run these from the repository root:

```bash
python3 tools/build_community_catalog.py
python3 tools/test_build_community_catalog.py
python3 tools/verify_provider_assets.py
heavy-gradle :core:test :app:testDebugUnitTest
```

The builder uses sorted rows, canonical JSON, SQLite `WITHOUT ROWID` keys, `VACUUM`, and gzip `mtime=0`. Rebuilding the same retained source produces byte-identical assets and reports. The verifier checks the retained source and license hashes, compressed and uncompressed asset hashes, SQLite integrity and row counts, FTS population, exact SKU lookup, an ambiguous GTIN fixture, and representative text lookup without network access.

The generated [barcode coverage report](../work/providers/BARCODE_COVERAGE.md) measures exact-identifier availability by material and manufacturer in the retained Community snapshot. It does not measure camera decoding or claim market-wide coverage.
