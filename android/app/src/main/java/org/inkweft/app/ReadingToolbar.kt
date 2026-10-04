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
@Composable internal fun ReadingToolbar(enabled:Boolean,fullScreen:Boolean,
    onMap:()->Unit,onExcerpts:()->Unit,onAssociate:()->Unit,onWrite:()->Unit,onSearch:()->Unit,onOverview:()->Unit,onFullScreen:()->Unit,onExport:()->Unit,onTimer:()->Unit,showWriteControl:Boolean=true,pageActions:@Composable (()->Unit)->Unit={}){
    var more by remember{mutableStateOf(false)}
    FlowRow(Modifier.fillMaxWidth().testTag("reading-toolbar"),horizontalArrangement=Arrangement.Center){
        if(fullScreen){
            EditorAction("导图","mindmap",enabled,"quick-study",onMap)
            EditorAction("摘录","excerpt",enabled,"read-excerpts",onExcerpts)
            EditorAction("关联","link",enabled,"document-associations",onAssociate)
        }
        if(showWriteControl)TextButton(onWrite,modifier=Modifier.heightIn(min=48.dp).testTag("exit-readonly").describedAs("只读浏览，返回书写")){
            Text("返回书写",maxLines=1)
        }
        Box{
            EditorAction("更多","more",tag="toolbar-more"){more=true}
            DropdownMenu(more,{more=false},modifier=Modifier.testTag("reading-more-menu"),containerColor=Color.White){
                pageActions{more=false}
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
