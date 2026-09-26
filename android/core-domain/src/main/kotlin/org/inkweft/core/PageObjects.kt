// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.Base64
import java.util.UUID

enum class PageObjectKind { IMAGE, TEXT, TAPE }

/** Immutable author data. List order is object stacking order; ink sits above images/text. */
data class PageObject(
    val id:String, val kind:PageObjectKind,
    val x:Float=100f, val y:Float=180f, val width:Float=400f, val height:Float=220f,
    val text:String="", val image:String="", val color:Int=0xff24342f.toInt(),
    val fontSize:Float=28f, val revealed:Boolean=false
) {
    init {
        UUID.fromString(id)
        require(listOf(x,y,width,height,fontSize).all{it.isFinite()})
        require(width in 24f..4000f && height in 24f..4000f && fontSize in 12f..96f)
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
            out.writeInt(0x49574f31);out.writeInt(objects.size)
            objects.forEach { o ->
                out.writeUTF(o.id);out.writeByte(o.kind.ordinal)
                listOf(o.x,o.y,o.width,o.height,o.fontSize).forEach(out::writeFloat)
                out.writeInt(o.color);out.writeBoolean(o.revealed);out.writeUTF(o.text)
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
            require(input.readInt()==0x49574f31)
            val count=input.readInt();require(count in 0..MAX_OBJECTS)
            val objects=List(count) {
                val id=input.readUTF();val kind=PageObjectKind.entries.getOrNull(input.readUnsignedByte())?:error("Object kind")
                val x=input.readFloat();val y=input.readFloat();val w=input.readFloat();val h=input.readFloat();val font=input.readFloat()
                val color=input.readInt();val revealed=input.readBoolean();val text=input.readUTF()
                val size=input.readInt();require(size in 0..MAX_IMAGE && size<=input.available())
                val image=ByteArray(size);input.readFully(image)
                PageObject(id,kind,x,y,w,h,text,if(size==0)""else Base64.getEncoder().encodeToString(image),color,font,revealed)
            }
            require(input.available()==0);encode(objects);objects
        }
    }
}
