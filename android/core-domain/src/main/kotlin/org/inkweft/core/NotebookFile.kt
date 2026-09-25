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
            d.writeInt(MAGIC);for(t in listOf(title,text)){val b=t.toByteArray(Charsets.UTF_8);d.writeInt(b.size);d.write(b)}
            d.writeInt(pages.size)
            for(p in pages){val b=p.encode();require(body.size().toLong()+b.size+36<=MAX_BYTES);d.writeInt(b.size);d.write(b)}
        }
        val data=body.toByteArray();return data+MessageDigest.getInstance("SHA-256").digest(data)
    }
    companion object {
        const val MAX_BYTES=32_000_000
        private const val MAGIC=0x49574231
        fun isBook(bytes:ByteArray)=bytes.size>=4&&DataInputStream(ByteArrayInputStream(bytes)).readInt()==MAGIC
        fun decode(bytes:ByteArray):NotebookFile {
            require(bytes.size in 52..MAX_BYTES);val body=bytes.copyOfRange(0,bytes.size-32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),bytes.copyOfRange(bytes.size-32,bytes.size)))
            return DataInputStream(ByteArrayInputStream(body)).use{d->
                require(d.readInt()==MAGIC)
                fun field(max:Int):String{val n=d.readInt();require(n in 0..max&&n<=d.available());val b=ByteArray(n);d.readFully(b);val s=b.toString(Charsets.UTF_8);require(s.toByteArray(Charsets.UTF_8).contentEquals(b));return s}
                val title=field(480);val text=field(400_000);val n=d.readInt();require(n in 1..500)
                val pages=List(n){val size=d.readInt();require(size in 1..InkPageFile.MAX_BYTES&&size<=d.available());val p=ByteArray(size);d.readFully(p);InkPageFile.decode(p)}
                require(d.available()==0);NotebookFile(title,text,pages)
            }
        }
    }
}
