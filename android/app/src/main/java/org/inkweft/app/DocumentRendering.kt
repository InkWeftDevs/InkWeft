// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Rect as PdfRect
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.DocumentRepository
import java.io.File
import kotlin.math.*

internal data class DocumentTile(val bitmap:Bitmap,val bounds:CanvasBounds)
internal data class PdfTextHit(val pageId:String,val documentSha256:String,val sourcePage:Int,val bounds:CanvasBounds,val text:String)
internal data class PdfSearchPage(val hasText:Boolean,val hit:PdfTextHit?)
internal class DocumentRendering(context:Context,private val repo:DocumentRepository,root:File?=null):java.io.Closeable {
    private val files=root?.let{org.inkweft.data.OriginalFileCache(it)}
    private val renderLock=Mutex()
    private val textLock=Mutex()
    private var textDocument:com.artifex.mupdf.fitz.Document?=null
    private var textLease:org.inkweft.data.OriginalFileCache.Lease?=null
    private var textHash:String?=null
    private var pageRenderer:PdfRenderer?=null
    private var pageLease:org.inkweft.data.OriginalFileCache.Lease?=null
    private var pageHash:String?=null
    private val maintenance=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var closed=false
    private val unsubscribe=RenderResources.onTrim{trim()}
    private suspend fun file(source:PdfDocumentSource)=files?.acquire(source.size,source.sha256){out,active->source.copyTo(out,active)}?:repo.openFile(source)
    private fun releaseText(){try{textDocument?.destroy()}finally{textDocument=null;textLease?.close();textLease=null;textHash=null}}
    private suspend fun document(source:PdfDocumentSource):com.artifex.mupdf.fitz.Document {
        check(!closed){"DOCUMENT_RENDERING_CLOSED"}
        if(textHash==source.sha256)return checkNotNull(textDocument)
        releaseText()
        val lease=file(source)
        try{return com.artifex.mupdf.fitz.Document.openDocument(lease.file.absolutePath).also{textLease=lease;textDocument=it;textHash=source.sha256}}
        catch(t:Throwable){lease.close();throw t}
    }
    private fun releaseRenderer(){try{pageRenderer?.close()}finally{pageRenderer=null;pageLease?.close();pageLease=null;pageHash=null}}
    private suspend fun renderer(source:PdfDocumentSource):PdfRenderer {
        check(!closed){"DOCUMENT_RENDERING_CLOSED"}
        if(pageHash==source.sha256)return checkNotNull(pageRenderer)
        releaseRenderer();val lease=file(source)
        try{val fd=ParcelFileDescriptor.open(lease.file,ParcelFileDescriptor.MODE_READ_ONLY)
            try{return PdfRenderer(fd).also{pageRenderer=it;pageLease=lease;pageHash=source.sha256}}catch(t:Throwable){fd.close();throw t}
        }catch(t:Throwable){lease.close();throw t}
    }
    fun trim(){if(!closed)maintenance.launch{textLock.withLock{releaseText()};renderLock.withLock{releaseRenderer()};files?.trim();repo.trimFiles()}}
    suspend fun clearOriginalCache()=withContext(Dispatchers.IO){textLock.withLock{releaseText()};renderLock.withLock{releaseRenderer()};files?.trim();repo.trimFiles()}
    override fun close(){if(closed)return;closed=true;unsubscribe();maintenance.launch{try{textLock.withLock{releaseText()};renderLock.withLock{releaseRenderer()}}finally{maintenance.cancel()}}}

    private fun paperBounds(page:PdfRect,box:PdfRect):CanvasBounds {
        require(page.x1>page.x0&&page.y1>page.y0)
        val fit=min(1000f/(page.x1-page.x0),1414f/(page.y1-page.y0))
        val left=(1000-(page.x1-page.x0)*fit)/2;val top=(1414-(page.y1-page.y0)*fit)/2
        return CanvasBounds((left+(box.x0-page.x0)*fit).toDouble(),(top+(box.y0-page.y0)*fit).toDouble(),
            (left+(box.x1-page.x0)*fit).toDouble(),(top+(box.y1-page.y0)*fit).toDouble())
    }
    /** Match the complete native text layer and locate its first character span, without excerpt limits. */
    suspend fun search(pageId:String,query:String,sources:MutableMap<String,PdfDocumentSource> = mutableMapOf()):PdfSearchPage?=withContext(Dispatchers.IO){
        val term=query.trim();if(term.isEmpty())return@withContext null
        require(term.length<=256)
        textLock.withLock {
            ensureActive()
            val source=repo.read(pageId,sources)?:return@withLock null
            // Retain one seekable native document across sequential page searches.
            sources.entries.removeAll{it.value!==source.document}
            val document=document(source.document)
            try{val page=document.loadPage(source.page)
                try{val structured=page.toStructuredText()
                    try{
                        // Bundled fitz's search JNI aborts under CheckJNI; use its stable native character quads.
                        val lines=structured.blocks.flatMap{it.lines.toList()}
                        val text=buildString{lines.forEach{line->line.chars.forEach{appendCodePoint(it.c)};append('\n')}}
                        val start=text.indexOf(term,ignoreCase=true)
                        if(start<0)return@withLock PdfSearchPage(text.isNotBlank(),null)
                        var offset=0;var box:PdfRect?=null;var firstLine:PdfRect?=null
                        for(line in lines){
                            ensureActive()
                            for(char in line.chars){
                                val count=Character.charCount(char.c)
                                if(offset<start+term.length&&offset+count>start){
                                    val rect=char.quad.toRect()
                                    if(firstLine==null)firstLine=PdfRect(line.bbox)
                                    if(box==null)box=PdfRect(rect)else box.union(rect)
                                }
                                offset+=count
                            }
                            offset++
                            if(offset>=start+term.length)break
                        }
                        val matched=box?:return@withLock PdfSearchPage(text.isNotBlank(),null)
                        val location=if(matched.x0==matched.x1||matched.y0==matched.y1)firstLine?:matched else matched
                        val raw=paperBounds(page.bounds,location)
                        val bounds=CanvasBounds(raw.left.coerceIn(0.0,1000.0),raw.top.coerceIn(0.0,1414.0),raw.right.coerceIn(0.0,1000.0),raw.bottom.coerceIn(0.0,1414.0))
                        if(bounds.left==bounds.right||bounds.top==bounds.bottom)return@withLock PdfSearchPage(text.isNotBlank(),null)
                        val snippet=text.substring((start-35).coerceAtLeast(0),(start+term.length+100).coerceAtMost(text.length))
                        ensureActive()
                        PdfSearchPage(text.isNotBlank(),PdfTextHit(pageId,source.document.sha256,source.page,bounds,snippet.replace('\n',' ').take(400)))
                    }finally{structured.destroy()}
                }finally{page.destroy()}
            }finally{if(!currentCoroutineContext().isActive)releaseText()}
        }
    }
    suspend fun text(pageId:String,bounds:CanvasBounds):String=text(pageId,InkRegion(listOf(
        EraserPoint(bounds.left.toFloat(),bounds.top.toFloat()),EraserPoint(bounds.right.toFloat(),bounds.bottom.toFloat()))))
    suspend fun text(pageId:String,region:InkRegion):String=withContext(Dispatchers.IO){textLock.withLock {
        ensureActive()
        val source=repo.read(pageId)?:return@withLock ""
        val document=document(source.document)
        try{val page=document.loadPage(source.page)
            try{val r=page.bounds
                val structured=page.toStructuredText()
                try{structured.blocks.flatMap{it.lines.toList()}.mapNotNull{line->
                    ensureActive()
                    val box=paperBounds(r,line.bbox)
                    if(!box.intersects(region.bounds))null else buildString{line.chars.forEach{char->
                        val glyph=paperBounds(r,char.quad.toRect())
                        if(region.contains((glyph.left+glyph.right)/2,(glyph.top+glyph.bottom)/2))appendCodePoint(char.c)
                    }}.takeIf{it.isNotBlank()}
                }.joinToString("\n").take(20000)}finally{structured.destroy()}
            }finally{page.destroy()}
        }finally{if(!currentCoroutineContext().isActive)releaseText()}
    }}
    suspend fun render(pageId:String,bounds:CanvasBounds,pixels:Int):DocumentTile? {
        var produced:DocumentTile?=null
        try{return withContext(Dispatchers.IO){renderLock.withLock {
        ensureActive()
        val source=repo.read(pageId)?:return@withLock null
        val pdf=renderer(source.document)
        val w=bounds.right-bounds.left;val h=bounds.bottom-bounds.top
        require(w>0&&h>0&&pixels>0)
        // Bounds are already clipped to the visible window; keep one raster pixel per screen pixel.
        val scale=pixels/max(w,h)
        RenderResources.admit((ceil(w*scale).toLong().coerceAtLeast(1))*(ceil(h*scale).toLong().coerceAtLeast(1))*4)
        val bitmap=Bitmap.createBitmap(ceil(w*scale).toInt().coerceAtLeast(1),ceil(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        try {
            RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"pdf",pageId,RenderResources.Role.IN_FLIGHT)
            bitmap.eraseColor(Color.WHITE)
            pdf.openPage(source.page).use{page->
                val fit=min(1000f/page.width,1414f/page.height)
                val left=(1000-page.width*fit)/2;val top=(1414-page.height*fit)/2
                val matrix=Matrix().apply{setScale((fit*scale).toFloat(),(fit*scale).toFloat());postTranslate(((left-bounds.left)*scale).toFloat(),((top-bounds.top)*scale).toFloat())}
                page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            ensureActive();RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"pdf",pageId,RenderResources.Role.ACTIVE);DocumentTile(bitmap,bounds).also{produced=it}
        }catch(t:Throwable){RenderResources.release(bitmap,pageId);bitmap.recycle();throw t}
        }}}catch(t:Throwable){produced?.let{RenderResources.release(it.bitmap,pageId);it.bitmap.recycle()};throw t}
    }
}
