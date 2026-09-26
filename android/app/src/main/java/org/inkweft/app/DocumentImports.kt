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
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

internal class DocumentImportException(val explanation:String):Exception(explanation)
internal object DocumentImports {
    const val HELP="直接导入 PDF、无加密 EPUB 和 PalmDOC MOBI。PDF 保留原版页面；电子书按固定页面排版后批注。DOC/DOCX、PPT/PPTX 请用 Office 或 LibreOffice 导出 PDF；AZW3/HUFF MOBI 用 calibre 转 PDF；CAJ 用本机 CAJ 阅读器或 caj2pdf 转 PDF，再导入。单个源文件最多 32 MB、500 页；不支持密码和 DRM 文件。"
    suspend fun read(context:Context,uri:Uri):ContentTransfer.Prepared=withContext(Dispatchers.IO){
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}.orEmpty()
        val bytes=context.contentResolver.openInputStream(uri)?.use{input->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){ensureActive();val n=input.read(buffer);if(n<0)break;if(n==0)continue
                require(output.size().toLong()+n<=NotebookFile.MAX_BYTES){"DOCUMENT_SIZE_LIMIT"};output.write(buffer,0,n)};output.toByteArray()
        }?:error("DOCUMENT_UNREADABLE")
        if(runCatching{ContentTransfer.kind(bytes)}.isSuccess)return@withContext ContentTransfer.decode(bytes){ensureActive()}
        if(bytes.size>PdfDocumentSource.MAX_BYTES)throw DocumentImportException("源文档超过 32 MB，请拆分后导入。")
        val extension=name.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT)
        val pdf=if(bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray()))bytes
            else if(extension in listOf("epub","mobi"))convert(context,bytes,extension)
            else throw DocumentImportException(HELP)
        val file=File.createTempFile("document-inspect-",".pdf",context.cacheDir)
        val count=try{file.writeBytes(pdf);ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{renderer->
            require(renderer.pageCount in 1..500);repeat(renderer.pageCount){i->ensureActive();renderer.openPage(i).use{require(it.width>0&&it.height>0)}};renderer.pageCount
        }}}catch(c:CancellationException){throw c}catch(_:Exception){throw DocumentImportException("PDF 无法完整读取，可能已加密、损坏或超过 500 页。请先用本机阅读器检查。")}finally{file.delete()}
        val title=name.substringBeforeLast('.',name).filter{!it.isISOControl()}.trim().take(120).ifBlank{"导入文档"}
        ContentTransfer.document(title,PdfDocumentSource(pdf,count),ContentTransfer.hash(bytes))
    }
    internal suspend fun convert(context:Context,bytes:ByteArray,extension:String):ByteArray {
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
        val document=Document.openDocument(bytes,extension)
        val output=File.createTempFile("ebook-fixed-",".pdf",context.cacheDir)
        try {
            if(document.needsPassword())throw DocumentImportException("文档已加密，请先取得可阅读的无加密副本。")
            if(document.isReflowable)document.layout(595f,842f,14f)
            val count=document.countPages();require(count in 1..500)
            val writer=DocumentWriter(output.absolutePath,"pdf","")
            try{repeat(count){i->currentCoroutineContext().ensureActive();val page=document.loadPage(i)
                try{val device=writer.beginPage(page.bounds);try{page.run(device,Matrix())}finally{writer.endPage();device.destroy()}}finally{page.destroy()}
                require(output.length()<=PdfDocumentSource.MAX_BYTES)
            };writer.close()}finally{writer.destroy()}
            require(output.length() in 8..PdfDocumentSource.MAX_BYTES.toLong());return output.readBytes()
        }finally{document.destroy();output.delete()}
    }
}
