# SpoolForge revised roadmap

> **Changed 2026-10-03:** Full Spectrum (M7–M8) was removed from the app and M9–M12 are dropped, per [ADR-002](decisions/ADR-002-refocus-on-tag-manager-and-public-release.md). The replacement milestones in that record are still proposed. The history below is kept as written.

This roadmap starts from the existing uncommitted prototype; it is not a greenfield plan.

## Existing-work classification

### Already implemented

- Native Kotlin/Compose Android app and renamed package/product identity (`app/`, `docs/BRANDING.md`).
- Bundled offline OFD catalog with 22,347 packages, FTS search, integrity metadata and Room migrations (`catalog-tool`, `Databases.kt`, catalog assets/tests).
- Merged exact GTIN index from retained OFD, OpenPrintTag and SpoolmanDB Community rows with per-match sources (`GtinIndex.kt`, `work/gtin`).
- Camera barcode/QR classification and one-photo AI label review with deterministic-code precedence (`BarcodeCamera.kt`, `LabelCamera.kt`, `LabelScan.kt`).
- User-created/edited records, field source labels, recents, deletion and process-restart persistence (`Databases.kt`, `MainViewModel.kt`).
- Standard OpenSpool and PAXX-style encoding, payload preview/omissions, NTAG capacity gate, guarded overwrite, write, fresh readback, semantic verification and durable unknown reconciliation (`OpenSpoolCodec.kt`, `WriteStateMachine.kt`, `NfcCoordinator.kt`).
- One- or two-tag completion flow and generated human-readable/QR label prototype (`MainActivity.kt`).

### Partially implemented

- Provenance is mostly a source string per field; structured source records, conflicts and retrieval dates are new scaffolding, not persisted.
- OFD, local records, merged GTIN, and SpoolmanDB Community now run behind the provider interface; live refresh and broader provider adapters remain open.
- Saved records resemble filament profiles but still combine product/package and owned-spool concerns.
- AI label scanning works as a debug prototype; release-safe credential/service design and full live acceptance remain open.
- PAXX payload writing has physical evidence for earlier payloads, but the current app needs a pinned-release regression corpus and printer acceptance for every claimed field/type.
- QR generation exists in label output; a portable versioned QR identity/import contract does not.

### Designed/documented

- OpenPrintTag and broader codec expansion, Spoolman integration, local-first boundaries, and lossless unknown-field handling (`docs/FEASIBILITY.md`, `docs/ARCHITECTURE.md`).
- Authorized future 3D Filament Profiles provider contract without scraping (`docs/THREE_D_FILAMENT_PROFILES_INTEGRATION.md`).

### Researched

- OFD, OPT, SpoolmanDB, The Filament Database, Icecat, 3D Filament Profiles, vendor formats and PAXX source snapshots (`research/`, `docs/SOURCES.md`, `work/gtin`).
- Amazon/Rainforest enrichment (`docs/AMAZON_CATALOG_ENRICHMENT.md`) was investigated and then rejected as the product path.

### Assumed but unverified

- Competitor runtime behavior beyond first-party claims.
- Complete physical interoperability across U1 bays/tag placement and all PAXX types/subtypes.
- Hosted-catalog mobile refresh reliability and production-scale cross-source entity resolution.
- Release-safe AI latency, cost and accuracy.

### Potentially obsolete

- Treating a generic scanner/inventory/tagger bundle as sufficient differentiation.
- Making 3D Filament Profiles an important upstream dependency.
- Runtime Amazon/Rainforest enrichment.
- Using “Recent” as the long-term inventory model.
- Embedding a provider API key in any distributable APK.

## Keep / Change / Add / Remove / Defer

| Decision | Item | Reason / dependency / migration |
|---|---|---|
| KEEP | Native Android, offline OFD, search, manual edit, GTIN/QR/NFC entry | Proven reusable foundation; no migration |
| KEEP | Conservative frozen NFC write and durable UNKNOWN semantics | Critical safety/accuracy behavior; extend through codec interface |
| KEEP | Standard OpenSpool and PAXX profile separation | Core compatibility distinction; strengthen pinned fixtures |
| KEEP | AI label scan as optional accelerator | Useful when catalogs miss; never required |
| CHANGE | `FilamentRecord` and Room custom records | Migrate additively into profile, observation and spool tables |
| CHANGE | Catalog importer/index | Adapt OFD to `CatalogProvider`; keep exact GTIN candidates and sources |
| CHANGE | Generated QR | Define versioned offline portable identity rather than an app-only pointer |
| CHANGE | TD single field | Current selection plus measurement history and evidence kind |
| CHANGE | “Recent” | Remains navigation history; inventory becomes explicit `PhysicalSpool` |
| ADD | Provider registry and SpoolmanDB adapter | Enables open-source breadth without schema lock-in |
| ADD | Codec registry, conversion preview and unknown-field preservation | Makes interoperability the product, not a checkbox |
| ADD | Portable import/export bundle and QR schema | Prevents app/service lock-in |
| ADD | Physical-spool CRUD and multiple tag bindings | Required to track two tags per spool correctly |
| REMOVE | Rainforest/Amazon runtime path | Owner decision and weak product fit; no active code remains, docs retained as superseded history |
| REMOVE | 3DFP scraping/dependency assumption | No supported licensed bulk contract; no code migration |
| DEFER | OpenPrintTag/OpenTag3D writer, TD1 hardware, Spoolman sync | Valuable after canonical persistence and codec registry |
| DEFER | Drying/moisture history, project accounting, printer control/cloud sync | Does not advance focused portable-identity promise |

## Milestones and mapping

### M0 — Pivot foundation (this task)

Reuses all existing prototype work. Adds competitive evidence, product/architecture/ADR/roadmap, canonical profile/spool/provenance scaffolding, provider boundary, codec boundary and unit tests. Acceptance: documents agree; legacy app still builds/tests; new models reject invalid ranges; codec targets and capacity are explicit.

### M1 — Canonical persistence and migration

Completed in user database v5: profile, observation, override, identifier, spool, tag-binding and TD-measurement tables; deterministic lossless v4 backfill; transactional compatibility/canonical writes; shared-profile-safe deletion; and a v5→v4 rollback migration that retains the legacy backup tables. Automated acceptance covers multiple spools per profile, conflicting sourced observations, pinned choices, two tag bindings, old edits/evidence, and rollback. Real-device acceptance on the 2023 Razr preserved all six custom records and four recents by logical hash, backfilled six profiles and spools with no foreign-key violations, survived a cold restart, and visibly restored saved entries.

### M2 — Provider-backed identification

Previous OFD catalog and merged GTIN work supply most input. Adapt OFD/local to `CatalogProvider`; add SpoolmanDB Community as a secondary candidate source; rank exact identifiers, then structured fields; never auto-merge conflicts. Acceptance: deterministic offline refresh/build, provenance on every candidate, duplicate/ambiguous/malformed fixtures, representative barcode miss-rate report.

Completed with OFD, local, merged-GTIN and full SpoolmanDB Community adapters; a deterministic 53,355-variant offline Community sidecar; 30,161 GTIN/SKU associations; exact-identifier then structured/text ranking; and conflict annotations that retain every candidate. Host tests cover malformed identifiers, ambiguous GTINs, cross-provider duplicate SKUs, provenance, deterministic ordering and corrupt-sidecar repair. The material/manufacturer coverage report records a 91.22% exact-barcode miss rate within the retained Community snapshot and states its denominator. Real-device acceptance on the 2023 Razr returned four ambiguous GTIN candidates, three Community candidates for shared SKU `33102`, one structured text result, an 87.42 ms initial lookup and 4.08 ms warm p95; the visible combined search retained all five OFD/Community SKU candidates and displayed the selected Community source correctly.

### M3 — Portable spool identity and labels

Builds on current QR label prototype. Define and publish a versioned compact offline QR schema, import/export bundle, configurable human-readable labels and spool/profile identity distinction. Acceptance: round-trip without service, forward-version handling, Unicode and capacity tests, app-removal survivability documented.

Completed with the self-contained UTF-8 `filamajig.spool` version 1 QR contract; distinct reusable-profile and physical-spool IDs; initial and remaining quantities; a source-bearing JSON export bundle; local camera and pasted-bundle import; and configurable human-readable label fields. Host acceptance covers deterministic offline round trips, valid non-BMP Unicode, malformed and forward-version input, QR capacity, imported identity persistence, shared profiles, collision rollback and deletion. Real-device acceptance on the 2023 Razr verifies the actual camera decode path, exact Unicode and identity round trip, local operation, and the visible import/export surfaces. App-removal recovery and privacy boundaries are documented in [`PORTABLE_IDENTITY.md`](PORTABLE_IDENTITY.md).

### M4 — Codec registry and pinned PAXX release

Previous OpenSpool/PAXX/NFC state-machine work remains substantial. Route UI through registry; add exact standard vs PAXX field/omission previews and release-pinned fixtures; bind one or two tags to a spool. Acceptance: unit round trips, byte capacity, malformed/unknown preservation, current Razr NTAG215 write/readback, and U1 `v1.5.2-paxx12-21` recognition across the supported field matrix. No generic claim substitutes.

Completed with an explicit Standard OpenSpool/PAXX codec registry; exact included-field, JSON-type, omitted-field and NTAG215-capacity previews; byte-stable release-pinned PAXX fixtures; and transactional one- or two-tag spool bindings recorded only after fresh semantic readback. Host acceptance covers the supported PLA, PETG, ABS, TPU and PVA type matrix plus Silk, Rapid, Transparent, Flexible and Support subtype mappings, malformed and unknown-field preservation, restart reconciliation, duplicate UIDs, second-tag failure, and binding-callback recovery without rewriting. All 101 host tests, lint and both APK assemblies pass. Five instrumentation tests pass on the 2023 Razr, and a real NTAG215 written there was read back, bound as a single verified tag and recognized in U1 Bay 1 running `v1.5.2-paxx12-21` in OpenRFID mode.

### M5 — Open ecosystem adapters

Add read/convert support before write support for OpenPrintTag and OpenTag3D, then Spoolman import/export/sync. Acceptance is format-specific and separates radio hardware, read, write, verify and conversion. No printer control.

Implemented first in 0.5.0-m5 with revision-pinned read/convert adapters and later extended with explicit read/write codecs for OpenTag3D, OpenPrintTag, Anycubic ACE, Creality CFS, QIDI Box, and TigerTag. The CANVAS/U1 milestone adds a printer-compatibility resolver and a single-format ELEGOO CANVAS NTAG215 path consumed by PAXX/OpenRFID's Elegoo processor; stock U1 firmware is excluded. Every transport retains inspect, consent, write, fresh readback, and unknown-outcome semantics; unknown existing content is not overwritten. Physical acceptance remains format-specific and is not inferred from host tests. See [`OPEN_ECOSYSTEM_ADAPTERS.md`](OPEN_ECOSYSTEM_ADAPTERS.md).

### M6 — Identification and UX hardening

Refine the existing one-photo AI and retail-code paths, landscape/task restoration, latency transition, accessibility, bulk entry and one-minute workflow benchmarks. AI remains optional. Acceptance: real-device rotations, interruptions, network failure, obscure/manual filament, and side-by-side workflow timing.

Completed in 0.6.0-m6 with a one-photo camera that adapts and updates CameraX rotation in portrait or landscape; an elapsed, staged analysis transition; private captured-photo recovery after process interruption; explicit retry/discard after failures; stable idempotency across a bounded connection retry; accessible scanner/progress semantics; strict CSV/TSV bulk entry for up to 100 review-before-save records; and retained offline/manual paths when AI is unavailable. Host tests cover request identity, provider errors, strict bulk parsing/defaults and existing label reconciliation. On the 2023 Razr, the same activity instance survived portrait/landscape transitions, the final artifact's real MarsWork label run produced an editable MARSWORK PLA Cyan record with TD 2.7 in 8.905 seconds after a 5.399-second local QR decode, and the interrupted-network and process-recovery paths passed. The comparable device pipelines and limitations are recorded in [`IDENTIFICATION_UX.md`](IDENTIFICATION_UX.md).

### M7 — Full Spectrum foundation

Add richer TD/optical characterization, extensible appearance and C/M/Y/G/White/Black role assertions, recommended-versus-observed suitability, explicit unknown/untested states, and progressive-disclosure profile UI. Use an additive user database v5→v6 migration and leave NFC wire formats and portable identity v1 unchanged. No automatic numeric suitability thresholds until a reviewed evidence policy exists.

Implemented and validated in 0.7.0-m7 with canonical optical characterizations and shared evidence, extensible role and appearance assertions, separate recommended/observed assessments and factors, additive v5→v6 migration, repository APIs, and a collapsed profile surface. The protected build, SHA-bound exact-device acceptance on the 2023 Razr, compatibility guards, and independent Grok/Gemini/DeepSeek closure review pass. Release readiness remains a separate gate.

### Proposed M8 — Search and inventory readiness

Add indexed role, TD, evidence-origin, suitability, calibration-state and ownership filters. Report deterministic CMYG completeness, missing roles, anchors and alternate candidates. Do not rank a “best” set until a versioned scoring policy and minimum evidence rules pass review.

Implemented in 0.8.0-m8 with canonical DAO-backed local search, additive Room v6→v7 search indexes, structured Full Spectrum filters, deterministic CMYG/anchor readiness, and alternate role candidates carrying assertion authority and evidence identifiers. Catalog candidates without saved Full Spectrum evidence remain outside structured Full Spectrum results, and no candidate receives a “best” score.

### Proposed M9 — Sets and printer deployment

Add reusable manufacturer-calibrated, community-characterized, user-characterized and experimental sets; optional White/Black anchors; printer configurations; physical spool selection; contextual tool assignments; and stale/incomplete mapping detection. The U1 reference mapping remains an adapter/configuration example rather than a core assumption.

### Proposed M10 — Portable recipes and calibration

Model ratios separately from ordered cycles, translate them through a printer deployment, and implement a 10-color calibration run with partial/failure states and distinct target, predicted, observed and measured colors. Add 2:1/1:2 and 26-color templates only after the base workflow is accepted.

### Proposed M11 — Guidance export and slicer feasibility

Export deterministic setup guides and resolved tool sequences. Research exact Snapmaker Orca versions, project schemas and golden round-trip fixtures before deciding whether a fail-closed `.3mf` adapter is justified. Do not promise direct slicer-project generation in advance.

### Proposed M12 — Optional shared evidence

Define local import/export and moderation-quality evidence first. Consider community TD measurements, roles, recipes, palette results and images only with provenance, licensing, conflict retention, outlier handling and reversible moderation.

## Previous-to-revised mapping

| Previous work/milestone | Revised destination | Status retained |
|---|---|---|
| Feasibility and OFD snapshot | M0/M2 | Research and bundled catalog retained |
| M1 offline OpenSpool prototype | M4 | Codec/NFC implementation retained; broader milestone renamed |
| GTIN merge and camera scanner | M2/M6 | Implementation retained |
| AI label prototype | M6 | Implementation retained as optional |
| PAXX/U1 repair and hardware evidence | M4 | Evidence retained; pinned matrix still required |
| Label/QR prototype | M3 | Completed with versioned offline identity and configurable rendering |
| Amazon/Icecat trials | None | Research retained; product dependency removed |

M0 through M7 are implemented and validated. M8 is implemented pending final validation and review. M9 through M12 remain future work in [`FULL_SPECTRUM_PRODUCT_DIRECTION.md`](FULL_SPECTRUM_PRODUCT_DIRECTION.md). Release engineering remains a parallel gate: choose a credential-safe production AI boundary, complete distributable-app security/privacy review, and run release-candidate regression without weakening offline identification, evidence integrity, or conservative NFC guarantees.
