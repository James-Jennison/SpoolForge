package net.jamesjennison.filamajignfc

import net.jamesjennison.filamajignfc.core.*
import net.jamesjennison.filamajignfc.data.FullSpectrumProfileData

data class FullSpectrumUiSummary(val optical:String,val roles:String,val completeness:String,val recommended:String,val observed:String)

fun fullSpectrumUiSummary(data:FullSpectrumProfileData?):FullSpectrumUiSummary {
    val optical=data?.opticalCharacterizations.orEmpty().filter {
        it.propertyKey=="transmission_distance" && it.supersededByCharacterizationId==null && it.valueText!=null
    }
    val roles=data?.roles.orEmpty().filter { it.assertionState==AssertionState.ACTIVE.name }
    val completeness=SetCompletenessService.analyze(roles.map { it.roleKey })
    val recommended=data?.suitability.orEmpty().filter { it.assessmentKind==SuitabilityKind.RECOMMENDED.name }
    val observed=data?.suitability.orEmpty().filter { it.assessmentKind==SuitabilityKind.OBSERVED.name }
    return FullSpectrumUiSummary(
        when (optical.size) {
            0 -> "Unknown — a documented TD test would improve this profile"
            1 -> optical.single().let { "${it.valueText} ${it.unit} · ${it.origin} · 1 evidence record" }
            else -> "Conflicting evidence — " + optical.joinToString(" · ") { "${it.valueText} ${it.unit} (${it.origin})" }
        },
        roles.joinToString { "${it.roleKey} (${it.authority.lowercase().replace('_',' ')})" }.ifBlank { "None — appearance or HEX alone does not confirm a mixing role" },
        if(completeness.cmygComplete) "CMYG complete" else "Missing ${completeness.missingMixingRoles.joinToString()}; anchors: ${completeness.anchors.joinToString().ifBlank { "none" }}",
        recommended.lastOrNull()?.rating ?: "UNKNOWN — no reviewed policy result",
        observed.lastOrNull()?.rating ?: "UNTESTED",
    )
}
