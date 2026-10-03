# ADR-002: Refocus on the filament tag manager and a public release

- Status: Proposed — awaiting owner acceptance
- Date: 2026-10-03
- Supersedes: the "Proposed M9–M12" milestones in [`ROADMAP.md`](../ROADMAP.md) if accepted

## Context

The original brief (2026-09-06) asked for a printer-agnostic, offline, account-free Android filament tag manager: find a real filament, pick the exact variant, review the values, write an open or vendor tag with the phone, and verify it by reading it back. ADR-001 widened that into a portable filament identity workbench, and M7–M8 added a Full Spectrum color-mixing domain. Every remaining roadmap milestone (M9–M12) is Full Spectrum work.

A study of the original briefs on 2026-10-01 found four items from the original brief still unbuilt and on no milestone: automatic catalog refresh, color-similarity search, brand → product → color browsing, and workflows for printers without an NFC reader. The same review found that competing apps cover general tag writing but require accounts or impose write quotas, so the account-free, verified, multi-format writer remains the differentiator.

On 2026-10-03 the owner decided that SpoolForge will be published on Google Play as a free app, with public source under GPL-3.0-or-later. The source was published the same day.

## Decision

1. **Core product.** SpoolForge's core is the original tag manager: identify a filament, review it, write a tag in the reader's format, verify it. Inventory and portable identity stay as supporting features.
2. **Full Spectrum is frozen at M8.** What M7 and M8 shipped stays in the app behind its existing opt-in controls and keeps its tests. M9–M12 (sets, recipes, slicer guidance, shared evidence) are deferred with no planned date.
3. **Next milestones** replace M9–M12:
   - **R1 — Release readiness.** Release signing, a Play listing, a privacy statement covering the camera and optional AI providers, a build that works from a clean clone, and removal of debug-only configuration from release builds.
   - **R2 — Finding a filament.** Brand → product → color browsing and color-similarity search over the bundled catalogs.
   - **R3 — Catalog refresh.** Update the bundled open catalogs without an app update, with the same provenance and licence records.
   - **R4 — Printers without a reader.** Set the active spool through Spoolman or Moonraker.
4. **AI label scanning** stays optional. The providers are the user's own ChatGPT plan and a self-hosted local vision model; no provider credential ships in the app.
5. **3D Filament Profiles** is link-only (a pre-filled search in the browser) until its owner grants licensed access. No scraping, page reading, or AI extraction of its data.
6. **Compatibility claims** continue to require device evidence per format and reader.

## Consequences

- `ROADMAP.md` and `PRODUCT.md` need their milestone sections rewritten once this is accepted; until then they describe the superseded plan.
- Full Spectrum code remains a maintenance cost with no planned growth; removing it would be a separate decision.
- Release work now precedes feature work, because the repository is already public.
- Eligibility of the "Sign in with ChatGPT" preview for a Play-distributed app must be confirmed under R1; if it is not eligible, the local model becomes the only shipped provider.

## Open questions for the owner

- Is the order R1 → R4 right, or should finding a filament (R2) come before release?
- Should Full Spectrum be hidden entirely in the first public release rather than left opt-in?
- Will the Play listing carry ads or donations? Either changes what third parties will license.
