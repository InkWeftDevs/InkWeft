// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Strict allow-list: never pass exception messages, note identifiers or text. */
enum class DiagnosticCode { APP_START, ACTIVITY_START, ACTIVITY_STOP, NOTEBOOK_UI, INK_UI, INPUT_AXES, USER_MARK, EXPORT, HISTORY, PAGE_OBJECT_UI }
enum class DiagnosticResult { OBSERVED, LOADING, EDITING, SAVING, SAVED, UNKNOWN, CONFLICT, REJECTED, READ_FAILED, CANCELLED, OK, IO_FAILED, INVALID_DATA, NOT_AVAILABLE }
data class DiagnosticEvent(val session: String, val wallMillis: Long, val elapsedMillis: Long,
    val code: DiagnosticCode, val result: DiagnosticResult, val count: Long = -1, val auxiliary: Long = -1) {
    init {
        require(session.matches(Regex("[0-9a-f]{12}")))
        require(wallMillis >= 0 && elapsedMillis >= 0)
        require(count in -1..1_000_000_000_000L && auxiliary in -1..1_000_000_000_000L)
    }
    fun line() = listOf(session, wallMillis, elapsedMillis, code.name, result.name, count, auxiliary).joinToString("\t")
    fun json() = "{\"session\":\"$session\",\"utc_ms\":$wallMillis,\"elapsed_ms\":$elapsedMillis,\"code\":\"${code.name}\",\"result\":\"${result.name}\",\"count\":$count,\"auxiliary\":$auxiliary}"
}

class DiagnosticLog(val session: String = UUID.randomUUID().toString().replace("-", "").take(12)) {
    private val events = ArrayDeque<DiagnosticEvent>()
    @Synchronized fun add(code: DiagnosticCode, result: DiagnosticResult, wall: Long, elapsed: Long, count: Long = -1, auxiliary: Long = -1) {
        events.addLast(DiagnosticEvent(session, wall.coerceAtLeast(0), elapsed.coerceAtLeast(0), code, result,
            count.coerceIn(-1, 1_000_000_000_000L), auxiliary.coerceIn(-1, 1_000_000_000_000L)))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }
    @Synchronized fun snapshot(): List<DiagnosticEvent> = events.toList()
    @Synchronized fun prependHistory(history: List<DiagnosticEvent>) {
        val merged = (history + events).takeLast(MAX_EVENTS)
        events.clear(); events.addAll(merged)
    }
    @Synchronized fun clear() { events.clear() }
    companion object {
        const val MAX_EVENTS = 200
        const val MAX_JOURNAL_BYTES = 64 * 1024
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun encode(events: List<DiagnosticEvent>): ByteArray {
            require(events.size <= MAX_EVENTS)
            val body = events.joinToString("\n") { it.line() }
            return ("IWDIAG1\n" + hash(body.toByteArray(Charsets.UTF_8)) + "\n" + body).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_JOURNAL_BYTES) }
        }
        fun decode(bytes: ByteArray): List<DiagnosticEvent> {
            require(bytes.size <= MAX_JOURNAL_BYTES)
            val chunks = bytes.toString(Charsets.UTF_8).split('\n', limit = 3)
            require(chunks.size == 3 && chunks[0] == "IWDIAG1")
            require(chunks[1] == hash(chunks[2].toByteArray(Charsets.UTF_8)))
            if (chunks[2].isEmpty()) return emptyList()
            val lines = chunks[2].split('\n'); require(lines.size <= MAX_EVENTS)
            return lines.map { row ->
                val f = row.split('\t'); require(f.size == 7)
                DiagnosticEvent(f[0], f[1].toLong(), f[2].toLong(), DiagnosticCode.valueOf(f[3]),
                    DiagnosticResult.valueOf(f[4]), f[5].toLong(), f[6].toLong())
            }
        }
    }
}

object DiagnosticArchive {
    const val MAX_REPORT_BYTES = 96 * 1024
    const val MAX_ZIP_BYTES = 192 * 1024
    private val readme = """
墨织诊断包 / InkWeft.Diagnostics/1
这是运行环境和受限事件记录，不是笔记备份，也不是CI或设备验收报告。
不包含笔记标题、正文、笔迹坐标、图片、PDF、数据库、系统logcat、异常消息或堆栈、密码、令牌、设备序列号、账号或文件URI。
包括设备型号/系统、应用版本/构建、内存/电量/热状态快照、最近200条固定类型事件及部分已观察状态的数量。
事件UTC可能受系统时钟影响；elapsed_ms只在本次系统启动中有意义，session是每次应用进程随机生成的短标识，不是设备标识。
计数来自界面观察，不是数据库完整性/持久性证明。旧进程最后约1秒的诊断记录可能未落盘。日志不可用不等于笔记损坏。
系统退出原因仅附数值信息（可用时）；不读退出描述或trace。没有捕获旧版本的日志，也没有执行Gradle/Room/Compose/Pencil3测试。
诊断包是明文。仅由用户导出/分享，不自动上传；选择云盘或分享目标后由该提供方处理。
report.json: 环境与采样状态。events.jsonl: 事件。manifest.json: 前三份文件的SHA-256完整性摘要，不是签名。
""".trimIndent() + "\n"
    fun build(reportJson: String, events: List<DiagnosticEvent>): ByteArray {
        val report = reportJson.toByteArray(Charsets.UTF_8)
        require(report.size <= MAX_REPORT_BYTES && events.size <= DiagnosticLog.MAX_EVENTS)
        val files = linkedMapOf("README.txt" to readme.toByteArray(Charsets.UTF_8), "report.json" to report,
            "events.jsonl" to (events.joinToString("\n") { it.json() } + "\n").toByteArray(Charsets.UTF_8))
        val manifest = files.entries.joinToString(",", "{\"format\":\"InkWeft.Diagnostics/1\",\"files\":[", "]}") { (name, data) ->
            "{\"name\":\"$name\",\"bytes\":${data.size},\"sha256\":\"${DiagnosticLog.hash(data)}\"}"
        }
        files["manifest.json"] = manifest.toByteArray(Charsets.UTF_8)
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for ((name, data) in files) { zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(data); zip.closeEntry() }
        } }.toByteArray().also { require(it.size <= MAX_ZIP_BYTES) }
    }
}
