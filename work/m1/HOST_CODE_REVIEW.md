# Host source inspection — provisional findings during implementation

These are Codex inspection notes, not external reviewer findings. Recheck current source before remediation; worker may already be changing it. Root will reconcile after worker hands off; do not claim these fixes have happened.

- OpenSpoolCodec.decode currently only checks string diameter and range ordering. Numeric diameter175 bypasses the string check; invalid/missing brand/type/color, wrong typed and negative/oversized temperatures may return Supported. Require supported exact types, mandatory fields, range bounds and numeric/string decimal-mm validation before editable Supported; test these malformed inputs.
- Unknown alpha is classified known but no preservation/edit contract demonstrated; preserve losslessly or remain read-only, not Supported then silently lose alpha.
- additionalColors.take(4) is silent truncation; declare field loss or reject until explicit selection, including profile omitted-field behavior.
- WriteStateMachine allows any existing records to reach writing on boolean overwriteApproved, without same-tag/content consent binding. Verify coordinator binds consent to UID+existing message digest and rejects unknown profiles as edit targets; changed tag needs renewed inspection/consent.
- Pending write intent/payload is public mutable ByteArray and no durable journal shown yet. Freeze/deep-copy and persist intent/UID/phase before write so process death does not silently clear UNKNOWN or allow stale automatic write. Cancellation must not relabel an already UNKNOWN write as cancelled-before-writing.
- Reconciliation must check complete NDEF record framing/MIME/count plus decoded payload and same UID, not just any extracted JSON. A different URI+JSON/multiple record tag must not verify as the intended one-record tag.
- StrictJson accepts lone escaped surrogate and Unicode numeric digits via isDigit; consider malformed Unicode/ASCII JSON number fixtures and bounded lexical conversion before numeric overflow/large exponents.

Acceptance is behavior-based; test adversarial outcomes through codec/coordinator/journal boundaries rather than only mirroring each state transition.

## Host checks during resumed action30

- Asset inspected:22347 rows and22347 unique package IDs; SUNLU ABS Orange maps to expected source fixture; compressed1754819 bytes. app/src/main/assets/ofd-catalog-manifest.json ends in literal backslash-n and fails JSON parsing with Extra data at char204. Fix generator and regenerate, not just generated output.
- DataStore.ensureCatalog currently inserts chunks outside one transaction and trusts count>0. Process death after first batch leaves partial catalog accepted next launch. Core CatalogGenerationStore tests alone do not prove Android DataStore atomicity; wire validated staging/transaction and expected count/digest.
- MainActivity currently offers create-only dialog but no custom list/recents/edit/read screen; custom temperatures/provenance persistence and tag editing need real UI path before claiming M1.
- Host is running heavy-gradle :core:test :app:assembleDebug :app:lintDebug; results will be recorded separately. Do not race host Gradle with another build.

## Local M1 scope still required (recheck newest files)

- CustomRecord persists no temperatures, per-field provenance or original source link; CatalogEntry.toRecord always labels records CATALOG/OFD even sourceRevision=user. Real user edits must retain original field observations and saved temperatures, distinguish CATALOG_EDITED/CUSTOM and survive restart.
- recents snapshot currently only package_id/source_revision, insufficient historical snapshot after catalog removal. No visible recents retrieval path was seen.
- decodedSeed loses min/max nozzle/bed and numeric diameter; creating custom from supported read may silently drop fields on rewrite. Expose edit with loss preview and retain understood fields; unsupported unknown fields read-only.
- NFC panel currently displayed only within selected detail; scanning on initial catalog shows no decoded record UI unless selected. Make scan/read entry visible independent of catalog selection.
- Use user-facing NFC messages rather than raw enum phases and internal field lists where possible. Confirm actual small-screen keyboard and scroll usability on2023.

## Verified2023 visual/runtime findings

Actual corrected APK installed and SUNLU search/detail verified through app UI. Screenshot work/m1/host-evidence/sunlu-detail.png shows label/value collision (Exact package touches1.75), uneven segmented-button heights from wrapping PAXX label, white status-bar icons on light background, and raw internal omitted-field identifiers in product flow. Correct spacing, matching control height/short labels, status-bar appearance and human-readable field names. Preserve source traceability in detail but do not require user to understand enum/property names.

Catalog packaging root fix: keep .tsv.gzip extension; .gz is transparently decompressed/renamed by Android merger and caused proven first-launch error. Generator/build/app references must agree.

Host initial review is already submitted as review-cycle-38636c32948d under spoolio-m1-20260906, and attached to supervisor. Do not resubmit worker package. It contains exact core+app source from host snapshot, known partial implementation scope and real host results. Source changes after that snapshot need targeted re-review.

Before remediation read HOST_REMEDIATION_DIRECTION.md. It preserves Owner requirements when reviewer proposed remedies differ, especially UNKNOWN journaling, optional tag label facts and migration of already installed user.db.
