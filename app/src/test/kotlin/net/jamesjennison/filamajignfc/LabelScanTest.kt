package net.jamesjennison.filamajignfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelScanTest {
    @Test fun completeUpcSuppressesSpuriousEan8SubsetDecode() {
        val upc = classifyLabelCode("027680274484", "UPC_A").copy(photoRole = "profile_label")
        val fragment = classifyLabelCode("22117762", "EAN_8")
        val compressed = classifyLabelCode("04481867", "UPC_E")
        val qr = classifyLabelCode("{\"schema\":\"filament-rfid/1\"}", "QR_CODE")
        val result = reconcileLabelCodes(listOf(fragment, compressed, upc, qr))
        assertEquals(listOf(upc, qr), result)
    }

    @Test fun printedTransmissionDistanceIsRetainedWithAiProvenance() {
        val absent = """{"value":null,"confidence":1,"evidence":null,"basis":"absent"}"""
        val fields = listOf("manufacturer","diameter_mm","net_weight_g","gtin","sku","lot").joinToString(",") { "\"$it\":$absent" }
        val json = """{"provider":"openai","model":"gpt-5.6-terra","fields":{
          $fields,
          "label_brand":{"value":"MARSWORK","confidence":0.99,"evidence":"MARSWORK","basis":"printed"},
          "product":{"value":"PLA Basic","confidence":0.99,"evidence":"PLA Basic","basis":"printed"},
          "material":{"value":"PLA","confidence":0.99,"evidence":"PLA Basic","basis":"printed"},
          "color":{"value":"Cyan","confidence":0.99,"evidence":"Cyan","basis":"printed"},
          "color_hex":{"value":"00ADFF","confidence":0.99,"evidence":"#00ADFF","basis":"printed"},
          "nozzle_min_c":{"value":190,"confidence":0.99,"evidence":"190-230C","basis":"printed"},
          "nozzle_max_c":{"value":230,"confidence":0.99,"evidence":"190-230C","basis":"printed"},
          "bed_min_c":{"value":35,"confidence":0.99,"evidence":"35-65C","basis":"printed"},
          "bed_max_c":{"value":65,"confidence":0.99,"evidence":"35-65C","basis":"printed"},
          "transmission_distance":{"value":2.7,"confidence":0.99,"evidence":"TD: 2.7","basis":"printed"}
        },"other_codes":[],"needs_user_review":[]}""".trimIndent()
        val result = parseLabelScan(json.encodeToByteArray(), listOf(classifyLabelCode("https://spooldb.com/f/9403", "QR_CODE")))
        assertEquals("2.7", result.seed.transmissionDistance)
        assertTrue(result.seed.sources.getValue("transmissionDistance").contains("printed"))
    }

    @Test fun deterministicRetailCodeOverridesConflictingAiGtinAndKeepsProvenance() {
        val json = """{
          "provider":"openai","model":"gpt-5.6-terra","fields":{
            "label_brand":{"value":"Polymaker","confidence":0.97,"evidence":"POLYMAKER","basis":"printed"},
            "material":{"value":"PLA","confidence":0.99,"evidence":"PLA","basis":"printed"},
            "product":{"value":"Panchroma","confidence":0.9,"evidence":"Panchroma","basis":"printed"},
            "color":{"value":"Purple-Red","confidence":0.9,"evidence":"PURPLE-RED","basis":"printed"},
            "color_hex":{"value":"8A3048","confidence":0.7,"evidence":"swatch","basis":"visual_estimate"},
            "diameter_mm":{"value":1.75,"confidence":0.99,"evidence":"1.75mm","basis":"printed"},
            "net_weight_g":{"value":1000,"confidence":0.9,"evidence":"1kg","basis":"printed"},
            "nozzle_min_c":{"value":null,"confidence":1,"evidence":null,"basis":"absent"},
            "nozzle_max_c":{"value":null,"confidence":1,"evidence":null,"basis":"absent"},
            "bed_min_c":{"value":null,"confidence":1,"evidence":null,"basis":"absent"},
            "bed_max_c":{"value":null,"confidence":1,"evidence":null,"basis":"absent"},
            "gtin":{"value":"12345678901231","confidence":0.4,"evidence":"blurred","basis":"barcode"},
            "sku":{"value":"CA09019","confidence":0.99,"evidence":"SKU","basis":"printed"},
            "manufacturer":{"value":null,"confidence":1,"evidence":null,"basis":"absent"},
            "lot":{"value":null,"confidence":1,"evidence":null,"basis":"absent"}
          },"other_codes":[],"needs_user_review":[]
        }""".trimIndent()
        val local = listOf(classifyLabelCode("6938936717461", "EAN_13"))
        val result = parseLabelScan(json.encodeToByteArray(), local)
        assertEquals("06938936717461", result.seed.gtin)
        assertEquals("CA09019", result.seed.articleNumber)
        assertEquals("Polymaker", result.seed.brand)
        assertTrue(result.seed.sources.getValue("brand").contains("97%"))
        assertTrue(result.review.any { it.contains("different GTINs") })
    }

    @Test fun fnskuShapedCodeRemainsAnUnverifiedAmazonStyleCandidate() {
        val code = classifyLabelCode("X004QSH4ZH", "CODE_128")
        assertEquals(null, code.gtin)
        assertEquals("Amazon-style identifier candidate", code.kind)
        assertEquals("Amazon-style identifier candidate", classifyLabelCode("X004QSH4ZH", "QR_CODE").kind)
        assertEquals("Code 128 identifier", classifyLabelCode("X0123", "CODE_128").kind)
    }

    @Test fun invalidAiColorUsesReviewPlaceholderWithoutAiProvenance() {
        val claim = """{"value":null,"confidence":1,"evidence":null,"basis":"absent"}"""
        val fields = listOf("label_brand","manufacturer","product","material","color","diameter_mm","net_weight_g","nozzle_min_c","nozzle_max_c","bed_min_c","bed_max_c","gtin","sku","lot").joinToString(",") { "\"$it\":$claim" }
        val invalid = """{"value":"NOT_HEX","confidence":0.95,"evidence":"swatch","basis":"visual_estimate"}"""
        val json = """{"provider":"openai","model":"gpt-5.6-terra","fields":{$fields,"color_hex":$invalid},"other_codes":[],"needs_user_review":[]}"""
        val result = parseLabelScan(json.encodeToByteArray(), emptyList())
        assertEquals("808080", result.seed.hex)
        assertTrue(result.seed.sources.getValue("colorHex").contains("Placeholder color"))
        assertTrue(result.review.any { it.contains("Confirm the color swatch") })
        assertEquals("1.75", result.seed.diameter)
        assertEquals("1000", result.seed.mass)
        assertEquals(ASSUMED_DEFAULT_SOURCE, result.seed.sources.getValue("diameter"))
        assertEquals(ASSUMED_DEFAULT_SOURCE, result.seed.sources.getValue("mass"))
    }

    @Test fun conflictingGtinsAndMarketplaceCodeAreNeverSilentlySelectedAsSku() {
        val absent = """{"value":null,"confidence":1,"evidence":null,"basis":"absent"}"""
        val fields = listOf("label_brand","manufacturer","product","material","color","diameter_mm","net_weight_g","nozzle_min_c","nozzle_max_c","bed_min_c","bed_max_c","gtin","sku","lot","color_hex").joinToString(",") { "\"$it\":$absent" }
        val aiGtin = """{"value":"6938936717461","confidence":0.8,"evidence":"barcode","basis":"barcode"}"""
        val fieldsWithAiGtin = fields.replace("\"gtin\":$absent", "\"gtin\":$aiGtin")
        val json = """{"provider":"openai","model":"gpt-5.6-terra","fields":{$fieldsWithAiGtin},"other_codes":[],"needs_user_review":[]}"""
        val codes = listOf(classifyLabelCode("6938936717461", "EAN_13"), classifyLabelCode("012345678905", "UPC_A"), classifyLabelCode("X004QSH4ZH", "CODE_128"))
        val result = parseLabelScan(json.encodeToByteArray(), codes)
        assertEquals(null, result.seed.gtin)
        assertEquals(null, result.seed.articleNumber)
        assertTrue(result.review.any { it.contains("Multiple valid GTINs") })
        assertTrue(result.review.any { it.contains("not used as the manufacturer SKU") })
        assertEquals("06938936717461", parseLabelScan(json.encodeToByteArray(), emptyList()).seed.gtin)
    }

    @Test fun aiCannotPromoteAmazonStyleCandidateToSkuOrPackagingConditionToBrand() {
        val absent = """{"value":null,"confidence":1,"evidence":null,"basis":"absent"}"""
        val fields = listOf("manufacturer","product","material","color","diameter_mm","net_weight_g","nozzle_min_c","nozzle_max_c","bed_min_c","bed_max_c","gtin","lot").joinToString(",") { "\"$it\":$absent" }
        val json = """{"provider":"openai","model":"gpt-5.6-terra","fields":{
          $fields,
          "label_brand":{"value":"New","confidence":0.96,"evidence":"New","basis":"printed"},
          "sku":{"value":"X0037RJ695","confidence":0.96,"evidence":"barcode","basis":"printed"},
          "color_hex":{"value":"808080","confidence":0.7,"evidence":"box","basis":"visual_estimate"}
        },"other_codes":[],"needs_user_review":[]}""".trimIndent()
        val result = parseLabelScan(json.encodeToByteArray(), listOf(classifyLabelCode("X0037RJ695", "CODE_128")))
        assertEquals(null, result.seed.articleNumber)
        assertEquals("", result.seed.brand)
        assertTrue(result.seed.sources.getValue("brand").contains("Ignored packaging condition"))
        assertTrue(result.review.any { it.contains("Amazon-style identifier candidate") })
        assertTrue(result.review.any { it.contains("packaging condition") })
    }

    @Test fun nonMarketplaceX0ManufacturerSkuSurvivesMerge() {
        val absent = """{"value":null,"confidence":1,"evidence":null,"basis":"absent"}"""
        val fields = listOf("manufacturer","product","material","color","diameter_mm","net_weight_g","nozzle_min_c","nozzle_max_c","bed_min_c","bed_max_c","gtin","lot").joinToString(",") { "\"$it\":$absent" }
        val json = """{"provider":"openai","model":"gpt-5.6-terra","fields":{
          $fields,
          "label_brand":{"value":"Example","confidence":0.99,"evidence":"Example","basis":"printed"},
          "sku":{"value":"X0123","confidence":0.99,"evidence":"SKU X0123","basis":"printed"},
          "color_hex":{"value":"808080","confidence":0.7,"evidence":"box","basis":"visual_estimate"}
        },"other_codes":[],"needs_user_review":[]}""".trimIndent()
        val result = parseLabelScan(json.encodeToByteArray(), listOf(classifyLabelCode("X0123", "CODE_128")))
        assertEquals("X0123", result.seed.articleNumber)
        assertEquals("Example", result.seed.brand)
        assertTrue(result.review.none { it.contains("Amazon-style identifier candidate") })
    }

    @Test fun aiOnlyAmazonStyleIdentifierCannotSilentlyBecomeSku() {
        val absent = """{"value":null,"confidence":1,"evidence":null,"basis":"absent"}"""
        val fields = listOf("label_brand","manufacturer","product","material","color","diameter_mm","net_weight_g","nozzle_min_c","nozzle_max_c","bed_min_c","bed_max_c","gtin","lot").joinToString(",") { "\"$it\":$absent" }
        val json = """{"provider":"openai","model":"gpt-5.6-terra","fields":{
          $fields,
          "sku":{"value":"X004QSH4ZH","confidence":0.96,"evidence":"barcode","basis":"printed"},
          "color_hex":{"value":"808080","confidence":0.7,"evidence":"box","basis":"visual_estimate"}
        },"other_codes":[],"needs_user_review":[]}""".trimIndent()
        val result = parseLabelScan(json.encodeToByteArray(), emptyList())
        assertEquals(null, result.seed.articleNumber)
        assertTrue(result.review.any { it.contains("X004QSH4ZH") && it.contains("without confirmation") })
    }

    @Test fun providerErrorMessageIsExtractedWithoutReturningWholeResponse() {
        val body = """{"error":"model requires more system memory"}""".encodeToByteArray()
        assertEquals("model requires more system memory", ollamaErrorMessage(body))
        assertEquals(null, ollamaErrorMessage("not json".encodeToByteArray()))
    }
}
