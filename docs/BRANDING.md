# SpoolForge product identity

- Product name: **SpoolForge**
- Description: **Universal filament identity, inventory, and RFID interoperability.**
- App store name: **SpoolForge**

The Android application ID is `net.jamesjennison.spoolforge`, set on 2026-10-03
before any store release. Earlier builds used `net.jamesjennison.filamajignfc`
and, before that, `net.jamesjennison.spoolio`; Android treats each as a separate
app, so data from an earlier debug install must be copied across once.

The Kotlin namespace and packages remain `net.jamesjennison.filamajignfc`.
Room schema paths, existing database names, `filamajig.spool` portable identity
documents, `application/vnd.filamajig.*` MIME types, `filamajig:` Spoolman
external IDs, request identifiers, and bundled `spoolio`/`filamajig` artifact
format identifiers likewise remain stable compatibility contracts. Internal
source symbols such as `FilamajigApplication`, `FilamajigTheme`, and
`Theme.FilamajigNfc` are not public branding and remain in place to avoid an
unrelated source-tree refactor.

## Colors

Forest green on warm cream, with amber for work in progress, in light and dark
(`Theme.kt`). The palette is deliberately quiet: filament swatches are the
strongest color on any screen, and nothing in the interface should resemble
one.

| Role | Light | Dark |
|---|---|---|
| Primary (buttons, links) | `#286354` | `#7FCDB6` |
| Accent (tag writing in progress) | `#FBE8C6` panel, `#8A5A00` text | `#4A3410` panel, `#F2B45A` text |
| Cards | `#E6EDE6` | `#1F2925` |
| Background | `#F8F6F0` | `#141A18` |
| Error | `#9B2C2C` | `#F2B8B5` |

## Icon and launch screen

The icon is a filament spool seen face-on with two NFC waves: cream (`#FFFCF5`)
and amber (`#F2B45A`) on the brand green (`#286354`). The source drawing is
[`branding/spoolforge-icon.svg`](branding/spoolforge-icon.svg); the app uses the
same paths as an adaptive icon (`ic_launcher_foreground`, with a single-color
`ic_launcher_monochrome` for themed icons). The launch screen shows the icon on
the brand green: Android 12 and later through the system splash screen, older
versions through the window background.

The SpoolForge app-store name is a release acceptance requirement. It must be verified
against the actual store listing when a store submission is prepared.
