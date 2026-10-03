# PAXX U1 compatibility contract

SpoolForge's `openspool-paxx-u1-1.0` codec targets the exact
`v1.5.2-paxx12-21` tag of
`paxx12-snapmaker-u1/SnapmakerU1-Extended-Firmware`, commit
`8d97e83f0329b72c512563a8e98305cfbdef7a18`. The retained parser snapshot has
SHA-256 `e34cad67d090ea1978601f819ec89c27e0f4b0be56da0683ac6c279a8c7d2e40`.
This target is shown before every PAXX write.

The codec writes one NFC Forum MIME record with type `application/json`. It
writes `protocol`, `version`, `brand`, uppercase `type`, `subtype`,
`color_hex`, optional nozzle and bed limits, decimal-millimetre `diameter`,
integer-gram `weight`, and as many as four `additional_color_hexes`. PAXX's
parser passes brand and subtype through, converts type to uppercase, maps the
five colors to its RGB slots, converts diameter `1.75` to its internal value
`175`, and stores weight and temperature values. It collapses a positive bed
minimum to one bed temperature, otherwise using the bed maximum.

SpoolForge's first-class material matrix is PLA, PETG, ABS, TPU, and PVA. Its
subtype mapping covers Basic, Matte, Silk, Support, HF, 95A, 95A HF,
SnapSpeed, Transparent, Rapid, and Flexible. The pinned parser accepts subtype
as a string; these labels include the vocabulary used by its release fixtures.
Unsupported materials remain available locally and can use the Standard
OpenSpool codec, but SpoolForge does not claim PAXX/U1 compatibility for them.

`transmission_distance` is an optional numeric SpoolForge extension. The pinned
PAXX parser ignores it. The app labels that distinction in its preview. The app
does not emit `alpha`: the pinned parser accepts it, but SpoolForge preserves an
incoming alpha-bearing record read-only until lossless editing semantics are
defined. Local product names, GTINs, SKUs, package IDs, provenance, quantity,
purchase data, and notes are omitted from this tag codec and remain in local or
portable spool records.

The registry preview lists the exact serialized field names and JSON types,
all omitted local fields, JSON payload bytes, and total NDEF message bytes
against the NTAG215's reported 492-byte NDEF capacity. A write is bound to a
physical spool only after a fresh semantic readback verifies it. A second
verified tag may occupy binding position two; the first tag's UID is rejected
for that position. Pending and unknown results retain spool, codec, and tag
position across restart, and reconciliation remains read-only.

Automated fixtures cover byte-stable Standard and PAXX payloads, numeric field
types, registry identity, all five supported material types, release-example
subtypes, malformed payloads, unknown-field preservation, capacity rejection,
and one/two-tag persistence. Hardware acceptance remains separate: the current
APK must write and read back actual NTAG215 tags on the 2023 Razr, then the U1
running the pinned release must recognize the tested field/type matrix.

## ELEGOO CANVAS processor compatibility

The separate `elegoo-canvas-1.0` compatibility rule targets one NTAG215 carrying only the raw CANVAS format. It does not combine CANVAS and OpenSpool encodings. CANVAS reads the vendor layout natively; a Snapmaker U1 requires PAXX extended firmware with **OpenRFID** selected and the Elegoo processor enabled. Stock U1 firmware is unsupported for this mode.

Current-source verification on 2026-09-29 used PAXX `develop` commit `87df4f1356ffebb7e22e18c62441333c1c440eb4`. That firmware build pins OpenRFID commit `1a6f605d0334157b532afdd14f89fc182d9000f6`. Its `ElegooTagProcessor` reads raw bytes `0x40..0x68`, requires the `EEEEEEEE` manufacturer marker (a format constant, not a brand; SpoolForge reports the profile brand as omitted rather than rejecting non-ELEGOO profiles), maps the two-byte material/subtype code, and extracts RGBA color, nozzle minimum/maximum, diameter, and weight. The pinned reader reads NTAG215's 135 physical pages. Upstream OpenRFID head `a13bc9e181374e182f61db4e69abf9fa1786cc29` additionally supports capacity-container-based NTAG213/215/216 reads, but SpoolForge intentionally requires NTAG215 for this compatibility rule.

PAXX disables Elegoo processing by default because factory tag placement is documented as unreliable on the U1. In firmware-config, select **Snapmaker Components → RFID Detection System → OpenRFID** (or the generic-vendor variant). Then edit `/oem/printer_data/config/extended/openrfid_user.cfg`, uncomment this section, and restart the printer:

```ini
[elegoo_tag_processor]
```

This is a community-firmware compatibility path, not official Snapmaker or ELEGOO endorsement. Source support does not replace physical acceptance on an actual CANVAS and U1/PAXX installation.
