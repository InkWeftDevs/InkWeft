// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Only fixed event codes and aggregate UI counts cross this boundary. No note objects. */
class AppDiagnostics(private val context: Context) {
    private val log = DiagnosticLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val fileMutex = Mutex()
    private val file = AtomicFile(File(context.noBackupFilesDir, "diagnostics-v1.log"))
    @Volatile private var historyStatus = "LOADING"
    @Volatile private var journalStatus = "NOT_WRITTEN"
    private var notebookState: List<Long> = emptyList()
    private var inkState: List<Long> = emptyList()
    private var notebookResult = DiagnosticResult.NOT_AVAILABLE
    private var inkResult = DiagnosticResult.NOT_AVAILABLE
    private var notebookObservedAt = 0L
    private var inkObservedAt = 0L
    private var axes: Pair<Boolean, Boolean>? = null
    private val initialized = scope.async {
        fileMutex.withLock {
            try {
                val bytes = file.openRead().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) { val size = input.read(chunk); if (size < 0) break; require(output.size() + size <= DiagnosticLog.MAX_JOURNAL_BYTES); output.write(chunk, 0, size) }
                    output.toByteArray()
                }
                log.prependHistory(DiagnosticLog.decode(bytes)); historyStatus = "LOADED"
            } catch (_: FileNotFoundException) { historyStatus = "NO_PRIOR_LOG"
            } catch (_: IOException) { historyStatus = "UNAVAILABLE_IO"
            } catch (_: IllegalArgumentException) { historyStatus = "INVALID_DISCARDED"
            } catch (_: SecurityException) { historyStatus = "UNAVAILABLE_PERMISSION" }
        }
    }
    init {
        event(DiagnosticCode.APP_START)
        scope.launch {
            initialized.await()
            for (signal in writes) { delay(750); fileMutex.withLock { persist() } }
        }
    }
    fun event(code: DiagnosticCode, result: DiagnosticResult = DiagnosticResult.OBSERVED, count: Long = -1, auxiliary: Long = -1) {
        log.add(code, result, System.currentTimeMillis(), SystemClock.elapsedRealtime(), count, auxiliary)
        writes.trySend(Unit)
    }
    @Synchronized fun notebook(loading: Boolean, failed: Boolean, notes: Int, drafts: Int, dirty: Int, result: DiagnosticResult) {
        val values = listOf(if (loading) 1L else 0L, if (failed) 1L else 0L, notes.toLong(), drafts.toLong(), dirty.toLong())
        if (values != notebookState || result != notebookResult) {
            notebookState = values; notebookResult = result; notebookObservedAt = System.currentTimeMillis()
            event(DiagnosticCode.NOTEBOOK_UI, result, notes.toLong(), dirty.toLong())
        }
    }
    @Synchronized fun ink(loading: Boolean, failed: Boolean, visible: Int, queued: Int, revision: Long, gesture: Boolean, result: DiagnosticResult) {
        val values = listOf(if (loading) 1L else 0L, if (failed) 1L else 0L, visible.toLong(), queued.toLong(), revision, if (gesture) 1L else 0L)
        if (values != inkState || result != inkResult) {
            inkState = values; inkResult = result; inkObservedAt = System.currentTimeMillis()
            event(DiagnosticCode.INK_UI, result, visible.toLong(), queued.toLong())
        }
    }
    @Synchronized fun inputAxes(pressure: Boolean, tilt: Boolean) {
        val value = pressure to tilt
        if (value != axes) { axes = value; event(DiagnosticCode.INPUT_AXES, count = if (pressure) 1 else 0, auxiliary = if (tilt) 1 else 0) }
    }
    private fun persist() {
        var stream: java.io.FileOutputStream? = null
        try {
            val bytes = DiagnosticLog.encode(log.snapshot())
            stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); journalStatus = "WRITTEN"
        } catch (_: Exception) {
            runCatching { file.failWrite(stream) }; journalStatus = "MEMORY_ONLY_WRITE_FAILED"
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        initialized.await(); fileMutex.withLock { log.clear(); persist() }
    }
    @Synchronized private fun observations() = JSONObject().apply {
        put("scope", "LATEST_UI_OBSERVATIONS_NOT_TRANSACTION_AUDIT")
        put("notebook_status", notebookResult.name); put("notebook_utc_ms", notebookObservedAt)
        put("notebook_counts_fields", JSONArray(listOf("loading", "read_failed", "visible_notes", "drafts", "dirty_drafts")))
        put("notebook_counts", JSONArray(notebookState))
        put("most_recent_ink_status", inkResult.name); put("most_recent_ink_utc_ms", inkObservedAt)
        put("ink_counts_fields", JSONArray(listOf("loading", "read_failed", "visible_strokes", "queued_operations", "page_revision", "gesture_active")))
        put("most_recent_ink_counts", JSONArray(inkState))
        put("input_pressure_observed", axes?.first ?: JSONObject.NULL)
        put("input_tilt_observed", axes?.second ?: JSONObject.NULL)
        put("input_scope", "MOST_RECENT_REPORTED_AXES_NOT_PENCIL_CERTIFICATION")
    }
    private fun publicText(s: String) = s.filter { !it.isISOControl() }.take(100)
    private fun optional(block: () -> Any?): Any = try { block() ?: JSONObject.NULL } catch (_: Exception) { JSONObject.NULL }
    private fun report(eventCount: Int): String {
        val dm = context.resources.displayMetrics
        val am = context.getSystemService(ActivityManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val result = JSONObject().apply {
            put("format", "InkWeft.Diagnostics/1")
            put("captured_utc_ms", System.currentTimeMillis()); put("elapsed_ms", SystemClock.elapsedRealtime())
            put("app", JSONObject().apply {
                put("application_id", context.packageName); put("version_name", BuildConfig.VERSION_NAME); put("version_code", BuildConfig.VERSION_CODE)
                put("build_commit", BuildConfig.BUILD_COMMIT); put("source_commit", BuildConfig.SOURCE_COMMIT); put("debug", BuildConfig.DEBUG)
                put("signer_sha256", optional {
                    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    JSONArray(info.signingInfo?.apkContentsSigners?.map { DiagnosticLog.hash(it.toByteArray()) } ?: emptyList<String>())
                })
            })
            put("device", JSONObject().apply {
                put("manufacturer", publicText(Build.MANUFACTURER)); put("model", publicText(Build.MODEL)); put("brand", publicText(Build.BRAND))
                put("sdk", Build.VERSION.SDK_INT); put("android_release", publicText(Build.VERSION.RELEASE)); put("security_patch", publicText(Build.VERSION.SECURITY_PATCH))
                put("abis", JSONArray(Build.SUPPORTED_ABIS.take(8).map(::publicText)))
                put("window_width_px", dm.widthPixels); put("window_height_px", dm.heightPixels); put("density_dpi", dm.densityDpi)
                put("font_scale", context.resources.configuration.fontScale.toDouble()); put("orientation", context.resources.configuration.orientation)
            })
            put("resources_snapshot", JSONObject().apply {
                put("scope", "POINT_IN_TIME_NOT_THERMAL_OR_POWER_BENCHMARK")
                put("pss_kib", optional { Debug.getPss() }); put("native_heap_allocated_bytes", optional { Debug.getNativeHeapAllocatedSize() })
                put("java_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                put("app_volume_available_bytes", optional { context.filesDir.usableSpace })
                put("thermal_status", optional { pm?.currentThermalStatus }); put("power_save", optional { pm?.isPowerSaveMode })
                put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: JSONObject.NULL)
                put("battery_scale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: JSONObject.NULL)
                put("battery_temperature_tenths_celsius", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: JSONObject.NULL)
                put("battery_status", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: JSONObject.NULL)
            })
            put("observations", observations())
            put("recent_process_exits", optional {
                JSONArray(am?.getHistoricalProcessExitReasons(context.packageName, 0, 3)?.map { e -> JSONObject().apply {
                    put("reason_code", e.reason); put("exit_status", e.status); put("utc_ms", e.timestamp); put("pss_kib", e.pss); put("rss_kib", e.rss)
                } } ?: emptyList<JSONObject>())
            })
            put("journal", JSONObject().apply { put("history_status", historyStatus); put("persistence", journalStatus); put("count", eventCount); put("max_events", DiagnosticLog.MAX_EVENTS); put("tail_may_be_lost", true) })
            put("privacy", "ALLOWLIST_ONLY_NO_NOTE_CONTENT_NO_IDS_NO_LOGCAT_NO_EXCEPTION_TEXT_NO_URI_NO_AUTO_UPLOAD")
            put("tests", JSONObject().apply { put("gradle", "NOT_RUN_BY_APP"); put("room_instrumentation", "NOT_RUN_BY_APP"); put("pencil3", "NOT_CERTIFIED"); put("optical_latency", "NOT_MEASURED"); put("backup", "THIS_IS_NOT_A_BACKUP") })
        }
        return result.toString(2) + "\n"
    }
    suspend fun bundle(): ByteArray = withContext(Dispatchers.IO) {
        initialized.await()
        fileMutex.withLock {
            persist()
            val snapshot = log.snapshot()
            DiagnosticArchive.build(report(snapshot.size), snapshot)
        }
    }
}
