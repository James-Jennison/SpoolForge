package net.jamesjennison.filamajignfc

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.jamesjennison.filamajignfc.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceExternalAdaptersAcceptanceTest {
    @Test fun externalFormatsConvertOfflineOnRazr2023() {
        assertEquals("motorola razr 2023", Build.MODEL)
        val context = InstrumentationRegistry.getInstrumentation().context
        val optPayload = context.assets.open("openprinttag-01-payload.bin").use { it.readBytes() }
        val opt = OpenPrintTagAdapter.decode(optPayload) as DecodeResult.Supported
        assertEquals("Prusament", opt.convertedRecord?.brand?.value)
        assertEquals("1.75", opt.convertedRecord?.diameterMm?.value)

        val tag = ByteArray(224)
        putUInt(tag,0,2,2000); putText(tag,2,5,"PLA"); putText(tag,12,16,"Example")
        tag[0x3c]=1; tag[0x3d]=2; tag[0x3e]=3; tag[0x3f]=-1
        putUInt(tag,0x8c,2,1750); putUInt(tag,0x9e,2,1000)
        val ot3d = OpenTag3dV2Adapter.decode(tag) as DecodeResult.Supported
        assertEquals("Example", ot3d.convertedRecord?.brand?.value)

        val export = SpoolmanCodec.encodeExport(checkNotNull(opt.convertedRecord), 1012, 800)
        val roundTrip = SpoolmanCodec.decodeExport(export).single()
        assertEquals(800, roundTrip.remainingQuantityG)
        assertEquals("Prusament", roundTrip.filament.brand.value)
    }
    private fun putText(out:ByteArray,offset:Int,length:Int,value:String)=value.encodeToByteArray().take(length).forEachIndexed{i,b->out[offset+i]=b}
    private fun putUInt(out:ByteArray,offset:Int,length:Int,value:Long){repeat(length){i->out[offset+length-1-i]=(value ushr (i*8)).toByte()}}
}
