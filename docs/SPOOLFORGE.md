# SpoolForge

**Universal filament identity, inventory, and RFID interoperability.**

SpoolForge is the Forge family's material identity layer. It identifies physical
filament spools, maintains normalized filament profiles and owned-spool
inventory, records what is known about each spool with provenance, and reads or
writes the RFID/NFC formats that its implemented codecs explicitly support.
Where formats can represent compatible facts, SpoolForge provides loss-aware
translation between supported printer ecosystems rather than treating a shared
chip technology as a shared data format.

SpoolForge owns the physical filament and spool domain. Slicing, model editing,
general printer control, print queues, and farm management remain outside its
responsibility. Other Forge-family software may consume SpoolForge's normalized
material data, but this repository does not claim integrations that have not
been implemented.

SpectrumSmith is retired. It is not an active Forge product and SpoolForge does
not depend on it.

The public product name changed from Filamajig to SpoolForge. Compatibility-
sensitive identifiers retain their legacy values as documented in
[`BRANDING.md`](BRANDING.md), so installed applications, stored data, portable
identities, and external references are not broken merely for naming symmetry.
