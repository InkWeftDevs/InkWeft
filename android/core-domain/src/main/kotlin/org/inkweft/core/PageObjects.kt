// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.Base64
import java.util.UUID

enum class PageObjectKind { IMAGE, TEXT, TAPE }
enum class TextFont { SYSTEM, WENKAI, SERIF }

/** Immutable author data. List order is object stacking order; ink sits above images/text. */
data class PageObject(
    val id:String, val kind:PageObjectKind,
    val x:Float=100f, val y:Float=180f, val width:Float=400f, val height:Float=220f,
    val text:String="", val image:String="", val color:Int=0xff24342f.toInt(),
    val fontSize:Float=28f, val revealed:Boolean=false,
    val font:TextFont=TextFont.SYSTEM,val lineSpacing:Float=1f,val bold:Boolean=false,
    val sourceStrokeIds:List<String> = emptyList()
) {
    init {
        UUID.fromString(id)
        require(listOf(x,y,width,height,fontSize).all{it.isFinite()})
        require(width in 24f..4000f && height in 24f..4000f && fontSize in 12f..96f)
        require(lineSpacing.isFinite()&&lineSpacing in 1f..2f)
        require(sourceStrokeIds.size<=256&&sourceStrokeIds.distinct().size==sourceStrokeIds.size)
        sourceStrokeIds.forEach{UUID.fromString(it)}
        require(sourceStrokeIds.isEmpty()||kind==PageObjectKind.TEXT)
        require(kotlin.math.abs(x)+width<=BoardLimits.WORLD && kotlin.math.abs(y)+height<=BoardLimits.WORLD)
        require(text.length<=4000 && image.length<=PageObjectCodec.MAX_IMAGE*4/3+4)
        require(when(kind){PageObjectKind.IMAGE->image.isNotEmpty()&&text.isEmpty();PageObjectKind.TEXT->text.isNotBlank()&&image.isEmpty();PageObjectKind.TAPE->text.isEmpty()&&image.isEmpty()})
    }
    fun bounds()=CanvasBounds(x.toDouble(),y.toDouble(),(x+width).toDouble(),(y+height).toDouble())
}

object PageObjectCodec {
    const val MAX_IMAGE=200_000
    const val MAX_BYTES=1_600_000
    const val MAX_OBJECTS=32
    fun encode(objects:List<PageObject>):ByteArray {
        require(objects.size<=MAX_OBJECTS && objects.map{it.id}.distinct().size==objects.size)
        val buffer=ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(0x49574f32);out.writeInt(objects.size)
            objects.forEach { o ->
                out.writeUTF(o.id);out.writeByte(o.kind.ordinal)
                listOf(o.x,o.y,o.width,o.height,o.fontSize).forEach(out::writeFloat)
                out.writeInt(o.color);out.writeBoolean(o.revealed);out.writeUTF(o.text)
                out.writeByte(o.font.ordinal);out.writeFloat(o.lineSpacing);out.writeBoolean(o.bold)
                out.writeInt(o.sourceStrokeIds.size);o.sourceStrokeIds.forEach(out::writeUTF)
                val image=if(o.image.isEmpty())byteArrayOf()else Base64.getDecoder().decode(o.image)
                require(image.size<=MAX_IMAGE)
                if(image.isNotEmpty())require(image.size>4&&image[0]==0xff.toByte()&&image[1]==0xd8.toByte())
                out.writeInt(image.size);out.write(image)
                require(buffer.size()<=MAX_BYTES)
            }
        }
        return buffer.toByteArray()
    }
    fun decode(bytes:ByteArray):List<PageObject> {
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version=input.readInt();require(version in listOf(0x49574f31,0x49574f32))
            val count=input.readInt();require(count in 0..MAX_OBJECTS)
            val objects=List(count) {
                val id=input.readUTF();val kind=PageObjectKind.entries.getOrNull(input.readUnsignedByte())?:error("Object kind")
                val x=input.readFloat();val y=input.readFloat();val w=input.readFloat();val h=input.readFloat();val fontSize=input.readFloat()
                val color=input.readInt();val revealed=input.readBoolean();val text=input.readUTF()
                val font=if(version==0x49574f32)TextFont.entries.getOrNull(input.readUnsignedByte())?:error("Font")else TextFont.SYSTEM
                val spacing=if(version==0x49574f32)input.readFloat()else 1f
                val bold=version==0x49574f32&&input.readBoolean()
                val source=if(version==0x49574f32){val n=input.readInt();require(n in 0..256);List(n){input.readUTF()}}else emptyList()
                val size=input.readInt();require(size in 0..MAX_IMAGE && size<=input.available())
                val image=ByteArray(size);input.readFully(image)
                PageObject(id,kind,x,y,w,h,text,if(size==0)""else Base64.getEncoder().encodeToString(image),color,fontSize,revealed,font,spacing,bold,source)
            }
            require(input.available()==0);encode(objects);objects
        }
    }
}
