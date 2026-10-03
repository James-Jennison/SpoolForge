package net.jamesjennison.filamajignfc.core

data class TagFormat(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val protocolVersion: String,
    val compatibilityTarget: String,
    val transport: TagTransport = TagTransport.NDEF,
)

enum class TagTransport { NDEF, NTAG_RAW, MIFARE_CLASSIC_QIDI, MIFARE_CLASSIC_CFS }

class EncodedTag(payload: ByteArray, val format: TagFormat, val omittedFields: Set<String>, val includedWireFields: Set<String>, records:List<TagRecord>) {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
    val sizeBytes: Int get() = bytes.size
    val ndefRecords:List<TagRecord> = records.map { TagRecord(it.tnf,it.mime,it.payload,it.id) }
    val ndefMessageSizeBytes:Int = if(format.transport==TagTransport.NDEF) ndefRecords.sumOf { record ->
        val payloadSize=record.payload.size
        2 + (if(payloadSize<256)1 else 4) + (if(record.id.isNotEmpty())1 else 0) + record.mime.encodeToByteArray().size + record.id.size + payloadSize
    } else bytes.size
    fun fits(capacityBytes: Int): Boolean = capacityBytes >= 0 && ndefMessageSizeBytes <= capacityBytes
    override fun equals(other:Any?)=other is EncodedTag&&format==other.format&&omittedFields==other.omittedFields&&includedWireFields==other.includedWireFields&&bytes.contentEquals(other.bytes)&&ndefRecords==other.ndefRecords
    override fun hashCode()=31*(31*(31*(31*format.hashCode()+omittedFields.hashCode())+includedWireFields.hashCode())+bytes.contentHashCode())+ndefRecords.hashCode()
}

interface FilamentTagCodec {
    val format: TagFormat
    fun intent(record: FilamentRecord): OpenSpoolIntent
    fun encode(record: FilamentRecord): EncodedTag = intent(record).let { encoded ->
        val root = StrictJson().parse(encoded.payload) as JsonValue.Obj
        EncodedTag(encoded.payload, format, encoded.omittedFields, root.values.keys, encoded.ndefRecords)
    }
    fun decode(payload: ByteArray): DecodeResult
}

object StandardOpenSpoolTagCodec : FilamentTagCodec {
    override val format = TagFormat("openspool-1.0", "Standard OpenSpool 1.0", OpenSpoolCodec.MIME, "1.0", "OpenSpool 1.0 readers")
    override fun intent(record: FilamentRecord) = OpenSpoolCodec.encode(record, OpenSpoolProfile.CANONICAL,format.id)
    override fun decode(payload: ByteArray) = OpenSpoolCodec.decode(payload)
}

object PaxxU1ExtendedTagCodec : FilamentTagCodec {
    const val TARGET_FIRMWARE = "v1.5.2-paxx12-21"
    override val format = TagFormat("openspool-paxx-u1-1.0", "PAXX U1 Extended", OpenSpoolCodec.MIME, "1.0", "Snapmaker U1 $TARGET_FIRMWARE")
    override fun intent(record: FilamentRecord) = OpenSpoolCodec.encode(record, OpenSpoolProfile.PAXX,format.id)
    override fun decode(payload: ByteArray) = OpenSpoolCodec.decode(payload)
}

object TagCodecRegistry {
    val codecs: List<FilamentTagCodec> = listOf(
        StandardOpenSpoolTagCodec,PaxxU1ExtendedTagCodec,ElegooCanvasTagCodec,
        OpenPrintTagWriteCodec,AnycubicAceTagCodec,CrealityCfsTagCodec,OpenTag3dWriteCodec,QidiBoxTagCodec,TigerTagWriteCodec,
    )
    const val DEFAULT_CODEC_ID = "elegoo-canvas-1.0"
    fun require(id: String): FilamentTagCodec = codecs.singleOrNull { it.format.id == id }
        ?: throw ValidationException("Unknown tag codec '$id'")
}
