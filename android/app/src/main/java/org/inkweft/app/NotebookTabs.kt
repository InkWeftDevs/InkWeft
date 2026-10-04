package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun NotebookTabs(ui:NotebookUi,enabled:Boolean,open:(String)->Unit,close:(String)->Unit,library:()->Unit,modifier:Modifier=Modifier,split:(String,Boolean)->Unit={_,_->},compact:Boolean=false){
    val state=rememberLazyListState()
    var availableWidth by remember{mutableIntStateOf(0)}
    var menu by remember{mutableStateOf(false)}
    var filter by remember{mutableStateOf("")}
    var hidden by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(ui.selectedId,ui.openIds,availableWidth){val index=ui.openIds.indexOf(ui.selectedId);if(index>=0)state.animateScrollToItem(index)}
    Row(modifier.then(if(compact)Modifier else Modifier.fillMaxWidth().background(InkTheme.TabBar).padding(horizontal=8.dp)).testTag("notebook-tabs"),verticalAlignment=Alignment.CenterVertically){
        if(!compact&&!hidden)LazyRow(state=state,modifier=Modifier.weight(1f).onSizeChanged{availableWidth=it.width},horizontalArrangement=Arrangement.spacedBy(2.dp)){
            items(ui.openIds,key={it}){id->
                val draft=ui.drafts[id];val name=draft?.title?:ui.notes.firstOrNull{it.id==id}?.title?:"笔记"
                Column(Modifier.width(if(ui.selectedId==id)220.dp else 168.dp).clip(RoundedCornerShape(topStart=10.dp,topEnd=10.dp)).background(if(ui.selectedId==id)InkTheme.Surface else InkTheme.TabBar)
                    .drawBehind{if(ui.selectedId==id)drawRect(InkTheme.Accent,Offset(0f,size.height-2.dp.toPx()),Size(size.width,2.dp.toPx()))}){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={open(id)},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("notebook-tab-$id")
                            .semantics{selected=ui.selectedId==id}.describedAs("笔记：$name"+(if(ui.selectedId==id)"，当前笔记"else"")+(if(draft?.dirty==true)"，未保存"else""))){
                            Text(name+(if(draft?.dirty==true)" *"else""),maxLines=1,overflow=TextOverflow.MiddleEllipsis,fontSize=13.sp,color=if(ui.selectedId==id)InkTheme.Accent else InkTheme.Secondary)
                        }
                        IconButton(onClick={close(id)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("close-tab-$id").describedAs("关闭标签：$name")){Glyph("close",Quiet,Modifier.size(16.dp))}
                    }
                }
            }
        }
        if(!compact&&hidden)Spacer(Modifier.weight(1f))
        Box{
            val current=ui.current
            TextButton({menu=true},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("tabs-list").describedAs("${if(compact)"当前笔记：${current?.title}。"else""}已打开 ${ui.openIds.size} 份笔记，查看完整名称")){
                Text(if(compact)current?.title.orEmpty()+(if(current?.dirty==true)" *"else"")else"已打开 ${ui.openIds.size}",modifier=if(compact)Modifier.weight(1f,fill=false)else Modifier,maxLines=1,overflow=TextOverflow.MiddleEllipsis,fontSize=13.sp)
                Text(" ⌄",fontSize=13.sp)
            }
            if(menu)Popup(alignment=Alignment.TopEnd,offset=IntOffset(0,with(LocalDensity.current){40.dp.roundToPx()}),onDismissRequest={menu=false},properties=PopupProperties(focusable=true)){
                Surface(Modifier.widthIn(max=(LocalConfiguration.current.screenWidthDp-24).dp).width(360.dp).heightIn(max=minOf(520.dp,LocalConfiguration.current.screenHeightDp.dp-80.dp)).testTag("tabs-popup"),shape=RoundedCornerShape(16.dp),color=Color.White,shadowElevation=8.dp,border=BorderStroke(1.dp,Line)){
                    Column(Modifier.padding(8.dp)){
                        Text("已打开 ${ui.openIds.size} 份笔记",Modifier.padding(8.dp),style=MaterialTheme.typography.titleSmall)
                        if(!compact)TextButton({hidden=!hidden;menu=false},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("tabs-hide")){Text(if(hidden)"显示笔记栏"else"隐藏笔记栏")}
                        TextButton({ui.openIds.filter{it!=ui.selectedId}.forEach(close);menu=false},enabled=enabled&&ui.openIds.size>1,modifier=Modifier.fillMaxWidth().testTag("tabs-close-others")){Text("关闭其他笔记",color=Color(0xffc13c43))}
                        OutlinedTextField(filter,{filter=it},singleLine=true,placeholder={Text("查找已打开的笔记")},modifier=Modifier.fillMaxWidth().testTag("tabs-filter"))
                        LazyColumn(Modifier.weight(1f,fill=false).testTag("tabs-scroll"),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(vertical=8.dp)){
                            items(ui.openIds.filter{id->(ui.drafts[id]?.title?:ui.notes.firstOrNull{it.id==id}?.title.orEmpty()).contains(filter,true)},key={it}){id->
                                val name=ui.drafts[id]?.title?:ui.notes.firstOrNull{it.id==id}?.title?:"笔记"
                                var actions by remember{mutableStateOf(false)}
                                Surface(shape=RoundedCornerShape(9.dp),color=Color.White,border=BorderStroke(1.dp,if(id==ui.selectedId)Forest else Line)){
                                    Row(verticalAlignment=Alignment.CenterVertically){
                                        TextButton({open(id);menu=false},enabled=enabled,shape=RoundedCornerShape(8.dp),modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("tabs-list-$id").semantics{selected=id==ui.selectedId}){Text(name,Modifier.fillMaxWidth().testTag("tabs-full-name-$id"),fontSize=14.sp,textAlign=TextAlign.Start)}
                                        Box{
                                            IconButton({actions=true},enabled=enabled,modifier=Modifier.size(48.dp).testTag("tabs-actions-$id").describedAs("$name 的更多操作")){Glyph("more")}
                                            DropdownMenu(actions,{actions=false},containerColor=Color.White){
                                                DropdownMenuItem(text={Text("左右分屏")},onClick={actions=false;menu=false;split(id,false)},modifier=Modifier.testTag("tab-split-horizontal"))
                                                DropdownMenuItem(text={Text("上下分屏")},onClick={actions=false;menu=false;split(id,true)},modifier=Modifier.testTag("tab-split-vertical"))
                                                DropdownMenuItem(text={Text("关闭笔记",color=Color(0xffc13c43))},onClick={actions=false;close(id)},modifier=Modifier.testTag("tab-close-note"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        TextButton({menu=false;library()},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("tabs-open-library")){Text("打开其他笔记")}
                    }
                }
            }
        }
    }
}
