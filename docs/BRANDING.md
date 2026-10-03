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

The SpoolForge app-store name is a release acceptance requirement. It must be verified
against the actual store listing when a store submission is prepared.
