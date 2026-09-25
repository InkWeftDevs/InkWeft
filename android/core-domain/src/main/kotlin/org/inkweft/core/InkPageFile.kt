// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest
import java.util.Collections

/** Visible CONTENT copy, not a full vault/history backup. v1 reads remain supported. */
class InkPageFile(val title:String,val text:String,strokes:List<InkStroke>,val world:Boolean=false,val paper:PaperStyle=PaperStyle.RULED) {
    val strokes:List<InkStroke> = Collections.unmodifiableList(ArrayList(strokes))
    init {
        require(title.isNotBlank() && title.length<=120 && text.length<=100_000)
        require(strokes.size<=InkLimits.MAX_STROKES && strokes.map{it.id}.distinct().size==strokes.size)
        require(strokes.sumOf{it.samples.size}<=InkLimits.MAX_PAGE_POINTS)
        require(strokes.all { it.world==world })
    }
    fun encode():ByteArray {
        val body=ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(0x49575032);out.writeBoolean(world);out.writeByte(paper.ordinal)
            fun field(text:String){val bytes=text.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes)}
            field(title);field(text);out.writeInt(strokes.size)
            strokes.forEach{val bytes=InkStrokeCodec.encode(it);out.writeInt(bytes.size);out.write(bytes)}
        }
        val bytes=body.toByteArray();require(bytes.size+32<=MAX_BYTES)
        return bytes+MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    companion object {
        const val MAX_BYTES=4_000_000
        fun decode(bytes:ByteArray):InkPageFile {
            require(bytes.size in 48..MAX_BYTES)
            val body=bytes.copyOfRange(0,bytes.size-32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),bytes.copyOfRange(bytes.size-32,bytes.size)))
            return DataInputStream(ByteArrayInputStream(body)).use { input ->
                val magic=input.readInt();require(magic==0x49575031 || magic==0x49575032){"Unknown page copy version"}
                val world=if(magic==0x49575032)input.readBoolean() else false
                val paper=if(magic==0x49575032)PaperStyle.entries.getOrNull(input.readUnsignedByte())?:error("Unknown paper") else PaperStyle.RULED
                fun field(limit:Int):String{val n=input.readInt();require(n in 0..limit && n<=input.available());val b=ByteArray(n);input.readFully(b);val s=b.toString(Charsets.UTF_8);require(s.toByteArray(Charsets.UTF_8).contentEquals(b));return s}
                val title=field(480);val text=field(400_000);val count=input.readInt();require(count in 0..InkLimits.MAX_STROKES)
                var points=0
                val strokes=List(count){val n=input.readInt();require(n in 1..InkLimits.MAX_STROKE_BYTES && n<=input.available());val b=ByteArray(n);input.readFully(b);InkStrokeCodec.decode(b).also{points+=it.samples.size;require(points<=InkLimits.MAX_PAGE_POINTS)}}
                require(input.available()==0);InkPageFile(title,text,strokes,world,paper)
            }
        }
    }
}
