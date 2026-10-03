# Bundled GTIN brand coverage audit

Audit of the built three-source index on 2026-09-07. Counts are direct candidates and
distinct normalized GTIN-14 keys, not inferred brand/name matches.

| Brand target | Candidates | GTINs | Sources |
|---|---:|---:|---|
| eSUN | 407 | 350 | OpenPrintTag 352; Community 55 |
| Hatchbox | 38 | 37 | OFD 36; OpenPrintTag 2 |
| Ultimaker | 0 | 0 | none |
| XYZprinting | 0 | 0 | none |
| NinjaTek | 0 | 0 | none |
| 3D Fuel | 0 | 0 | none |
| SUNLU | 0 | 0 | none |
| Infix | 0 | 0 | none |
| Extrudr | 0 | 0 | none |
| Verbatim | 0 | 0 | none |
| Prusament | 366 | 156 | OFD 81; OpenPrintTag 285 |
| Bambu Lab | 230 | 190 | OFD 212; Community 18 |
| Polymaker | 2,801 | 746 | Community 2,128; OpenPrintTag 487; OFD 186 |

The live Polymaker label GTIN `06938936717461` and the earlier live package GTIN
`00080238000089` both have zero direct candidates. A brand being covered does not imply
that every regional, marketplace, or packaging SKU is present.
