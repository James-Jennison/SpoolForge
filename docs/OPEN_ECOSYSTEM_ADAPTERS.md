# Open ecosystem adapters

SpoolForge reads external tag formats before offering conversion. Known fields receive a source string tied to an upstream revision, and conversion opens the normal local-record review dialog. The current codec registry can also create explicitly selected external formats; it never assumes that two ecosystems are compatible merely because they use the same chip family.

## Compatibility matrix

| Format | Radio / NDEF | Detect | Decode | Convert to local | Write | Fresh verify | Physical evidence in M5 |
|---|---|---:|---:|---:|---:|---:|---|
| OpenSpool 1.0 | NFC-A / NDEF Type 2, `application/json` | Yes | Yes | Yes | Yes | Yes | Existing NTAG215 acceptance |
| PAXX U1 Extended | NFC-A / NDEF Type 2, `application/json` | Yes | Yes | Yes | Yes | Yes | Existing NTAG215 and U1 Bay 1 acceptance |
| ELEGOO CANVAS + PAXX/OpenRFID | NTAG215 / raw CANVAS block at page 16 | Yes | Yes | Yes | Yes | Yes | Host resolver/codec/lock/capacity tests; a written NTAG215 was accepted by an Elegoo CANVAS (owner-reported, see `work/canvas-paxx/owner-reported-canvas-acceptance.json`); Snapmaker U1 acceptance through PAXX/OpenRFID pending |
| OpenTag3D 2.000 | NFC-A / NDEF Type 2, `application/opentag3d` | Yes | Yes | Yes | Yes | Yes | Host round-trip tests; no physical OpenTag3D tag supplied |
| OpenPrintTag current | NFC-V / ISO 15693 NDEF, `application/vnd.openprinttag` | Yes | Yes | Yes | Initialize blank writable NDEF tags | Yes | Fixture/round-trip/routing tests; no physical NFC-V tag supplied |
| Anycubic ACE Pro | NTAG213/215/216 raw user pages | Yes | Yes | Yes | Yes | Yes | Host byte round-trip tests; physical ACE acceptance pending |
| Creality CFS | Protected MIFARE Classic 1K sector 1 | Yes | Yes | Yes | Yes | Yes | Host crypto/format tests; phone and CFS acceptance pending |
| QIDI Box | MIFARE Classic 1K sector 1 data block | Yes | Yes | Yes | Yes | Yes | Host block round-trip tests; phone and QIDI acceptance pending |
| TigerTag 2.1 | NTAG213/215/216 raw user pages | Yes | Yes | Yes | Yes | Yes | Host byte round-trip tests; physical reader acceptance pending |
| Spoolman JSON | File/text JSON | Yes | Yes | Yes | Export only | Round-trip tests | Host tests |
| Spoolman server | HTTP(S) API v1 | Yes | Yes | Profile sync | Vendor/profile create or profile update | Response plus stable-ID lookup | Mock transport tests; no server URL supplied |

A lack of physical evidence is not reported as hardware compatibility. Android reader mode enables both `FLAG_READER_NFC_A` and `FLAG_READER_NFC_V`; a real tag plus its intended printer or material system remains the acceptance gate for each radio and format path. MIFARE Classic additionally depends on phone hardware support.

## Creality CFS

The CFS codec writes the documented 48-byte encrypted record to sector 1 blocks 4–6 of a MIFARE Classic 1K tag, derives sector authentication from the tag's four-byte UID, and verifies through a fresh authenticated read. Blank initialization is accepted only when factory Key A authenticates and the complete readable transport trailer matches the documented access bytes and default Key B. After writing, SpoolForge reconnects and authenticates derived Key A, rechecks the access bytes and readable derived Key B, then performs the exact data readback; that trailer-verification requirement survives process restart. If power or RF loss occurs after all three exact encrypted data blocks reach a still-factory-keyed tag, SpoolForge offers an explicit trailer-only completion action bound to the journaled UID and payload; it never retries that mutation automatically. It maps the current documented Creality material IDs, stores color and length, and generates one frozen serial shared by the optional two-tag workflow. Unknown material IDs, nonblank unrecognized sector data, unsupported UIDs, and tags that do not complete the authentication-trailer transition remain read-only or unresolved rather than being rewritten. SpoolForge never changes a hardware UID.

The implementation is based on the public interoperability documentation in MakaiView/cfs-programmer at revision `52349f4f9ed861a9ab04cd4da5e158366e446506` and cross-checked against soylentOrange/K2-RFID at revision `b4f2760c7eb6df9fbd2b8fcc84811d73c3c8482d`. Physical acceptance still requires a supported Android phone, two blank tags, and an actual CFS reader.

## One-format CANVAS and U1/PAXX compatibility

The compatibility resolver maps `elegoo_canvas + snapmaker_u1_paxx` to a single NTAG215 encoded only in ELEGOO CANVAS format. CANVAS consumes that format natively. PAXX's OpenRFID integration consumes the same raw block through `ElegooTagProcessor`; no OpenSpool record is added. Stock Snapmaker U1 firmware is not compatible with this rule.

Before writing, SpoolForge identifies the silicon with `GET_VERSION`, requires NTAG215, checks 504-byte user capacity, rejects relevant dynamic-lock, block-lock, reserved-control, or password-protected states, and shows the detected tag type. It modifies only pages 16–31, where factory tags place the CANVAS filament block at absolute byte `0x40`. Existing unrecognized data in those pages is rejected; recognized data requires UID/content-bound overwrite consent. All user pages 4–129 are read. Outside the CANVAS block, only zero bytes or the standard factory empty-NDEF marker are allowed; any payload data is rejected and preserved, preventing a nominal CANVAS tag from silently retaining a competing NDEF/OpenSpool payload. A fresh read must exactly match the frozen block and decode back to the supported profile fields before verification succeeds.

The CANVAS manufacturer word is the fixed marker `0xEEEEEEEE` that every CANVAS reader requires, so it cannot carry a brand. SpoolForge therefore writes any brand's profile and reports `brand` as an omitted field in the write preview, with a hint that readers will treat the tag as ELEGOO-format filament. On decode the brand is reported as `ELEGOO` by convention with a distinct provenance string that names the format marker rather than a tag field. CANVAS preserves supported material/subtype, RGB color, nozzle range, diameter, and nominal weight. It does not carry brand, color name, bed range, GTIN/SKU, additional colors, or transmission distance.

## OpenTag3D

The adapter is pinned to OpenTag3D revision `d0f706896e772663404edab8aa814633ba3c6543`, format 2.000. It decodes the 224-byte core as unsigned big-endian fields, including manufacturer, material and modifier, four RGBA colors, SKU, six-byte barcode, diameter in micrometres, target weight, temperature values scaled by five, and HueForge transmission distance scaled by one tenth. A newer major version remains read-only. Known fields from a newer 2.x minor version can be converted with a warning, while extension bytes remain preserved in the source payload.

Source: <https://github.com/GooborgStudios/OpenTag3D> and <https://opentag3d.info/spec.html>

## OpenPrintTag

The adapter is pinned to specification revision `7e09cc38df1c8e7824a67f5b1ae93071f52519ad`. It uses a bounded CBOR reader, honors the meta-region main offset, supports definite and indefinite containers, skips unknown values, and reports unknown numeric field keys while retaining the complete payload. Diameter key 61 is interpreted in micrometres. Removed legacy key 30 is accepted in millimetres for old tags; if both are present, key 61 wins with a warning. If neither exists, the specification's 1.75 mm default is applied. Actual net weight falls back to nominal net weight, and missing local-only values are surfaced for review rather than written back.

The test corpus includes the exact 245-byte payload reconstructed from the official `tests/encode_decode/01_info.yaml` fixture. Its Apache-2.0 attribution is recorded beside the fixture.

Source: <https://github.com/OpenPrintTag/openprinttag-specification>

## Spoolman

SpoolForge accepts the official flattened JSON export shapes from `/api/v1/export/spools?fmt=json` and `/api/v1/export/filaments?fmt=json`, including keys such as `filament.vendor.name`. It also accepts nested Spoolman API response objects. A spool import keeps separate Spoolman spool and filament IDs, initial and remaining weights, vendor data, and a revision-bound field source. Each record is reviewed before local save. Local records can be shared in the same flattened spool-export shape.

Live sync is intentionally profile-scoped. It looks up vendor and filament records using stable legacy `filamajig:` external IDs, creates missing records, and updates an existing filament instead of duplicating it. Spoolman's filament create contract requires density, so SpoolForge either uses the reviewed value entered for the sync or shows the standard material value it inferred. A transport interruption after POST/PATCH produces an unknown outcome and is never retried automatically. The UI tells the user to run Sync again; the repeated lookup uses the same stable external ID, finds a creation whose response was lost, and updates it instead of issuing a duplicate create.

The current Spoolman spool API has no external-ID field. SpoolForge therefore does not create a physical spool during live sync because a lost response could otherwise create a duplicate on retry. Physical spool data remains available through import/export until a durable server-side idempotency key or a persisted explicit mapping is designed and tested.

Local Spoolman commonly uses cleartext HTTP, so the prototype accepts HTTP only for loopback, private/link-local address literals, and reserved `.local` names. Other server names require HTTPS. The app does not collect or embed Spoolman credentials.

Source: <https://github.com/Donkie/Spoolman>, inspected at revision `81636f253ecd6ac76fcfe5d13fe2cbb417095823`.
