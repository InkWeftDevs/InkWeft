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
    suspend fun render(pageId:String,bounds:CanvasBounds,pixels:Int):DocumentTile?=withContext(Dispatchers.IO){lock.withLock {
        ensureActive()
        val local=if(cache.containsKey(pageId))cache[pageId]else {
            repo.read(pageId)?.let{s->
                val file=File(folder,s.document.sha256+".pdf")
                if(!file.isFile||file.length()!=s.document.size.toLong()||ContentTransfer.hash(file.readBytes())!=s.document.sha256)file.writeBytes(s.document.bytes())
                Local(file,s.page)
            }.also{cache[pageId]=it}
        }?:return@withLock null
        val w=bounds.right-bounds.left;val h=bounds.bottom-bounds.top
        val scale=pixels.coerceIn(128,2048)/max(w,h)
        val bitmap=Bitmap.createBitmap(ceil(w*scale).toInt().coerceAtLeast(1),ceil(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            ParcelFileDescriptor.open(local.file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{pdf->pdf.openPage(local.page).use{page->
                val fit=min(1000f/page.width,1414f/page.height)
                val left=(1000-page.width*fit)/2;val top=(1414-page.height*fit)/2
                val matrix=Matrix().apply{setScale((fit*scale).toFloat(),(fit*scale).toFloat());postTranslate(((left-bounds.left)*scale).toFloat(),((top-bounds.top)*scale).toFloat())}
                page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }}}
            ensureActive();DocumentTile(bitmap,bounds)
        }catch(t:Throwable){bitmap.recycle();throw t}
    }}
}
