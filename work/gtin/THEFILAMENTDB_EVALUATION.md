# TheFilamentDB source evaluation

Evaluated 2026-09-07 for inclusion in Filamajig NFC's offline GTIN index.

- Source page: https://thefilamentdb.com/database
- Download: https://apis.issou.best/thefilamentdb/database/download
- Published license: CC BY 4.0; title `TheFilamentDB`, author `issou.best`
- Snapshot SHA-256: `72de6f6eecb9fd31842b07070b5b22040b76c35376262fee2eb2ee4f7c0e219c`
- Snapshot format: gzip JSONL
- Rows inspected: 13,958
- Exact fields: `brand`, `colorHex`, `colorName`, `diameter`, `factoryInfo`, `filamentPage`, `isColorBlended`, `material`, `moreDetails`, `name`, `provenance`

The export contains no GTIN, EAN, UPC, barcode, SKU, or stable source record ID.
It is therefore excluded from the GTIN index. Joining it to barcode-bearing sources by
brand/name/color would be an inferred association and could attach the wrong product to
a scanned package. It may be evaluated later as a separately attributed catalog enrichment
source if Filamajig needs its non-barcode fields.
