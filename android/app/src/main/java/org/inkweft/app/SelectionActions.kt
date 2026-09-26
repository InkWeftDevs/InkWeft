// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.inkweft.core.*

internal data class SelectedInk(val region:InkRegion,val revision:Long,val strokes:List<InkStroke>)
@Composable
internal fun SelectionActions(selection:SelectedInk?,all:List<InkStroke>,enabled:Boolean,
    freehand:Boolean,onMode:(Boolean)->Unit,apply:(Long,InkMutation)->Boolean,
    clear:()->Unit,excerpt:(SelectedInk)->Unit){
    var beauty by remember{mutableStateOf(false)};var color by remember{mutableStateOf(false)}
    val s=selection;val count=s?.strokes?.size?:0
    Column(Modifier.background(androidx.compose.ui.graphics.Color.White)){
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=!freehand,onClick={onMode(false);clear()},label={Text("矩形框选")},modifier=Modifier.testTag("selection-rectangle"))
            FilterChip(selected=freehand,onClick={onMode(true);clear()},label={Text("自由套索")},modifier=Modifier.testTag("selection-lasso"))
            if(s!=null){
                TextButton(onClick={if(apply(s.revision,InkMutation.Visibility(s.strokes.map{it.id},false)))clear()},enabled=enabled&&count>0,modifier=Modifier.testTag("selection-delete")){Text("删除选中 $count 笔")}
                TextButton(onClick={val ids=all.filter{it.bounds().intersects(s.region.bounds)}.map{it.id};if(ids.isNotEmpty()&&apply(s.revision,InkMutation.Cut(EraseSelection(s.region.mask(),ids))))clear()},enabled=enabled,modifier=Modifier.testTag("selection-erase-inside")){Text("只擦框内部分")}
                TextButton(onClick={runCatching{InkSelectionEdit.copy(s.strokes,0f,0f)}.getOrNull()?.let{if(apply(s.revision,InkMutation.Replace(emptyList(),it)))clear()}},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-copy")){Text("原位复制")}
                TextButton(onClick={beauty=true},enabled=enabled&&count in 1..256&&s.strokes.all{it.pen==InkPen.PEN&&it.cuts.isEmpty()},modifier=Modifier.testTag("selection-beautify")){Text("稳线美化")}
                TextButton(onClick={color=true},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-recolor")){Text("改色")}
                TextButton(onClick={excerpt(s)},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-excerpt")){Text("摘录为摘要卡")}
                TextButton(onClick=clear){Text("取消选择")}
            }
        }
        Text(if(count>0)"选中 $count 笔。拖动选区移动；复制为原位独立笔迹，可撤销。"else"圈住完整笔迹进行编辑；“只擦框内部分”按虚线范围精确擦除，不整笔删除。",fontSize=11.sp,color=Quiet,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
    }
    if(beauty&&s!=null)BeautifyDialog(s.strokes,{beauty=false}){changed->if(apply(s.revision,InkMutation.Replace(s.strokes.map{it.id},changed))){clear();beauty=false}}
    if(color&&s!=null)AlertDialog(onDismissRequest={color=false},title={Text("修改选中笔迹颜色")},text={Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf(0xff24342f,0xffb83239,0xff3159b8,0xff14735d,0xffa57605).forEach{c->Button(onClick={if(apply(s.revision,InkMutation.Replace(s.strokes.map{it.id},InkSelectionEdit.recolor(s.strokes,c.toInt())))){clear();color=false}},colors=ButtonDefaults.buttonColors(containerColor=androidx.compose.ui.graphics.Color(c)),modifier=Modifier.size(48.dp),contentPadding=PaddingValues(0.dp)){Text("●")}}
    }},confirmButton={TextButton(onClick={color=false}){Text("取消")}})
}
@Composable
private fun BeautifyDialog(original:List<InkStroke>,dismiss:()->Unit,apply:(List<InkStroke>)->Unit){
    var strength by remember{mutableFloatStateOf(.5f)}
    var comparison by remember{mutableStateOf(false)}
    val model=remember{BeautifyPreview(original)}
    val preview by model.state.collectAsStateWithLifecycle()
    var attempt by remember{mutableIntStateOf(0)}
    LaunchedEffect(model,strength,attempt){model.compute(strength)}
    val ready=preview.strokes.takeIf{preview.strength==strength}
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("beautify-dialog"),title={Text("稳线美化 · 先预览后应用")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("减轻细小抖动，保留转折与端点。不是换字体或自动纠错；应用后仍可撤销。已局部擦除或荧光笔暂不美化。",fontSize=12.sp)
        Slider(value=strength,onValueChange={strength=it},valueRange=0f..1f,modifier=Modifier.testTag("beautify-strength"))
        Text("强度 ${(strength*100).toInt()}%",color=Quiet)
        Text(when{preview.error->"预览未完成，原笔迹保留。请重试。";ready==null->"正在生成预览…";else->"预览已就绪"},fontSize=12.sp,modifier=Modifier.testTag("beautify-status"))
        if(preview.error)TextButton(onClick={attempt++},modifier=Modifier.testTag("beautify-retry")){Text("重新生成预览")}
        AndroidView(factory={InkCanvasView(it).apply{this.preview=true}},update={it.configure(true,PaperStyle.BLANK,null);it.showStrokes(if(comparison)model.original else ready?:model.original)},modifier=Modifier.fillMaxWidth().height(180.dp).testTag("beautify-preview"))
        FilterChip(selected=comparison,onClick={comparison=!comparison},label={Text(if(comparison)"正在查看原迹"else"查看原迹对比")},modifier=Modifier.testTag("beautify-original"))
    }},confirmButton={TextButton(onClick={ready?.let(apply)},enabled=ready!=null,modifier=Modifier.testTag("apply-beautify")){Text("应用到选中笔迹")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}
