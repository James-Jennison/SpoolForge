# SpoolForge portable spool identity 1

SpoolForge portable identity is a self-contained UTF-8 JSON format for moving one physical spool and its reusable filament profile without an account, server, proprietary URL, or installed app. The legacy MIME type for a full bundle is `application/vnd.filamajig.spool+json`; it remains unchanged so existing exports and integrations stay readable.

## Identity and versioning

Every document has the legacy compatibility identifier `schema: "filamajig.spool"`, integer `version: 1`, a `profile_id`, and a different `spool_id`. The profile identifies the reusable material/package definition. The spool identifies one owned physical instance and carries its initial and remaining quantity. Implementations must not substitute one ID for the other.

Version 1 readers reject older, malformed, oversized, or wrong-schema documents. A document with a larger positive version is preserved as uninterpreted bytes and must not be imported, rewritten, or silently downgraded.

## Encodings

The compact QR form includes:

- schema, version, profile ID, and spool ID;
- brand, product, material, color, diameter, mass, temperature, TD, GTIN/SKU, source revision, and additional colors when present;
- initial and remaining spool quantity.

The export bundle contains the same identity plus the source string attached to every supported field. Null optional values are omitted. Object keys have a fixed order, color hex values are normalized to six uppercase digits, and UTF-8 is used without ASCII escaping. A compact QR is limited to 2,048 bytes; larger data must use the export bundle.

## Offline round trip

The detail view for a saved local spool generates its QR locally and can share the full JSON bundle through Android's user-selected share target. The home screen can paste/import that bundle. The label/QR camera recognizes this schema locally and bypasses AI analysis. Both import paths open the ordinary editable review form before saving.

The QR contains its data directly rather than a link. A printed QR or separately saved JSON bundle therefore remains readable after SpoolForge is removed, its local database is lost, or the original phone is unavailable. Reinstalling the app is not required to recover the JSON with any standards-compliant QR decoder, though another implementation must follow this versioning contract to interpret it.

## Safety and privacy

Portable documents contain spool data selected for export. They do not contain API keys, device identifiers, NFC UIDs, image data, account data, or network locations. Sharing is an explicit Android user action.
