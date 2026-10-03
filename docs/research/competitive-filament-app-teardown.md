# Competitive filament application teardown

Research date: **2026-09-07** (America/Los_Angeles). “Claimed” means the vendor or project says a feature exists; “source” means public source or a published schema confirms it. `Unknown` means the available evidence did not establish the behavior. Catalog counts are snapshot measurements and will change.

## Decision summary

The original broad hypothesis is only partly differentiated. Spool Hoarder now combines offline-first inventory, AI label recognition, UPC, NFC/QR reading, OpenSpool writing, TD1 capture, backup/export, and optional sync. SimplyPrint combines the widest printer-connected inventory workflow with labels and broad NFC format claims. Tag My Spool is already a focused offline tag writer with OFD, customizable labels, TD, and several codecs.

The defensible opening for SpoolForge is therefore narrower: a native Android **portable filament identity workbench** that makes every imported, scanned, edited, encoded, and converted value explainable; keeps product definitions separate from owned spools; works locally; and treats exact codec profiles—especially PAXX U1 Extended—as testable compatibility contracts. Inventory is necessary context, not the differentiator. Printer control is outside the product.

## Evidence key and limitations

- **Verified/source:** public source, schema, or repository evidence was inspected.
- **Official claim:** current first-party site/store/documentation claim; runtime was not independently exercised.
- **Community report:** used only when first-party material did not answer the question.
- Architecture/framework is `Unknown` unless source or an official statement identifies it. A store listing does not prove Flutter, React Native, or native implementation.
- 3D Filament Profiles blocked the research client with HTTP 403. Its public GitHub project, public help references, and current first-party update posts were usable; internal endpoints were not scraped.
- No competitor was physically tested with a Snapmaker U1. Generic OpenSpool is not evidence of PAXX Extended output.

## Product and deployment matrix

| Product | Platforms / implementation | Offline and account | Deployment / backend | Price model |
|---|---|---|---|---|
| 3D Filament Profiles | Responsive web application; framework details not established | Account used for personal spools; catalog browsing is public; offline operation not established | Proprietary hosted service | Public service; donations/membership accepted; feature gating not verified |
| Tag My Spool | Android and iPhone; framework unknown | Officially works offline; local configuration, iCloud sync on iOS; no subscription | No required proprietary backend for core tagging | One-time paid app, observed US listing $7.99 |
| Spool Hoarder | Android, iPhone/iPad, macOS, Windows; vendor calls platforms native | Offline-first, no account required for core; optional sync | Optional proprietary cloud and AI credits | Free limits (50 active spools, 5 projects, 1 printer); Pro $19.99/year; credit packs |
| SimplyPrint | Web plus Android/iOS apps and desktop NFC Agent | Account/cloud required for integrated service | Proprietary hosted print platform; printer agent | Filament manager on Free; plan-dependent NFC-write, scan, label and view limits |
| Spoolman GO | Android companion | No developer cloud/account; configured self-hosted server required in Live Mode | Depends on Spoolman/FilaMan endpoint | In-app subscription details not fully exposed in listing; demo available |
| Spoolman | Web client/server | Self-hosted; LAN/offline possible after installation | Open-source self-hosted HTTP/WebSocket service | Free/MIT |
| OFD | Web editor plus static REST/bulk data; not an inventory app | Public reading; account only for contribution route | Public static service and Git repository | Free/MIT |
| SpoolmanDB Community | Git repository and static JSON | Downloadable/offline dataset | GitHub Pages/static files | Free/MIT plus published usage terms |

Primary sources: [3D Filament Profiles project](https://github.com/MarksMakerSpace/filament-profiles), [Tag My Spool Google Play](https://play.google.com/store/apps/details?id=com.kraftpixellabs.tagmyspool), [Tag My Spool App Store](https://apps.apple.com/us/app/tag-my-spool-nfc-filament/id6753719221), [Spool Hoarder](https://spoolhoarder.com/), [Spool Hoarder Google Play](https://play.google.com/store/apps/details?id=com.spoolhoarder.app), [SimplyPrint filament management](https://simplyprint.io/features/filament-management), [Spoolman GO Google Play](https://play.google.com/store/apps/details?id=com.spoolmango.android), [Spoolman](https://github.com/Donkie/Spoolman), [OFD](https://github.com/OpenFilamentCollective/open-filament-database), [SpoolmanDB Community](https://github.com/Icezaza2543/SpoolmanDB-Community).

## Data and profile matrix

`Y` is source-confirmed or an explicit current first-party claim; `C` means custom/user field rather than a canonical field; `P` means partial; `?` is unknown.

| Capability | 3DFP | Tag My Spool | Spool Hoarder | SimplyPrint | Spoolman GO / Spoolman | OFD | SpoolmanDB-C |
|---|---:|---:|---:|---:|---:|---:|---:|
| Public catalog | Y | OFD | SpoolmanDB | OFD | SpoolmanDB import | Y | Y |
| Brand/product/material/subtype | Y | Y | Y | Y | Y | Y | Y |
| Manufacturer distinct from brand | ? | ? | ? | ? | Vendor only | P | manufacturer field |
| Color name + HEX/swatches | Y | Y | Y | Y | Y | Y | Y |
| Multi-color / gradient | Y | ? | Y | Y | Y | color lists/traits | Y |
| SKU/article number | ? | ? | Y | Y | Y | Y | `codes` |
| UPC/EAN/GTIN | ? | lookup not claimed | UPC scan | scan | external import dependent | Y, sparse | `eans`, sparse |
| Diameter + net weight | Y | Y | Y | Y | Y | Y | Y |
| Spool/tare weight | ? | ? | Y | Y | Y | sparse | Y |
| Density / length | Y | length Y | ? | Y | density Y | density Y / length absent | density Y |
| Nozzle + bed ranges | Y | Y | Y | Y | single settings or custom | Y | Y |
| Chamber / drying | dryer data Y | ? | drying history Y | drying status Y | custom fields | sparse/max dry | shared material defaults vary |
| Maximum volumetric flow | Y | ? | ? | ? | custom | slicer dictionaries, sparse | ? |
| Cost/vendor/purchase fields | personal spool Y | ? | Y | Y | Y | store links only | source URLs only |
| Notes/custom fields | personal spool Y/? | presets | Y | Y | Y | source notes | source metadata |
| TD | Y, community | Y for OPT/TigerTag | TD1 capture | format dependent | custom | no canonical TD | no canonical TD |
| Field-level provenance/conflicts | ? | ? | ? | ? | ? | source/revision, not user conflict UI | source links, not user conflict UI |

Measured repository snapshot already retained in this repository: OFD has 166 brands, 2,089 product lines, 14,569 color variants and 22,347 packages; 2,693 packages have a GTIN/EAN value. SpoolmanDB Community has 489 manufacturers and 53,355 expanded variants; 4,610 rows have `eans`, 13,801 have `codes`. OPT has 14,245 material records and 10,304 packages, with 6,193 populated GTINs and 125 TD values. Counts measure field presence, not correctness or unique retail products; SpoolmanDB expansion repeats product data across package combinations.

OFD publishes a static REST API plus JSON, NDJSON, SQLite and CSV downloads under MIT and explicitly permits redistribution and commercial embedding. SpoolmanDB Community publishes schema-validated compiled JSON under MIT with separate policy/terms and warns that safety values require manufacturer verification. 3D Filament Profiles has no supported licensed bulk API established by this research; it remains a reference/optional future partner, not a feed.

## Discovery, inventory, and workflow matrix

| Product | Search / retail identification | Physical-spool inventory | Generated labels | Typical friction |
|---|---|---|---|---|
| 3DFP | Rich filter/search by catalog attributes; retail barcode/AI identification not verified | Personal spools and ownership indicators; exact quantity/usage behavior partly unverified | PNG/PDF labels and slicer profile exports are officially announced | Hosted account and manual catalog selection; no supported product API found |
| Tag My Spool | OFD browse/filter and manual presets; UPC/AI scanning not claimed | Saved tag configurations described as inventory, but consumption tracking not established | Custom field layout, print/export | User starts by constructing/selecting tag data; exact PAXX target absent |
| Spool Hoarder | AI label, UPC, NFC, QR/order import all enter a review step | Strong: unique spools, remaining mass, price, location, projects, usage, trends | Share cards; QR/NFC support; printable-label depth unclear | Best general local workflow; full limits/sync/AI depend on Pro/credits |
| SimplyPrint | OFD presets, barcode/QR/NFC scans | Strongest printer-connected inventory and automatic usage | QR/barcode designer and thermal printers | Cloud account/platform scope and plan limits add friction for a tag-only user |
| Spoolman GO | Server search/filter, QR and NFC | Strong when backed by a server; weight booking | Server’s labels / QR deep links | Setup and server reachability are prerequisites |
| Spoolman | Search/filter and external DB import | Strong self-hosted spool/filament/vendor/location model and printer integrations | Configurable QR labels | Installation and printer ecosystem are substantial overhead for a one-minute tagging task |
| OFD / SpoolmanDB | Search/download/import only | None | None | Dataset, not end-user workflow |

Estimated major-action paths from first-party descriptions (not timed runtime tests):

- **Database:** catalog search → candidate → review → create spool is roughly 3–4 actions in Spool Hoarder/SimplyPrint/3DFP; Tag My Spool adds a saved tag/preset orientation.
- **Retail package:** Spool Hoarder most clearly advertises camera → unified review → save. SimplyPrint advertises scan lookup. Competitor miss/fallback behavior is not documented well.
- **NFC:** Tag My Spool and SimplyPrint expose choose/read/write flows; Spool Hoarder advertises read many formats but only standard OpenSpool writing. Exact preview, capacity validation and post-write readback behavior are mostly unknown.
- **PAXX U1:** no mandatory competitor proves a selectable `v1.5.2-paxx12-21` profile, exact numeric extended payload preview, capacity gate, and semantic readback. Generic OpenSpool claims remain insufficient.
- **Label:** SimplyPrint has the broadest documented thermal/QR workflow; Tag My Spool has configurable layouts; 3DFP exports profile/spool labels.

## NFC/RFID capability matrix

Values explicitly separate read (`R`) and write (`W`). `Claim` means first-party marketing/docs rather than inspected codec source.

| Product | NDEF/NTAG | OpenSpool | OpenPrintTag | OpenTag/OpenTag3D | Vendor formats | PAXX Extended |
|---|---|---|---|---|---|---|
| Tag My Spool | R/W NTAG claim | R/W claim; core fields described | R/W basic-field claim | TigerTag R/W claim | Anycubic ACE R/W; Creality via external writer | Unknown; no exact profile/version claim |
| Spool Hoarder | Mobile R/W claim | R/W standard OpenSpool | R claim | OpenTag3D R claim | Bambu/Prusa R claims | Unknown |
| SimplyPrint | Mobile/browser/desktop varies | R/W claim | R/W claim | R/W claim | Creality, Anycubic, QIDI and others claimed; Bambu generally read/sync | Unknown |
| Spoolman GO | Android NDEF | R/W OpenSpool 1.0 claim | Unknown | Unknown | Unknown | “suitable for U1” claim, full extended fields not established |
| Spoolman | New v0.27 tag scanner links UID; writes no payload in this workflow | Server itself does not need payload | UID-only lookup can accept opaque tags | UID-only | UID-only | No encoder established |
| 3DFP | No writer established | No | No | No | No | No |

OpenSpool’s documented base is one NDEF MIME `application/json` record with JSON `protocol=openspool`, `version=1.0`. PAXX’s current development documentation names an **OpenSpool U1 Extended Format** with numeric temperature, diameter and weight values plus `subtype`, bed range and optional extra colors/alpha. The pinned owner target is release `v1.5.2-paxx12-21`; compatibility must be tested against that release, not inferred from later `develop` documentation. [PAXX release](https://github.com/paxx12-snapmaker-u1/SnapmakerU1-Extended-Firmware/releases/tag/v1.5.2-paxx12-21), [current PAXX format documentation](https://github.com/paxx12-snapmaker-u1/SnapmakerU1-Extended-Firmware/blob/develop/docs/design/filament_detect.md), [OpenSpool](https://github.com/spuder/OpenSpool).

OpenPrintTag uses structured NDEF/CBOR and ISO15693/NFC-V hardware; payload compatibility does not make an NTAG215 physically compatible. OpenTag3D 2.000 targets NFC Type 2/NTAG215 or 216, requires `application/opentag3d`, recommends two tags on opposite spool faces, and includes optional TD. [OpenPrintTag](https://openprinttag.org/), [OpenTag3D specification](https://opentag3d.info/spec.html).

## Color intelligence

3D Filament Profiles is the reference leader for searchable community color/profile and TD data, and now exports slicer profiles. Spool Hoarder is the inventory leader for operational color tools and claims TD1 USB capture. Tag My Spool exposes manual TD in its advanced OpenPrintTag/TigerTag data. OFD is strongest as openly reusable product/color hierarchy; its current TD coverage is absent. OPT contains sparse TD and richer RGBA/LAB/RAL structures. SpoolForge should store a current TD value and an append-only measurement history so manufacturer, catalog, manual and TD1 measurements remain distinguishable. AJAX-3D TD1 support is a future input adapter; manual entry remains sufficient for the initial product.

## Individual assessments

### 3D Filament Profiles

- **Exceptional:** deep community catalog presentation, visual color/TD context, personal ownership, label exports and slicer-profile exports.
- **Friction:** hosted workflow and no supported reusable bulk/API contract found; it cannot be the app’s dependable offline data layer.
- **Constraint:** proprietary service/data access boundary. The public GitHub repository is an issue/logo project, not a licensed complete data export.
- **Adopt:** approachable browsing, ownership indicators, profile/label exports, TD visibility.
- **Do not copy:** dependence on undocumented internal endpoints or account-bound data for core identity.

### Tag My Spool

- **Exceptional:** focused cross-platform tag construction, one-time price, offline use, OFD import, customizable labels, several codecs and manual TD.
- **Friction:** inventory appears preset/tag-centric; barcode/AI discovery, remaining-use accounting, provenance conflict handling and exact PAXX compatibility are not established.
- **Constraint:** closed implementation prevents codec verification; platform-specific hardware limits remain.
- **Adopt:** explicit format choice, reusable presets, backup/restore, label customization.
- **Do not copy:** treating “OpenSpool” as one undifferentiated compatibility promise.

### Spool Hoarder

- **Exceptional:** best documented local-first general inventory; unified review after AI/barcode/NFC/order scans; bulk operations, usage/cost tracking, backups and optional sync.
- **Friction:** free inventory limits and AI credit economics; exact tag conversion and PAXX guarantees are not documented.
- **Constraint:** proprietary app and optional cloud/AI service; code-level format behavior cannot be audited.
- **Adopt:** one review surface for all identification routes, fast bulk entry, robust backup/export.
- **Do not copy:** broad project/analytics assistant scope before portable identity is excellent.

### SimplyPrint

- **Exceptional:** broadest printer-connected inventory, automatic usage deduction, OFD integration, tag-format breadth, mobile/desktop readers and thermal-label workflow.
- **Friction:** account/cloud platform and plan limits are unnecessary for a user who only wants portable filament identity.
- **Constraint:** filament management is coupled to a much larger proprietary print-management ecosystem.
- **Adopt:** physical “digital twin,” label tooling, import/export and broad adapters.
- **Do not copy:** printer dashboard, slicer, queue, farm and cloud-control surface.

### Spoolman GO

- **Exceptional:** focused Android companion, OpenSpool/QR, editable server records, remaining-weight operations.
- **Friction:** requires a reachable self-hosted Spoolman/FilaMan service for real use.
- **Constraint:** server schema and availability own the workflow.
- **Adopt:** clean companion UX and explicit connectivity errors.
- **Do not copy:** server requirement for basic tagging.

### Spoolman

- **Exceptional:** mature open self-hosted inventory/API, unique filament and spool records, extensible fields, labels and strong Klipper/Moonraker integration.
- **Friction:** deployment and integration overhead; mobile NFC is UID-linking rather than a portable-data authoring workbench.
- **Constraint:** server and printer-integration domain.
- **Adopt:** stable API, separate spool/filament/vendor/location entities, import/export/sync target.
- **Do not copy:** become a server or print-management hub.

### OFD

- **Exceptional:** best open normalized product/package hierarchy, explicit MIT reuse, stable IDs, contribution UI and bulk formats.
- **Friction:** sparse GTIN and several advanced fields; community data is not guaranteed manufacturer truth.
- **Constraint:** catalog only; no owned-spool lifecycle.
- **Adopt:** primary bundled/cacheable source and IDs.
- **Do not copy:** bind the app domain one-to-one to its evolving source schema.

### SpoolmanDB Community

- **Exceptional:** widest measured brand/expanded-row coverage, Spoolman-compatible contract, maintained schemas and useful code/EAN/source fields.
- **Friction:** expansion inflates apparent product count; provenance and temperatures vary; maintenance mode is stated.
- **Constraint:** flat compatibility output and shared defaults are optimized for Spoolman import.
- **Adopt:** secondary provider and candidate enrichment after conflict/deduplication gates.
- **Do not copy:** assume repeated expanded rows are independent evidence.

## Category leaders

| Category | Evidence-backed leader |
|---|---|
| Reusable public product catalog | OFD for hierarchy/licensing; SpoolmanDB Community for breadth |
| Visual filament lookup / TD reference | 3D Filament Profiles |
| General local-first inventory | Spool Hoarder |
| Printer-connected inventory | SimplyPrint |
| Self-hosted interoperability/API | Spoolman |
| Focused tag authoring | Tag My Spool |
| Barcode-to-review workflow | Spool Hoarder (official claim; runtime not verified) |
| Label printing | SimplyPrint for printer breadth; Tag My Spool for mobile layout customization |
| Standard OpenSpool | No verified winner; implementations are claimed, not cross-tested |
| Snapmaker U1/PAXX exact compatibility | **Unknown** among competitors |
| Overall UX | Unknown without comparative runtime testing |

## Validated market gap

The broad `Identify → Enrich → Edit → Encode → Label → Track` loop is **not unique**. Spool Hoarder and SimplyPrint cover most of it, and Tag My Spool covers much of the tag/label segment. What remains poorly evidenced across the market is a coherent, auditable workflow in which:

1. every field retains source and conflict evidence;
2. a reusable filament definition and each owned spool are separate identities;
3. a user can inspect and convert existing tag/QR representations without losing unknown data;
4. codecs expose their exact fields, omissions, payload size and target compatibility;
5. PAXX `v1.5.2-paxx12-21` is an explicit tested target rather than a generic “OpenSpool/U1” badge;
6. core search, edit, inventory, tag read/write/verify, QR and export remain local and account-free.

That is a narrower but credible reason for SpoolForge to exist. If runtime comparison later shows a competitor provides those guarantees, the differentiation must be narrowed again rather than manufactured.
