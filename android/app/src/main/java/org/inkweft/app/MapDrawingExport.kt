// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.File
import java.io.OutputStream
import java.util.UUID

internal enum class MapDrawingFormat(val mime:String,val extension:String){PNG("image/png","png"),PDF("application/pdf","pdf")}
internal data class MapDrawingSnapshot(val title:String,val nodes:List<MapSceneNode>,val layout:String,val groups:List<KnowledgeData.MapSummaryGroup>,val presentations:Map<String,KnowledgeData.CardPresentation>)

/** Text/cards and manual summaries; source pixels and author overlays stay in the full backup. */
internal object MapDrawingExport {
    fun write(snapshot:MapDrawingSnapshot,format:MapDrawingFormat,output:OutputStream){
        require(snapshot.nodes.isNotEmpty()&&snapshot.nodes.size<=StudyGraph.MAX_NODES&&snapshot.layout in MapLayouts.supported)
        val layouts=snapshot.nodes.associate{it.id to MapNodeMetrics.measure(it.title,it.body,structural=it.cardId==null)}
        val groups=MapSummaryPainter.measure(snapshot.nodes,layouts,snapshot.groups,layout=snapshot.layout)
        val boxes=snapshot.nodes.map{n->val size=layouts.getValue(n.id);RectF(n.x.toFloat(),n.y.toFloat(),n.x.toFloat()+size.width,n.y.toFloat()+size.height)}+groups.map{it.bounds}
        val bounds=RectF(boxes.first());boxes.drop(1).forEach(bounds::union)
        val fit=MapExportSizing.fit(CanvasBounds(bounds.left.toDouble(),bounds.top.toDouble(),bounds.right.toDouble(),bounds.bottom.toDouble()))
        fun draw(canvas:Canvas){
            canvas.drawColor(Color.WHITE)
            canvas.withTranslation(fit.translateX.toFloat(),fit.translateY.toFloat()){
                scale(fit.scale.toFloat(),fit.scale.toFloat())
                MapSummaryPainter.draw(this,groups)
                MapScenePainter.draw(this,snapshot.nodes,presentations=snapshot.presentations,layout=snapshot.layout)
            }
        }
        when(format){
            MapDrawingFormat.PNG->{val bitmap=createBitmap(fit.width,fit.height,Bitmap.Config.ARGB_8888)
                try{draw(Canvas(bitmap));check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))}finally{bitmap.recycle()}}
            MapDrawingFormat.PDF->{val document=PdfDocument()
                try{val page=document.startPage(PdfDocument.PageInfo.Builder(fit.width,fit.height,1).create());draw(page.canvas);document.finishPage(page);document.writeTo(output)}finally{document.close()}}
        }
    }
}

internal class MapDrawingExportControl(val busy:()->Boolean,val start:(MapDrawingSnapshot,MapDrawingFormat)->Unit)

/** Register once at workspace scope, so closing a menu or rotating the picker cannot lose its file. */
@Composable internal fun rememberMapDrawingExport(message:(String)->Unit):MapDrawingExportControl{
    val context=LocalContext.current;val scope=rememberCoroutineScope();val latestMessage by rememberUpdatedState(message)
    var pendingPath by rememberSaveable{mutableStateOf<String?>(null)}
    var writing by remember{mutableStateOf(false)}
    fun pendingFile():File?=pendingPath?.let{File(it)}?.takeIf{it.parentFile?.canonicalFile==context.cacheDir.canonicalFile&&it.name.startsWith("inkweft-map-export-")}
    val finish:(android.net.Uri?)->Unit={uri->
        val file=pendingFile();pendingPath=null
        if(file!=null){writing=true;scope.launch{try{
            if(uri!=null){withContext(Dispatchers.IO){check(file.isFile);checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{out->file.inputStream().use{it.copyTo(out)}}};latestMessage("导图已导出。完整编辑内容及原迹请保留资料库备份。")}
        }catch(c:CancellationException){throw c}catch(_:Exception){latestMessage("导出未确认，请重新导出；本机笔记已保留。")}finally{withContext(NonCancellable+Dispatchers.IO){file.delete()};writing=false}}}
        else if(uri!=null)latestMessage("导出预览已失效，请重新导出；本机笔记已保留。")
    }
    val png=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapDrawingFormat.PNG.mime),finish)
    val pdf=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MapDrawingFormat.PDF.mime),finish)
    return MapDrawingExportControl({writing||pendingPath!=null},{snapshot,format->
        if(!writing&&pendingPath==null){writing=true;scope.launch{
            val file=File(context.cacheDir,"inkweft-map-export-${UUID.randomUUID()}.${format.extension}")
            try{withContext(Dispatchers.Default){file.outputStream().use{MapDrawingExport.write(snapshot,format,it)}}
                pendingPath=file.absolutePath;writing=false
                val name=snapshot.title.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"),"_").take(80).ifBlank{"墨织导图"}+".${format.extension}"
                if(format==MapDrawingFormat.PNG)png.launch(name)else pdf.launch(name)
            }catch(c:CancellationException){pendingPath=null;withContext(NonCancellable+Dispatchers.IO){file.delete()};writing=false;throw c}
            catch(_:Exception){pendingPath=null;withContext(Dispatchers.IO){file.delete()};writing=false;latestMessage("暂时无法导出此图，请尝试缩小范围；本机内容已保留。")}
        }}
    })
}
