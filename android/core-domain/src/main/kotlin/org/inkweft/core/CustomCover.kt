// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*

enum class CoverLayout { LABEL, BAND, MINIMAL }
data class CustomCover(val color:Int=0xffdceae0.toInt(),val layout:CoverLayout=CoverLayout.LABEL,
    val title:String="",val subtitle:String="",val showTitle:Boolean=true,
    val zoom:Float=1f,val focusX:Float=.5f,val focusY:Float=.5f,val image:ByteArray=byteArrayOf())

/** Self-contained author appearance. Never store a transient picker URI. */
object CustomCoverCodec {
    const val MAX_IMAGE=180_000
    const val MAX_PAYLOAD=182_000
    fun validate(c:CustomCover){
        require(c.color ushr 24==255)
        require(c.title.length<=80&&c.subtitle.length<=80)
        require((c.title+c.subtitle).none{it<' '||it=='\u007f'})
        require(c.zoom.isFinite()&&c.zoom in 1f..3f&&c.focusX.isFinite()&&c.focusX in 0f..1f&&c.focusY.isFinite()&&c.focusY in 0f..1f)
        require(c.image.size<=MAX_IMAGE)
    }
    fun encode(c:CustomCover):ByteArray{
        validate(c);val out=ByteArrayOutputStream()
        DataOutputStream(out).use{d->d.writeInt(0x49574331);d.writeInt(c.color);d.writeInt(c.layout.ordinal);d.writeUTF(c.title);d.writeUTF(c.subtitle);d.writeBoolean(c.showTitle);d.writeFloat(c.zoom);d.writeFloat(c.focusX);d.writeFloat(c.focusY);d.writeInt(c.image.size);d.write(c.image)}
        return out.toByteArray().also{require(it.size<=MAX_PAYLOAD)}
    }
    fun decode(bytes:ByteArray):CustomCover{
        require(bytes.size<=MAX_PAYLOAD)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            require(d.readInt()==0x49574331);val color=d.readInt();val layout=CoverLayout.entries.getOrNull(d.readInt())?:error("COVER_LAYOUT")
            val title=d.readUTF();val subtitle=d.readUTF();val show=d.readUnsignedByte();require(show in 0..1)
            val zoom=d.readFloat();val x=d.readFloat();val y=d.readFloat();val n=d.readInt();require(n in 0..MAX_IMAGE&&n<=d.available())
            val image=ByteArray(n);d.readFully(image);require(d.available()==0)
            CustomCover(color,layout,title,subtitle,show==1,zoom,x,y,image).also(::validate)
        }
    }
}
