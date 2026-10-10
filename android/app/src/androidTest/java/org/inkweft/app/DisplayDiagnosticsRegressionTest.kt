// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DisplayDiagnosticsRegressionTest {
    @Test fun displaySnapshotIsBoundedAndNeverClaimsMeasuredFpsOrOpticalLatency()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as InkWeftApplication
        val bytes=app.diagnostics.bundle()
        val report=ZipInputStream(ByteArrayInputStream(bytes)).use{zip->
            var found:JSONObject?=null
            while(true){val entry=zip.nextEntry?:break;if(entry.name=="report.json")found=JSONObject(zip.readBytes().toString(Charsets.UTF_8))}
            checkNotNull(found)
        }
        assertEquals("NOT_MEASURED",report.getJSONObject("tests").getString("optical_latency"))
        val device=report.getJSONObject("device");assertTrue(device.has("display_snapshot"))
        if(!device.isNull("display_snapshot")){
            val display=device.getJSONObject("display_snapshot")
            assertEquals("DEFAULT_DISPLAY_POINT_IN_TIME_NOT_MEASURED_FPS_OR_RECORDING_HISTORY",display.getString("scope"))
            assertTrue(display.getInt("mode_width_px")>0);assertTrue(display.getInt("mode_height_px")>0)
            for(key in listOf("mode_refresh_hz","reported_refresh_hz"))if(!display.isNull(key)){val hz=display.getDouble(key);assertTrue(hz.isFinite()&&hz>0)}
            assertTrue(display.getJSONArray("supported_mode_refresh_hz").length()<=32)
        }
    }
}
