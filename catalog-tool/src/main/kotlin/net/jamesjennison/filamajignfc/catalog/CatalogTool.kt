package net.jamesjennison.filamajignfc.catalog

import net.jamesjennison.filamajignfc.core.*
import java.io.BufferedWriter
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

private const val REVISION = "e3888b68"
private data class Row(val fields: List<String>)

fun main(args: Array<String>) {
    require(args.size == 3) { "usage: catalog-tool <ofd-data-dir> <output.tsv.gz> <license>" }
    val root=Path.of(args[0]); val output=Path.of(args[1]); val license=Path.of(args[2])
    require(Files.isDirectory(root)); require(Files.isRegularFile(license))
    val rows=mutableListOf<Row>(); val json=StrictJson(128*1024,32)
    Files.list(root).use { brands -> brands.filter(Files::isDirectory).sorted().forEach { brandDir ->
        val brand=obj(json,brandDir.resolve("brand.json")); val brandName=brand.string("name") ?: error("brand name missing: $brandDir")
        Files.list(brandDir).use { materials -> materials.filter(Files::isDirectory).sorted().forEach { materialDir ->
            val material=obj(json,materialDir.resolve("material.json")); val materialName=material.string("material") ?: error("material missing: $materialDir")
            Files.list(materialDir).use { products -> products.filter(Files::isDirectory).sorted().forEach { productDir ->
                val product=obj(json,productDir.resolve("filament.json")); val productName=product.string("name") ?: productDir.fileName.toString()
                Files.list(productDir).use { colors -> colors.filter(Files::isDirectory).sorted().forEach { colorDir ->
                    val variant=obj(json,colorDir.resolve("variant.json")); val sizesPath=colorDir.resolve("sizes.json"); if(!Files.isRegularFile(sizesPath)) return@forEach
                    val sizes=json.parse(Files.readAllBytes(sizesPath)) as? JsonValue.Arr ?: error("sizes not array: $sizesPath")
                    sizes.values.forEachIndexed { index, value ->
                        val size=value as? JsonValue.Obj ?: error("size not object: $sizesPath[$index]")
                        val packageId=size.string("uuid") ?: error("package uuid missing: $sizesPath[$index]")
                        val diameter=size.decimal("diameter") ?: error("diameter missing: $packageId")
                        val mass=size.int("filament_weight") ?: error("mass missing: $packageId")
                        val gtin=size.string("gtin") ?: size.string("ean")
                        val articleNumber=size.string("article_number")
                        val colors=colors(variant, colorDir)
                        validateRecord(FilamentRecord(packageId,variant.string("uuid"),product.string("uuid"),FieldValue(brandName,"OFD $REVISION"),FieldValue(materialName,"OFD $REVISION"),FieldValue(productName,"OFD $REVISION"),FieldValue(variant.string("name")?:colorDir.fileName.toString(),"OFD $REVISION"),FieldValue(colors.first(),"OFD $REVISION"),FieldValue(diameter,"OFD $REVISION"),FieldValue(mass,"OFD $REVISION"),product.int("min_print_temperature")?.let{FieldValue(it,"OFD $REVISION")},product.int("max_print_temperature")?.let{FieldValue(it,"OFD $REVISION")},product.int("min_bed_temperature")?.let{FieldValue(it,"OFD $REVISION")},product.int("max_bed_temperature")?.let{FieldValue(it,"OFD $REVISION")},gtin,articleNumber,REVISION,Provenance.CATALOG,colors.drop(1)))
                        rows += Row(listOf(packageId,variant.string("uuid")?:"",product.string("uuid")?:"",brandName,materialName,productName,variant.string("name")?:colorDir.fileName.toString(),colors.first(),diameter,mass.toString(),product.int("min_print_temperature")?.toString()?:"",product.int("max_print_temperature")?.toString()?:"",product.int("min_bed_temperature")?.toString()?:"",product.int("max_bed_temperature")?.toString()?:"",gtin?:"",articleNumber?:"",REVISION,colors.drop(1).joinToString(",")))
                    }
                } }
            } }
        } }
    } }
    rows.sortBy { it.fields[0] }
    require(rows.map { it.fields[0] }.toSet().size == rows.size) { "duplicate package UUID" }
    Files.createDirectories(output.parent)
    GZIPOutputStream(Files.newOutputStream(output)).bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine("package_id\tvariant_id\tproduct_id\tbrand\tmaterial\tproduct\tcolor_name\tcolor_hex\tdiameter_mm\tmass_g\tnozzle_min_c\tnozzle_max_c\tbed_min_c\tbed_max_c\tgtin\tarticle_number\tsource_revision\tadditional_color_hexes")
        rows.forEach { row -> writer.appendLine(row.fields.joinToString("\t") { escape(it) }) }
    }
    val digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output)).joinToString(""){"%02x".format(it)}
    val manifest=output.resolveSibling("ofd-catalog-manifest.json")
    val contentDigest=contentDigest(rows)
    Files.writeString(manifest,"""{"source":"OpenFilamentCollective/open-filament-database","revision":"$REVISION","packages":${rows.size},"sha256":"$digest","content_sha256":"$contentDigest","format":"spoolio-tsv-gzip-v2"}""" + "\n")
    Files.copy(license,output.resolveSibling("OFD-LICENSE.txt"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    println("generated packages=${rows.size} bytes=${Files.size(output)} sha256=$digest")
}

private fun obj(json:StrictJson,path:Path):JsonValue.Obj = json.parse(Files.readAllBytes(path)) as? JsonValue.Obj ?: error("object required: $path")
private fun escape(value:String):String { require('\t' !in value && '\n' !in value && '\r' !in value) { "unsafe TSV field" }; return value }

private fun colors(variant: JsonValue.Obj, path: Path): List<String> {
    val value=variant.values["color_hex"]
    val colors=when(value) {
        is JsonValue.Str -> listOf(value.value)
        is JsonValue.Arr -> value.values.map { (it as? JsonValue.Str)?.value ?: error("color_hex array must contain strings: $path") }
        else -> error("color_hex missing: $path")
    }
    require(colors.isNotEmpty()) { "color_hex is empty: $path" }
    return colors.map { "#${normalizeHex(it)}" }
}

private fun contentDigest(rows: List<Row>): String {
    val digest=MessageDigest.getInstance("SHA-256")
    rows.forEach { row -> row.fields.forEachIndexed { index, value ->
        val normalized=if(index==7) value.removePrefix("#") else value
        val bytes=normalized.encodeToByteArray()
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    } }
    return digest.digest().joinToString(""){"%02x".format(it)}
}
