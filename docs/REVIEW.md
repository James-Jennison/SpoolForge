# Independent review and research acceptance

Research outcome: **FEASIBLE WITH LIMITATIONS**. This assessment is research-only; it does not establish a tested application, phone/tag workflow, installed firmware compatibility, or release readiness.

Machine-global change: `spoolio-feasibility-20260906`.
Initial review: `review-cycle-511bc96fa65d` — Claude, Gemini and DeepSeek all COMPLETE with persisted responses. Targeted remediation review: `review-cycle-5ac84cff9d4b`.

The initial package was independently reviewed before cross-reviewer conclusions were shared. Codex then normalized findings, preserved their original severity/evidence, revised the research and submitted a targeted remediation package. Reviewer agreement is advisory; it does not replace future physical acceptance. No Mistral escalation was needed: material concerns were addressed by clarifying the scope and acceptance gates.

## Final reviewer status

| Reviewer | Initial review | Targeted remediation review |
|---|---|---|
| Claude — architecture | COMPLETE; one distribution-evidence finding | COMPLETE; NO FINDINGS; distribution correction confirmed |
| Gemini — platforms | COMPLETE; download and OPT-version concerns | COMPLETE; NO_FINDINGS |
| DeepSeek — adversarial | COMPLETE; seven findings/acceptance concerns | COMPLETE; NO_FINDINGS; prior research findings closed |

All six responses are preserved in `research/evidence/initial-{reviewer}.json` and `remediation-{reviewer}.json`, with broker response digests. Claude independently recalculated the NDEF arithmetic and reconciled source-file counts from the supplied evidence. Gemini's response loosely calls 146/217 “NDEF”; the report correctly distinguishes NDEF143/214 from the full Type2 TLV146/217. No reviewer performed physical testing.

**Research decision:** assessment is ready for the Owner's implementation decision. This status applies only to the research artifact. **Implementation decision: pending Owner approval of M1.** No app code was written.

## Initial findings and Codex dispositions

### spoolio-claude-1 — MEDIUM — Section 1/2/12/18 — OFD bulk API accessibility risk understated relative to its centrality to the 'OFD alone' V1 recommendation

**Finding:** The report's own evidence shows a systematic (not isolated) HTTP 403 across every tested OFD bulk-distribution endpoint (index, manifest, compressed JSON, and SQLite), yet the recommendation to use 'OFD alone' for V1 explicitly cites OFD's live 'Daily static API, JSON/gzip, NDJSON, CSV, SQLite/xz... best V1 hierarchy and offline distribution' as a primary differentiator over the alternative sources, and Section 12's proposed sync lifecycle (steps 1-3) depends on the app's own HTTP client successfully reaching these same endpoints. The risk is present in the failure-modes table only as a generic, undifferentiated 'MEDIUM: Upstream unavailable/403/update unavailable' line, which does not reflect that the evidence gathered here shows a 100% failure rate across all four tested bulk endpoints (via a different, working access path — the Git repository — for the same data), a pattern more consistent with systematic bot/WAF/User-Agent filtering than a transient outage.

**Evidence:** Section 1: 'The OFD hosted API returned HTTP 403 to the direct download client for index, manifest, compressed JSON, and SQLite... The public API landing page was readable through web research; the public Git repository and actual source dataset were independently accessible.' Section 2's comparison table lists OFD's 'Daily static API... best V1 hierarchy and offline distribution' as its key recommendation driver over OPT/SpoolmanDB/TheFilamentDB. Section 12 step 2-3 describes the app 'checking for updates... online' and 'download[ing] into a bounded temporary file' from this same API. Section 18's risk table entry for this exact failure mode is generic ('Upstream unavailable/403/update unavailable') and does not distinguish a systematic pre-existing 403 (observed here, before any app exists) from a future transient outage.

**Consequence:** If this 403 reflects a durable access barrier to OFD's bulk-download surface for any non-browser client (as the evidence pattern suggests, given the landing page and Git repo worked while every binary-distribution endpoint failed), the proposed app's live daily-refresh mechanism — the specific capability cited as justifying 'OFD alone' over other candidate sources — may be non-functional from day one, silently degrading the product to a bundled-snapshot-only tool indefinitely. This would materially weaken the comparative case for OFD over OPT/SpoolmanDB (whose distribution mechanisms were not shown to have the same access problem), a conclusion the current risk framing does not surface clearly enough for an owner approval decision.

**Required correction:** Before owner approval treats 'OFD alone via its documented API' as settled, perform a targeted diagnostic (a plain HTTP GET to the documented bulk endpoints using a standard mobile-app-style User-Agent/header set, distinct from whatever client produced the 403 here) to determine whether the 403 is (a) specific to the research tool/User-Agent used, (b) requires an API key or registration not yet identified in the documentation, or (c) a durable block affecting all non-browser clients — and reflect the answer explicitly in the source-selection rationale rather than only in the generic risk table.

**Objective acceptance test:** A documented, reproducible successful HTTP fetch of at least one OFD bulk endpoint (e.g. `brands/index.json` or `/json/all.json`) using a plain HTTP client with headers representative of what the Android app will actually send, prior to M1 sign-off — or, failing that, an explicit owner-facing statement that V1 will ship as bundled-snapshot-only with no live refresh path until this is resolved.

**Confidence:** MEDIUM-HIGH that the evidence pattern (100% failure across all 4 tested bulk endpoints, contrasted with success via the Git repository) is more consistent with a systematic access barrier than coincidence; MEDIUM on whether this specific finding materially changes the source-selection recommendation, since it's possible the block is trivially fixable (e.g. a missing header) and the report's existing generic risk-table entry, while not emphasizing this connection, does already gate 'automatic sync release' on diagnosing client access first.

**Codex disposition: CONFIRMED WITH NARROWER SCOPE.** Distribution reliability was insufficiently prominent. Report now proposes bundled-source-only M1 and gates live refresh separately. Cause of403 is unknown, not proven bot filtering.

### spoolio-gemini-1 — HIGH — Data Pipeline & Integration

**Finding:** Unverified HTTP 403 API transport behavior creates runtime update failure risk for automated client catalog updates.

**Evidence:** Section 1 states 'The OFD hosted API returned HTTP 403 to the direct download client for index, manifest, compressed JSON, and SQLite'. Section 12 proposes daily online sync checks against api.openfilamentdatabase.org without detailing client header requirements, User-Agent policies, or fallback archive mechanisms.

**Consequence:** Mobile client catalog update attempts will fail with HTTP 403 Forbidden errors if the CDN or host blocks default Android HTTP client signatures, leaving users permanently reliant on stale bundled snapshots without diagnostic visibility.

**Required correction:** Specify and verify explicit client HTTP request headers (e.g. customized User-Agent, Accept headers) and establish fallback repository archive mirrors in the catalog update specification prior to M1 implementation.

**Objective acceptance test:** Automated network integration test executing a catalog refresh via the application's configured HTTP stack against the canonical endpoint succeeds with HTTP 200 OK and valid payload SHA-256 digest validation.

**Confidence:** HIGH

**Codex disposition: CONFIRMED WITH NARROWER SCOPE.** Hosted refresh is explicitly outside M1; pinned licensed source is the build input. No header disguise or mirror evasion authorized. Future permitted intended-client validation remains a release gate.

### spoolio-gemini-2 — MEDIUM — Codec & Standards Interoperability

**Finding:** OpenPrintTag draft specification unreleased key migrations (diameter keys 30 vs 61) present serialization interoperability hazards.

**Evidence:** Section 7 documents that spec commit 7e09cc38 introduces unreleased draft diameter key 61 (µm) and nozzle key 62 (µm) replacing legacy keys 30/33 (mm), while upstream release tags returned 404/empty.

**Consequence:** Emitting unreleased draft key definitions onto NFC-V tags will cause currently deployed OpenPrintTag readers expecting stable key assignments (30/33) to fail parsing filament diameter and nozzle temperature settings.

**Required correction:** Isolate the OpenPrintTag codec behind an explicit schema versioning gate that defaults tag serialization to published stable key assignments (keys 30 and 33 in mm) and rejects unversioned main-branch key drift.

**Objective acceptance test:** Codec unit test suite validates that default serialized CBOR payloads emit legacy keys 30 and 33 in mm, and emit keys 61 and 62 in µm only when explicitly configured for draft schema targets.

**Confidence:** HIGH

**Codex disposition: ALREADY MITIGATED.** OPT deferred and exact consumer/spec contract required; no published stable release was evidenced. Nozzle diameter is not nozzle temperature. No draft emission promised.

### spoolio-deepseek-1 — HIGH — Data-source availability and V1 source decision

**Finding:** The recommendation to use OFD as the sole V1 catalog source depends on published API/download artifacts that were not verified; direct downloads returned HTTP 403 and only the Git repository was directly accessible. This leaves the core offline catalog and update path materially unproven.

**Evidence:** Report sections 2 and 3 state: "The OFD hosted API returned HTTP 403 ... for index, manifest, compressed JSON, and SQLite. ... Published binary sizes, validators and successful bulk-client behavior are therefore unverified." It still recommends "Use OFD alone, importing a pinned source revision."

**Consequence:** If the documented API blocks the app's client or the prescribed SQLite/JSON artifacts cannot be reliably obtained, V1 cannot bundle or update the catalog as planned. Implementation may be blocked or forced to rely on an untested ad-hoc path.

**Required correction:** Before M1 approval or implementation, verify that the documented bulk endpoints or the pinned source repository can produce a reproducible, licensed data snapshot usable by the app. Record exact endpoint/commit, content digest, and a fallback plan if the API remains inaccessible. Do not treat source-repository accessibility as equivalent to published artifacts.

**Objective acceptance test:** In a controlled network environment, download or generate the pinned OFD snapshot from the documented source without credentials, validate record counts against the report's measured counts, and import into a staging catalog. The update flow must complete with a documented fallback if the endpoint returns 403.

**Confidence:** HIGH

**Codex disposition: CONFIRMED WITH NARROWER SCOPE.** Fresh source validation parses32107 files and matches measured166/714/2089/14569/22347 hierarchy counts. Hashed source build input is proven; normalized app artifact still must be built in M1. No live refresh promised.

### spoolio-deepseek-2 — HIGH — OpenSpool / PAXX compatibility profile

**Finding:** The report identifies a direct unit conflict in the primary U1/PAXX adapter: PAXX source parses `diameter` as mm and multiplies by 100, while U1-RFID's write path and README supply 175. If M1 copies that example or otherwise writes 175, the value becomes 17,500 internally and is physically wrong.

**Evidence:** Report section 9: "`diameter` is parsed as mm and multiplied by 100. U1-RFID's inspected write path supplies 175, which would become 17,500 internally; its README also shows 175. Use 1.75 for that PAXX adapter, verified against firmware. Do not copy the example blindly." Section 6 table also states "**Never 175** for the inspected PAXX parser."

**Consequence:** Writing a tag with 175 for diameter to an NTAG215 accepted by PAXX could cause incorrect printer settings or material diameter interpretation. V1 acceptance on the owner's primary target would fail or produce unsafe print parameters.

**Required correction:** Enforce the proposed PAXX profile to write `1.75` (decimal mm) for 1.75 mm filament. Create regression tests that reject or prevent `175`. Ensure any adapter/fixture derived from U1-RFID is not copied without correction.

**Objective acceptance test:** Build a minimal OpenSpool/PAXX profile write with diameter 1.75, read it back on a phone and on PAXX/U1, and assert the parsed internal diameter is 1.75 mm (not 175 or 17,500). Include a negative test that a legacy 175 input is flagged or converted only by explicit correction.

**Confidence:** HIGH

**Codex disposition: ALREADY MITIGATED.** Original mapping already required decimal1.75 and never175. M1 regression wording reinforced. No app exists to patch and no upstream changes authorized.

### spoolio-deepseek-3 — HIGH — Feasibility evidence from existing applications

**Finding:** None of the inspected existing Android writer applications demonstrates the requested verified write/read-back acceptance criterion. SpoolPainter's write-followed-by-read-back is commented out and transitions to success; U1-RFID reports success without fresh read-back. Thus the feasibility of reliable tag writing is not proven by prior art.

**Evidence:** Report section 10: "SpoolPainter 2.4.1 NfcRepository.kt has the write-followed-by-read-back block commented out, then transitions to success. ... U1-RFID also reports written after the write/format call without fresh read-back. These upstream applications cannot be described as proven implementations of the requested reliability standard."

**Consequence:** If the project team assumes open-source examples imply that verified writing on Android is straightforward, M1 may inherit latent false-success behavior. Physical tests could pass incorrectly or reliability requirements may be underestimated.

**Required correction:** Explicitly record in feasibility documentation that no inspected application demonstrates verified NTAG215/OpenSpool read-back. Do not use those applications as evidence of this capability. Implement the report's separate frozen intent/write/fresh read/compare state machine and require it in acceptance.

**Objective acceptance test:** M1 test 4 (owner's phone/NTAG215 read/inspect → capacity check → write → fresh read → decode/compare) plus failure/RF-removal tests must be executed and evidence captured. Only a successful fresh decode/compare may display Verified. The test must not rely on SpoolPainter/U1-RFID results.

**Confidence:** HIGH

**Codex disposition: ALREADY MITIGATED.** Report never relied on these apps as demonstrated verification. Explicit no physical demonstration added; fresh read/compare is mandatory future acceptance.

### spoolio-deepseek-4 — MEDIUM — OpenSpool under-specification and version negotiation

**Finding:** OpenSpool 1.0 has no rigorous required/optional JSON Schema, and the PAXX parser does not enforce the JSON `version` value as a strict negotiated contract. This permits unsupported future fields or versions to be silently interpreted or ignored by some readers.

**Evidence:** Report section 6: "The documentation provides version 1.0 and an example, not a rigorous required/optional JSON Schema." Section 9: "The parser does not enforce the JSON `version` value as a strict negotiated contract. This is incomplete compatibility semantics."

**Consequence:** A tag written as OpenSpool 1.0 with extensions or later versions may be partially read by PAXX or third-party apps, causing missing fields or misinterpretation. Edit/rewrite could corrupt unknown data because version handling is ambiguous.

**Required correction:** The application must define and publish its own exact OpenSpool profile with strict `protocol`/`version` validation. Unsupported versions and unknown fields must be read-only or rejected, never silently down-converted. Verify PAXX's actual installed parser behavior against that profile.

**Objective acceptance test:** Tests with `version` unknown/missing and with incompatible additional fields must produce read-only/rejected outcomes on the app. If PAXX is used, verify its behavior on the same fixtures and document any differences.

**Confidence:** HIGH

**Codex disposition: CONFIRMED.** Exact protocol/version validation and read-only unknown profiles now explicit; no silent down-conversion.

### spoolio-deepseek-5 — MEDIUM — OpenPrintTag spec stability for later milestone

**Finding:** OpenPrintTag's inspected default branch contains an unreleased diameter migration changing keys/units, and no stable release tag is available (`releases/latest` 404). The report nevertheless lists OPT as a later codec, making that interoperability claim speculative and introducing risk of version drift.

**Evidence:** Report section 7: "Current inspected spec commit 7e09cc38 has an unreleased diameter migration ... legacy filament_diameter key 30 in mm is replaced by filament_diameter_v2 key 61 in µm ... API tags returned an empty list and latest-release lookup returned 404. There is no release tag to assume as an automatically stable contract."

**Consequence:** If implementation proceeds against moving spec headers on the main branch, written NFC-V OPT tags may use incompatible keys/units and fail on consumer readers. Database/spec schema mismatch may corrupt tag content.

**Required correction:** Defer any OPT implementation until a released spec tag and consumer compatibility fixtures exist. If development occurs earlier, pin an exact spec commit, implement only released keys, and mark unreleased fields read-only. Do not emit `main`-only keys.

**Objective acceptance test:** Before the OPT milestone, identify a tagged release or explicitly accepted pinned spec and database schema. Run codec tests against static fixtures including legacy diameter key 30 and current draft key 61 to ensure versioned mapping.

**Confidence:** HIGH

**Codex disposition: ALREADY MITIGATED.** OPT is deferred. Selected consumer-compatible pinned spec needed; no released tag exists so cannot invent stable release contract.

### spoolio-deepseek-6 — MEDIUM — Primary target firmware/version identification

**Finding:** The report does not identify the owner's exact installed PAXX build; it only lists discovered tags. All PAXX parser behavior claims relevant to M1, including the diameter conflict, are based on source snapshots that may not match the owner's firmware.

**Evidence:** Report section 9: "The newest discovered release tag is v1.5.2-paxx12-21 ... GitHub's releases/latest returned older ... These are not the owner's verified installed build. Record the exact installed version and match its source before a physical test."

**Consequence:** If the installed PAXX version differs in parser behavior or supported fields, M1 acceptance test 6 (U1/PAXX reads material/color) may fail unexpectedly or pass without validating the actual target. Fixes may target an irrelevant source version.

**Required correction:** Include a mandatory pre-M1 step to record the owner's phone model/Android version, tag capability, and exact installed PAXX build. Obtain the source for that exact build and map the compatibility profile behavior to it before any physical test.

**Objective acceptance test:** Before M1 implementation approval, demonstrate that the exact installed PAXX build is identified and that the source snapshot used for OpenSpool parser analysis matches it. Any discrepancy is escalated and documented.

**Confidence:** MEDIUM

**Codex disposition: CONFIRMED WITH NARROWER SCOPE.** Hardware and exact installed build identification are required before physical tests in owner-approved M1. They do not block completion of research or approval to begin local implementation.

### spoolio-deepseek-7 — LOW — Storage and performance planning

**Finding:** The report's catalog storage and transient space budgets (15–50 MB catalog, 100–200 MB transient) are not measured; they are planning allowances. Search latency under 100 ms p95 is a target, not benchmarked.

**Evidence:** Report section 12: "Actual normalized Room+FTS size is unknown. Planning allowance: 15–50 MB catalog per generation and roughly 100–200 MB transient free space, to be replaced by measurements in M1. These are budgets, not measured requirements." Search target: "under 100 ms p95 warm query latency ... not benchmarked here."

**Consequence:** Low-end devices or tight storage may fail first launch or update if actual normalized DB/FTS size or transient decompression exceeds budget. Search performance may miss the stated target.

**Required correction:** In M1, measure the actual bundled normalized Room+FTS database size, compressed download size, decompression/transient peak, and warm p95 search latency on the owner's phone. Replace budgets with measured values before release.

**Objective acceptance test:** Build the normalized catalog DB and run updates on candidate devices. Record actual sizes and latencies; confirm they fit within the agreed budget and the 100 ms p95 search target on the owner's phone.

**Confidence:** MEDIUM

**Codex disposition: ALREADY MITIGATED.** Figures were labeled budgets not measurements; actual Room/storage/p95 measurements now explicit in M1 acceptance.

## Ledger terminology

The installed broker supports a smaller disposition enum than the Owner's normalized vocabulary. Its records use `CONFIRMED` with `resolved=true`, while the immutable reason preserves the exact `CONFIRMED WITH NARROWER SCOPE` or `ALREADY MITIGATED` conclusion above. This confirms the underlying risk, not every proposed remedy or an implementation defect. It does not accept an untested HIGH risk for release. Initial attempts using the full human vocabulary were rejected; no failed disposition was recorded as successful.

## Evidence boundaries

- Public catalog records were parsed and measured. All 32,107 retained OFD JSON files were hashed; counts reconciled to the initial metrics. This validates research input, not an app importer.
- Payload arithmetic was recalculated locally: canonical NDEF143 bytes / Type2 TLV146; proposed extended NDEF214 / TLV217. Actual tag capacity and persistence remain physical tests.
- Relative document links were checked. Checksums cover the final report, source ledger, catalog evidence and public compiled artifact. Checksums detect local changes, not manufacturer correctness.
- No upstream application tests, Android builds, installation, NFC writes, firmware tests or performance benchmarks ran.
- The initial two oversized reviewer submissions were rejected before cycle creation; the ledger was checked before a smaller package was submitted. The successful initial and remediation cycles are distinct, durable reviews.
- Physical hardware acceptance and hosted catalog download validation remain future gates. Core M1 is explicitly bundled-catalog-only, requiring no runtime catalog server.
