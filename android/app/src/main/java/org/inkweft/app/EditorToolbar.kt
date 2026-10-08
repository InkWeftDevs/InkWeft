// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.content.SharedPreferences
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
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.launch

internal object EditorToolOrder {
    val labels=linkedMapOf("undo" to "撤销","redo" to "重做","pen" to "笔","highlighter" to "荧光笔","tape" to "胶带","favorites" to "收藏笔","map" to "导图","associations" to "关联","excerpts" to "摘录列表","eraser" to "橡皮","lasso" to "套索","area" to "圈选擦除","image" to "图片","camera" to "拍照","text" to "文本框","excerpt" to "摘录","tag" to "标签","shape" to "图形","sticker" to "贴纸","objects" to "对象选择","beauty" to "自动美化","readonly" to "只读模式","finger" to "手指书写／移动","add-page" to "添加页面","fullscreen" to "全屏专注","export" to "导出文档","timer" to "计时器")
    val fixed=setOf("finger","beauty")
    val destinations=setOf("map","associations","excerpts")
    val primary=setOf("pen","highlighter","eraser","lasso")
    val defaultHidden=setOf("shape","sticker","objects","camera","tag","area","beauty","readonly","finger","add-page","fullscreen","export","timer")
    fun icon(id:String)=when(id){"map"->"mindmap";"associations"->"link";"excerpts"->"excerpt";"lasso"->"select";"area"->"area-erase";"favorites"->"favorite-pens";else->id}
    fun read(context:Context):List<String> = read(context.getSharedPreferences("inkweft-editor",0))
    fun read(prefs:SharedPreferences):List<String>{val raw=prefs.getString("toolbar-order-v32","").orEmpty().split(',').filter{it in labels}.distinct();val order=(raw+labels.keys.filterNot{it in raw}).toMutableList();if("highlighter" !in raw){order.remove("highlighter");order.add(order.indexOf("pen")+1,"highlighter")};return order}
}
/** Stable identifiers preserve visibility when new tools are added. Changes apply immediately. */
@Composable internal fun EditorToolbar(fullScreen:Boolean=false,pageActions:@Composable (Boolean,()->Unit)->Unit={_,_->},inkProperties:@Composable ()->Unit={},content:@Composable (String,()->Unit)->Unit){
    val context=LocalContext.current;val prefs=remember(context){context.getSharedPreferences("inkweft-editor",0)}
    val configuration=rememberEditorToolbarPreferences()
    val order=configuration.value.order;val hidden=configuration.value.hidden
    var customizing by remember{mutableStateOf(false)}
    fun save(order:List<String> = configuration.value.order,hidden:Set<String> = configuration.value.hidden){
        // Keep unknown legacy IDs too; adding shortcuts must not erase another tool's settings.
        val retained=prefs.getString("toolbar-order-v32","").orEmpty().split(',').filter{it.isNotBlank()&&it !in EditorToolOrder.labels}
        prefs.edit().putString("toolbar-order-v32",(order+retained).joinToString(",")).putStringSet("toolbar-hidden-v32",hidden).apply()
    }
    fun move(id:String,delta:Int){
        if(id in EditorToolOrder.fixed)return
        val group=configuration.value.order;val next=group.indexOf(id)+delta
        if(next in group.indices)save(order=group.toMutableList().apply{removeAt(indexOf(id));add(next,id)})
    }
    var more by remember{mutableStateOf(false)}
    val toolScroll=rememberScrollState()
    BoxWithConstraints{
    val wide=maxWidth>=960.dp
    val inline=(EditorToolOrder.primary+setOf("undo","redo"))-hidden
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal=4.dp).testTag("editor-toolbar"),verticalAlignment=Alignment.CenterVertically){
        listOf("undo","redo").filter{it in inline}.forEach{id->EditorToolSlot(id){content(id){}}}
        if(wide)Spacer(Modifier.weight(1f))
        Row(Modifier.then(if(wide)Modifier else Modifier.weight(1f)).testTag("editor-tool-scroll").horizontalScroll(toolScroll),verticalAlignment=Alignment.CenterVertically){
            order.filter{it in EditorToolOrder.primary&&it in inline}.forEach{id->EditorToolSlot(id){content(id){}}}
            if(wide){
                VerticalDivider(Modifier.padding(horizontal=12.dp).height(22.dp),color=Line)
                inkProperties()
            }
        }
        if(wide){Spacer(Modifier.weight(1f));EditorToolSlot("layers"){content("layers"){}}}
        Box {
            EditorTool("更多工具","more",false,true,"toolbar-more"){more=true}
            DropdownMenu(more,{more=false},modifier=Modifier.testTag("editor-more-menu"),containerColor=androidx.compose.ui.graphics.Color.White){
                pageActions(!wide){more=false}
                val overflow=order.filter{it !in inline&&(it !in hidden||it in EditorToolOrder.fixed)&&
                    (fullScreen||it !in EditorToolOrder.destinations+"readonly")}
                listOf("插入" to setOf("image","camera","text","shape","sticker","tape"),"工具" to (EditorToolOrder.labels.keys-setOf("image","camera","text","shape","sticker","tape"))).forEach{(title,ids)->
                    val group=overflow.filter{it in ids}
                    if(group.isNotEmpty()){
                        Text(title,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelLarge,color=Quiet)
                        CompositionLocalProvider(LocalEditorToolMenu provides true){group.forEach{id->EditorToolSlot(id){content(id){more=false}}}}
                    }
                }
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
                                        if(id in EditorToolOrder.fixed)Spacer(Modifier.size(48.dp))else IconToggleButton(!hideGroup,{show->save(hidden=if(show)configuration.value.hidden-id else configuration.value.hidden+id)},modifier=Modifier.size(48.dp).testTag("toolbar-visible-$id").describedAs((if(hideGroup)"显示"else"隐藏")+EditorToolOrder.labels.getValue(id))){Glyph(if(hideGroup)"eye-off"else"eye",if(hideGroup)Quiet else TextInk)}
                                        Glyph(EditorToolOrder.icon(id),if(hideGroup)Quiet else TextInk)
                                        Text(EditorToolOrder.labels.getValue(id),Modifier.weight(1f).padding(horizontal=12.dp),color=if(hideGroup)Quiet else TextInk)
                                        var menu by remember{mutableStateOf(false)}
                                        if(id in EditorToolOrder.fixed)Spacer(Modifier.size(48.dp))else Box {
                                            IconButton(onClick={menu=true},modifier=Modifier.size(48.dp).testTag("toolbar-drag-$id").describedAs("拖动${EditorToolOrder.labels[id]}排序，点按更多排序方式")
                                                .semantics{customActions=listOf(CustomAccessibilityAction("上移"){move(id,-1);true},CustomAccessibilityAction("下移"){move(id,1);true})}
                                                .pointerInput(id){var accumulated=0f;detectDragGestures(onDragStart={accumulated=0f},onDragEnd={accumulated=0f},onDragCancel={accumulated=0f}){change,amount->
                                                    change.consume();accumulated+=amount.y
                                                    while(kotlin.math.abs(accumulated)>=rowPixels){val delta=if(accumulated>0)1 else -1;move(id,delta);accumulated-=delta*rowPixels}
                                                }}){Glyph("drag-handle",Quiet)}
                                            DropdownMenu(menu,{menu=false},containerColor=androidx.compose.ui.graphics.Color.White){
                                                DropdownMenuItem(text={Text("上移")},onClick={move(id,-1);menu=false},enabled=index>0)
                                                DropdownMenuItem(text={Text("下移")},onClick={move(id,1);menu=false},enabled=index<group.lastIndex)
                                                DropdownMenuItem(text={Text("移到最前")},onClick={save(order=listOf(id)+configuration.value.order.filterNot{it==id});menu=false},enabled=index>0)
                                            }
                                        }
                                    }
                                    if(index<group.lastIndex)HorizontalDivider(Modifier.padding(start=88.dp),color=Line)
                                }}
                            }
                        }
                    }
                }
                TextButton(onClick={save(order=EditorToolOrder.labels.keys.toList(),hidden=EditorToolOrder.defaultHidden+(configuration.value.hidden-EditorToolOrder.labels.keys))},modifier=Modifier.padding(start=16.dp,bottom=8.dp).testTag("toolbar-reset")){Text("恢复默认")}
            }
        }
    }
}

/** Slot identity is stable across saved reordering; the control owns its tooltip. */
@Composable internal fun EditorToolSlot(id:String,content:@Composable ()->Unit){
    key(id){Box(Modifier.widthIn(min=48.dp).heightIn(min=48.dp),contentAlignment=Alignment.Center){content()}}
}
