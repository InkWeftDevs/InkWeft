package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun NotebookTabs(ui:NotebookUi,enabled:Boolean,open:(String)->Unit,close:(String)->Unit,library:()->Unit){
    val state=rememberLazyListState()
    LaunchedEffect(ui.selectedId,ui.openIds){val index=ui.openIds.indexOf(ui.selectedId);if(index>=0)state.animateScrollToItem(index)}
    Row(Modifier.fillMaxWidth().background(Side).testTag("notebook-tabs"),verticalAlignment=Alignment.CenterVertically){
        LazyRow(state=state,modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(2.dp)){
            items(ui.openIds,key={it}){id->
                val draft=ui.drafts[id];val name=draft?.title?:ui.notes.firstOrNull{it.id==id}?.title?:"笔记"
                Column(Modifier.widthIn(min=140.dp,max=240.dp).background(if(ui.selectedId==id)Color.White else Color.Transparent)){
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(onClick={open(id)},enabled=enabled,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("notebook-tab-$id")){
                            Text(name+(if(draft?.dirty==true)" *"else""),maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                        }
                        IconButton(onClick={close(id)},enabled=enabled,modifier=Modifier.size(48.dp).testTag("close-tab-$id").describedAs("关闭标签：$name")){Glyph("close",Quiet,Modifier.size(16.dp))}
                    }
                    HorizontalDivider(thickness=2.dp,color=if(ui.selectedId==id)Forest else Color.Transparent)
                }
            }
        }
        TextButton(onClick=library,enabled=enabled,modifier=Modifier.testTag("tabs-open-library")){Glyph("add");Text("打开",fontSize=12.sp)}
    }
}
