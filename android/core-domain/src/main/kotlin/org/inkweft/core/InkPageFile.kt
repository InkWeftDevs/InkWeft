// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core
import java.io.*
import java.security.MessageDigest
import java.util.Collections

/** Visible content copy, including effective masks; not vault or undo history. */
class InkPageFile(val title:String,val text:String,strokes:List<InkStroke>,val world:Boolean=false,val paper:PaperStyle=PaperStyle.RULED){
    val strokes:List<InkStroke> = Collections.unmodifiableList(ArrayList(strokes))
    init{require(title.isNotBlank()&&title.length<=120&&text.length<=100_000);require(strokes.size<=InkLimits.MAX_STROKES&&strokes.map{it.id}.distinct().size==strokes.size);require(strokes.sumOf{it.samples.size}<=InkLimits.MAX_PAGE_POINTS);require(strokes.all{it.world==world})}
    fun encode():ByteArray {
        val body=ByteArrayOutputStream();DataOutputStream(body).use{out->
            out.writeInt(if(strokes.any{s->s.cuts.any{it.shape!=InkCutShape.ROUND}})0x49575034 else if(strokes.any{it.cuts.isNotEmpty()})0x49575033 else 0x49575032);out.writeBoolean(world);out.writeByte(paper.ordinal)
            fun field(t:String){val b=t.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b)}
            field(title);field(text);out.writeInt(strokes.size)
            strokes.forEach{val b=InkStrokeCodec.encode(it);require(body.size().toLong()+b.size+36<=MAX_BYTES);out.writeInt(b.size);out.write(b)}
        };val bytes=body.toByteArray();require(bytes.size+32<=MAX_BYTES);return bytes+MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    companion object {
        const val MAX_BYTES=4_000_000
        fun decode(bytes:ByteArray):InkPageFile {
            require(bytes.size in 48..MAX_BYTES);val body=bytes.copyOfRange(0,bytes.size-32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),bytes.copyOfRange(bytes.size-32,bytes.size)))
            return DataInputStream(ByteArrayInputStream(body)).use{input->
                val magic=input.readInt();require(magic in listOf(0x49575031,0x49575032,0x49575033,0x49575034)){"Unknown page copy version"}
                val world=if(magic!=0x49575031)input.readBoolean()else false
                val paper=if(magic!=0x49575031)PaperStyle.entries.getOrNull(input.readUnsignedByte())?:error("Unknown paper")else PaperStyle.RULED
                fun field(limit:Int):String{val n=input.readInt();require(n in 0..limit&&n<=input.available());val b=ByteArray(n);input.readFully(b);val s=b.toString(Charsets.UTF_8);require(s.toByteArray(Charsets.UTF_8).contentEquals(b));return s}
                val title=field(480);val text=field(400_000);val count=input.readInt();require(count in 0..InkLimits.MAX_STROKES);var points=0
                val strokes=List(count){val n=input.readInt();require(n in 1..InkLimits.MAX_STROKE_BYTES&&n<=input.available());val b=ByteArray(n);input.readFully(b);InkStrokeCodec.decode(b).also{points+=it.samples.size;require(points<=InkLimits.MAX_PAGE_POINTS)}}
                require(input.available()==0);InkPageFile(title,text,strokes,world,paper)
            }
        }
    }
}
