# SpoolForge product definition

**App-store name:** SpoolForge
**Description:** **Universal filament identity, inventory, and RFID interoperability.**

## Problem and promise

Ordinary filament arrives with inconsistent labels, incomplete retail identifiers, no portable digital identity, and printer-specific tag expectations. SpoolForge turns a package, label, catalog match, QR code, or existing NFC tag into an editable and explainable filament profile, then binds owned spools to portable NFC/QR identities.

SpoolForge owns the Forge family's filament and material layer: canonical filament properties, physical-spool identity and inventory, evidence about each spool, supported RFID/NFC representations, and loss-aware translation between supported ecosystems. It does not invent integrations with other Forge products; it provides the reusable material-domain foundation they may consume.

The one-minute target is: identify a filament, review the values and their sources, create a spool, select an exact tag profile, preview omissions and capacity, write, and verify.

## Target users

- Android users who buy third-party, generic, rebranded, or older filament;
- owners of printers and material systems that consume PAXX/OpenSpool, ELEGOO CANVAS, OpenPrintTag, Anycubic ACE, OpenTag3D, QIDI Box, or TigerTag records;
- makers who want local inventory without running a server or joining a printer cloud;
- users moving data among OFD, Spoolman, open tag formats, QR labels, and future tools.

## Differentiation

Tag My Spool is a strong focused writer; Spool Hoarder is a stronger general local inventory; SimplyPrint is a stronger printer-connected platform; Spoolman is a stronger self-hosted inventory/API; 3D Filament Profiles is a stronger visual catalog and TD reference. SpoolForge should win only where it has a coherent advantage: transparent provenance and conflicts, loss-aware conversion, exact codec contracts, verified PAXX Extended output, and local portable identity without requiring a server or account.

## Principles

1. Open-data-first: OFD is the primary source; SpoolmanDB Community and other permitted sources are adapters.
2. Local-first: cached search, saved profiles/spools, editing, decoding, encoding, QR/label output, export and NFC verification work offline.
3. Interoperability-first: data leaves in documented formats; no proprietary URL is required to understand a spool.
4. Provenance-aware: source observations and user overrides survive normalization and disagreement.
5. Profile and spool are distinct: many owned spools may reference one product definition.
6. Codec isolation: a vendor or standard format never dictates the canonical database.
7. Explicit compatibility: tag technology and tag data format are separate; each reader ecosystem is an explicit target and the dual U1/CANVAS layout is named as such.
8. Conservative writes: preview, consent, byte-capacity gate, readback, and durable unknown outcomes remain mandatory.

## Non-goals for the focused roadmap

SpoolForge is not a slicer, model editor, printer dashboard, print queue, farm manager, cloud print service, marketplace, social network, or replacement for Spoolman. Printer integrations are consumers and export/sync targets. AI label recognition is an optional identification accelerator; search, code scan, NFC read and manual creation remain complete paths. Full Spectrum support does not make predicted RGB a guaranteed printed result, and direct slicer-project generation remains a version-specific research question rather than an MVP promise.
