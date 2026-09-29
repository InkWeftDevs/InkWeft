// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object EditorToolOrder {
    val labels=linkedMapOf("undo" to "撤销","redo" to "重做","pen" to "笔","map" to "导图","eraser" to "橡皮","lasso" to "套索","area" to "圈选擦除","image" to "图片","camera" to "拍照","text" to "文本框","excerpt" to "摘录","tag" to "标签","shape" to "图形","sticker" to "贴纸","objects" to "对象选择","beauty" to "实时字迹调整","readonly" to "只读模式","finger" to "手写／电容笔","add-page" to "添加页面","fullscreen" to "全屏专注","export" to "导出文档","timer" to "计时器")
    val fixed=setOf("finger")
    val primary=setOf("undo","redo","pen","eraser","lasso","excerpt","map","finger")
    val defaultHidden=setOf("shape","sticker","objects","camera","tag","area","beauty","readonly","finger","add-page","fullscreen","export","timer")
    fun icon(id:String)=when(id){"map"->"mindmap";"lasso"->"select";"area"->"area-erase";"favorites"->"favorite-pens";else->id}
    fun read(context:Context):List<String>{val raw=context.getSharedPreferences("inkweft-editor",0).getString("toolbar-order-v32","").orEmpty().split(',').filter{it in labels}.distinct();return raw+labels.keys.filterNot{it in raw}}
}
/** Stable identifiers preserve visibility when new tools are added. Changes apply immediately. */
@Composable internal fun EditorToolbar(externalMore:Boolean=false,moreRequest:Int=0,content:@Composable (String,()->Unit)->Unit){
    val context=LocalContext.current;val prefs=remember{context.getSharedPreferences("inkweft-editor",0)}
    var order by remember{mutableStateOf(EditorToolOrder.read(context))}
    var hidden by remember{mutableStateOf((prefs.getStringSet("toolbar-hidden-v32",EditorToolOrder.defaultHidden).orEmpty()+EditorToolOrder.defaultHidden.filter{it !in prefs.getString("toolbar-order-v32","").orEmpty().split(',')})-EditorToolOrder.fixed)}
    var customizing by remember{mutableStateOf(false)}
    fun save(){prefs.edit().putString("toolbar-order-v32",order.joinToString(",")).putStringSet("toolbar-hidden-v32",hidden).apply()}
    fun move(id:String,delta:Int){
        val group=order;val next=group.indexOf(id)+delta
        if(next in group.indices){val from=order.indexOf(id);val target=order.indexOf(group[next]);order=order.toMutableList().apply{removeAt(from);add(target,id)};save()}
    }
    var more by remember{mutableStateOf(false)}
    LaunchedEffect(moreRequest){if(moreRequest>0)more=true}
    BoxWithConstraints{
    val visiblePrimary=EditorToolOrder.primary+if(maxWidth>=528.dp)setOf("image","text")else emptySet()
    FlowRow(Modifier.testTag("editor-toolbar")){
        order.filter{it in visiblePrimary&&it !in hidden}.forEach{key(it){EditorToolSlot(it){content(it){}}}}
        Box {
            if(!externalMore)IconButton(onClick={more=true},modifier=Modifier.testTag("toolbar-more").describedAs("更多工具")){Glyph("more")}
            DropdownMenu(more,{more=false},containerColor=androidx.compose.ui.graphics.Color.White){
                Row(Modifier.widthIn(max=320.dp).horizontalScroll(rememberScrollState())){order.filter{it !in visiblePrimary&&it !in hidden}.forEach{id->EditorToolSlot(id){content(id){more=false}}}}
                DropdownMenuItem(text={Text("自定义快捷栏")},onClick={more=false;customizing=true},modifier=Modifier.testTag("toolbar-customize"))
            }
        }
    }
    }
    if(customizing)androidx.compose.ui.window.Popup(alignment=Alignment.TopEnd,onDismissRequest={customizing=false},properties=androidx.compose.ui.window.PopupProperties(focusable=true)){
        val height=LocalConfiguration.current.screenHeightDp.dp*.86f
        val rowPixels=with(LocalDensity.current){56.dp.toPx()}
        Surface(Modifier.padding(16.dp).widthIn(max=520.dp).fillMaxWidth().heightIn(max=height).testTag("toolbar-customize-panel"),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=androidx.compose.ui.graphics.Color.White){
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={customizing=false},modifier=Modifier.testTag("toolbar-close")){Text("关闭")}
                    Text(if(LocalDensity.current.fontScale>1.5f&&LocalConfiguration.current.screenWidthDp<480)"自定义\n快捷栏"else"自定义快捷栏",Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleLarge,fontSize=if(LocalDensity.current.fontScale>1.5f)16.sp else 20.sp)
                    TextButton(onClick={customizing=false},modifier=Modifier.testTag("toolbar-done")){Text("完成")}
                }
                Text("点眼睛显示或隐藏，拖动右侧把手排序",Modifier.padding(horizontal=24.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=Quiet)
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    listOf(false).forEach{hideGroup->
                        val group=order
                        Text("工具",Modifier.padding(top=8.dp),style=MaterialTheme.typography.titleMedium)
                        Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Line),color=androidx.compose.ui.graphics.Color.White){
                            Column {
                                if(group.isEmpty())Text(if(hideGroup)"全部工具已显示"else"从更多操作中添加工具",Modifier.padding(16.dp),color=Quiet)
                                group.forEachIndexed{index,id->key(id){
                                    val hideGroup=id in hidden
                                    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=4.dp).testTag("toolbar-row-$id"),verticalAlignment=Alignment.CenterVertically){
                                        if(id in EditorToolOrder.fixed)Spacer(Modifier.size(48.dp))else IconToggleButton(!hideGroup,{show->hidden=if(show)hidden-id else hidden+id;save()},modifier=Modifier.size(48.dp).testTag("toolbar-visible-$id").describedAs((if(hideGroup)"显示"else"隐藏")+EditorToolOrder.labels.getValue(id))){Glyph(if(hideGroup)"eye-off"else"eye",if(hideGroup)Quiet else TextInk)}
                                        Glyph(EditorToolOrder.icon(id),if(hideGroup)Quiet else TextInk)
                                        Text(EditorToolOrder.labels.getValue(id),Modifier.weight(1f).padding(horizontal=12.dp),color=if(hideGroup)Quiet else TextInk)
                                        var menu by remember{mutableStateOf(false)}
                                        Box {
                                            IconButton(onClick={menu=true},modifier=Modifier.size(48.dp).testTag("toolbar-drag-$id").describedAs("拖动${EditorToolOrder.labels[id]}排序，点按更多排序方式")
                                                .semantics{customActions=listOf(CustomAccessibilityAction("上移"){move(id,-1);true},CustomAccessibilityAction("下移"){move(id,1);true})}
                                                .pointerInput(id){var accumulated=0f;detectDragGestures(onDragStart={accumulated=0f},onDragEnd={accumulated=0f},onDragCancel={accumulated=0f}){change,amount->
                                                    change.consume();accumulated+=amount.y
                                                    while(kotlin.math.abs(accumulated)>=rowPixels){val delta=if(accumulated>0)1 else -1;move(id,delta);accumulated-=delta*rowPixels}
                                                }}){Glyph("drag-handle",Quiet)}
                                            DropdownMenu(menu,{menu=false},containerColor=androidx.compose.ui.graphics.Color.White){
                                                DropdownMenuItem(text={Text("上移")},onClick={move(id,-1);menu=false},enabled=index>0)
                                                DropdownMenuItem(text={Text("下移")},onClick={move(id,1);menu=false},enabled=index<group.lastIndex)
                                                DropdownMenuItem(text={Text("移到最前")},onClick={order=listOf(id)+order.filterNot{it==id};save();menu=false},enabled=index>0)
                                            }
                                        }
                                    }
                                    if(index<group.lastIndex)HorizontalDivider(Modifier.padding(start=88.dp),color=Line)
                                }}
                            }
                        }
                    }
                }
                TextButton(onClick={order=EditorToolOrder.labels.keys.toList();hidden=EditorToolOrder.defaultHidden;save()},modifier=Modifier.padding(start=16.dp,bottom=8.dp).testTag("toolbar-reset")){Text("恢复默认")}
            }
        }
    }
}

/** Same visual/hit-area contract for every tool; names remain available on long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EditorToolSlot(id:String,content:@Composable ()->Unit){
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip={PlainTooltip{Text(EditorToolOrder.labels[id].orEmpty())}},state=rememberTooltipState()){
        Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){content()}
    }
}
