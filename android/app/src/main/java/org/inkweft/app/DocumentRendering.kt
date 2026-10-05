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
internal class DocumentRendering(context:Context,private val repo:DocumentRepository,root:File=File(context.cacheDir,"document-render")) {
    private val folder=root.apply{mkdirs()}
    private data class Local(val file:File,val page:Int)
    private val cache=mutableMapOf<String,Local?>()
    private val lock=Mutex()
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
        lock.withLock {
            ensureActive()
            val source=repo.read(pageId,sources)?:return@withLock null
            // ponytail: keep one PDF in memory; broaden caching only if alternating documents needs it.
            sources.entries.removeAll{it.value!==source.document}
            val document=com.artifex.mupdf.fitz.Document.openDocument(source.document.bytes(),"application/pdf")
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
            }finally{document.destroy()}
        }
    }
    suspend fun text(pageId:String,bounds:CanvasBounds):String=withContext(Dispatchers.IO){lock.withLock {
        val source=repo.read(pageId)?:return@withLock ""
        val document=com.artifex.mupdf.fitz.Document.openDocument(source.document.bytes(),"application/pdf")
        try{val page=document.loadPage(source.page)
            try{val r=page.bounds;val fit=min(1000f/(r.x1-r.x0),1414f/(r.y1-r.y0));val left=(1000-(r.x1-r.x0)*fit)/2;val top=(1414-(r.y1-r.y0)*fit)/2
                val structured=page.toStructuredText()
                try{structured.blocks.flatMap{it.lines.toList()}.mapNotNull{line->
                    val box=paperBounds(r,line.bbox)
                    if(!box.intersects(bounds))null else buildString{line.chars.forEach{char->
                        val x=left+(char.origin.x-r.x0)*fit
                        if(x>=bounds.left&&x<=bounds.right)appendCodePoint(char.c)
                    }}.takeIf{it.isNotBlank()}
                }.joinToString("\n").take(20000)}finally{structured.destroy()}
            }finally{page.destroy()}
        }finally{document.destroy()}
    }}
    suspend fun render(pageId:String,bounds:CanvasBounds,pixels:Int):DocumentTile?=withContext(Dispatchers.IO){lock.withLock {
        ensureActive()
        cache[pageId]?.let{if(!it.file.isFile)cache.remove(pageId)}
        val local=if(cache.containsKey(pageId))cache[pageId]else {
            repo.read(pageId)?.let{s->
                val file=File(folder,s.document.sha256+".pdf")
                if(!file.isFile||file.length()!=s.document.size.toLong()||ContentTransfer.hash(file.readBytes())!=s.document.sha256)file.writeBytes(s.document.bytes())
                Local(file,s.page)
            }.also{cache[pageId]=it}
        }?:return@withLock null
        val w=bounds.right-bounds.left;val h=bounds.bottom-bounds.top
        require(w>0&&h>0&&pixels>0)
        // Bounds are already clipped to the visible window; keep one raster pixel per screen pixel.
        val scale=pixels/max(w,h)
        RenderResources.admit((ceil(w*scale).toLong().coerceAtLeast(1))*(ceil(h*scale).toLong().coerceAtLeast(1))*4)
        val bitmap=Bitmap.createBitmap(ceil(w*scale).toInt().coerceAtLeast(1),ceil(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        try {
            RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"pdf",pageId,RenderResources.Role.IN_FLIGHT)
            bitmap.eraseColor(Color.WHITE)
            ParcelFileDescriptor.open(local.file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->pdf.openPage(local.page).use{page->
                val fit=min(1000f/page.width,1414f/page.height)
                val left=(1000-page.width*fit)/2;val top=(1414-page.height*fit)/2
                val matrix=Matrix().apply{setScale((fit*scale).toFloat(),(fit*scale).toFloat());postTranslate(((left-bounds.left)*scale).toFloat(),((top-bounds.top)*scale).toFloat())}
                page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }}}
            ensureActive();RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"pdf",pageId,RenderResources.Role.ACTIVE);DocumentTile(bitmap,bounds)
        }catch(t:Throwable){RenderResources.release(bitmap,pageId);bitmap.recycle();throw t}
    }}
}
