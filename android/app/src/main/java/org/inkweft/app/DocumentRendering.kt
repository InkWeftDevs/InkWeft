// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.DocumentRepository
import java.io.File
import kotlin.math.*

internal data class DocumentTile(val bitmap:Bitmap,val bounds:CanvasBounds)
internal class DocumentRendering(context:Context,private val repo:DocumentRepository) {
    private val folder=File(context.cacheDir,"document-render").apply{mkdirs()}
    private data class Local(val file:File,val page:Int)
    private val cache=mutableMapOf<String,Local?>()
    private val lock=Mutex()
    suspend fun text(pageId:String,bounds:CanvasBounds):String=withContext(Dispatchers.IO){lock.withLock {
        val source=repo.read(pageId)?:return@withLock ""
        val document=com.artifex.mupdf.fitz.Document.openDocument(source.document.bytes(),"application/pdf")
        try{val page=document.loadPage(source.page)
            try{val r=page.bounds;val fit=min(1000f/(r.x1-r.x0),1414f/(r.y1-r.y0));val left=(1000-(r.x1-r.x0)*fit)/2;val top=(1414-(r.y1-r.y0)*fit)/2
                val structured=page.toStructuredText()
                try{structured.blocks.flatMap{it.lines.toList()}.mapNotNull{line->
                    val b=line.bbox;val box=CanvasBounds((left+(b.x0-r.x0)*fit).toDouble(),(top+(b.y0-r.y0)*fit).toDouble(),(left+(b.x1-r.x0)*fit).toDouble(),(top+(b.y1-r.y0)*fit).toDouble())
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
        val scale=pixels.coerceIn(128,2048)/max(w,h)
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
