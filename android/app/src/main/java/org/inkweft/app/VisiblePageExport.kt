// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import kotlin.math.*

/** Deliberately rendered output. No hidden payload, source PDF bytes, originals/EXIF or author metadata is embedded. */
internal object VisiblePageExport {
    suspend fun prepare(app:InkWeftApplication,pageId:String,expanded:Boolean,pdf:Boolean):java.io.File {
        var generated:java.io.File?=null
        try{return withContext(Dispatchers.IO){
        val directory=java.io.File(app.cacheDir,"visible-exports").also{check(it.isDirectory||it.mkdirs())}
        val file=java.io.File.createTempFile("page-",if(pdf)".pdf"else".png",directory);generated=file
        try{file.outputStream().buffered().use{write(app,pageId,expanded,pdf,it)};ensureActive();file}
        catch(t:Throwable){file.delete();throw t}
        }}catch(t:Throwable){generated?.delete();throw t}
    }
    /** Small fixture convenience; UI retains a prepared file, never an encoded page buffer. */
    suspend fun encode(app:InkWeftApplication,pageId:String,expanded:Boolean,pdf:Boolean):ByteArray {
        val file=prepare(app,pageId,expanded,pdf)
        return try{file.readBytes()}finally{file.delete()}
    }
    private suspend fun write(app:InkWeftApplication,pageId:String,expanded:Boolean,pdf:Boolean,output:java.io.OutputStream){
        val saved=app.authoring.exportHeader(pageId)
        val state=saved.authoring.state
        val layout=DocumentWhitespaceLayout(if(expanded)state.blanks else emptyList())
        val objects=saved.objects.filter{state.layers.visible(LayerContent(LayerContentKind.OBJECT,it.id))}
        val suppressed=saved.objects.flatMap{it.sourceStrokeIds}.toSet()
        val bounds=if(saved.world){
            val annotationBounds=state.visibleAnnotations().mapNotNull{a->
                val region=state.regions.firstOrNull{it.target==a.target}
                if(region?.collapsed==true)null else when(a.target.kind){
                    AnnotationTargetKind.PAGE->a.displayBounds(AnnotationFrame(0.0,0.0))
                    AnnotationTargetKind.PAGE_OBJECT->objects.firstOrNull{it.id==a.target.id}?.let{o->val box=a.displayBounds(a.targetFrame(o.bounds()));val clip=region?.bounds(o.bounds())
                        if(clip==null)box else if(!box.intersects(clip))null else CanvasBounds(max(box.left,clip.left),max(box.top,clip.top),min(box.right,clip.right),min(box.bottom,clip.bottom))}
                    else->null
                }
            }
            val regionBounds=state.regions.mapNotNull{r->objects.firstOrNull{it.id==r.target.id}?.let{r.bounds(it.bounds())}}
            var content=(objects.map{it.bounds()}+annotationBounds+regionBounds).reduceOrNull{a,b->a.union(b)}
            app.inkRepository.visitVisible(pageId,saved.authoring.inkRevision){batch->
                app.authoring.visibleExportInk(pageId,saved.authoring,batch).filter{it.id !in suppressed}.forEach{stroke->content=content?.union(stroke.bounds())?:stroke.bounds()}
            }
            content?.padded(24.0)?:CanvasBounds(0.0,0.0,1000.0,1414.0)
        }else CanvasBounds(0.0,0.0,1000.0,layout.height)
        val width=max(1.0,bounds.right-bounds.left);val height=max(1.0,bounds.bottom-bounds.top)
        require(width<=100_000&&height<=100_000){"分享区域超过 10 万作者单位，请缩小画布范围"}
        val objectsPainter=PageObjectPainter(requireCompleteImages=true);objectsPainter.mapScenes=objects.mapNotNull{it.mapEmbed?.takeIf{it.policy==MapEmbedPolicy.LIVE}?.target?.notebookId}.distinct().flatMap{app.mapGraphs.read(it)}.associateBy{it.ref};val annotations=AnnotationPainter();val renderer=InkBrushes.renderer();val pencil=PencilTileRenderer(live=false)
        val tile=if(saved.world)null else app.documentRendering.render(pageId,CanvasBounds(0.0,0.0,1000.0,1414.0),2048)
        try {
        fun stroke(canvas:Canvas,s:InkStroke){val save=canvas.save();s.cuts.forEach{canvas.clipOutPath(VisibleInkGeometry.cutPath(it))};if(s.pen==InkPen.PENCIL)pencil.draw(canvas,s)else renderer.draw(canvas,InkBrushes.stroke(s),Matrix());canvas.restoreToCount(save)}
        suspend fun original(canvas:Canvas,clip:CanvasBounds){
            canvas.drawColor(Color.WHITE)
            if(tile!=null)canvas.drawBitmap(tile.bitmap,null,RectF(0f,0f,1000f,1414f),Paint(Paint.FILTER_BITMAP_FLAG))
            else PaperPainter.draw(canvas,PaperTemplates.guides(saved.paper,clip,saved.world,1.0),1.0)
            for(layer in state.layers.layers.filter{it.visible}){
                val localObjects=objects.filter{state.layers.layer(LayerContent(LayerContentKind.OBJECT,it.id))?.id==layer.id}
                objectsPainter.draw(canvas,localObjects,false,clip)
                app.inkRepository.visitVisible(pageId,saved.authoring.inkRevision,clip){batch->
                    app.authoring.visibleExportInk(pageId,saved.authoring,batch,layer.id).filter{it.id !in suppressed}.forEach{stroke(canvas,it)}
                }
                annotations.draw(canvas,state.visibleAnnotations().filter{state.layers.layer(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))?.id==layer.id},objects,Matrix(),regions=state.regions)
                objectsPainter.draw(canvas,localObjects,true,clip)
            }
            annotations.regions(canvas,state.regions,objects)
        }
        suspend fun draw(canvas:Canvas){
            canvas.drawColor(Color.WHITE);canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat())
            if(saved.world||!expanded)original(canvas,if(saved.world)bounds else CanvasBounds(0.0,0.0,1000.0,1414.0))
            else {
                for((source,shift)in layout.sourceSegments()){
                    val save=canvas.save();canvas.translate(0f,shift.toFloat());canvas.clipRect(source.left.toFloat(),source.top.toFloat(),source.right.toFloat(),source.bottom.toFloat());original(canvas,source);canvas.restoreToCount(save)
                }
                for(blank in layout.blanks){
                    val save=canvas.save();canvas.translate(0f,layout.blankTop(blank.id).toFloat());canvas.clipRect(0f,0f,1000f,blank.shownHeight.toFloat());canvas.drawColor(Color.WHITE)
                    if(!blank.collapsed)for(layer in state.layers.layers.filter{it.visible})state.visibleAnnotations().filter{it.target==AnnotationTarget(AnnotationTargetKind.WHITESPACE,blank.id)&&state.layers.layer(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id))?.id==layer.id}.forEach{annotations.draw(canvas,it.stroke,it.localFrame,Matrix())}
                    else canvas.drawText("留白已折叠",20f,20f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.GRAY;textSize=16f})
                    canvas.restoreToCount(save)
                }
            }
        }
            // Both formats share one bounded raster; PDF no longer accumulates native vector
            // commands for an entire large page while waiting for finishPage/writeTo.
            val scale=min(2.0,min(4096/max(width,height),sqrt(8_000_000/(width*height))))
            val w=max(1,(width*scale).toInt());val h=max(1,(height*scale).toInt());RenderResources.admit(w.toLong()*h*4)
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val owner="visible-export-${java.util.UUID.randomUUID()}";RenderResources.track(bitmap,bitmap.allocationByteCount.toLong(),"export",owner,RenderResources.Role.IN_FLIGHT)
            try{
                val canvas=Canvas(bitmap);canvas.scale(scale.toFloat(),scale.toFloat());draw(canvas)
                app.authoring.checkExportHeads(pageId,saved.authoring)
                if(pdf){val document=PdfDocument();try{
                    val page=document.startPage(PdfDocument.PageInfo.Builder(ceil(width).toInt(),ceil(height).toInt(),1).create())
                    try{page.canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))}finally{document.finishPage(page)}
                    document.writeTo(output)
                }finally{document.close()}}
                else check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)){"PNG 编码未完成，本次未输出"}
            }finally{RenderResources.release(bitmap,owner);bitmap.recycle()}
        }finally{pencil.clear();objectsPainter.clear();tile?.let{RenderResources.release(it.bitmap,pageId);it.bitmap.recycle()}}
    }
}

@Composable internal fun VisiblePageShareDialog(pageId:String,world:Boolean,hasBlanks:Boolean,dismiss:()->Unit){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    var expanded by remember{mutableStateOf(hasBlanks&&!world)};var pdf by remember{mutableStateOf(true)}
    var pending by remember{mutableStateOf<java.io.File?>(null)};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    val destination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if(pdf)"application/pdf"else"image/png")){uri->
        val file=pending;pending=null
        if(uri!=null&&file!=null)scope.launch{busy=true;try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).use{out->file.inputStream().use{it.copyTo(out,64*1024)}}};message="可见范围已写入所选位置"}catch(c:CancellationException){throw c}catch(_:Exception){message="写入未确认，请检查目标文件；原笔记保留"}finally{file.delete();busy=false}}
        else file?.delete()
    }
    DisposableEffect(Unit){onDispose{pending?.delete()}}
    AlertDialog(onDismissRequest={if(!busy)dismiss()},title={Text("分享当前页可见范围")},text={Column{
        Text("隐藏层与已删除内容不会输出；锁定层仍可见。PDF/PNG 不嵌入原始 PDF、图片原件或隐藏数据，PDF 背景最长边 2048 像素，图片采用已保存预览（最长边最多 1024 像素）；不是原始矢量／原图保真输出。PDF/PNG 均按最长边 4096 像素、最多 800 万像素渲染。")
        if(hasBlanks&&!world)Row{FilterChip(expanded,{expanded=true},enabled=!busy,label={Text("含展开留白")});FilterChip(!expanded,{expanded=false},enabled=!busy,label={Text("原页尺寸·可见渲染")})}
        if(hasBlanks&&!expanded)Text("本次排除全部留白及留白内笔迹；原页内容保持原坐标")
        Row{FilterChip(pdf,{pdf=true},enabled=!busy,label={Text("PDF")});FilterChip(!pdf,{pdf=false},enabled=!busy,label={Text("PNG")})}
        message?.let{Text(it)}
    }},confirmButton={TextButton({busy=true;scope.launch{try{pending?.delete();pending=VisiblePageExport.prepare(app,pageId,expanded,pdf);destination.launch("墨织可见页.${if(pdf)"pdf"else"png"}")}catch(c:CancellationException){throw c}catch(e:Exception){message=if(e is RenderBudgetBusy)"显示内存不足，本次未输出；关闭其他图像后重试，原内容保留"else e.message?:"无法生成完整可见输出，未写入文件"}finally{busy=false}}},enabled=!busy,modifier=Modifier.testTag("share-visible-confirm")){Text("按此范围导出")}},dismissButton={TextButton(dismiss,enabled=!busy){Text("关闭")}})
}
