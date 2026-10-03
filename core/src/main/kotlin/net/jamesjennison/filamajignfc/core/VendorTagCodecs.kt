package net.jamesjennison.filamajignfc.core

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private fun externalIntent(record:FilamentRecord,codec:FilamentTagCodec,payload:ByteArray,recordMime:String=codec.format.mimeType,tnf:Int=2,id:ByteArray=byteArrayOf(),omitted:Set<String> = emptySet()) =
    OpenSpoolIntent(record,OpenSpoolProfile.CANONICAL,payload,omitted,codec.format.id,listOf(TagRecord(tnf,recordMime,payload,id)))

private fun externalEncoded(codec:FilamentTagCodec,intent:OpenSpoolIntent,fields:Set<String>) = EncodedTag(intent.payload,codec.format,intent.omittedFields,fields,intent.ndefRecords)

object OpenTag3dWriteCodec:FilamentTagCodec {
    override val format=TagFormat("opentag3d-2.000-write","OpenTag3D 2.000",OpenTag3dV2Adapter.format.mimeType,"2.000","OpenTag3D 2.000 readers")
    private val fields=setOf("version","material","modifier","brand","color_name","colors","sku","gtin","diameter","temperatures","weight","transmission_distance")
    override fun intent(record:FilamentRecord):OpenSpoolIntent {
        validateRecord(record)
        val out=ByteArray(224)
        putUInt(out,0,2,2000)
        val family=materialFamily(record.material.value)
        putText(out,0x02,5,family)
        putText(out,0x07,5,openTagModifier(record.material.value,record.product.value,family))
        putText(out,0x0C,16,record.brand.value)
        putText(out,0x1C,32,record.colorName.value)
        putRgba(out,0x3C,record.colorHex.value)
        record.additionalColors.take(3).forEachIndexed{i,color->putRgba(out,0x40+i*4,color)}
        record.sku?.let{putText(out,0x6C,16,it)}
        record.gtin?.takeIf{it.all(Char::isDigit)}?.toBigIntegerOrNull()?.let{value->
            if(value.bitLength()>48)throw ValidationException("OpenTag3D GTIN exceeds its six-byte field")
            putUInt(out,0x7C,6,value.toLong())
        }
        val diameter=record.diameterMm.value.toBigDecimal().movePointRight(3).setScale(0,RoundingMode.UNNECESSARY).intValueExact()
        putUInt(out,0x8C,2,diameter.toLong())
        putScaledTemp(out,0x91,record.nozzleMinC?.value);putScaledTemp(out,0x92,record.nozzleMaxC?.value)
        putScaledTemp(out,0x95,record.bedMinC?.value);putScaledTemp(out,0x96,record.bedMaxC?.value)
        putUInt(out,0x9E,2,record.nominalMassG.value.toLong())
        record.transmissionDistance?.let{td->
            val scaled=td.value.toBigDecimal().movePointRight(1).setScale(0,RoundingMode.UNNECESSARY).intValueExact()
            if(scaled !in 1..255)throw ValidationException("OpenTag3D transmission distance must fit tenths in one byte")
            out[0xA7]=scaled.toByte()
        }
        val omitted=setOf("bedTemperatureTargets","drying","remainingWeight","packageIdentity").filterTo(mutableSetOf()){true}
        return externalIntent(record,this,out,omitted=omitted)
    }
    override fun encode(record:FilamentRecord)=intent(record).let{externalEncoded(this,it,fields)}
    override fun decode(payload:ByteArray)=OpenTag3dV2Adapter.decode(payload)
    private fun putScaledTemp(out:ByteArray,offset:Int,value:Int?){value?:return;if(value%5!=0)throw ValidationException("OpenTag3D temperatures must be multiples of 5 C");val scaled=value/5;if(scaled !in 1..255)throw ValidationException("OpenTag3D temperature is outside its one-byte range");out[offset]=scaled.toByte()}
}

object OpenPrintTagWriteCodec:FilamentTagCodec {
    override val format=TagFormat("openprinttag-current-write","OpenPrintTag / Prusa initialization",OpenPrintTagAdapter.format.mimeType,"current","Unprotected, NDEF-formatted NFC-V / ISO 15693 tags")
    private val fields=setOf("material_class","material_type","material_name","brand_name","gtin","sku","nominal_netto_full_weight","primary_color","secondary_colors","transmission_distance","filament_diameter_v2","print_temperatures","bed_temperatures")
    override fun intent(record:FilamentRecord):OpenSpoolIntent {
        validateRecord(record)
        val materialType=OPT_MATERIAL_TYPES[materialFamily(record.material.value)]
        val main=linkedMapOf<Long,Any?>(8L to 0L,10L to record.product.value.ifBlank{record.material.value},11L to record.brand.value,16L to record.nominalMassG.value.toLong(),19L to rgba(record.colorHex.value),61L to record.diameterMm.value.toBigDecimal().movePointRight(3).setScale(0,RoundingMode.UNNECESSARY).longValueExact())
        materialType?.let{main[9L]=it};record.gtin?.takeIf{it.all(Char::isDigit)}?.toLongOrNull()?.let{main[4L]=it};record.sku?.take(16)?.let{main[6L]=it}
        record.additionalColors.take(5).forEachIndexed{i,color->main[20L+i]=rgba(color)}
        record.transmissionDistance?.let{main[27L]=it.value.toBigDecimal()}
        record.nozzleMinC?.let{main[34L]=it.value.toLong()};record.nozzleMaxC?.let{main[35L]=it.value.toLong()}
        record.bedMinC?.let{main[37L]=it.value.toLong()};record.bedMaxC?.let{main[38L]=it.value.toLong()}
        val payload=CborWriter.map(emptyMap())+CborWriter.map(main)
        return externalIntent(record,this,payload,omitted=setOf("colorName","drying","remainingWeight"))
    }
    override fun encode(record:FilamentRecord)=intent(record).let{externalEncoded(this,it,fields)}
    override fun decode(payload:ByteArray)=OpenPrintTagAdapter.decode(payload)
}

object AnycubicAceTagCodec:FilamentTagCodec {
    override val format=TagFormat("anycubic-ace-v2","Anycubic ACE Pro",RAW_MIME,"2","Anycubic ACE Pro on NTAG213/215/216",TagTransport.NTAG_RAW)
    private val fields=setOf("format","sku","manufacturer","type","color","nozzle_range","bed_range","diameter","weight")
    override fun intent(record:FilamentRecord):OpenSpoolIntent {
        validateRecord(record)
        val type=anycubicType(record)
        val out=ByteArray(144)
        out[0]=0x7B;out[2]=0x65
        putText(out,4,20,ANYCUBIC_SKUS.getValue(type));putText(out,24,20,"AC");putText(out,44,20,type)
        val color=normalizeHex(record.colorHex.value);out[64]=0xFF.toByte();out[65]=color.substring(4,6).toInt(16).toByte();out[66]=color.substring(2,4).toInt(16).toByte();out[67]=color.substring(0,2).toInt(16).toByte()
        putU16Le(out,80,record.nozzleMinC?.value?:throw ValidationException("Anycubic ACE requires a nozzle minimum temperature"));putU16Le(out,82,record.nozzleMaxC?.value?:throw ValidationException("Anycubic ACE requires a nozzle maximum temperature"))
        putU16Le(out,100,record.bedMinC?.value?:throw ValidationException("Anycubic ACE requires a bed minimum temperature"));putU16Le(out,102,record.bedMaxC?.value?:throw ValidationException("Anycubic ACE requires a bed maximum temperature"))
        putU16Le(out,104,record.diameterMm.value.toBigDecimal().movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).intValueExact());putU16Le(out,108,record.nominalMassG.value)
        out[143]=0x4D
        return externalIntent(record,this,out,RAW_MIME,-1,omitted=setOf("brand","product","colorName","additionalColors","transmissionDistance"))
    }
    override fun encode(record:FilamentRecord)=intent(record).let{externalEncoded(this,it,fields)}
    override fun decode(payload:ByteArray):DecodeResult { return try {
        if(payload.size!=144||payload[0]!=0x7B.toByte()||payload[2]!=0x65.toByte())return DecodeResult.Rejected("Not an Anycubic ACE tag")
        val source="Anycubic ACE tag";val type=ascii(payload,44,20).ifBlank{throw ValidationException("Anycubic material is missing")};val color="%02X%02X%02X".format(payload[67].toInt()and 255,payload[66].toInt()and 255,payload[65].toInt()and 255)
        supportedRecord(payload,"anycubic",source,"Anycubic",type,type,color,u16le(payload,104).toBigDecimal().movePointLeft(2).toPlainString(),u16le(payload,108),u16le(payload,80),u16le(payload,82),u16le(payload,100),u16le(payload,102))
    }catch(e:ValidationException){DecodeResult.Rejected(e.message?:"Malformed Anycubic tag")} }
}

object TigerTagWriteCodec:FilamentTagCodec {
    override val format=TagFormat("tigertag-2.1","Compatible with TigerTag 2.1",RAW_MIME,"2.1","TigerTag-compatible NTAG213/215/216 readers",TagTransport.NTAG_RAW)
    private val fields=setOf("format_id","product_id","material_id","aspect","type","diameter","brand_id","colors","weight","temperatures","transmission_distance","remaining_weight")
    override fun intent(record:FilamentRecord):OpenSpoolIntent {
        validateRecord(record);val family=materialFamily(record.material.value);val material=TIGER_MATERIALS[family]?:throw ValidationException("TigerTag registry mapping is unavailable for '$family'")
        val out=ByteArray(144);putUInt(out,0,4,0x5BF59264);putUInt(out,4,4,0xFFFFFFFFL);putUInt(out,8,2,material.toLong());out[10]=tigerAspect(record.product.value).toByte();out[12]=0x8E.toByte();out[13]=when(record.diameterMm.value.toBigDecimal()){BigDecimal("1.75")->0x38.toByte();BigDecimal("2.85")->0xDD.toByte();else->throw ValidationException("TigerTag supports registered 1.75 or 2.85 mm diameter IDs")};putUInt(out,14,2,tigerBrand(record.brand.value).toLong());putRgba(out,16,record.colorHex.value);putUInt(out,20,3,record.nominalMassG.value.toLong());out[23]=21
        putUInt(out,24,2,(record.nozzleMinC?.value?:0).toLong());putUInt(out,26,2,(record.nozzleMaxC?.value?:0).toLong());out[30]=(record.bedMinC?.value?:0).toByte();out[31]=(record.bedMaxC?.value?:0).toByte()
        record.additionalColors.getOrNull(0)?.let{putRgb(out,36,it)};record.additionalColors.getOrNull(1)?.let{putRgb(out,40,it)}
        record.transmissionDistance?.let{putUInt(out,44,2,it.value.toBigDecimal().movePointRight(1).setScale(0,RoundingMode.UNNECESSARY).longValueExact())}
        putUInt(out,76,3,record.nominalMassG.value.toLong())
        return externalIntent(record,this,out,RAW_MIME,-1,omitted=setOf("sku","gtin","colorName","drying","signature"))
    }
    override fun encode(record:FilamentRecord)=intent(record).let{externalEncoded(this,it,fields)}
    override fun decode(payload:ByteArray):DecodeResult { return try {
        if(payload.size!=144||u32(payload,0)!=0x5BF59264L)return DecodeResult.Rejected("Not a standard TigerTag")
        val source="TigerTag 2.1";val material=TIGER_MATERIALS.entries.firstOrNull{it.value==u16be(payload,8)}?.key?:"Unknown ${u16be(payload,8)}";val brand=TIGER_BRANDS.entries.firstOrNull{it.value==u16be(payload,14)}?.key?:"Generic";val color=payload.copyOfRange(16,19).joinToString(""){"%02X".format(it.toInt()and 255)};val diameter=when(payload[13].toInt()and 255){0x38->"1.75";0xDD->"2.85";else->throw ValidationException("Unknown TigerTag diameter ID")}
        supportedRecord(payload,"tigertag",source,brand,material,material,color,diameter,u24be(payload,20),u16be(payload,24),u16be(payload,26),payload[30].toInt()and 255,payload[31].toInt()and 255)
    }catch(e:ValidationException){DecodeResult.Rejected(e.message?:"Malformed TigerTag")} }
}

object QidiBoxTagCodec:FilamentTagCodec {
    override val format=TagFormat("qidi-box-1","QIDI Box",RAW_MIME,"1","QIDI Box MIFARE Classic 1K",TagTransport.MIFARE_CLASSIC_QIDI)
    private val fields=setOf("material_code","color_code","manufacturer_code")
    override fun intent(record:FilamentRecord):OpenSpoolIntent {
        validateRecord(record);val material=qidiMaterial(record);val color=qidiNearestColor(normalizeHex(record.colorHex.value));val out=ByteArray(16);out[0]=material.toByte();out[1]=color.toByte();out[2]=1
        return externalIntent(record,this,out,RAW_MIME,-1,omitted=setOf("brand","temperatures","diameter","weight","sku","gtin","transmissionDistance","additionalColors"))
    }
    override fun encode(record:FilamentRecord)=intent(record).let{externalEncoded(this,it,fields)}
    override fun decode(payload:ByteArray):DecodeResult { return try {
        if(payload.size!=16||payload.copyOfRange(3,16).any{it!=0.toByte()})return DecodeResult.Rejected("Not a QIDI Box data block")
        val material=QIDI_MATERIALS[payload[0].toInt()and 255]?:return DecodeResult.Rejected("Unknown QIDI material code")
        val rgb=QIDI_COLORS[payload[1].toInt()and 255]?:return DecodeResult.Rejected("Unknown QIDI color code")
        supportedRecord(payload,"qidi","QIDI Box tag","QIDI",material,material,"%06X".format(rgb),"1.75",1000,null,null,null,null,listOf("QIDI tags do not carry temperatures, diameter, or weight; local defaults are shown for review"))
    }catch(e:ValidationException){DecodeResult.Rejected(e.message?:"Malformed QIDI tag")} }
}

object CrealityCfsTagCodec:FilamentTagCodec {
    override val format=TagFormat("creality-cfs-1","Creality CFS",RAW_MIME,"1","Creality CFS on MIFARE Classic 1K",TagTransport.MIFARE_CLASSIC_CFS)
    private val fields=setOf("date_code","vendor_code","batch_code","material_id","color","length_m","serial")
    private val authenticationAesKey=byteArrayOf(113,51,98,117,94,116,49,110,113,102,90,40,112,102,36,49)
    private val payloadAesKey=byteArrayOf(72,64,67,70,107,82,110,122,64,75,65,116,66,74,112,50)
    private val transportAccessBytes=byteArrayOf(0xFF.toByte(),0x07,0x80.toByte(),0x69)

    override fun intent(record:FilamentRecord):OpenSpoolIntent {
        validateRecord(record)
        if(record.diameterMm.value.toBigDecimal().compareTo(BigDecimal("1.75"))!=0)throw ValidationException("Creality CFS supports 1.75 mm filament profiles")
        val materialId=crealityMaterialId(record)
        val lengthMeters=(record.nominalMassG.value.toLong()*330L/1000L).toInt()
        if(lengthMeters !in 1..9999)throw ValidationException("Creality CFS calculated filament length must fit four decimal digits")
        val serial=stableCfsSerial(record)
        val plain=("AB124"+"0276"+"A2"+"1$materialId"+"0${normalizeHex(record.colorHex.value)}"+lengthMeters.toString().padStart(4,'0')+serial+"0".repeat(14)).encodeToByteArray()
        check(plain.size==48)
        val encrypted=aes(Cipher.ENCRYPT_MODE,payloadAesKey,plain)
        return externalIntent(record,this,encrypted,RAW_MIME,-1,omitted=setOf("brand","product","colorName","temperatures","diameter","additionalColors","transmissionDistance","sku","gtin"))
    }

    override fun encode(record:FilamentRecord)=intent(record).let{externalEncoded(this,it,fields)}

    override fun decode(payload:ByteArray):DecodeResult {
        if(payload.size!=48)return DecodeResult.Rejected("Creality CFS data must contain three MIFARE blocks")
        return try {
            val plain=aes(Cipher.DECRYPT_MODE,payloadAesKey,payload)
            if(plain.any{it.toInt() !in 0x20..0x7e})return DecodeResult.Rejected("Not a Creality CFS encrypted payload")
            val text=plain.toString(Charsets.US_ASCII)
            val date=text.substring(0,5);val vendor=text.substring(5,9);val batch=text.substring(9,11);val materialId=text.substring(11,17);val color=text.substring(17,24);val length=text.substring(24,28);val serial=text.substring(28,34);val reserve=text.substring(34,48)
            if(!date.matches(Regex("[A-Z0-9]{5}"))||!vendor.matches(Regex("[A-Z0-9]{4}"))||!batch.matches(Regex("[A-Z0-9]{2}"))||!materialId.matches(Regex("[A-Z0-9]{6}"))||!color.matches(Regex("0[0-9A-F]{6}"))||!length.matches(Regex("[0-9]{4}"))||!serial.matches(Regex("[A-Z0-9]{6}"))||!reserve.matches(Regex("[A-Z0-9]{14}")))return DecodeResult.Rejected("Decrypted data does not match the Creality CFS field layout")
            val known=CFS_MATERIAL_NAMES[materialId.substring(1)]?:return DecodeResult.ReadOnly("Unknown Creality CFS material ID $materialId",payload.copyOf())
            val meters=length.toInt();if(meters<=0)return DecodeResult.Rejected("Creality CFS length is zero")
            val mass=(meters.toLong()*1000L/330L).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            supportedRecord(payload,"creality-cfs","Creality CFS tag","Creality",known,known,color.substring(1),"1.75",mass,null,null,null,null,listOf("CFS tags store filament length, not exact mass or temperature settings; mass is estimated at 330 m per kg","CFS metadata: date $date, vendor $vendor, batch $batch, serial $serial"))
        }catch(e:Exception){DecodeResult.Rejected("Malformed Creality CFS payload")}
    }

    fun authenticationKey(uid:ByteArray):ByteArray {
        require(uid.size==4){"Creality CFS requires a four-byte MIFARE Classic UID"}
        val repeated=ByteArray(16){uid[it%uid.size]}
        return aes(Cipher.ENCRYPT_MODE,authenticationAesKey,repeated).copyOf(6)
    }

    fun hasSupportedBlankTrailer(trailer:ByteArray):Boolean = trailer.size==16&&trailer.copyOfRange(6,10).contentEquals(transportAccessBytes)&&trailer.copyOfRange(10,16).all{it==0xFF.toByte()}
    fun hasFinalizedTrailer(trailer:ByteArray,derivedKey:ByteArray):Boolean = derivedKey.size==6&&trailer.size==16&&trailer.copyOfRange(6,10).contentEquals(transportAccessBytes)&&trailer.copyOfRange(10,16).contentEquals(derivedKey)

    private fun aes(mode:Int,key:ByteArray,input:ByteArray):ByteArray {
        require(input.size%16==0)
        return Cipher.getInstance("AES/ECB/NoPadding").apply{init(mode,SecretKeySpec(key,"AES"))}.doFinal(input)
    }

    private fun stableCfsSerial(record:FilamentRecord):String {
        val seed=listOf(record.packageId,record.variantId.orEmpty(),record.productId.orEmpty(),record.sourceRevision).joinToString("\u0000").encodeToByteArray()
        val digest=MessageDigest.getInstance("SHA-256").digest(seed)
        val number=(((digest[0].toLong()and 255L)shl 24)or((digest[1].toLong()and 255L)shl 16)or((digest[2].toLong()and 255L)shl 8)or(digest[3].toLong()and 255L))%900000L+100000L
        return number.toString()
    }
}

private object CborWriter {
    fun map(values:Map<Long,Any?>):ByteArray {val out=ByteArrayOutputStream();head(out,5,values.size.toLong());values.forEach{(k,v)->head(out,0,k);value(out,v)};return out.toByteArray()}
    private fun value(out:ByteArrayOutputStream,value:Any?){when(value){is Long->if(value>=0)head(out,0,value)else head(out,1,-1-value);is Int->value(out,value.toLong());is String->{val b=value.encodeToByteArray();head(out,3,b.size.toLong());out.write(b)};is ByteArray->{head(out,2,value.size.toLong());out.write(value)};is BigDecimal->{out.write(0xFB);val bits=value.toDouble().toBits();repeat(8){i->out.write((bits ushr(56-i*8)).toInt())}};else->throw ValidationException("Unsupported OpenPrintTag CBOR value")}}
    private fun head(out:ByteArrayOutputStream,major:Int,n:Long){when{n<24->out.write((major shl 5)or n.toInt());n<=255->{out.write((major shl 5)or 24);out.write(n.toInt())};n<=65535->{out.write((major shl 5)or 25);out.write((n ushr 8).toInt());out.write(n.toInt())};n<=0xffffffffL->{out.write((major shl 5)or 26);repeat(4){i->out.write((n ushr(24-i*8)).toInt())}};else->{out.write((major shl 5)or 27);repeat(8){i->out.write((n ushr(56-i*8)).toInt())}}}}
}

private const val RAW_MIME="application/vnd.filamajig.raw"
private val OPT_MATERIAL_TYPES=mapOf("PLA" to 0L,"PETG" to 1L,"TPU" to 2L,"ABS" to 3L,"ASA" to 4L,"PC" to 5L,"PVA" to 20L)
private val ANYCUBIC_SKUS=mapOf("PLA" to "AHPLBK-101","PLA+" to "AHPLPBK-102","PLA HIGH SPEED" to "AHHSBK-102","PLA MATTE" to "HYGBK-101","PLA SILK" to "HSCWH-101","PETG" to "HPEBK-103","ASA" to "HASBK-101","ABS" to "HABBK-102","TPU" to "HTPBK-101","PLA LUMINOUS" to "HFGBL-101")
private val TIGER_MATERIALS=mapOf("PLA" to 38219,"PETG" to 38256,"ABS" to 20562,"TPU" to 43518,"PVA" to 9483,"ASA" to 12844,"PC" to 30458)
private val TIGER_BRANDS=mapOf("Generic" to 65535,"Anycubic" to 15962,"Creality" to 26956,"Prusament" to 46392,"ELEGOO" to 57632)
private val QIDI_MATERIALS=mapOf(1 to "PLA",2 to "PLA Matte",3 to "PLA Metal",4 to "PLA Silk",5 to "PLA-CF",6 to "PLA-Wood",7 to "PLA Basic",10 to "Support PLA",11 to "ABS",12 to "ABS-GF",18 to "ASA",20 to "ASA-CF",23 to "PC",25 to "PA-CF",27 to "PA12-CF",30 to "PAHT-CF",31 to "PAHT-GF",39 to "PETG Basic",40 to "PETG Tough",41 to "PETG Rapid",44 to "PETG-CF",45 to "PETG Translucent",47 to "PVA",50 to "TPU 95A-HF")
private val QIDI_COLORS=mapOf(1 to 0xFAFAFA,2 to 0x060606,3 to 0xD9E3ED,4 to 0x5CF30F,5 to 0x63E492,6 to 0x2850FF,7 to 0xFE98FE,8 to 0xDFD628,9 to 0x228332,10 to 0x99DEFF,11 to 0x1714B0,12 to 0xCEC0FE,13 to 0xCADE4B,14 to 0x1353AB,15 to 0x5EA9FD,16 to 0xA878FF,17 to 0xFE717A,18 to 0xFF362D,19 to 0xE2DFCD,20 to 0x898F9B,21 to 0x6E3812,22 to 0xCAC59F,23 to 0xF28636,24 to 0xB87F2B)
private val CFS_MATERIAL_NAMES=mapOf("00001" to "PLA","00002" to "PLA Silk","00003" to "PETG","00004" to "ABS","00005" to "TPU","00006" to "PLA-CF","00007" to "ASA","00008" to "PA","00009" to "PA-CF","00010" to "BVOH","00011" to "PVA","00012" to "HIPS","00013" to "PET-CF","00014" to "PETG-CF","00015" to "PA6-CF","00016" to "PAHT-CF","00017" to "PPS","00018" to "PPS-CF","00019" to "PP","00020" to "PET","00021" to "PC","01001" to "Hyper PLA","02001" to "Hyper PLA-CF","03001" to "Hyper ABS","04001" to "CR-PLA","05001" to "CR-Silk","06001" to "CR-PETG","06002" to "Hyper PETG","07001" to "CR-ABS","08001" to "Ender-PLA","09001" to "EN-PLA+","10001" to "HP-TPU","11001" to "CR-Nylon","13001" to "CR-PLA Carbon","14001" to "CR-PLA Matte","15001" to "CR-PLA Fluo","16001" to "CR-TPU","17001" to "CR-Wood","18001" to "HP Ultra PLA","19001" to "HP-ASA")

private fun materialFamily(value:String)=Regex("(?:^|[^A-Z0-9])(PETG|PLA|ABS|TPU|PVA|ASA|PC)(?:$|[^A-Z0-9])").find(value.uppercase())?.groupValues?.get(1)?:throw ValidationException("Cannot map material '$value' to this tag format")
private fun anycubicType(record:FilamentRecord):String {val text="${record.material.value} ${record.product.value}".uppercase();return when{ "HIGH SPEED" in text||"RAPID" in text->"PLA HIGH SPEED";"MATTE" in text->"PLA MATTE";"SILK" in text->"PLA SILK";"LUMINOUS" in text||"GLOW" in text->"PLA LUMINOUS";"PLA+" in text->"PLA+";else->materialFamily(text)}.also{if(it !in ANYCUBIC_SKUS)throw ValidationException("Anycubic ACE cannot map material '$text'")}}
private fun openTagModifier(material:String,product:String,family:String)=listOf(material,product).joinToString(" ").replace(family,"",ignoreCase=true).trim().take(5)
private fun tigerAspect(product:String)=when{product.contains("silk",true)->92;product.contains("matte",true)->146;product.contains("wood",true)->123;product.contains("clear",true)||product.contains("transparent",true)->21;else->104}
private fun tigerBrand(brand:String)=TIGER_BRANDS.entries.firstOrNull{it.key.equals(brand,true)}?.value?:65535
private fun qidiMaterial(record:FilamentRecord):Int {val text="${record.material.value} ${record.product.value}".uppercase();return QIDI_MATERIALS.entries.sortedByDescending{it.value.length}.firstOrNull{(_,v)->text.contains(v.uppercase())}?.key?:QIDI_MATERIALS.entries.firstOrNull{(_,v)->text.contains(v.substringBefore(' ').uppercase())}?.key?:throw ValidationException("QIDI Box cannot map material '$text'")}
private fun crealityMaterialId(record:FilamentRecord):String {
    val text="${record.material.value} ${record.product.value}".uppercase().replace('_',' ').replace('-',' ')
    if(record.brand.value.equals("Creality",true)) {
        val productMatches=listOf("HYPER PLA CF" to "02001","HYPER PETG" to "06002","HYPER ABS" to "03001","HYPER PLA" to "01001","CR PLA MATTE" to "14001","CR PLA CARBON" to "13001","CR PLA FLUO" to "15001","CR PLA" to "04001","CR SILK" to "05001","CR PETG" to "06001","CR ABS" to "07001","ENDER PLA" to "08001","EN PLA+" to "09001","HP TPU" to "10001","CR NYLON" to "11001","CR TPU" to "16001","CR WOOD" to "17001","HP ULTRA PLA" to "18001","HP ASA" to "19001")
        productMatches.firstOrNull{(name,_)->text.contains(name)}?.let{return it.second}
    }
    val generic=listOf("PAHT CF" to "00016","PETG CF" to "00014","PLA CF" to "00006","PA6 CF" to "00015","PA CF" to "00009","PET CF" to "00013","PPS CF" to "00018","PLA SILK" to "00002","BVOH" to "00010","PETG" to "00003","PLA" to "00001","ABS" to "00004","TPU" to "00005","ASA" to "00007","NYLON" to "00008","PA" to "00008","PVA" to "00011","HIPS" to "00012","PPS" to "00017","PP" to "00019","PET" to "00020","PC" to "00021")
    return generic.firstOrNull{(name,_)->Regex("(?:^| )${Regex.escape(name)}(?: |$)").containsMatchIn(text)}?.second?:throw ValidationException("Creality CFS cannot map material '${record.material.value}'")
}
private fun qidiNearestColor(hex:String):Int {val rgb=hex.toInt(16);return QIDI_COLORS.entries.firstOrNull{it.value==rgb}?.key?:throw ValidationException("QIDI Box supports only its registered 24-color palette; choose an exact QIDI palette color instead of silently approximating #$hex")}
internal fun supportedRecord(raw:ByteArray,prefix:String,source:String,brand:String,material:String,product:String,color:String,diameter:String,mass:Int,nozzleMin:Int?,nozzleMax:Int?,bedMin:Int?,bedMax:Int?,warnings:List<String> = emptyList(),brandSource:String = source):DecodeResult.Supported {val record=FilamentRecord("$prefix:"+raw.contentHashCode(),null,null,FieldValue(brand,brandSource),FieldValue(material,source),FieldValue(product,source),FieldValue("",source),FieldValue(color,source),FieldValue(diameter,source),FieldValue(mass,source),nozzleMin?.let{FieldValue(it,source)},nozzleMax?.let{FieldValue(it,source)},bedMin?.let{FieldValue(it,source)},bedMax?.let{FieldValue(it,source)},sourceRevision=source,provenance=Provenance.CUSTOM);return DecodeResult.Supported(record.toOpenSpoolValues(),raw.copyOf(),source,record,warnings)}
private fun rgba(hex:String)=byteArrayOf(*normalizeHex(hex).chunked(2).map{it.toInt(16).toByte()}.toByteArray(),0xFF.toByte())
private fun putRgba(out:ByteArray,offset:Int,hex:String){rgba(hex).copyInto(out,offset)}
private fun putRgb(out:ByteArray,offset:Int,hex:String){rgba(hex).copyOf(3).copyInto(out,offset)}
private fun putText(out:ByteArray,offset:Int,length:Int,value:String){val bytes=value.encodeToByteArray();if(bytes.size>length)throw ValidationException("'$value' exceeds a $length-byte tag field");bytes.copyInto(out,offset)}
private fun putUInt(out:ByteArray,offset:Int,length:Int,value:Long){if(value<0||length<8&&value>=(1L shl(length*8)))throw ValidationException("Tag integer does not fit $length bytes");repeat(length){i->out[offset+length-1-i]=(value ushr(i*8)).toByte()}}
private fun putU16Le(out:ByteArray,offset:Int,value:Int){if(value !in 0..65535)throw ValidationException("Tag value does not fit 16 bits");out[offset]=value.toByte();out[offset+1]=(value ushr 8).toByte()}
private fun ascii(data:ByteArray,offset:Int,length:Int)=data.copyOfRange(offset,offset+length).takeWhile{it!=0.toByte()}.toByteArray().toString(Charsets.US_ASCII)
private fun u16le(data:ByteArray,offset:Int)=(data[offset].toInt()and 255)or((data[offset+1].toInt()and 255)shl 8)
private fun u16be(data:ByteArray,offset:Int)=((data[offset].toInt()and 255)shl 8)or(data[offset+1].toInt()and 255)
private fun u24be(data:ByteArray,offset:Int)=((data[offset].toInt()and 255)shl 16)or((data[offset+1].toInt()and 255)shl 8)or(data[offset+2].toInt()and 255)
private fun u32(data:ByteArray,offset:Int)=(0..3).fold(0L){a,i->(a shl 8)or(data[offset+i].toLong()and 255)}
