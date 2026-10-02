// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Browse existing material without changing the writing toolbar's saved configuration. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReadingToolbar(externalMore:Boolean,moreRequest:Int,enabled:Boolean,fullScreen:Boolean,
    onMap:()->Unit,onExcerpts:()->Unit,onWrite:()->Unit,onSearch:()->Unit,onOverview:()->Unit,onFullScreen:()->Unit,onExport:()->Unit,onTimer:()->Unit){
    var more by remember{mutableStateOf(false)}
    var consumedMoreRequest by remember{mutableIntStateOf(moreRequest)}
    LaunchedEffect(moreRequest){if(moreRequest>consumedMoreRequest)more=true;consumedMoreRequest=moreRequest}
    FlowRow(Modifier.fillMaxWidth().testTag("reading-toolbar"),horizontalArrangement=Arrangement.Center){
        Row{
            EditorToolSlot("map"){IconButton(onMap,enabled=enabled,modifier=Modifier.size(48.dp).testTag("quick-study").describedAs("笔记导图")){Glyph("mindmap")}}
            EditorToolSlot("excerpt"){IconButton(onExcerpts,enabled=enabled,modifier=Modifier.size(48.dp).testTag("read-excerpts").describedAs("本笔记摘录")){Glyph("excerpt")}}
        }
        TextButton(onWrite,modifier=Modifier.heightIn(min=48.dp).testTag("exit-readonly").describedAs("只读浏览，返回书写")){
            Text("返回书写",maxLines=1)
        }
        Box{
            if(!externalMore)IconButton({more=true},modifier=Modifier.size(48.dp).testTag("toolbar-more").describedAs("更多阅读工具")){Glyph("more")}
            DropdownMenu(more,{more=false},modifier=Modifier.testTag("reading-more-menu"),containerColor=Color.White){
                DropdownMenuItem(text={Text(if(fullScreen)"退出全屏"else"全屏专注")},leadingIcon={Glyph("fullscreen")},onClick={more=false;onFullScreen()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("quick-fullscreen"))
                if(fullScreen){
                    DropdownMenuItem(text={Text("查找笔记")},leadingIcon={Glyph("search")},onClick={more=false;onSearch()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("reading-search"))
                    DropdownMenuItem(text={Text("文档概览")},leadingIcon={Glyph("overview")},onClick={more=false;onOverview()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("reading-overview"))
                }
                DropdownMenuItem(text={Text("导出文档")},leadingIcon={Glyph("export")},onClick={more=false;onExport()},enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("quick-export"))
                DropdownMenuItem(text={Text("计时器")},leadingIcon={Glyph("timer")},onClick={more=false;onTimer()},modifier=Modifier.heightIn(min=48.dp).testTag("quick-timer"))
            }
        }
    }
}
