// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.Base64
import java.util.UUID

enum class PageObjectKind { IMAGE, TEXT, TAPE, SHAPE, MAP, FORMULA }
enum class TapePattern { STRIPES, SPARKLES, GRID, SOLID, DOTS, WAVES }
enum class ObjectShape { RECTANGLE, ELLIPSE, TRIANGLE, LINE, ARROW, TABLE }
data class TapePoint(val x:Float,val y:Float) { init { require(x.isFinite()&&y.isFinite()&&x>=0&&y>=0) } }
enum class TextFont { SYSTEM, WENKAI, SERIF }

/** A grapheme's ink box, relative to its text object. Erasure never reflows its neighbours. */
data class TextGlyph(val start:Int,val end:Int,val x:Float,val y:Float,val width:Float,val height:Float,val hidden:Boolean=false,val weight:Float=0f,val color:Int?=null,val grain:Float=0f) {
    init { require(grain.isFinite()&&grain in 0f..10f);require(weight.isFinite()&&weight in 0f..1f);require(start>=0&&end>start);require(listOf(x,y,width,height).all{it.isFinite()});require(x>=0&&y>=0&&width>0&&height>0) }
}

data class TextErasePoint(val x:Float,val y:Float) {
    init { require(x.isFinite()&&y.isFinite()&&kotlin.math.abs(x)<=BoardLimits.WORLD*2&&kotlin.math.abs(y)<=BoardLimits.WORLD*2) }
}
/** Subtractive local-space mask, scoped to characters that existed when erased. */
data class TextErasePath(val start:Int,val end:Int,val radius:Float,val points:List<TextErasePoint>) {
    init { require(start>=0&&end>start);require(radius.isFinite()&&radius in .01f..5000f);require(points.size in 1..InkLimits.MAX_POINTS) }
    fun transformed(dx:Float=0f,dy:Float=0f,scale:Float=1f,offset:Int=0)=copy(start=start+offset,end=end+offset,radius=radius*scale,
        points=points.map{TextErasePoint(it.x*scale+dx,it.y*scale+dy)})
}

/** Immutable author data. Within a user layer, images are backdrops, text/shapes follow,
 * and tapes are foreground. List order controls stacking within each of those groups. */
data class PageObject(
    val id:String, val kind:PageObjectKind,
    val x:Float=100f, val y:Float=180f, val width:Float=400f, val height:Float=220f,
    val text:String="", val image:String="", val color:Int=0xff24342f.toInt(),
    val fontSize:Float=28f, val revealed:Boolean=false,
    val font:TextFont=TextFont.SYSTEM,val lineSpacing:Float=1f,val bold:Boolean=false,
    val sourceStrokeIds:List<String> = emptyList(),val hidden:Boolean=false,
    val glyphs:List<TextGlyph> = emptyList(),val erasures:List<TextErasePath> = emptyList(),
    val tapePoints:List<TapePoint> = emptyList(),val lineWidth:Float=24f,val shape:ObjectShape=ObjectShape.RECTANGLE,val tapePattern:TapePattern=TapePattern.STRIPES,val mapEmbed:MapEmbed?=null,
    val textRuns:List<TextRun> = emptyList(),
    val imageSource:String?=null
) {
    init {
        UUID.fromString(id)
        require(imageSource==null||kind==PageObjectKind.IMAGE&&ImageSource.validHash(imageSource))
        require(textRuns.isEmpty()||kind==PageObjectKind.TEXT&&glyphs.isNotEmpty())
        require(textRuns.size<=4000&&textRuns.zipWithNext().all{(a,b)->a.end<=b.start})
        require(textRuns.all{it.end<=text.length&&it.x<=width&&it.baseline<=height+it.size})
        require(textRuns.all{r->r.sourceIds.all{it in sourceStrokeIds}})
        require((kind==PageObjectKind.MAP)==(mapEmbed!=null));mapEmbed?.let{MapEmbedCodec.encode(it)}
        require(lineWidth.isFinite()&&lineWidth in .5f..192f)
        require(tapePoints.isEmpty()||kind==PageObjectKind.TAPE)
        require(tapePoints.size<=2048&&tapePoints.all{it.x<=width&&it.y<=height})
        require(listOf(x,y,width,height,fontSize).all{it.isFinite()})
        require(width in 24f..4000f && height in 24f..4000f && fontSize in 12f..96f)
        require(lineSpacing.isFinite()&&lineSpacing in 1f..2f)
        require(sourceStrokeIds.size<=256&&sourceStrokeIds.distinct().size==sourceStrokeIds.size)
        sourceStrokeIds.forEach{UUID.fromString(it)}
        require(sourceStrokeIds.isEmpty()||kind==PageObjectKind.TEXT||kind==PageObjectKind.FORMULA)
        require(!hidden||sourceStrokeIds.isNotEmpty())
        require(glyphs.isEmpty()||kind==PageObjectKind.TEXT)
        require(erasures.isEmpty()||kind==PageObjectKind.TEXT&&glyphs.isNotEmpty()||kind==PageObjectKind.FORMULA)
        require(erasures.size<=InkLimits.MAX_CUTS&&erasures.sumOf{it.points.size}<=InkLimits.MAX_CUT_POINTS)
        require(erasures.all{it.end<=text.length})
        require(glyphs.size<=4000&&glyphs.zipWithNext().all{(a,b)->a.end<=b.start})
        require(glyphs.all{it.end<=text.length&&it.x+it.width<=width+.01f&&it.y+it.height<=height+.01f})
        require(kotlin.math.abs(x)+width<=BoardLimits.WORLD && kotlin.math.abs(y)+height<=BoardLimits.WORLD)
        require(text.length<=4000 && image.length<=PageObjectCodec.MAX_IMAGE*4/3+4)
        require(when(kind){PageObjectKind.IMAGE->image.isNotEmpty()&&text.isEmpty();PageObjectKind.TEXT,PageObjectKind.FORMULA->text.isNotBlank()&&image.isEmpty();PageObjectKind.TAPE,PageObjectKind.SHAPE,PageObjectKind.MAP->text.isEmpty()&&image.isEmpty()})
        if(kind==PageObjectKind.FORMULA)FormulaText.validate(text)
    }
    fun bounds()=CanvasBounds(x.toDouble(),y.toDouble(),(x+width).toDouble(),(y+height).toDouble())
    fun visibleText():String {
        if(hidden)return ""
        if(glyphs.none{it.hidden})return text
        val removed=BooleanArray(text.length);glyphs.filter{it.hidden}.forEach{g->for(i in g.start until g.end)removed[i]=true}
        return text.filterIndexed{i,_->!removed[i]}
    }
}

object PageObjectCodec {
    const val MAX_IMAGE=200_000
    const val MAX_BYTES=1_600_000
    const val MAX_OBJECTS=32
    const val MAX_RECORDS=288
    fun encode(objects:List<PageObject>):ByteArray {
        require(objects.count{!it.hidden}<=MAX_OBJECTS && objects.size<=MAX_RECORDS && objects.map{it.id}.distinct().size==objects.size)
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            val originals=objects.any{it.imageSource!=null}
            out.writeInt(if(originals)0x49574f3a else 0x49574f39);out.writeInt(objects.size)
            objects.forEach { o ->
                out.writeUTF(o.id);out.writeByte(o.kind.ordinal)
                listOf(o.x,o.y,o.width,o.height,o.fontSize).forEach(out::writeFloat)
                out.writeInt(o.color);out.writeBoolean(o.revealed);out.writeUTF(o.text)
                out.writeByte(o.font.ordinal);out.writeFloat(o.lineSpacing);out.writeBoolean(o.bold)
                out.writeInt(o.sourceStrokeIds.size);o.sourceStrokeIds.forEach(out::writeUTF)
                out.writeBoolean(o.hidden)
                out.writeInt(o.glyphs.size);o.glyphs.forEach{g->
                    out.writeInt(g.start);out.writeInt(g.end);listOf(g.x,g.y,g.width,g.height).forEach(out::writeFloat);out.writeBoolean(g.hidden);out.writeFloat(g.weight);out.writeBoolean(g.color!=null);g.color?.let(out::writeInt);out.writeFloat(g.grain)
                }
                out.writeInt(o.erasures.size);o.erasures.forEach{cut->
                    out.writeInt(cut.start);out.writeInt(cut.end);out.writeFloat(cut.radius);out.writeInt(cut.points.size)
                    cut.points.forEach{out.writeFloat(it.x);out.writeFloat(it.y)}
                }
                out.writeFloat(o.lineWidth);out.writeByte(o.shape.ordinal);out.writeByte(o.tapePattern.ordinal);out.writeInt(o.tapePoints.size)
                o.tapePoints.forEach{out.writeFloat(it.x);out.writeFloat(it.y)}
                val image=if(o.image.isEmpty())byteArrayOf()else Base64.getDecoder().decode(o.image)
                require(image.size<=MAX_IMAGE)
                if(image.isNotEmpty())require(image.size>4&&image[0]==0xff.toByte()&&image[1]==0xd8.toByte())
                out.writeInt(image.size);out.write(image)
                val embed=o.mapEmbed?.let(MapEmbedCodec::encode);out.writeInt(embed?.size?:0);embed?.let(out::write)
                out.writeInt(o.textRuns.size);o.textRuns.forEach{r->
                    out.writeUTF(r.id);out.writeUTF(r.lineId);out.writeInt(r.start);out.writeInt(r.end)
                    out.writeFloat(r.x);out.writeFloat(r.baseline);out.writeFloat(r.size);out.writeLong(r.sourceRevision)
                    out.writeByte(r.policy.ordinal);out.writeUTF(r.model);out.writeFloat(r.score)
                    out.writeInt(r.sourceIds.size);r.sourceIds.forEach(out::writeUTF)
                }
                if(originals)out.writeUTF(o.imageSource.orEmpty())
                require(buffer.size()<=MAX_BYTES)
            }
        }
        return buffer.toByteArray()
    }
    fun decode(bytes:ByteArray):List<PageObject> {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version=input.readInt();require(version in listOf(0x49574f31,0x49574f32,0x49574f33,0x49574f34,0x49574f35,0x49574f36,0x49574f37,0x49574f38,0x49574f39,0x49574f3a))
            val count=input.readInt();require(count in 0..MAX_RECORDS)
            val objects=List(count) {
                val id=input.readUTF();val kind=PageObjectKind.entries.getOrNull(input.readUnsignedByte())?:error("Object kind")
                val x=input.readFloat();val y=input.readFloat();val w=input.readFloat();val h=input.readFloat();val fontSize=input.readFloat()
                val color=input.readInt();val revealed=input.readBoolean();val text=input.readUTF()
                val font=if(version>=0x49574f32)TextFont.entries.getOrNull(input.readUnsignedByte())?:error("Font")else TextFont.SYSTEM
                val spacing=if(version>=0x49574f32)input.readFloat()else 1f
                val bold=version>=0x49574f32&&input.readBoolean()
                val source=if(version>=0x49574f32){val n=input.readInt();require(n in 0..256);List(n){input.readUTF()}}else emptyList()
                val hidden=version>=0x49574f33&&input.readBoolean()
                val glyphs=if(version>=0x49574f34){val n=input.readInt();require(n in 0..4000);List(n){TextGlyph(input.readInt(),input.readInt(),input.readFloat(),input.readFloat(),input.readFloat(),input.readFloat(),input.readBoolean(),input.readFloat(),if(version>=0x49574f37&&input.readBoolean())input.readInt()else null,if(version>=0x49574f37)input.readFloat()else 0f)}}else emptyList()
                val erasures=if(version>=0x49574f34){
                    val n=input.readInt();require(n in 0..InkLimits.MAX_CUTS);var total=0
                    List(n){val start=input.readInt();val end=input.readInt();val radius=input.readFloat();val points=input.readInt()
                        require(points in 1..InkLimits.MAX_POINTS);total+=points;require(total<=InkLimits.MAX_CUT_POINTS&&points.toLong()*8<=input.available())
                        TextErasePath(start,end,radius,List(points){TextErasePoint(input.readFloat(),input.readFloat())})}
                }else emptyList()
                val lineWidth=if(version>=0x49574f35)input.readFloat()else 24f
                val shape=if(version>=0x49574f35)ObjectShape.entries.getOrNull(input.readUnsignedByte())?:error("Shape")else ObjectShape.RECTANGLE
                val tapePattern=if(version>=0x49574f36)TapePattern.entries.getOrNull(input.readUnsignedByte())?:error("Tape pattern")else TapePattern.STRIPES
                val tapePoints=if(version>=0x49574f35){val n=input.readInt();require(n in 0..2048&&n.toLong()*8<=input.available());List(n){TapePoint(input.readFloat(),input.readFloat())}}else emptyList()
                val size=input.readInt();require(size in 0..MAX_IMAGE && size<=input.available())
                val image=ByteArray(size);input.readFully(image)
                val embed=if(version>=0x49574f38){val n=input.readInt();require(n in 0..MapEmbedCodec.MAX_BYTES&&n<=input.available());if(n==0)null else MapEmbedCodec.decode(ByteArray(n).also(input::readFully))}else null
                val runs=if(version>=0x49574f39){val n=input.readInt();require(n in 0..4000);List(n){
                    val rid=input.readUTF();val line=input.readUTF();val start=input.readInt();val end=input.readInt()
                    val rx=input.readFloat();val baseline=input.readFloat();val size=input.readFloat();val revision=input.readLong()
                    val policy=TextLayoutPolicy.entries.getOrNull(input.readUnsignedByte())?:error("Text layout")
                    val model=input.readUTF();val score=input.readFloat();val count=input.readInt();require(count in 0..256)
                    TextRun(rid,line,start,end,rx,baseline,size,List(count){input.readUTF()},revision,policy,model,score)
                }}else emptyList()
                val original=if(version>=0x49574f3a)input.readUTF().ifEmpty{null}else null
                PageObject(id,kind,x,y,w,h,text,if(size==0)""else Base64.getEncoder().encodeToString(image),color,fontSize,revealed,font,spacing,bold,source,hidden,glyphs,erasures,tapePoints,lineWidth,shape,tapePattern,embed,runs,original)
            }
            require(input.available()==0);encode(objects);objects
        }
    }
}
