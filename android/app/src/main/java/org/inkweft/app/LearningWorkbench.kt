// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*

internal fun LearningDirectoryState.resolve(target:StableTargetRef):LearningEntry {
    val n=notes.find{it.id==target.notebookId}
    val e=when(target.kind){
        LearningTargetKind.NOTE->n?.let{LearningEntry(target,it.title,"笔记",it.trashedAt==null)}
        LearningTargetKind.PAGE->pages.find{it.id==target.id&&it.notebookId==target.notebookId}?.let{LearningEntry(target,n?.title.orEmpty(),"第 ${it.position+1} 页 · 上次阅读位置",it.trashedAt==null&&n?.trashedAt==null)}
        LearningTargetKind.MAP->maps.find{it.target==target}?:n?.takeIf{target.id==null}?.let{LearningEntry(target,"主图",it.title,it.trashedAt==null)}
        LearningTargetKind.BRANCH->branches.find{it.target==target}
        LearningTargetKind.COLLECTION->collections.find{it.target==target}
        LearningTargetKind.CARD->cards.find{it.id==target.id&&it.notebookId==target.notebookId}?.let{LearningEntry(target,it.title,n?.title.orEmpty(),it.trashedAt==null&&n?.trashedAt==null)}
    }
    return e?:LearningEntry(target,"内容暂不可用","目标已移除或尚未恢复",false)
}

/** Host owns frames, lifecycle, configuration and failure states. Providers receive metadata/actions only. */
@Composable internal fun LearningWorkbench(directory:Flow<LearningDirectoryState>,store:LearningStore,dismiss:()->Unit,open:(StableTargetRef)->Unit){
    val configuration=remember(store){store.observe()}
    val config by configuration.collectAsStateWithLifecycle(initialValue=store.read())
    val indexFlow=remember(directory){directory.map<LearningDirectoryState,Result<LearningDirectoryState>>{Result.success(it)}.catch{emit(Result.failure(it))}}
    val result by indexFlow.collectAsStateWithLifecycle(initialValue=null)
    val index=result?.getOrNull()
    var editing by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf("")}
    var bookFilter by rememberSaveable{mutableStateOf<String?>(null)}
    var pinPicker by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf<String?>(null)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val density=androidx.compose.ui.platform.LocalDensity.current
    val windowWidth=with(density){androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()}
    val export=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")){uri->
        if(uri!=null)try{context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(store.sharedLayout())};message="布局已导出"}catch(_:Exception){message="布局未能导出，请重试"}
    }
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("learning-workbench"),color=Side){Column(Modifier.safeDrawingPadding().padding(horizontal=16.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){IconButton(onClick=dismiss,modifier=Modifier.describedAs("返回资料库")){Glyph("back")};Text("学习",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);TextButton(onClick={editing=!editing}){Text(if(editing)"完成"else"自定义")}}
            if(message!=null)Text(message!!,color=Quiet)
            if(config.error)Text("布局读取失败，原配置已保留。请导出诊断后重试。",color=MaterialTheme.colorScheme.error)
            else LazyVerticalGrid(columns=GridCells.Fixed(if(windowWidth>=900.dp&&density.fontScale<1.4f)2 else 1),modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
                if(editing)item(span={GridItemSpan(maxLineSpan)}){TextButton(onClick={export.launch("InkWeft-学习布局.json")}){Text("导出布局（不含私人入口）")}}
                items(config.widgets.filter{it.visible||editing},key={it.id}){instance->
                    val definition=LearningWidgets.definitions.find{it.qualifiedKey==instance.definition}
                    WidgetHost(instance,definition,editing,{updated->store.configure(config.widgets.map{if(it.id==updated.id)updated else it})},{delta->store.configure(LearningWidgets.move(config.widgets,instance.id,delta))}){
                        when{
                            definition==null||!definition.accepts(instance)->Text("组件暂不可用，配置已保留。",color=Quiet)
                            result==null->Text("正在读取…",color=Quiet)
                            result?.isFailure==true->Text("读取失败，请返回后重试。原内容未改变。",color=MaterialTheme.colorScheme.error)
                            index!=null->{
                                val entries=when(definition.key){
                                    "continue"->config.recent.map(index::resolve)
                                    "shortcuts"->config.shortcuts.map(index::resolve)+index.notes.filter{it.favorite&&it.trashedAt==null}.map{LearningEntry(StableTargetRef(LearningTargetKind.NOTE,it.id),it.title,"已收藏")}
                                    "maps"->index.maps.filter{it.available&&(bookFilter==null||it.target.notebookId==bookFilter)&&it.title.contains(query,true)}
                                    "inbox"->index.inbox
                                    else->emptyList()
                                }.distinctBy{it.target}
                                if(definition.key=="maps"){
                                    OutlinedTextField(query,{query=it},label={Text("查找图名")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("learning-map-search"))
                                    var menu by remember{mutableStateOf(false)}
                                    Box{TextButton(onClick={menu=true}){Text(bookFilter?.let{id->index.notes.find{it.id==id}?.title}?:"全部笔记")};DropdownMenu(menu,{menu=false}){
                                        DropdownMenuItem(text={Text("全部笔记")},onClick={bookFilter=null;menu=false})
                                        index.notes.filter{it.trashedAt==null}.forEach{n->DropdownMenuItem(text={Text(n.title)},onClick={bookFilter=n.id;menu=false})}
                                    }}
                                }
                                if(entries.isEmpty())Text(when(definition.key){"continue"->"打开一份笔记或导图，从这里继续。";"shortcuts"->"收藏笔记或添加一个快捷入口。";"maps"->"没有匹配的导图。";else->"没有待整理摘录。"},color=Quiet,modifier=Modifier.padding(vertical=8.dp))
                                entries.take(instance.size.rows).forEach{entry->LearningEntryRow(entry,{open(entry.target)},if(definition.key=="shortcuts"&&entry.target in config.shortcuts)({store.shortcut(entry.target,false)})else null)}
                                if(entries.size>instance.size.rows){var expanded by remember{mutableStateOf(false)}
                                    TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起"else"查看其余 ${entries.size-instance.size.rows} 项")}
                                    if(expanded)entries.drop(instance.size.rows).forEach{entry->LearningEntryRow(entry,{open(entry.target)})}
                                }
                                if(definition.key=="shortcuts")TextButton(onClick={pinPicker=true}){Glyph("add");Text("添加快捷入口")}
                            }
                        }
                    }
                }
            }
        }}
    }
    if(pinPicker&&index!=null)Dialog({pinPicker=false}){Surface(shape=RoundedCornerShape(16.dp),color=Side){Column(Modifier.padding(16.dp).heightIn(max=520.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Text("固定到学习",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(onClick={pinPicker=false},modifier=Modifier.describedAs("关闭入口选择")){Glyph("close")}}
        Text("独立于收藏与资料库置顶",color=Quiet,style=MaterialTheme.typography.bodySmall)
        LazyColumn{items((index.notes.filter{it.trashedAt==null}.map{LearningEntry(StableTargetRef(LearningTargetKind.NOTE,it.id),it.title,"笔记")}+index.maps+index.collections).filter{it.available}){e->
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(e.target in config.shortcuts,{store.shortcut(e.target,it)});Column(Modifier.weight(1f).padding(vertical=8.dp)){Text(e.title);Text(e.subtitle,color=Quiet,style=MaterialTheme.typography.bodySmall)}}
        }}
    }}}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun WidgetHost(instance:WidgetInstance,definition:WidgetDefinition?,editing:Boolean,update:(WidgetInstance)->Unit,move:(Int)->Unit,content:@Composable ColumnScope.()->Unit){
    Surface(shape=RoundedCornerShape(16.dp),color=Side,border=BorderStroke(1.dp,Line),modifier=Modifier.fillMaxWidth().testTag("widget-${definition?.key?:instance.id}")){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(definition?.title?:instance.definition,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);if(editing)Switch(instance.visible,{update(instance.copy(visible=it))})}
            if(editing){FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                WidgetSize.entries.forEach{size->FilterChip(instance.size==size,{update(instance.copy(size=size))},label={Text(size.label)})}
                TextButton(onClick={move(-1)}){Text("上移")};TextButton(onClick={move(1)}){Text("下移")}
            }}
            if(instance.visible)content()
        }
    }
}
@Composable private fun LearningEntryRow(entry:LearningEntry,open:()->Unit,remove:(()->Unit)?=null){
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=entry.available,onClick=open).testTag("learning-target-${entry.target.id?:entry.target.notebookId}"),verticalAlignment=Alignment.CenterVertically){
        Glyph(if(entry.target.kind in listOf(LearningTargetKind.MAP,LearningTargetKind.BRANCH))"mindmap"else"note",if(entry.available)Forest else Quiet)
        Column(Modifier.weight(1f).padding(horizontal=12.dp,vertical=8.dp)){Text(entry.title,maxLines=2,overflow=TextOverflow.Ellipsis);Text(if(entry.available)entry.subtitle else"暂不可用 · 恢复后可继续打开",color=Quiet,style=MaterialTheme.typography.bodySmall)}
        if(remove!=null)IconButton(onClick=remove,modifier=Modifier.describedAs("移除快捷入口")){Glyph("close")}
    }
}
