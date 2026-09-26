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
    const val MAX_STROKE_BYTES=2_000_000
    const val MAX_CUTS=128
    const val MAX_CUT_POINTS=32_768
}
data class InkSample(val x:Float,val y:Float,val elapsedMs:Long,val pressure:Float=-1f,val tilt:Float=-1f,val orientation:Float=-1f,val world:Boolean=false) {
    init {
        require(x.isFinite()&&y.isFinite()&&x in -BoardLimits.WORLD..BoardLimits.WORLD&&y in -BoardLimits.WORLD..BoardLimits.WORLD)
        if(!world)require(x in 0f..InkLimits.WIDTH&&y in 0f..InkLimits.HEIGHT)
        require(elapsedMs in 0..3_600_000)
        require(pressure==-1f||(pressure.isFinite()&&pressure in 0f..1f))
        require(tilt==-1f||(tilt.isFinite()&&tilt in 0f..Math.PI.toFloat()/2))
        require(orientation==-1f||(orientation.isFinite()&&orientation in 0f..2*Math.PI.toFloat()))
    }
}
enum class InkPen { PEN,HIGHLIGHTER,BALLPOINT,BRUSH,MARKER }
enum class InkTool { TOUCH,STYLUS,MOUSE }
data class EraserPoint(val x:Float,val y:Float) {
    init {require(x.isFinite()&&y.isFinite()&&x in -BoardLimits.WORLD..BoardLimits.WORLD&&y in -BoardLimits.WORLD..BoardLimits.WORLD)}
}
/** A genuine subtractive author-space mask, not a white stroke. Original ink is
 * retained for undo. Masks belong only to the strokes selected at erase time. */
enum class InkCutShape { ROUND, RECTANGLE, POLYGON }
class InkCut(val id:String,val radius:Float,points:List<EraserPoint>,val shape:InkCutShape=InkCutShape.ROUND) {
    val points:List<EraserPoint> = Collections.unmodifiableList(ArrayList(points))
    init {UUID.fromString(id);require(radius.isFinite()&&radius in .01f..5000f);require(points.size in 1..InkLimits.MAX_POINTS);when(shape){InkCutShape.RECTANGLE->require(points.size==2&&points[0].x<points[1].x&&points[0].y<points[1].y);InkCutShape.POLYGON->require(points.size in 3..512);else->Unit}}
}
class EraseSelection(val cut:InkCut,strokeIds:List<String>) {
    val strokeIds:List<String> = Collections.unmodifiableList(strokeIds.sorted())
    init {require(strokeIds.size in 1..InkLimits.MAX_STROKES&&strokeIds.distinct().size==strokeIds.size);strokeIds.forEach{UUID.fromString(it)}}
}
class InkStroke(val id:String,val pen:InkPen,val color:Int,val width:Float,val tool:InkTool,
    samples:List<InkSample>,val world:Boolean=false,cuts:List<InkCut> = emptyList()) {
    val samples:List<InkSample> = Collections.unmodifiableList(ArrayList(samples))
    val cuts:List<InkCut> = Collections.unmodifiableList(ArrayList(cuts))
    init {
        UUID.fromString(id);require(width.isFinite()&&width in .5f..48f);require((color ushr 24) in 1..255)
        require(samples.size in 1..InkLimits.MAX_POINTS)
        require(cuts.size<=InkLimits.MAX_CUTS&&cuts.sumOf{it.points.size}<=InkLimits.MAX_CUT_POINTS&&cuts.map{it.id}.distinct().size==cuts.size)
        val first=samples.first()
        samples.forEachIndexed{i,p->require(p.world==world);if(!world)require(p.x in 0f..InkLimits.WIDTH&&p.y in 0f..InkLimits.HEIGHT)
            require((p.pressure>=0)==(first.pressure>=0)&&(p.tilt>=0)==(first.tilt>=0)&&(p.orientation>=0)==(first.orientation>=0))
            if(i>0){require(p.elapsedMs>=samples[i-1].elapsedMs);require(p!=samples[i-1])}}
    }
    fun withCuts(extra:List<InkCut>):InkStroke = if(extra.isEmpty())this else InkStroke(id,pen,color,width,tool,samples,world,cuts+extra)
}
object InkCutCodec {
    fun write(out:DataOutputStream,cut:InkCut){if(cut.shape!=InkCutShape.ROUND){out.writeUTF("IW-CUT-2");out.writeByte(cut.shape.ordinal)};out.writeUTF(cut.id);out.writeFloat(cut.radius);out.writeInt(cut.points.size);cut.points.forEach{out.writeFloat(it.x);out.writeFloat(it.y)}}
    fun read(input:DataInputStream):InkCut {val token=input.readUTF();val shape=if(token=="IW-CUT-2")InkCutShape.entries.getOrNull(input.readUnsignedByte())?:error("Unsupported cut shape")else InkCutShape.ROUND;val id=if(token=="IW-CUT-2")input.readUTF()else token;val radius=input.readFloat();val count=input.readInt();require(count in 1..InkLimits.MAX_POINTS&&count.toLong()*8<=input.available());return InkCut(id,radius,List(count){EraserPoint(input.readFloat(),input.readFloat())},shape)}
    fun encode(cut:InkCut):ByteArray=ByteArrayOutputStream().also{b->DataOutputStream(b).use{write(it,cut)}}.toByteArray()
    fun decode(bytes:ByteArray):InkCut {require(bytes.size<=70_000);return DataInputStream(ByteArrayInputStream(bytes)).use{val cut=read(it);require(it.available()==0);cut}}
}
object InkStrokeCodec {
    private const val PAGE=0x49575331
    private const val WORLD=0x49575332
    private const val MASKED=0x49575333
    private const val SHAPED=0x49575334
    fun encode(stroke:InkStroke):ByteArray=ByteArrayOutputStream().also{b->DataOutputStream(b).use{out->
        out.writeInt(if(stroke.cuts.any{it.shape!=InkCutShape.ROUND})SHAPED else if(stroke.cuts.isEmpty())if(stroke.world)WORLD else PAGE else MASKED)
        if(stroke.cuts.isNotEmpty())out.writeBoolean(stroke.world)
        out.writeUTF(stroke.id);out.writeByte(stroke.pen.ordinal);out.writeInt(stroke.color);out.writeFloat(stroke.width)
        out.writeByte(stroke.tool.ordinal);out.writeInt(stroke.samples.size)
        stroke.samples.forEach{p->out.writeFloat(p.x);out.writeFloat(p.y);out.writeLong(p.elapsedMs);out.writeFloat(p.pressure);out.writeFloat(p.tilt);out.writeFloat(p.orientation)}
        if(stroke.cuts.isNotEmpty()){out.writeInt(stroke.cuts.size);stroke.cuts.forEach{InkCutCodec.write(out,it)}}
    }}.toByteArray()
    fun decode(bytes:ByteArray):InkStroke {
        require(bytes.size in 1..InkLimits.MAX_STROKE_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{input->
            val magic=input.readInt();require(magic in listOf(PAGE,WORLD,MASKED,SHAPED)){"Unsupported ink format; retain original bytes"}
            val world=if(magic==MASKED||magic==SHAPED)input.readBoolean()else magic==WORLD
            val id=input.readUTF();val pen=InkPen.entries.getOrNull(input.readUnsignedByte())?:error("Unknown pen")
            val color=input.readInt();val width=input.readFloat();val tool=InkTool.entries.getOrNull(input.readUnsignedByte())?:error("Unknown tool")
            val count=input.readInt();require(count in 1..InkLimits.MAX_POINTS&&count.toLong()*28<=input.available())
            val points=List(count){InkSample(input.readFloat(),input.readFloat(),input.readLong(),input.readFloat(),input.readFloat(),input.readFloat(),world)}
            val cuts=if(magic==MASKED||magic==SHAPED){val n=input.readInt();require(n in 1..InkLimits.MAX_CUTS);var total=0;List(n){InkCutCodec.read(input).also{total+=it.points.size;require(total<=InkLimits.MAX_CUT_POINTS)}}}else emptyList()
            require(input.available()==0){"Trailing ink data"};InkStroke(id,pen,color,width,tool,points,world,cuts)
        }
    }
}
sealed interface InkMutation {
    /** New immutable versions; original strokes stay available for undo/history.
     * Empty hidden is a duplicate. No caller can mutate the frozen payload lists. */
    class Replace(hidden:List<String>,added:List<InkStroke>):InkMutation {
        val hidden:List<String> = Collections.unmodifiableList(hidden.sorted())
        val added:List<InkStroke> = Collections.unmodifiableList(ArrayList(added))
        init{require(added.isNotEmpty()&&added.size<=256&&hidden.size<=256)
            require(hidden.distinct().size==hidden.size&&added.map{it.id}.distinct().size==added.size)
            hidden.forEach{UUID.fromString(it)};require(added.none{it.id in hidden})}
    }
    class Swap(hide:List<String>,show:List<String>):InkMutation {
        val hide:List<String> = Collections.unmodifiableList(hide.sorted())
        val show:List<String> = Collections.unmodifiableList(show.sorted())
        init{require(hide.size+show.size in 1..512);require((hide+show).distinct().size==hide.size+show.size);(hide+show).forEach{UUID.fromString(it)}}
    }

    class Add(val stroke:InkStroke):InkMutation
    class Visibility(ids:List<String>,val visible:Boolean):InkMutation {
        val ids:List<String> = Collections.unmodifiableList(ids.sorted())
        init{require(ids.size in 1..InkLimits.MAX_STROKES&&ids.size==ids.toSet().size);ids.forEach{UUID.fromString(it)}}
    }
    class Cut(val selection:EraseSelection):InkMutation
    data class CutVisibility(val cutId:String,val visible:Boolean):InkMutation{init{UUID.fromString(cutId)}}
}
class CommitInk(val commandId:String,val noteId:String,val expectedRevision:Long,val mutation:InkMutation) {
    init{UUID.fromString(commandId);UUID.fromString(noteId);require(expectedRevision in 0 until Long.MAX_VALUE-1)}
    val strokeIds:List<String> get()=when(val m=mutation){is InkMutation.Add->listOf(m.stroke.id);is InkMutation.Visibility->m.ids;is InkMutation.Cut->m.selection.strokeIds;is InkMutation.CutVisibility->emptyList();is InkMutation.Replace->m.hidden+m.added.map{it.id};is InkMutation.Swap->m.hide+m.show}
    fun digest():String {
        val out=ByteArrayOutputStream();DataOutputStream(out).use{d->
            d.writeUTF("inkweft.ink-command.v1");d.writeUTF(commandId);d.writeUTF(noteId);d.writeLong(expectedRevision)
            when(val m=mutation){
                is InkMutation.Add->{d.writeByte(1);val b=InkStrokeCodec.encode(m.stroke);d.writeInt(b.size);d.write(b)}
                is InkMutation.Visibility->{d.writeByte(2);d.writeBoolean(m.visible);d.writeInt(m.ids.size);m.ids.forEach(d::writeUTF)}
                is InkMutation.Cut->{d.writeByte(3);InkCutCodec.write(d,m.selection.cut);d.writeInt(m.selection.strokeIds.size);m.selection.strokeIds.forEach(d::writeUTF)}
                is InkMutation.CutVisibility->{d.writeByte(4);d.writeUTF(m.cutId);d.writeBoolean(m.visible)}
                is InkMutation.Replace->{d.writeByte(5);d.writeInt(m.hidden.size);m.hidden.forEach(d::writeUTF);d.writeInt(m.added.size);m.added.forEach{val b=InkStrokeCodec.encode(it);d.writeInt(b.size);d.write(b)}}
                is InkMutation.Swap->{d.writeByte(6);d.writeInt(m.hide.size);m.hide.forEach(d::writeUTF);d.writeInt(m.show.size);m.show.forEach(d::writeUTF)}
            }
        };return MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
}
sealed interface InkCommitResult {
    data class Committed(val revision:Long):InkCommitResult
    data object Conflict:InkCommitResult
    data object Rejected:InkCommitResult
    data object Unknown:InkCommitResult
}
data class StoredInk(val stroke:InkStroke,val visible:Boolean,val createdRevision:Long)
data class StoredCut(val selection:EraseSelection,val visible:Boolean,val createdRevision:Long)
/** noteId is the legacy name for the stable page ID, not the displayed page number. */
data class InkPage(val noteId:String,val revision:Long,val strokes:List<StoredInk>,val cuts:List<StoredCut> = emptyList())
object InkHitTest {
    private fun distance(p:InkSample,a:InkSample,b:InkSample):Float {val dx=b.x-a.x;val dy=b.y-a.y;val length=dx*dx+dy*dy;val t=if(length==0f)0f else (((p.x-a.x)*dx+(p.y-a.y)*dy)/length).coerceIn(0f,1f);return hypot(p.x-a.x-t*dx,p.y-a.y-t*dy)}
    private fun cross(a:InkSample,b:InkSample,c:InkSample)=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
    private fun near(a:InkSample,b:InkSample,c:InkSample,d:InkSample,r:Float):Boolean {if(cross(a,b,c)*cross(a,b,d)<0f&&cross(c,d,a)*cross(c,d,b)<0f)return true;return minOf(distance(a,c,d),distance(b,c,d),distance(c,a,b),distance(d,a,b))<=r}
    fun hits(stroke:InkStroke,eraser:List<InkSample>,radius:Float=12f):Boolean {
        if(eraser.isEmpty())return false
        require(radius.isFinite()&&radius>0)
        val er=CanvasBounds(eraser.minOf{it.x}.toDouble(),eraser.minOf{it.y}.toDouble(),eraser.maxOf{it.x}.toDouble(),eraser.maxOf{it.y}.toDouble()).padded(radius.toDouble())
        if(!stroke.bounds().intersects(er))return false
        val s=stroke.samples;val r=radius+stroke.width/2
        for(i in s.indices)for(j in eraser.indices)if(near(s[i],s[(i+1).coerceAtMost(s.lastIndex)],eraser[j],eraser[(j+1).coerceAtMost(eraser.lastIndex)],r))return true
        return false
    }
}
