// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest
import java.util.Collections

/** A book's visible content copy. Includes all pages and effective erase masks;
 * does not claim to include history, credentials, classification or revisions. */
class NotebookFile(val title:String,val text:String,pages:List<InkPageFile>) {
    val pages:List<InkPageFile> = Collections.unmodifiableList(ArrayList(pages))
    init {require(title.isNotBlank()&&title.length<=120&&text.length<=100_000);require(pages.size in 1..500&&pages.none{it.world})}
    fun encode():ByteArray {
        val body=ByteArrayOutputStream()
        DataOutputStream(body).use{d->
            val sources=pages.mapNotNull{it.source?.document}.distinctBy{it.sha256}
            d.writeInt(if(sources.isEmpty())MAGIC else MAGIC2);for(t in listOf(title,text)){val b=t.toByteArray(Charsets.UTF_8);d.writeInt(b.size);d.write(b)}
            if(sources.isNotEmpty()){d.writeInt(sources.size);for(source in sources){val bytes=source.bytes();require(body.size().toLong()+bytes.size<MAX_BYTES);d.writeInt(source.pages);d.writeInt(bytes.size);d.write(bytes)}}
            d.writeInt(pages.size)
            for(p in pages){val b=p.encode(false);require(body.size().toLong()+b.size+36<=MAX_BYTES);d.writeInt(b.size);d.write(b);if(sources.isNotEmpty()){d.writeInt(p.source?.let{s->sources.indexOfFirst{it.sha256==s.document.sha256}}?:-1);d.writeInt(p.source?.page?:0)}}
        }
        val data=body.toByteArray();return data+MessageDigest.getInstance("SHA-256").digest(data)
    }
    companion object {
        const val MAX_BYTES=64_000_000
        private const val MAGIC=0x49574231
        private const val MAGIC2=0x49574232
        fun isBook(bytes:ByteArray)=bytes.size>=4&&DataInputStream(ByteArrayInputStream(bytes)).readInt() in listOf(MAGIC,MAGIC2)
        fun decode(bytes:ByteArray):NotebookFile {
            require(bytes.size in 52..MAX_BYTES);val body=bytes.copyOfRange(0,bytes.size-32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),bytes.copyOfRange(bytes.size-32,bytes.size)))
            return DataInputStream(ByteArrayInputStream(body)).use{d->
                val magic=d.readInt();require(magic in listOf(MAGIC,MAGIC2))
                fun field(max:Int):String{val n=d.readInt();require(n in 0..max&&n<=d.available());val b=ByteArray(n);d.readFully(b);val s=b.toString(Charsets.UTF_8);require(s.toByteArray(Charsets.UTF_8).contentEquals(b));return s}
                val title=field(480);val text=field(400_000)
                val sources=if(magic==MAGIC2){val count=d.readInt();require(count in 1..500);List(count){val pages=d.readInt();val size=d.readInt();require(size in 8..PdfDocumentSource.MAX_BYTES&&size<=d.available());val pdf=ByteArray(size);d.readFully(pdf);PdfDocumentSource(pdf,pages)}}else emptyList()
                val n=d.readInt();require(n in 1..500)
                val pages=List(n){val size=d.readInt();require(size in 1..InkPageFile.MAX_BYTES&&size<=d.available());val p=ByteArray(size);d.readFully(p);val page=InkPageFile.decode(p);if(magic==MAGIC2){val source=d.readInt();val index=d.readInt();require(source in -1 until sources.size);val ref=if(source<0){require(index==0);null}else PdfPageSource(sources[source],index);InkPageFile(page.title,page.text,page.strokes,page.world,page.paper,page.objects,ref)}else page}
                require(d.available()==0);NotebookFile(title,text,pages)
            }
        }
    }
}
