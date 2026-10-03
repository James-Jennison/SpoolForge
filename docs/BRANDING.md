# SpoolForge product identity

- Product name: **SpoolForge**
- Description: **Universal filament identity, inventory, and RFID interoperability.**
- App store name: **SpoolForge**

The Android application ID and Kotlin namespace are
`net.jamesjennison.filamajignfc`. This legacy identifier is retained for
upgrade and installed-data compatibility after the Filamajig to SpoolForge
product rename. It previously replaced the prototype application ID
`net.jamesjennison.spoolio`; changing it again would create a third Android app
identity. Kotlin packages, Room schema paths, existing database names,
`filamajig.spool` portable identity documents, `application/vnd.filamajig.*`
MIME types, `filamajig:` Spoolman external IDs, request identifiers, and bundled
`spoolio`/`filamajig` artifact format identifiers likewise remain stable
compatibility contracts. Existing internal source symbols such as
`FilamajigApplication`, `FilamajigTheme`, and `Theme.FilamajigNfc` are not
public branding and remain in place to avoid an unrelated source-tree refactor.

The SpoolForge app-store name is a release acceptance requirement. It must be verified
against the actual store listing when a store submission is prepared.
