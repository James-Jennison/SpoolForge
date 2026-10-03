# SpoolForge

**Tap a spool, know what it is — and so does your printer.**

SpoolForge is the Forge family's local-first material identity layer. It identifies physical filament, maintains normalized profiles and spool inventory, reads and writes supported RFID/NFC formats, and translates metadata between explicitly supported printer ecosystems. Its compatibility resolver can select one NTAG215 with ELEGOO CANVAS encoding for both CANVAS and a Snapmaker U1 running PAXX/OpenRFID with the Elegoo processor enabled; stock U1 firmware is explicitly excluded. Separate codecs preserve format-oriented workflows for OpenPrintTag, Anycubic ACE, Creality CFS, OpenTag3D, QIDI Box, TigerTag, standard OpenSpool, and PAXX U1.

The current repository is an implementation prototype. See [SpoolForge's role in the Forge family](docs/SPOOLFORGE.md), [product definition](docs/PRODUCT.md), [architecture](docs/ARCHITECTURE.md), [pivot decision](docs/decisions/ADR-001-portable-filament-identity-pivot.md), [roadmap](docs/ROADMAP.md), and [competitive teardown](docs/research/competitive-filament-app-teardown.md).

## License

SpoolForge is free software licensed under the [GNU General Public License, version 3 or later](LICENSE). Bundled third-party data and the sources its codecs derive from are listed in [NOTICE](NOTICE).
