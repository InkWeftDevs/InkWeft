// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.UUID

internal class DocumentImportException(val explanation:String):Exception(explanation)
internal object DocumentImports {
    private val conversionLock=Mutex()
    const val HELP="直接导入 PDF、无加密 EPUB 和 PalmDOC MOBI。PDF 保留原版页面；电子书按固定页面排版后批注。DOC/DOCX、PPT/PPTX 请用 Office 或 LibreOffice 导出 PDF；AZW3/HUFF MOBI 用 calibre 转 PDF；CAJ 用本机 CAJ 阅读器或 caj2pdf 转 PDF，再导入。PDF 最多 512 MB、500 页；EPUB/MOBI 源文件最多 32 MB；不支持密码和 DRM 文件。"
    suspend fun read(context:Context,uri:Uri):ContentTransfer.Prepared {
        var returned:ContentTransfer.Prepared?=null
        try{return withContext(Dispatchers.IO){
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}.orEmpty()
        val input=context.contentResolver.openInputStream(uri)?:error("DOCUMENT_UNREADABLE")
        var stage:File?=null
        try{input.use{stream->
            val header=ByteArray(5);java.io.DataInputStream(stream).readFully(header)
            val whole=java.io.SequenceInputStream(header.inputStream(),stream)
            if(runCatching{ContentTransfer.kind(header)}.isSuccess)return@withContext ContentTransfer.read(whole){ensureActive()}
            val pdfInput=header.contentEquals("%PDF-".toByteArray())
            val extension=name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)
            if(!pdfInput&&extension !in listOf("epub","mobi"))throw DocumentImportException(HELP)
            val folder=File(context.cacheDir,"document-import-${UUID.randomUUID()}").apply{check(mkdirs())};stage=folder
            val original=File(folder,"source."+if(pdfInput)"pdf"else extension)
            val limit=if(pdfInput)PdfDocumentSource.MAX_BYTES else PdfDocumentSource.ARRAY_MAX_BYTES
            val digest=java.security.MessageDigest.getInstance("SHA-256");var seen=0L
            original.outputStream().use{out->val buffer=ByteArray(64*1024)
                while(true){ensureActive();var n=whole.read(buffer);if(n<0)break
                    if(n==0){val one=whole.read();if(one<0)break;buffer[0]=one.toByte();n=1}
                    require(n<=limit.toLong()-seen){"DOCUMENT_SIZE_LIMIT"}
                    require(folder.usableSpace>=32L*1024*1024+n){"ORIGINAL_LOW_SPACE"}
                    out.write(buffer,0,n);digest.update(buffer,0,n);seen+=n
                }
            }
            val originalHash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
            require(original.length()==seen){"ORIGINAL_LENGTH_CHANGED"}
            val pdf=if(pdfInput)original else convertFile(context,original.readBytes(),extension,folder)
            val count=try{ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->
                require(renderer.pageCount in 1..500);repeat(renderer.pageCount){i->ensureActive();renderer.openPage(i).use{require(it.width>0&&it.height>0)}};renderer.pageCount
            }}}catch(c:CancellationException){throw c}catch(_:Exception){throw DocumentImportException("PDF 无法完整读取，可能已加密、损坏或超过 500 页。请先用本机阅读器检查。")}
            val title=name.substringBeforeLast('.',name).filter{!it.isISOControl()}.trim().take(120).ifBlank{"导入文档"}
            val source=if(pdfInput)PdfDocumentSource.fromStream(OriginalBytes.stream(seen.toInt(),originalHash){require(original.length()==seen){"ORIGINAL_LENGTH_CHANGED"};original.inputStream()},count)else PdfDocumentSource.fromFile(pdf,count){ensureActive()}
            val result=ContentTransfer.document(title,source,originalHash){folder.deleteRecursively()}
            stage=null;returned=result;result
        }}finally{stage?.deleteRecursively()}
        }}catch(t:Throwable){returned?.close();throw t}
    }
    /** Compatibility helper for small conversion fixtures; direct PDF imports always use files. */
    internal suspend fun convert(context:Context,bytes:ByteArray,extension:String):ByteArray {
        val folder=File(context.cacheDir,"ebook-convert-${UUID.randomUUID()}").apply{check(mkdirs())}
        try{val file=convertFile(context,bytes,extension,folder);require(file.length()<=PdfDocumentSource.ARRAY_MAX_BYTES);return file.readBytes()}
        finally{folder.deleteRecursively()}
    }
    private suspend fun convertFile(context:Context,bytes:ByteArray,extension:String,directory:File):File = conversionLock.withLock {
        require(extension in listOf("epub","mobi"))
        if(extension=="epub"){
            var expanded=0L;var entries=0
            ZipInputStream(bytes.inputStream()).use{zip->val buffer=ByteArray(8192);while(zip.nextEntry!=null){require(++entries<=5000);while(true){currentCoroutineContext().ensureActive();val n=zip.read(buffer);if(n<0)break;expanded+=n;require(expanded<=128_000_000)};zip.closeEntry()}}
        }else{
            // Reject encryption and KF8/HUFF encodings before invoking the native MOBI parser.
            require(bytes.size>86)
            fun u16(i:Int)=((bytes[i].toInt()and 255)shl 8)or(bytes[i+1].toInt()and 255)
            fun u32(i:Int)=((bytes[i].toInt()and 255).toLong()shl 24)or((bytes[i+1].toInt()and 255).toLong()shl 16)or((bytes[i+2].toInt()and 255).toLong()shl 8)or(bytes[i+3].toInt()and 255).toLong()
            val record=u32(78).toInt();require(record>=0&&record+40<=bytes.size)
            if(u16(record) !in listOf(1,2)||u16(record+12)!=0||u32(record+36)>=8)throw DocumentImportException("此 MOBI 的压缩、加密或 KF8 版本暂不能直接导入。无 DRM 文件可先用 calibre 转 PDF。")
        }
        // Embed a default serif face in a temporary EPUB. Keep document CSS and
        // embedded fonts; avoid device-specific full CJK collections for defaults.
        val source=if(extension=="epub")File.createTempFile("ebook-font-",".epub",context.cacheDir)else null
        try{
        if(source!=null){
            val fontName="inkweft-${UUID.randomUUID()}.ttf"
            ZipOutputStream(source.outputStream()).use{out->
                ZipInputStream(bytes.inputStream()).use{input->
                    var entry=input.nextEntry
                    while(entry!=null){
                        currentCoroutineContext().ensureActive()
                        out.putNextEntry(ZipEntry(entry.name));input.copyTo(out);out.closeEntry();entry=input.nextEntry
                    }
                }
                out.putNextEntry(ZipEntry(fontName));context.assets.open("fonts/LXGWWenKaiLite-Regular.ttf").use{it.copyTo(out)};out.closeEntry()
            }
            com.artifex.mupdf.fitz.Context.setUserCSS("@font-face { font-family: serif; src: url('/$fontName'); }")
        }
        val document=if(source!=null)Document.openDocument(source.absolutePath)else Document.openDocument(bytes,extension)
        val output=File.createTempFile("ebook-fixed-",".pdf",directory)
        try {
            if(document.needsPassword())throw DocumentImportException("文档已加密，请先取得可阅读的无加密副本。")
            if(document.isReflowable)document.layout(595f,842f,14f)
            val count=document.countPages();require(count in 1..500)
            // Default PDF output embeds uncompressed CJK font streams. Flate keeps
            // text and vector geometry intact while avoiding multi-megabyte bloat.
            val writer=DocumentWriter(output.absolutePath,"pdf","compress,compress-fonts,compress-images,garbage=deduplicate")
            try{repeat(count){i->currentCoroutineContext().ensureActive();val page=document.loadPage(i)
                try{val device=writer.beginPage(page.bounds);try{page.run(device,Matrix())}finally{writer.endPage();device.destroy()}}finally{page.destroy()}
                require(output.length()<=PdfDocumentSource.MAX_BYTES)
            };writer.close()}finally{writer.destroy()}
            currentCoroutineContext().ensureActive()
            // Keep only glyphs actually used by this fixed-layout ebook. Compressing
            // a complete Android fallback CJK font alone still costs about 19 MB.
            val compact=File.createTempFile("ebook-subset-",".pdf",directory)
            try{
                val pdf=Document.openDocument(output.absolutePath) as PDFDocument
                try{pdf.subsetFonts();currentCoroutineContext().ensureActive();pdf.save(compact.absolutePath,"compress,compress-fonts,compress-images,garbage=deduplicate")}finally{pdf.destroy()}
                require(compact.length() in 8..PdfDocumentSource.MAX_BYTES.toLong());return@withLock compact
            }catch(t:Throwable){compact.delete();throw t}
        }finally{document.destroy();output.delete()}
        }finally{com.artifex.mupdf.fitz.Context.setUserCSS("");source?.delete()}
    }
}
