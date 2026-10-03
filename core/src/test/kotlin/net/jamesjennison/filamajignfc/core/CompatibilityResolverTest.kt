package net.jamesjennison.filamajignfc.core

import kotlin.test.*

class CompatibilityResolverTest {
    @Test fun `canvas plus u1 paxx resolves to one ntag215 canvas encoding`() {
        val result=assertIs<CompatibilityResult.Resolved>(CompatibilityResolver.resolve(setOf(PrinterTarget.ELEGOO_CANVAS,PrinterTarget.SNAPMAKER_U1_PAXX))).resolution
        assertEquals(setOf(PhysicalTagFamily.NTAG215),result.physicalTags)
        assertEquals(ElegooCanvasTagCodec.format.id,result.codecId)
        assertEquals("ELEGOO CANVAS",result.encodingName)
        assertEquals(CompatibilityPath.NATIVE,result.targetCompatibility.single{it.target==PrinterTarget.ELEGOO_CANVAS}.path)
        val paxx=result.targetCompatibility.single{it.target==PrinterTarget.SNAPMAKER_U1_PAXX}
        assertEquals(CompatibilityPath.ALTERNATE_FIRMWARE,paxx.path)
        assertEquals("OpenRFID",paxx.rfidSystemRequirement)
        assertEquals("Elegoo tag processor enabled",paxx.processorRequirement)
    }

    @Test fun `canvas alone keeps canvas encoding and ntag215`() {
        val result=assertIs<CompatibilityResult.Resolved>(CompatibilityResolver.resolve(setOf(PrinterTarget.ELEGOO_CANVAS))).resolution
        assertEquals(ElegooCanvasTagCodec.format.id,result.codecId)
        assertEquals(setOf(PhysicalTagFamily.NTAG215),result.physicalTags)
    }

    @Test fun `u1 paxx alone preserves existing openspool workflow`() {
        val result=assertIs<CompatibilityResult.Resolved>(CompatibilityResolver.resolve(setOf(PrinterTarget.SNAPMAKER_U1_PAXX))).resolution
        assertEquals(PaxxU1ExtendedTagCodec.format.id,result.codecId)
    }

    @Test fun `stock u1 is rejected for canvas compatibility`() {
        val result=assertIs<CompatibilityResult.Unsupported>(CompatibilityResolver.resolve(setOf(PrinterTarget.ELEGOO_CANVAS,PrinterTarget.SNAPMAKER_U1_STOCK)))
        assertContains(result.reason,"stock firmware")
    }
}

class Ntag215WriteValidatorTest {
    private fun version(storage:Int)=byteArrayOf(0x00,0x04,0x04,0x02,0x01,0x00,storage.toByte(),0x03)
    private fun config(auth0:Int=0xFF)=byteArrayOf(0,0,0,auth0.toByte())
    private fun locks(byte0:Int=0,byte1:Int=0,byte2:Int=0,byte3:Int=0xBD)=byteArrayOf(byte0.toByte(),byte1.toByte(),byte2.toByte(),byte3.toByte())

    @Test fun `recognizes ntag variants and accepts unlocked ntag215 capacity`() {
        assertEquals(Ntag21xProduct.NTAG213,Ntag215WriteValidator.identify(version(0x0F)))
        assertEquals(Ntag21xProduct.NTAG215,Ntag215WriteValidator.identify(version(0x11)))
        assertEquals(Ntag21xProduct.NTAG216,Ntag215WriteValidator.identify(version(0x13)))
        val result=Ntag215WriteValidator.inspect(version(0x11),byteArrayOf(0,0,0,0xBD.toByte()),config(),ElegooCanvasTagCodec.PAYLOAD_BYTES)
        assertEquals(504,result.writableBytes)
    }

    @Test fun `rejects unsupported capacity locked pages and password protected writes`() {
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x0F),locks(),config(),64)}
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(byte0=1),config(),64)}
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(byte2=1),config(),64)}
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(),config(16),64)}
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(),config(),505)}
    }

    @Test fun `lock bits for later page ranges do not falsely reject pages 16 through 31`() {
        (1..7).forEach{bit->Ntag215WriteValidator.inspect(version(0x11),locks(byte0=1 shl bit),config(),64)}
        (1..3).forEach{bit->Ntag215WriteValidator.inspect(version(0x11),locks(byte2=1 shl bit),config(),64)}
    }

    @Test fun `reserved lock control values fail closed`() {
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(byte1=1),config(),64)}
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(byte2=0x10),config(),64)}
        assertFailsWith<ValidationException>{Ntag215WriteValidator.inspect(version(0x11),locks(byte3=0),config(),64)}
    }
}
