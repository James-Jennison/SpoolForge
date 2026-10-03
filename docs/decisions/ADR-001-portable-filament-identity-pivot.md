# ADR-001: Pivot to portable filament identity

- Status: Accepted for pivot foundation
- Date: 2026-09-07

## Context

The repository began as an offline Android OFD/OpenSpool tag manager and expanded through GTIN indexing, barcode/QR capture, AI label extraction, local edits, PAXX encoding, generated labels and early inventory-like recents. The market moved: Tag My Spool now offers offline OFD-backed multi-format tag authoring and labels; Spool Hoarder offers local-first inventory plus AI/UPC/NFC/QR; SimplyPrint offers broad NFC, labels and automatic printer usage. A generic “filament scanner and inventory” position is not defensible.

## Decision

SpoolForge, then named Filamajig NFC, becomes a native Android portable filament identity workbench. Its core is provenance-aware normalization, reusable filament profiles, individual spool identity, loss-aware tag/QR conversion, and explicit verified codec targets. OFD remains primary open data. SpoolmanDB Community is a later provider. PAXX U1 Extended for `v1.5.2-paxx12-21` remains a first-class codec profile distinct from standard OpenSpool.

Catalog source schema, canonical domain, persistence, codec serialization and NFC hardware I/O are separate boundaries. Core operation remains local/account-free. Printer control remains out of scope. AI recognition remains optional.

## Consequences

- Existing native UI, catalog, GTIN index, scans, local edit provenance and conservative NFC state machine are retained.
- `FilamentRecord` remains a compatibility model while new canonical/profile/spool types are introduced safely.
- Existing saved records need an additive migration before inventory tracking ships.
- Tag screens must state target format, target firmware where relevant, fields omitted, bytes/capacity and verification result.
- The broad workflow is not claimed as unique; exact provenance/conversion/PAXX guarantees must earn the differentiation through tests.
- 3D Filament Profiles is not scraped and is no longer an assumed dependency.
- Historical Amazon/Rainforest and Icecat research is retained but superseded for product direction.

## Risks and unresolved questions

- Competitors may add transparent provenance or exact PAXX support.
- Cross-dataset entity resolution can create false matches; no automatic conflict merge is accepted yet.
- A portable QR schema requires a separate versioned specification and interoperability review.
- The pinned PAXX release must remain the acceptance oracle even when upstream `develop` changes.
- Debug AI credentials are unsuitable for release distribution.
