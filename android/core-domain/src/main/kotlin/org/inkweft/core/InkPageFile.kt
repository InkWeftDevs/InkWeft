// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core
import java.io.*
import java.util.Collections

/** Visible content copy, including effective masks; not vault or undo history. */
class InkPageFile(val title:String,val text:String,strokes:List<InkStroke>,val world:Boolean=false,val paper:PaperStyle=PaperStyle.RULED,objects:List<PageObject> = emptyList(),val source:PdfPageSource?=null,imageSources:List<ImageSource> = emptyList(),val authoring:PageAuthoring?=null){
    private val strokeIds=strokes.map{it.id}.toSet()
    val objects:List<PageObject> = Collections.unmodifiableList(objects.mapNotNull{o->val refs=o.sourceStrokeIds.filter{it in strokeIds};if(o.hidden&&refs.isEmpty())null else o.copy(sourceStrokeIds=refs,textRuns=o.textRuns.map{it.copy(sourceIds=it.sourceIds.filter{v->v in refs})})})
    val imageSources:List<ImageSource> = Collections.unmodifiableList(imageSources.distinctBy{it.sha256})
    val strokes:List<InkStroke> = Collections.unmodifiableList(ArrayList(strokes))
    init{authoring?.let(PageAuthoringCodec::encode);require(objects.mapNotNull{it.imageSource}.toSet()==this.imageSources.map{it.sha256}.toSet()){"IMAGE_ORIGINAL_CLOSURE"};require(source==null||!world);PageObjectCodec.encode(objects);require(title.isNotBlank()&&title.length<=120&&text.length<=100_000);require(strokes.size<=InkLimits.MAX_STROKES&&strokes.map{it.id}.distinct().size==strokes.size);require(strokes.sumOf{it.samples.size}<=InkLimits.MAX_PAGE_POINTS);require(strokes.all{it.world==world})}
    fun encode(includeSource:Boolean=true):ByteArray {
        require(objects.none{it.mapEmbed?.policy==MapEmbedPolicy.LIVE}){"LIVE_MAP_REQUIRES_FULL_BACKUP_OR_SNAPSHOT"}
        val body=ChecksummedBuffer();DataOutputStream(body).use{out->
            val withSource=includeSource&&source!=null
            out.writeInt(if(authoring!=null)0x49575038 else if(imageSources.isNotEmpty())0x49575037 else if(withSource)0x49575036 else if(objects.isNotEmpty())0x49575035 else if(strokes.any{s->s.cuts.any{it.shape!=InkCutShape.ROUND}})0x49575034 else if(strokes.any{it.cuts.isNotEmpty()})0x49575033 else 0x49575032);out.writeBoolean(world);out.writeByte(paper.ordinal)
            fun field(t:String){val b=t.toByteArray(Charsets.UTF_8);out.writeInt(b.size);out.write(b)}
            field(title);field(text);out.writeInt(strokes.size)
            strokes.forEach{val b=InkStrokeCodec.encode(it);require(body.size().toLong()+b.size+36<=MAX_BYTES);out.writeInt(b.size);out.write(b)}
            if(objects.isNotEmpty()||withSource||authoring!=null){val encoded=PageObjectCodec.encode(objects);out.writeInt(encoded.size);out.write(encoded)}
            if(imageSources.isNotEmpty()||authoring!=null)out.writeBoolean(withSource)
            if(withSource){val src=checkNotNull(source);require(body.size().toLong()+src.document.size+48<=MAX_BYTES){"CONTENT_SIZE_LIMIT_USE_FULL_BACKUP"};val bytes=src.document.bytes();out.writeInt(src.document.pages);out.writeInt(src.page);out.writeInt(bytes.size);out.write(bytes)}
            if(imageSources.isNotEmpty()||authoring!=null){
                out.writeInt(imageSources.size)
                imageSources.forEach{source->val bytes=source.bytes();require(body.size().toLong()+bytes.size+36<=MAX_BYTES);out.writeInt(bytes.size);out.write(bytes)}
            }
            authoring?.let{val bytes=PageAuthoringCodec.encode(it);out.writeInt(bytes.size);out.write(bytes)}
        };return body.finish(MAX_BYTES)
    }
    companion object {
        const val MAX_BYTES=64_000_000
        fun decode(bytes:ByteArray):InkPageFile=decodeRange(bytes,0,bytes.size)
        internal fun decodeRange(bytes:ByteArray,offset:Int,length:Int):InkPageFile {
            require(length in 48..MAX_BYTES)
            return ChecksummedBuffer.checkedInput(bytes,offset,length).use{input->
                val magic=input.readInt();require(magic in listOf(0x49575031,0x49575032,0x49575033,0x49575034,0x49575035,0x49575036,0x49575037,0x49575038)){"Unknown page copy version"}
                val world=if(magic!=0x49575031)input.readBoolean()else false
                val paper=if(magic!=0x49575031)PaperStyle.entries.getOrNull(input.readUnsignedByte())?:error("Unknown paper")else PaperStyle.RULED
                fun field(limit:Int):String{val n=input.readInt();require(n in 0..limit&&n<=input.available());val b=ByteArray(n);input.readFully(b);val s=b.toString(Charsets.UTF_8);require(s.toByteArray(Charsets.UTF_8).contentEquals(b));return s}
                val title=field(480);val text=field(400_000);val count=input.readInt();require(count in 0..InkLimits.MAX_STROKES);var points=0
                val strokes=List(count){val n=input.readInt();require(n in 1..InkLimits.MAX_STROKE_BYTES&&n<=input.available());val b=ByteArray(n);input.readFully(b);InkStrokeCodec.decode(b).also{points+=it.samples.size;require(points<=InkLimits.MAX_PAGE_POINTS)}}
                val objects=if(magic>=0x49575035){val n=input.readInt();require(n in 8..PageObjectCodec.MAX_BYTES&&n<=input.available());val b=ByteArray(n);input.readFully(b);PageObjectCodec.decode(b)}else emptyList()
                val source=if(magic==0x49575036||magic>=0x49575037&&input.readBoolean()){val pages=input.readInt();val page=input.readInt();val size=input.readInt();require(size in 8..PdfDocumentSource.ARRAY_MAX_BYTES&&size<=input.available());val pdf=ByteArray(size);input.readFully(pdf);PdfPageSource(PdfDocumentSource(pdf,pages),page)}else null
                val images=if(magic>=0x49575037){val count=input.readInt();require(count in (if(magic==0x49575038)0 else 1)..PageObjectCodec.MAX_OBJECTS);List(count){val size=input.readInt();require(size in 1..ImageSource.MAX_BYTES&&size<=input.available());ImageSource(ByteArray(size).also(input::readFully))}}else emptyList()
                val authoring=if(magic==0x49575038){val size=input.readInt();require(size in 8..PageAuthoringCodec.MAX_BYTES&&size<=input.available());PageAuthoringCodec.decode(ByteArray(size).also(input::readFully))}else null
                require(input.available()==0);InkPageFile(title,text,strokes,world,paper,objects,source,images,authoring)
            }
        }
    }
}
