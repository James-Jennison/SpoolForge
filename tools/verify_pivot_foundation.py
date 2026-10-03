from pathlib import Path

root = Path(__file__).resolve().parents[1]
required = {
    "README.md": ["SpoolForge", "portable"],
    "docs/PRODUCT.md": ["Problem and promise", "Differentiation", "Non-goals"],
    "docs/ARCHITECTURE.md": ["CatalogProvider", "PhysicalSpool", "PaxxU1ExtendedTagCodec"],
    "docs/ROADMAP.md": ["Already implemented", "Keep / Change / Add / Remove / Defer", "M1 — Canonical persistence"],
    "docs/decisions/ADR-001-portable-filament-identity-pivot.md": ["Status: Accepted", "v1.5.2-paxx12-21"],
    "docs/research/competitive-filament-app-teardown.md": ["2026-09-07", "Tag My Spool", "Spool Hoarder", "SimplyPrint", "Spoolman GO", "OFD"],
}
for relative, needles in required.items():
    text = (root / relative).read_text()
    for needle in needles:
        assert needle in text, f"{relative}: missing {needle!r}"

source = "\n".join((root / p).read_text() for p in [
    "core/src/main/kotlin/net/jamesjennison/filamajignfc/core/DomainModels.kt",
    "core/src/main/kotlin/net/jamesjennison/filamajignfc/core/CatalogProvider.kt",
    "core/src/main/kotlin/net/jamesjennison/filamajignfc/core/TagCodec.kt",
])
for symbol in ["FilamentProfile", "PhysicalSpool", "SourceRef", "CatalogProvider", "StandardOpenSpoolTagCodec", "PaxxU1ExtendedTagCodec"]:
    assert symbol in source, f"missing foundational symbol {symbol}"
print("Pivot documents and foundational boundaries: PASS")
