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

internal data class SelectedInk(val region:InkRegion,val revision:Long,val strokes:List<InkStroke>,val preview:ByteArray?=null,val objectRevision:Long?=null,val excerptText:String="",val authoringRevision:Long?=null)
@Composable
internal fun SelectionActions(selection:SelectedInk?,all:List<InkStroke>,enabled:Boolean,
    freehand:Boolean,onMode:(Boolean)->Unit,apply:(Long,InkMutation)->Boolean,
    clear:()->Unit,excerpt:(SelectedInk)->Unit,associate:(SelectedInk)->Unit={},fontBeauty:(SelectedInk)->Unit={},mapExcerpt:(SelectedInk)->Unit={}){
    var more by remember{mutableStateOf(false)}
    var transformError by remember{mutableStateOf(false)}
    var beauty by remember{mutableStateOf(false)};var color by remember{mutableStateOf(false)}
    val s=selection;val count=s?.strokes?.size?:0
    androidx.activity.compose.BackHandler(enabled=selection!=null){clear()}
    Column(Modifier.background(androidx.compose.ui.graphics.Color.White)){
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
            if(count==0){
            FilterChip(selected=!freehand,onClick={onMode(false);clear()},label={Text("矩形框选")},modifier=Modifier.testTag("selection-rectangle"))
            FilterChip(selected=freehand,onClick={onMode(true);clear()},label={Text("自由套索")},modifier=Modifier.testTag("selection-lasso"))
            }
            if(s!=null&&count==0&&all.any{it.bounds().intersects(s.region.bounds)}){
                TextButton(onClick={val ids=all.filter{it.bounds().intersects(s.region.bounds)}.map{it.id};if(ids.isNotEmpty()&&apply(s.revision,InkMutation.Cut(EraseSelection(s.region.mask(),ids))))clear()},enabled=enabled,modifier=Modifier.testTag("selection-erase-inside")){Text("只擦框内部分")}
            }
            if(s!=null&&count>0){
                TextButton(onClick={if(apply(s.revision,InkMutation.Visibility(s.strokes.map{it.id},false)))clear()},enabled=enabled&&count>0,modifier=Modifier.testTag("selection-delete")){Text("删除")}
                TextButton(onClick={runCatching{InkSelectionEdit.copy(s.strokes,0f,0f)}.getOrNull()?.let{if(apply(s.revision,InkMutation.Replace(emptyList(),it,s.strokes.map{it.id})))clear()}},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-copy")){Text("复制到当前层")}
                TextButton(onClick={color=true},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-recolor")){Text("改色")}
                TextButton(onClick={fontBeauty(s)},enabled=enabled&&count in 1..256&&s.strokes.none{it.pen==InkPen.HIGHLIGHTER},modifier=Modifier.testTag("selection-font-beauty")){Text("美化字迹")}
                Box {
                    TextButton(onClick={more=true},modifier=Modifier.testTag("selection-more")){Text("更多")}
                    DropdownMenu(more,{more=false}){
                        listOf("放大 10%","缩小 10%","水平翻转","垂直翻转").forEachIndexed{i,title->
                            DropdownMenuItem(text={Text(title)},enabled=enabled&&count in 1..256,onClick={more=false
                                runCatching{InkSelectionEdit.transform(s.strokes,if(i==0)1.1f else if(i==1).9f else 1f,i==2,i==3)}.onSuccess{changed->if(apply(s.revision,InkMutation.Replace(s.strokes.map{it.id},changed,s.strokes.map{it.id})))clear()}.onFailure{transformError=true}
                            },modifier=Modifier.testTag("selection-transform-$i"))
                        }
                        DropdownMenuItem(text={Text("摘录")},onClick={more=false;excerpt(s)},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-excerpt"))
                        DropdownMenuItem(text={Text("加入导图")},onClick={more=false;mapExcerpt(s)},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-map"))
                        DropdownMenuItem(text={Text("笔形润色")},onClick={more=false;beauty=true},enabled=enabled&&count in 1..256&&s.strokes.all{it.pen!=InkPen.HIGHLIGHTER&&it.cuts.isEmpty()},modifier=Modifier.testTag("selection-beautify"))
                        DropdownMenuItem(text={Text("只擦框内部分")},onClick={more=false;val ids=all.filter{it.bounds().intersects(s.region.bounds)}.map{it.id};if(ids.isNotEmpty()&&apply(s.revision,InkMutation.Cut(EraseSelection(s.region.mask(),ids))))clear()},enabled=enabled,modifier=Modifier.testTag("selection-erase-inside"))
                        DropdownMenuItem(text={Text("关联")},onClick={more=false;associate(s)},enabled=enabled&&count in 1..256,modifier=Modifier.testTag("selection-associate"))
                    }
                }
                IconButton(onClick=clear,modifier=Modifier.describedAs("取消选择")){Glyph("close")}
            }
        }
    }
    if(transformError)AlertDialog(onDismissRequest={transformError=false},text={Text("调整超出页面或笔宽范围，原笔迹保留。")},confirmButton={TextButton({transformError=false}){Text("知道了")}})
    if(beauty&&s!=null)BeautifyDialog(s.strokes,{beauty=false}){changed->if(apply(s.revision,InkMutation.Replace(s.strokes.map{it.id},changed,s.strokes.map{it.id}))){clear();beauty=false}}
    if(color&&s!=null)AlertDialog(onDismissRequest={color=false},title={Text("修改选中笔迹颜色")},text={Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        listOf(0xff24342f,0xffb83239,0xff3159b8,0xff14735d,0xffa57605).forEach{c->Button(onClick={if(apply(s.revision,InkMutation.Replace(s.strokes.map{it.id},InkSelectionEdit.recolor(s.strokes,c.toInt()),s.strokes.map{it.id}))){clear();color=false}},colors=ButtonDefaults.buttonColors(containerColor=androidx.compose.ui.graphics.Color(c)),modifier=Modifier.size(48.dp),contentPadding=PaddingValues(0.dp)){Text("●")}}
    }},confirmButton={TextButton(onClick={color=false}){Text("取消")}})
}
@Composable
internal fun BeautifyDialog(original:List<InkStroke>,dismiss:()->Unit,apply:(List<InkStroke>)->Unit){
    var strength by remember{mutableFloatStateOf(.5f)}
    var comparison by remember{mutableStateOf(false)}
    val model=remember{BeautifyPreview(original)}
    val preview by model.state.collectAsStateWithLifecycle()
    var attempt by remember{mutableIntStateOf(0)}
    LaunchedEffect(model,strength,attempt){model.compute(strength)}
    val ready=preview.strokes.takeIf{preview.strength==strength}
    EditorPanel("笔形润色","",dismiss,"beautify-dialog",footer={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton(onClick=dismiss){Text("取消")};Button(onClick={ready?.let(apply)},enabled=ready!=null,modifier=Modifier.testTag("apply-beautify")){Text("应用到选中笔迹")}}}){
    Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Slider(value=strength,onValueChange={strength=it},valueRange=0f..1f,modifier=Modifier.testTag("beautify-strength"))
        Text("强度 ${(strength*100).toInt()}%",color=Quiet)
        Text(when{preview.error->"预览未完成，原笔迹保留。请重试。";ready==null->"正在生成预览…";else->"预览已就绪"},fontSize=12.sp,modifier=Modifier.testTag("beautify-status"))
        if(preview.error)TextButton(onClick={attempt++},modifier=Modifier.testTag("beautify-retry")){Text("重新生成预览")}
        AndroidView(factory={InkCanvasView(it).apply{this.preview=true}},update={it.configure(true,PaperStyle.BLANK,null);it.showStrokes(if(comparison)model.original else ready?:model.original)},modifier=Modifier.fillMaxWidth().height(120.dp).testTag("beautify-preview"))
        FilterChip(selected=comparison,onClick={comparison=!comparison},label={Text(if(comparison)"正在查看原迹"else"查看原迹对比")},modifier=Modifier.testTag("beautify-original"))
    }}
}
