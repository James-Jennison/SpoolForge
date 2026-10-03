package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.data.*
import org.junit.Assert.*
import org.junit.Test

class FullSpectrumUiTest {
    @Test fun unknownProfileExplainsEvidenceAndDoesNotInventRecommendation() {
        val summary=fullSpectrumUiSummary(FullSpectrumProfileData(emptyList(),emptyList(),emptyList(),emptyList()))
        assertTrue(summary.optical.startsWith("Unknown"))
        assertTrue(summary.roles.contains("does not confirm"))
        assertTrue(summary.recommended.startsWith("UNKNOWN"))
        assertEquals("UNTESTED",summary.observed)
    }

    @Test fun recommendedAndObservedSuitabilityRemainVisiblySeparate() {
        fun assessment(id:String,kind:String,rating:String,at:Long)=FullSpectrumSuitabilityAssessmentEntity(id,"p",kind,rating,"PROFILE",null,null,null,"[]","rationale",null,null,"tester",at,null)
        val data=FullSpectrumProfileData(
            listOf(OpticalCharacterizationEntity(
                characterizationId="o", profileId="p", propertyKey="transmission_distance", valueText="2.7",
                unit="mm", methodKey="TD-1", methodVersion=null, origin="USER_MEASURED", geometry=null,
                wallThicknessMm=null, layerHeightMm=null, evidenceId=null, confidence="0.9", notes=null,
                environmentalNotes=null, measuredAt=1, instrument="TD-1", supersededByCharacterizationId=null, createdAt=1,
            )),
            emptyList(), listOf(FullSpectrumRoleAssertionEntity("r","p","C","MIXING_PRIMARY","USER_CONFIRMED","ACTIVE",null,null,null,null,1)),
            listOf(assessment("recommended","RECOMMENDED","UNKNOWN",1),assessment("observed","OBSERVED","GOOD",2)),
        )
        val summary=fullSpectrumUiSummary(data)
        assertTrue(summary.optical.contains("2.7 mm")); assertTrue(summary.roles.startsWith("C"))
        assertEquals("UNKNOWN",summary.recommended); assertEquals("GOOD",summary.observed)
    }

    @Test fun conflictingAndSupersededTdEvidenceIsExplainedWithoutSelectingAWinner() {
        fun optical(id:String,value:String,supersededBy:String?=null)=OpticalCharacterizationEntity(
            characterizationId=id, profileId="p", propertyKey="transmission_distance", valueText=value,
            unit="mm", methodKey="test", methodVersion=null, origin="USER_MEASURED", geometry=null,
            wallThicknessMm=null, layerHeightMm=null, evidenceId=null, confidence=null, notes=null,
            environmentalNotes=null, measuredAt=null, instrument=null,
            supersededByCharacterizationId=supersededBy, createdAt=1,
        )
        val summary=fullSpectrumUiSummary(FullSpectrumProfileData(
            listOf(optical("old","2.4","new"),optical("new","2.7"),optical("other","3.1")),
            emptyList(),emptyList(),emptyList(),
        ))
        assertTrue(summary.optical.startsWith("Conflicting evidence"))
        assertFalse(summary.optical.contains("2.4 mm"))
        assertTrue(summary.optical.contains("2.7 mm"))
        assertTrue(summary.optical.contains("3.1 mm"))
    }
}
