// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import kotlin.math.hypot

object InkLimits {
    const val WIDTH=1000f
    const val HEIGHT=1414f
    const val MAX_POINTS=8192
    const val MAX_STROKES=1000
    const val MAX_PAGE_POINTS=100_000
    const val MAX_STROKE_BYTES=300_000
}

data class InkSample(val x: Float,val y: Float,val elapsedMs: Long,val pressure: Float=-1f,val tilt: Float=-1f,val orientation: Float=-1f,val world: Boolean=false) {
    init {
        require(x.isFinite() && y.isFinite() && x in -BoardLimits.WORLD..BoardLimits.WORLD && y in -BoardLimits.WORLD..BoardLimits.WORLD)
        if(!world)require(x in 0f..InkLimits.WIDTH && y in 0f..InkLimits.HEIGHT)
        require(elapsedMs in 0..3_600_000)
        require(pressure == -1f || (pressure.isFinite() && pressure in 0f..1f))
        require(tilt == -1f || (tilt.isFinite() && tilt in 0f..(Math.PI.toFloat()/2)))
        require(orientation == -1f || (orientation.isFinite() && orientation in 0f..(2*Math.PI.toFloat())))
    }
}
enum class InkPen { PEN,HIGHLIGHTER }
enum class InkTool { TOUCH,STYLUS,MOUSE }

/** world=false retains fixed-paper validation; board coordinates are explicitly versioned. */
class InkStroke(val id: String,val pen: InkPen,val color: Int,val width: Float,val tool: InkTool,
                samples: List<InkSample>,val world: Boolean=false) {
    val samples: List<InkSample> = Collections.unmodifiableList(ArrayList(samples))
    init {
        UUID.fromString(id);require(width.isFinite() && width in .5f..48f);require((color ushr 24) in 1..255)
        require(samples.size in 1..InkLimits.MAX_POINTS)
        val first=samples.first()
        samples.forEachIndexed { i,p ->
            require(p.world==world)
            if(!world)require(p.x in 0f..InkLimits.WIDTH && p.y in 0f..InkLimits.HEIGHT)
            require((p.pressure>=0)==(first.pressure>=0));require((p.tilt>=0)==(first.tilt>=0));require((p.orientation>=0)==(first.orientation>=0))
            if(i>0){require(p.elapsedMs>=samples[i-1].elapsedMs);require(p!=samples[i-1])}
        }
    }
}

object InkStrokeCodec {
    private const val PAGE=0x49575331
    private const val WORLD=0x49575332
    fun encode(stroke: InkStroke): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(if(stroke.world)WORLD else PAGE);out.writeUTF(stroke.id)
            out.writeByte(stroke.pen.ordinal);out.writeInt(stroke.color);out.writeFloat(stroke.width)
            out.writeByte(stroke.tool.ordinal);out.writeInt(stroke.samples.size)
            stroke.samples.forEach { p -> out.writeFloat(p.x);out.writeFloat(p.y);out.writeLong(p.elapsedMs);out.writeFloat(p.pressure);out.writeFloat(p.tilt);out.writeFloat(p.orientation) }
        }
    }.toByteArray()
    fun decode(bytes: ByteArray): InkStroke {
        require(bytes.size in 1..InkLimits.MAX_STROKE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic=input.readInt();require(magic==PAGE || magic==WORLD){"Unsupported ink format; retain original bytes"}
            val id=input.readUTF();val pen=InkPen.entries.getOrNull(input.readUnsignedByte())?:error("Unknown pen")
            val color=input.readInt();val width=input.readFloat();val tool=InkTool.entries.getOrNull(input.readUnsignedByte())?:error("Unknown tool")
            val count=input.readInt();require(count in 1..InkLimits.MAX_POINTS && count*28==input.available())
            val points=List(count){InkSample(input.readFloat(),input.readFloat(),input.readLong(),input.readFloat(),input.readFloat(),input.readFloat(),magic==WORLD)}
            InkStroke(id,pen,color,width,tool,points,magic==WORLD)
        }
    }
}
sealed interface InkMutation {
    class Add(val stroke: InkStroke):InkMutation
    class Visibility(ids: List<String>,val visible: Boolean):InkMutation {
        val ids: List<String> = Collections.unmodifiableList(ids.sorted())
        init { require(ids.size in 1..InkLimits.MAX_STROKES && ids.size==ids.toSet().size);ids.forEach { UUID.fromString(it) } }
    }
}
class CommitInk(val commandId: String,val noteId: String,val expectedRevision: Long,val mutation: InkMutation) {
    init { UUID.fromString(commandId);UUID.fromString(noteId);require(expectedRevision in 0 until Long.MAX_VALUE-1) }
    val strokeIds: List<String> get()=when(mutation){is InkMutation.Add->listOf(mutation.stroke.id);is InkMutation.Visibility->mutation.ids}
    fun digest(): String {
        val out=ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeUTF("inkweft.ink-command.v1");d.writeUTF(commandId);d.writeUTF(noteId);d.writeLong(expectedRevision)
            when(val m=mutation){
                is InkMutation.Add->{d.writeByte(1);val b=InkStrokeCodec.encode(m.stroke);d.writeInt(b.size);d.write(b)}
                is InkMutation.Visibility->{d.writeByte(2);d.writeBoolean(m.visible);d.writeInt(m.ids.size);m.ids.forEach(d::writeUTF)}
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
sealed interface InkCommitResult {
    data class Committed(val revision: Long):InkCommitResult
    data object Conflict:InkCommitResult
    data object Rejected:InkCommitResult
    data object Unknown:InkCommitResult
}
data class StoredInk(val stroke: InkStroke,val visible: Boolean,val createdRevision: Long)
data class InkPage(val noteId: String,val revision: Long,val strokes: List<StoredInk>)

/** Swept whole-stroke eraser. Bounding boxes reject distant board content first. */
object InkHitTest {
    private fun distance(p: InkSample,a: InkSample,b: InkSample): Float {
        val dx=b.x-a.x;val dy=b.y-a.y;val length=dx*dx+dy*dy
        val t=if(length==0f)0f else (((p.x-a.x)*dx+(p.y-a.y)*dy)/length).coerceIn(0f,1f)
        return hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)
    }
    private fun cross(a: InkSample,b: InkSample,c: InkSample)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun near(a: InkSample,b: InkSample,c: InkSample,d: InkSample,r: Float):Boolean {
        if(cross(a,b,c)*cross(a,b,d)<0f && cross(c,d,a)*cross(c,d,b)<0f)return true
        return minOf(distance(a,c,d),distance(b,c,d),distance(c,a,b),distance(d,a,b))<=r
    }
    fun hits(stroke: InkStroke,eraser: List<InkSample>,radius: Float=12f):Boolean {
        if(eraser.isEmpty())return false
        val er=CanvasBounds(eraser.minOf{it.x}.toDouble(),eraser.minOf{it.y}.toDouble(),eraser.maxOf{it.x}.toDouble(),eraser.maxOf{it.y}.toDouble()).padded(radius.toDouble())
        if(!stroke.bounds().intersects(er))return false
        val s=stroke.samples;val r=radius+stroke.width/2
        for(i in s.indices)for(j in eraser.indices)if(near(s[i],s[(i+1).coerceAtMost(s.lastIndex)],eraser[j],eraser[(j+1).coerceAtMost(eraser.lastIndex)],r))return true
        return false
    }
}
