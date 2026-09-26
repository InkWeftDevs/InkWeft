// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.inkweft.core.*
import java.util.UUID

@Composable internal fun FontControls(font:TextFont,onFont:(TextFont)->Unit,bold:Boolean,onBold:(Boolean)->Unit,spacing:Float,onSpacing:(Float)->Unit){
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        TextFont.entries.forEach{f->FilterChip(font==f,{onFont(f)},label={Text(TextStyles.name(f))},modifier=Modifier.testTag("font-${f.name}"))}
    }
    Row {FilterChip(bold,{onBold(!bold)},label={Text("加粗")});Spacer(Modifier.width(10.dp));Text("行距 %.1f 倍".format(spacing))}
    Slider(spacing,onSpacing,valueRange=1f..2f,modifier=Modifier.testTag("text-spacing"))
}

@Composable internal fun FontBeautyDialog(selection:SelectedInk,world:Boolean,dismiss:()->Unit,apply:(PageObject)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var text by remember{mutableStateOf("")};var font by remember{mutableStateOf(TextFont.WENKAI)}
    var size by remember{mutableFloatStateOf(28f)};var spacing by remember{mutableFloatStateOf(1.2f)};var bold by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)};var status by remember{mutableStateOf("正在离线识别…")}
    var attempt by remember{mutableIntStateOf(0)}
    val id=remember{UUID.randomUUID().toString()}
    LaunchedEffect(attempt){loading=true;error=null
        try{val result=app.handwriting.recognize(selection.strokes){i,n->status="正在离线识别 ${i+1}/$n 行"};require(result.text.length<=4000);text=result.text;status="识别完成，请核对错字、标点和分行";if(text.isBlank())error="未识别出文字，请缩小选区或手动输入。"}
        catch(c:CancellationException){throw c}catch(_:Exception){error="识别未完成。可缩小到一两行重试，也可输入文字后选择字体。"}finally{loading=false}
    }
    val bounds=selection.strokes.map{it.bounds()}.reduce{a,b->a.union(b)}
    val x=if(world)bounds.left.toFloat()else bounds.left.toFloat().coerceIn(0f,760f)
    val y=if(world)bounds.top.toFloat()else bounds.top.toFloat().coerceIn(0f,1366f)
    val width=(bounds.right-bounds.left).toFloat().coerceIn(240f,if(world)2000f else 1000f-x)
    val candidate=if(text.isBlank())null else runCatching{
        val o=PageObject(id,PageObjectKind.TEXT,x,y,width,48f,text=text,color=selection.strokes.first().color,fontSize=size,font=font,lineSpacing=spacing,bold=bold,sourceStrokeIds=selection.strokes.map{it.id})
        val height=(TextStyles.layout(o).height+8f).coerceAtLeast(48f)
        require(height<=4000&&(world||height<=1414-y));o.copy(height=height)
    }.getOrNull()
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("font-beauty-dialog"),title={Text("字体美化")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("先核对识别结果，再应用字体。原迹保留；在对象工具中选择“恢复原迹”可还原。公式、图形和复杂分栏请单独选择。",fontSize=12.sp)
            Text(status,fontSize=12.sp);if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let{Text(it,fontSize=12.sp)}
            OutlinedTextField(text,{if(it.length<=4000){text=it;error=null}},enabled=!loading,label={Text("核对文字")},modifier=Modifier.fillMaxWidth().heightIn(min=100.dp,max=200.dp).testTag("beauty-recognized-text"))
            FontControls(font,{font=it},bold,{bold=it},spacing,{spacing=it})
            Text("字号 ${size.toInt()}");Slider(size,{size=it},valueRange=12f..96f)
            candidate?.let{o->AndroidView(factory={InkCanvasView(it).apply{preview=true}},update={it.configure(true,PaperStyle.BLANK,null);it.showObjects(listOf(o));it.fitContent()},modifier=Modifier.fillMaxWidth().height(160.dp).testTag("font-preview"))}
            if(text.isNotBlank()&&candidate==null)Text("文字超出页面，请减少字号或分段美化。",fontSize=12.sp)
            TextButton(onClick={attempt++},enabled=!loading){Text("重新识别")}
        }
    },confirmButton={TextButton(onClick={candidate?.let(apply)},enabled=!loading&&candidate!=null,modifier=Modifier.testTag("apply-font-beauty")){Text("应用字体")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
