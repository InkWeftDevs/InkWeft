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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FontControls(font:TextFont,onFont:(TextFont)->Unit,bold:Boolean,onBold:(Boolean)->Unit,spacing:Float,onSpacing:(Float)->Unit,showSpacing:Boolean=true){
    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){
        TextFont.entries.forEach{f->FilterChip(font==f,{onFont(f)},label={Text(TextStyles.name(f))},modifier=Modifier.testTag("font-${f.name}"))}
    }
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        FilterChip(bold,{onBold(!bold)},label={Text("加粗")});if(showSpacing){Spacer(Modifier.width(8.dp));Text("行距 %.1f".format(spacing),style=MaterialTheme.typography.bodySmall)
        Slider(spacing,onSpacing,valueRange=1f..2f,modifier=Modifier.weight(1f).testTag("text-spacing"))}
    }
}

@Composable internal fun FontBeautyDialog(selection:SelectedInk,world:Boolean,dismiss:()->Unit,apply:(PageObject)->Unit){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var text by remember{mutableStateOf("")};var font by remember{mutableStateOf(TextFont.WENKAI)}
    var size by remember{mutableFloatStateOf(28f)};var spacing by remember{mutableFloatStateOf(1.2f)};var bold by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)};var status by remember{mutableStateOf("正在离线识别…")}
    var attempt by remember{mutableIntStateOf(0)}
    var showText by remember{mutableStateOf(false)};var help by remember{mutableStateOf(false)}
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
    EditorPanel("美化字迹","",dismiss,"font-beauty-dialog",footer={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedButton(onClick=dismiss,modifier=Modifier.weight(1f)){Text("保留原样")}
            Button(onClick={candidate?.let(apply)},enabled=!loading&&candidate!=null,modifier=Modifier.weight(1f).testTag("apply-font-beauty")){Text("应用美化")}
        }
    }){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
            if(loading){Text(status,style=MaterialTheme.typography.bodySmall,color=Quiet);LinearProgressIndicator(Modifier.fillMaxWidth())}
            error?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            candidate?.let{o->
                AndroidView(factory={InkCanvasView(it).apply{preview=true;previewPadding=8.0}},update={it.configure(true,PaperStyle.BLANK,null);it.showObjects(listOf(o));it.fitContent()},modifier=Modifier.fillMaxWidth().height(96.dp).testTag("font-preview"))
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                TextFont.entries.forEach{f->FilterChip(font==f,{font=f},label={Text(TextStyles.name(f))},modifier=Modifier.testTag("font-${f.name}"))}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Text("加粗",Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Switch(bold,{bold=it},modifier=Modifier.testTag("beauty-bold"))
            }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Text("字号 ${size.toInt()}",Modifier.width(78.dp),style=MaterialTheme.typography.bodyMedium)
                Slider(size,{size=it},valueRange=12f..96f,modifier=Modifier.weight(1f).testTag("beauty-font-size"))
            }
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Text("行距 %.1f".format(spacing),Modifier.width(78.dp),style=MaterialTheme.typography.bodyMedium)
                Slider(spacing,{spacing=it},valueRange=1f..2f,modifier=Modifier.weight(1f).testTag("text-spacing"))
            }
            HorizontalDivider(color=Line)
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Text(text.replace('\n',' ').ifBlank{"未识别到文字"},Modifier.weight(1f),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=Quiet)
                TextButton(onClick={showText=!showText},modifier=Modifier.testTag("beauty-edit-text")){Text(if(showText)"收起"else"校对")}
                TextButton(onClick={help=!help}){Text("说明")}
            }
            if(showText)OutlinedTextField(text,{if(it.length<=4000){text=it;error=null}},enabled=!loading,label={Text("识别文字")},modifier=Modifier.fillMaxWidth().heightIn(min=96.dp,max=160.dp).testTag("beauty-recognized-text"))
            if(text.isNotBlank()&&candidate==null)Text("放不下这段字，请调小字号或分段美化。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            if(help){
                Text("选区识别后换字体，公式与图形请避开。原迹保留：点插入，选中文字，再点恢复原迹。",style=MaterialTheme.typography.bodySmall,color=Quiet)
                TextButton(onClick={attempt++},enabled=!loading){Text("重新识别")}
            }
        }
    }
}
