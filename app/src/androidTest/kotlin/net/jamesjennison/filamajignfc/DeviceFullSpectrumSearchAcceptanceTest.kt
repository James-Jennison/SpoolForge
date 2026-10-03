package net.jamesjennison.filamajignfc

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.jamesjennison.filamajignfc.core.*
import net.jamesjennison.filamajignfc.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFullSpectrumSearchAcceptanceTest {
    @Test fun indexedFiltersAndCompletenessRunOnExactTarget()=runBlocking {
        assertEquals("motorola razr 2023",Build.MODEL)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,UserDatabase::class.java).build()
        try {
            val now=System.currentTimeMillis()
            val record=CustomRecord("m8-device",null,"","","Device QA","PLA","Basic","Cyan","00ADFF","1.75",1000,190,230,35,65,null,null,"","device","CUSTOM","Device","Device","Device","Device","Device","Device","Device","Device","Device","Device","Device",now,"","2.7","Measured")
            val repository=LocalFilamentRepository(db)
            repository.save(record)
            repository.assertUserRole(record.id,"C",now+1)
            repository.recordObservedSuitability(record.id,SuitabilityRating.GOOD,"Device observation",now+2)
            assertEquals(listOf(record.id),repository.searchFullSpectrum(FullSpectrumSearchFilter(roleKey="C",tdMinimumMm="2",tdMaximumMm="3",suitability=SuitabilityRating.GOOD,testState=FullSpectrumTestState.TESTED,ownership=InventoryOwnership.OWNED)))
            val completeness=SetCompletenessService.analyze(repository.fullSpectrum(record.id)!!.roles.map { it.roleKey })
            assertEquals(setOf("M","Y","G"),completeness.missingMixingRoles)
            assertTrue(repository.alternateCandidates("C").single().evidenceId?.isNotBlank()==true)
            assertTrue(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { !it.moveToFirst() })
        } finally { db.close() }
    }
}
