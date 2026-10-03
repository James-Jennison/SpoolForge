package net.jamesjennison.filamajignfc.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import net.jamesjennison.filamajignfc.*
import net.jamesjennison.filamajignfc.core.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Immutable bundled sidecar. No network, no writable connection, no modifications to the OFD/user DBs. */
class GtinIndex(private val context: Context) {
    @Synchronized fun lookup(raw: String): List<FilamentItem> {
        val key = Gtin.normalize(raw) ?: throw IllegalArgumentException("Enter a valid GTIN/EAN/UPC-A with its check digit (8, 12, 13 or 14 digits).")
        val file = ensureFile()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            return db.rawQuery("SELECT gtin14,source,package_id,values_json,evidence_json FROM candidates WHERE gtin14=? ORDER BY source,package_id", arrayOf(key)).use(::decode)
        }
    }
    @Synchronized fun lookupByPackageId(packageId: String): FilamentItem? {
        require(packageId.isNotBlank() && packageId.length <= 300)
        SQLiteDatabase.openDatabase(ensureFile().path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            return db.rawQuery("SELECT gtin14,source,package_id,values_json,evidence_json FROM candidates WHERE package_id=? ORDER BY gtin14,source LIMIT 1", arrayOf(packageId)).use { cursor -> decode(cursor).firstOrNull() }
        }
    }
    private fun decode(cursor: android.database.Cursor): List<FilamentItem> = buildList {
        while (cursor.moveToNext()) {
            val key = cursor.getString(0); val id = cursor.getString(2)
            val values = JSONObject(cursor.getString(3)); val evidence = cursor.getString(4); val ev = JSONObject(evidence)
            val snapshot = ev.getJSONObject("snapshot"); val revision = snapshot.getString("revision")
            fun text(k: String) = values.optString(k, "")
            fun int(k: String) = text(k).toBigDecimalOrNull()?.runCatching { intValueExact() }?.getOrNull()
            val entry = CatalogEntry(id,text("variantId"),text("productId"),text("brand"),text("material"),text("product"),text("colorName"),text("colorHex"),text("diameter"),int("mass")?:0,int("nozzleMin"),int("nozzleMax"),int("bedMin"),int("bedMax"),key,text("articleNumber").ifBlank { null },revision,text("additionalColorHexes"))
            val origins = ev.getJSONObject("fields")
            val sources = origins.keys().asSequence().associateWith { field ->
                val claim = origins.getJSONObject(field)
                "${snapshot.getString("source")} ${revision} · ${claim.getString("origin")} · ${claim.getString("kind")}" }
            add(FilamentItem(entry, Provenance.CATALOG, sources, barcodeEvidence = evidence))
        }
    }
    private fun digest(file: File) = file.inputStream().use { input -> val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hash.digest().joinToString(""){"%02x".format(it)} }
    private fun ensureFile(): File {
        val manifest = context.assets.open("gtin-index-manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getString("format") == "spoolio-gtin-sqlite-v1")
        val expected = manifest.getString("sha256"); require(expected.matches(Regex("[a-f0-9]{64}")))
        val file = File(context.filesDir, "gtin-$expected.sqlite")
        if (file.isFile && digest(file) == expected) return file
        val temporary = File.createTempFile("gtin-", ".tmp", context.filesDir)
        try {
            GZIPInputStream(context.assets.open("gtin-index.sqlite.gzip")).use { input -> temporary.outputStream().use { output ->
                val buffer=ByteArray(65536);var count=0L;val limit=manifest.getLong("uncompressed_bytes")
                require(limit in 1..100_000_000)
                while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=limit);output.write(buffer,0,n)}
                require(count==limit)
            } }
            require(digest(temporary)==expected) { "Bundled barcode index checksum mismatch" }
            // All readers are opened/closed in this instance's synchronized lookup.
            check(temporary.renameTo(file)) { "Could not install barcode index" }
            return file
        } finally { temporary.delete() }
    }
}
