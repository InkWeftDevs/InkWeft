// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.Locale

/** Opt-in host probe; never runs with the normal unit suite. Not an Android performance result. */
object StorageScaleProbe {
    @JvmStatic fun main(args:Array<String>) {
        val root=File(args.single()).apply{mkdirs()};val start=System.nanoTime()
        val pdf=File(root,"synthetic-300MB.pdf");syntheticPdf(pdf,300_000_000)
        val source=PdfDocumentSource.fromFile(pdf,2);var copied=0L;var maxWrite=0
        source.copyTo(object:OutputStream(){override fun write(b:Int){copied++}
            override fun write(b:ByteArray,off:Int,len:Int){copied+=len;maxWrite=maxOf(maxWrite,len)}})
        check(copied==pdf.length()&&maxWrite<=65_536)
        try{source.bytes();error("Large original became an array")}catch(e:IllegalArgumentException){check(e.message=="CONTENT_SIZE_LIMIT_USE_FULL_BACKUP")}
        val schema=listOf(LibraryArchive.Table("sample",listOf(LibraryArchive.Column("position",'I'),LibraryArchive.Column("payload",'B')),listOf("position")))
        val payload=ByteArray(512_000){(it%251).toByte()};val count=2160L
        val archive=File(root,"synthetic-over-1GiB.iwbackup")
        val written=archive.outputStream().buffered().use{LibraryArchive.write(it,schema,object:LibraryArchive.Rows {
            override fun count(table:Int)=count
            override fun visit(table:Int,consume:(List<Any?>)->Unit){repeat(count.toInt()){consume(listOf(it.toLong(),payload))}}
        },0)}
        var seen=0L
        val read=archive.inputStream().buffered().use{LibraryArchive.read(it,schema,{_,row->
            check(row[0]==seen++);check((row[1] as ByteArray).contentEquals(payload))
        })}
        check(seen==count&&written==read&&written.bytes>LibraryArchive.LEGACY_MAX_BYTES)
        val result="""{"status":"PASS","heap_max_bytes":${Runtime.getRuntime().maxMemory()},"pdf_bytes":${pdf.length()},"pdf_sha256":"${source.sha256}","max_copy_write_bytes":$maxWrite,"archive_bytes":${written.bytes},"archive_rows":$seen,"archive_sha256":"${written.sha256}","elapsed_ms":${(System.nanoTime()-start)/1_000_000},"android_execution":"NOT_RUN"}"""
        File(root,"result.json").writeText(result+"\n");println(result)
    }
    /** Real two-page PDF with an unused large stream, exact xref offsets and standard Helvetica text. */
    internal fun syntheticPdf(file:File,size:Int) {
        val offsets=mutableListOf<Long>();var position=0L
        file.outputStream().buffered().use{out->
            fun text(s:String){val b=s.toByteArray(Charsets.US_ASCII);out.write(b);position+=b.size}
            fun obj(body:String){offsets+=position;text("${offsets.size} 0 obj\n$body\nendobj\n")}
            text("%PDF-1.7\n")
            obj("<< /Type /Catalog /Pages 2 0 R >>")
            obj("<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
            repeat(2){obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>")}
            obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            val content="BT /F1 18 Tf 72 700 Td (InkWeft streamed original) Tj ET\n"
            obj("<< /Length ${content.length} >>\nstream\n${content}endstream")
            offsets+=position;val padding=size-4096
            text("7 0 obj\n<< /Length $padding >>\nstream\n")
            val block=ByteArray(65_536);var left=padding
            while(left>0){val n=minOf(left,block.size);out.write(block,0,n);position+=n;left-=n}
            text("\nendstream\nendobj\n");val xref=position
            text("xref\n0 8\n0000000000 65535 f \n")
            offsets.forEach{text(String.format(Locale.ROOT,"%010d 00000 n \n",it))}
            // A trailing comment keeps the synthetic fixture exactly at the requested byte count.
            val tail="\ntrailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n"
            val fill=size-position-tail.length;check(fill>=2)
            text("%"+"x".repeat(fill.toInt()-2)+"\n");text(tail)
        }
        check(file.length()==size.toLong())
    }
}
