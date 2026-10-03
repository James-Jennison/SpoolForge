package net.jamesjennison.filamajignfc

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Compares the models a signed-in ChatGPT plan offers on real label photos, through the same
 * request path the app uses. It runs only when a ChatGPT account is connected on the device and
 * label photos have been placed in the app's `files/label-benchmark` directory, so it is skipped
 * in ordinary test runs. Results hold extracted label fields and timings only; no credentials.
 *
 * Each request is charged to the connected plan. The run stops at the first usage-limit error.
 */
@RunWith(AndroidJUnit4::class)
class DeviceChatGptModelBenchmark {
    @Test fun compareModelsOnLabelFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "label-benchmark")
        val photos = directory.listFiles { file -> file.extension == "jpg" }.orEmpty().sortedBy(File::getName)
        assumeTrue("no label fixtures on device", photos.isNotEmpty())
        val session = ChatGptSession(KeystoreChatGptStore(context))
        assumeTrue("no ChatGPT account connected", session.isConnected)

        val models = session.listModels()
        val runs = JSONArray()
        var usageLimited = false
        for (photoFile in photos) {
            val jpeg = photoFile.readBytes()
            val codes = decodeLabelCodesFromImage(jpeg).map { it.copy(photoRole = LabelPhotoRole.PROFILE.wireName) }
            val photo = LabelPhoto(LabelPhotoRole.PROFILE, jpeg, codes)
            for (model in models) {
                if (usageLimited) break
                var requests = 0
                var structuredAccepted = false
                val analyzer = ChatGptLabelAnalyzer({ true }, model.slug) { body ->
                    requests += 1
                    session.respond(body).also { structuredAccepted = body.has("text") }
                }
                val run = JSONObject().put("case", photoFile.nameWithoutExtension).put("model", model.slug).put("display_name", model.displayName)
                val started = SystemClock.elapsedRealtime()
                try {
                    val wrapped = JSONObject(analyzer.analyze(listOf(photo), codes).decodeToString())
                    val extracted = wrapped.getJSONObject("fields").put("other_codes", wrapped.getJSONArray("other_codes"))
                    run.put("status", "completed").put("extracted", extracted)
                    // The parsed result must also survive the app's own reconciliation step.
                    parseLabelScan(wrapped.toString().toByteArray(), codes)
                } catch (failure: ChatGptException) {
                    run.put("status", "failed").put("error", failure.code)
                    usageLimited = failure.code == CHATGPT_USAGE_LIMIT_CODE
                } catch (failure: Exception) {
                    run.put("status", "failed").put("error", failure.javaClass.simpleName)
                }
                run.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("requests", requests).put("structured_output", structuredAccepted)
                    .put("local_codes", codes.size)
                runs.put(run)
                File(directory, "results.json").writeText(
                    JSONObject().put("models", JSONArray(models.map { JSONObject().put("slug", it.slug).put("display_name", it.displayName) }))
                        .put("usage_limited", usageLimited).put("runs", runs).toString(2),
                )
            }
        }
    }
}
