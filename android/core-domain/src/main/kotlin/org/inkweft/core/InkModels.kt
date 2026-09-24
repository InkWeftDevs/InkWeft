// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.math.hypot

/** Native-independent author coordinates; rotations only change the viewport. */
object InkLimits {
    const val WIDTH = 1000f
    const val HEIGHT = 1414f
    const val MAX_POINTS = 8192
    const val MAX_STROKES = 1000
    const val MAX_PAGE_POINTS = 100_000
    const val MAX_STROKE_BYTES = 300_000
}

data class InkSample(val x: Float, val y: Float, val elapsedMs: Long,
                     val pressure: Float = -1f, val tilt: Float = -1f, val orientation: Float = -1f) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..InkLimits.WIDTH && y in 0f..InkLimits.HEIGHT)
        require(elapsedMs in 0..3_600_000)
        require(pressure == -1f || (pressure.isFinite() && pressure in 0f..1f))
        require(tilt == -1f || (tilt.isFinite() && tilt in 0f..(Math.PI.toFloat() / 2)))
        require(orientation == -1f || (orientation.isFinite() && orientation in 0f..(2 * Math.PI.toFloat())))
    }
}

enum class InkPen { PEN, HIGHLIGHTER }
enum class InkTool { TOUCH, STYLUS, MOUSE }

/** Copies collections at the boundary. No renderer, Context or mutable mesh in author data. */
class InkStroke(val id: String, val pen: InkPen, val color: Int, val width: Float,
                val tool: InkTool, samples: List<InkSample>) {
    val samples: List<InkSample> = Collections.unmodifiableList(ArrayList(samples))
    init {
        UUID.fromString(id)
        require(width.isFinite() && width in .5f..48f)
        require((color ushr 24) in 1..255)
        require(samples.size in 1..InkLimits.MAX_POINTS)
        val first = samples.first()
        samples.forEachIndexed { i, point ->
            require((point.pressure >= 0) == (first.pressure >= 0))
            require((point.tilt >= 0) == (first.tilt >= 0))
            require((point.orientation >= 0) == (first.orientation >= 0))
            if (i > 0) {
                require(point.elapsedMs >= samples[i - 1].elapsedMs)
                require(point != samples[i - 1])
            }
        }
    }
}

/** Fixed version, bounded decode. Brush mesh is derived by pinned AndroidX Ink, not stored. */
object InkStrokeCodec {
    private const val MAGIC = 0x49575331 // IWS1
    fun encode(stroke: InkStroke): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeUTF(stroke.id)
            out.writeByte(stroke.pen.ordinal); out.writeInt(stroke.color); out.writeFloat(stroke.width)
            out.writeByte(stroke.tool.ordinal); out.writeInt(stroke.samples.size)
            stroke.samples.forEach { p ->
                out.writeFloat(p.x); out.writeFloat(p.y); out.writeLong(p.elapsedMs)
                out.writeFloat(p.pressure); out.writeFloat(p.tilt); out.writeFloat(p.orientation)
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): InkStroke {
        require(bytes.size in 1..InkLimits.MAX_STROKE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Unsupported ink format; retain original bytes" }
            val id = input.readUTF()
            val pen = InkPen.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown pen")
            val color = input.readInt(); val width = input.readFloat()
            val tool = InkTool.entries.getOrNull(input.readUnsignedByte()) ?: error("Unknown input tool")
            val count = input.readInt()
            require(count in 1..InkLimits.MAX_POINTS && count * 28 == input.available())
            val points = List(count) {
                InkSample(input.readFloat(), input.readFloat(), input.readLong(),
                    input.readFloat(), input.readFloat(), input.readFloat())
            }
            InkStroke(id, pen, color, width, tool, points)
        }
    }
}

sealed interface InkMutation {
    class Add(val stroke: InkStroke) : InkMutation
    class Visibility(ids: List<String>, val visible: Boolean) : InkMutation {
        val ids: List<String> = Collections.unmodifiableList(ids.sorted())
        init {
            require(ids.size in 1..InkLimits.MAX_STROKES && ids.size == ids.toSet().size)
            ids.forEach { UUID.fromString(it) }
        }
    }
}

class CommitInk(val commandId: String, val noteId: String, val expectedRevision: Long,
                val mutation: InkMutation) {
    init { UUID.fromString(commandId); UUID.fromString(noteId); require(expectedRevision in 0 until Long.MAX_VALUE - 1) }
    val strokeIds: List<String> get() = when (mutation) {
        is InkMutation.Add -> listOf(mutation.stroke.id)
        is InkMutation.Visibility -> mutation.ids
    }
    fun digest(): String {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF("inkweft.ink-command.v1"); data.writeUTF(commandId)
            data.writeUTF(noteId); data.writeLong(expectedRevision)
            when (val change = mutation) {
                is InkMutation.Add -> { data.writeByte(1); val bytes = InkStrokeCodec.encode(change.stroke); data.writeInt(bytes.size); data.write(bytes) }
                is InkMutation.Visibility -> { data.writeByte(2); data.writeBoolean(change.visible); data.writeInt(change.ids.size); change.ids.forEach(data::writeUTF) }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

sealed interface InkCommitResult {
    data class Committed(val revision: Long) : InkCommitResult
    data object Conflict : InkCommitResult
    data object Rejected : InkCommitResult
    data object Unknown : InkCommitResult
}

data class StoredInk(val stroke: InkStroke, val visible: Boolean, val createdRevision: Long)
data class InkPage(val noteId: String, val revision: Long, val strokes: List<StoredInk>)

/** Whole-stroke eraser only. A swept segment, not just event endpoints. */
object InkHitTest {
    private fun distance(p: InkSample, a: InkSample, b: InkSample): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length).coerceIn(0f, 1f)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }
    private fun cross(a: InkSample, b: InkSample, c: InkSample): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun near(a: InkSample, b: InkSample, c: InkSample, d: InkSample, radius: Float): Boolean {
        val ab1 = cross(a,b,c); val ab2 = cross(a,b,d); val cd1 = cross(c,d,a); val cd2 = cross(c,d,b)
        if (ab1 * ab2 < 0f && cd1 * cd2 < 0f) return true
        return minOf(distance(a,c,d), distance(b,c,d), distance(c,a,b), distance(d,a,b)) <= radius
    }
    fun hits(stroke: InkStroke, eraser: List<InkSample>, radius: Float = 12f): Boolean {
        if (eraser.isEmpty()) return false
        val samples = stroke.samples; val r = radius + stroke.width / 2
        for (i in samples.indices) for (j in eraser.indices) {
            if (near(samples[i], samples[(i + 1).coerceAtMost(samples.lastIndex)],
                    eraser[j], eraser[(j + 1).coerceAtMost(eraser.lastIndex)], r)) return true
        }
        return false
    }
}
