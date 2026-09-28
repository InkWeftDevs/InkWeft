package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun NotebookTabs(ui:NotebookUi,enabled:Boolean,open:(String)->Unit,close:(String)->Unit,library:()->Unit){
    val state=rememberLazyListState()
    var availableWidth by remember{mutableIntStateOf(0)}
    var menu by remember{mutableStateOf(false)}
    var filter by remember{mutableStateOf("")}
    LaunchedEffect(ui.selectedId,ui.openIds,availableWidth){val index=ui.openIds.indexOf(ui.selectedId);if(index>=0)state.animateScrollToItem(index)}
    Row(Modifier.fillMaxWidth().background(Side).padding(horizontal=8.dp,vertical=0.dp).testTag("notebook-tabs"),verticalAlignment=Alignment.CenterVertically){
        LazyRow(state=state,modifier=Modifier.weight(1f).onSizeChanged{availableWidth=it.width},horizontalArrangement=Arrangement.spacedBy(2.dp)){
            items(ui.openIds,key={it}){id->
                val draft=ui.drafts[id];val name=draft?.title?:ui.notes.firstOrNull{it.id==id}?.title?:"笔记"
                Column(Modifier.width(168.dp).clip(RoundedCornerShape(6.dp)).background(if(ui.selectedId==id)Leaf else Color.White)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={open(id)},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=36.dp).testTag("notebook-tab-$id")){
                            Text(name+(if(draft?.dirty==true)" *"else""),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                        }
                        IconButton(onClick={close(id)},enabled=enabled,modifier=Modifier.size(36.dp).testTag("close-tab-$id").describedAs("关闭标签：$name")){Glyph("close",Quiet,Modifier.size(16.dp))}
                    }
                }
            }
        }
        Box{
            TextButton({menu=true},enabled=enabled,modifier=Modifier.heightIn(min=36.dp).testTag("tabs-list")){Text("${ui.openIds.size} ⌄",fontSize=13.sp)}
            DropdownMenu(menu,{menu=false},modifier=Modifier.width(300.dp).heightIn(max=480.dp)){
                OutlinedTextField(filter,{filter=it},singleLine=true,placeholder={Text("查找已打开的笔记")},modifier=Modifier.padding(8.dp).testTag("tabs-filter"))
                DropdownMenuItem(text={Text("打开其他笔记")},onClick={menu=false;library()},modifier=Modifier.testTag("tabs-open-library"))
                DropdownMenuItem(text={Text("关闭其他标签")},onClick={ui.openIds.filter{it!=ui.selectedId}.forEach(close);menu=false},enabled=ui.openIds.size>1,modifier=Modifier.testTag("tabs-close-others"))
                ui.openIds.filter{id->(ui.drafts[id]?.title?:ui.notes.firstOrNull{it.id==id}?.title.orEmpty()).contains(filter,true)}.forEach{id->
                    DropdownMenuItem(text={Text(ui.drafts[id]?.title?:ui.notes.firstOrNull{it.id==id}?.title?:"笔记",maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={open(id);menu=false},trailingIcon={IconButton({close(id)},modifier=Modifier.describedAs("关闭此标签")){Glyph("close")}},modifier=Modifier.testTag("tabs-list-$id"))
                }
            }
        }
    }
}
