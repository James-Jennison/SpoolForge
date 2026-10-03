package net.jamesjennison.filamajignfc

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceRotationAcceptanceTest {
    @Test fun mainActivityAdaptsOrientationWithoutRecreation() {
        assertEquals("motorola razr 2023", Build.MODEL)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainActivity
            scenario.onActivity { activity ->
                original = activity
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitForOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE, original)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitForOrientation(scenario, Configuration.ORIENTATION_PORTRAIT, original)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "device-rotation-m6-acceptance.json").writeText(
            JSONObject().put("model", Build.MODEL).put("app_version", BuildConfig.VERSION_NAME)
                .put("landscape_same_activity", true).put("portrait_same_activity", true)
                .put("configuration_recreation", false).toString(2)
        )
    }

    private fun waitForOrientation(scenario: ActivityScenario<MainActivity>, expected: Int, original: MainActivity) {
        repeat(30) {
            var matched = false
            scenario.onActivity { activity ->
                assertSame("MainActivity was recreated during rotation", original, activity)
                matched = activity.resources.configuration.orientation == expected
            }
            if (matched) return
            Thread.sleep(100)
        }
        scenario.onActivity { assertEquals(expected, it.resources.configuration.orientation) }
    }
}
