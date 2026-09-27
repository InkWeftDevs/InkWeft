// SPDX-License-Identifier: AGPL-3.0-or-later
/*
THESIS: Writing, beautifying and finding are visible tasks, not nested menus.
OWN-WORLD: White paper, graphite labels, forest selected tools, quiet gray control surfaces.
STORY: Choose a named tool, act on paper, preview the result, return to writing.
FIRST VIEWPORT: Document context, six labeled tools, one-row options, paper and history controls.
FORM: Compact task workbench, candidate 7, seed 50b66d5d; code-first as requested.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review,
the verdict, DESIGN.md, and every shipping raster carrying its provenance.
*/
package org.inkweft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun EditorTool(label:String,icon:String,selected:Boolean,enabled:Boolean,tag:String,
    modifier:Modifier=Modifier,onClick:()->Unit){
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(12.dp),
        color=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier=modifier.heightIn(min=56.dp).testTag(tag)){
        Column(Modifier.padding(horizontal=4.dp,vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(2.dp)){
            Glyph(icon)
            Text(label,style=MaterialTheme.typography.labelMedium,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,maxLines=2)
        }
    }
}

@Composable internal fun EditorAction(label:String,icon:String,enabled:Boolean=true,tag:String,onClick:()->Unit){
    TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag(tag),contentPadding=PaddingValues(horizontal=10.dp)){
        Glyph(icon,modifier=Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text(label,maxLines=1)
    }
}

/** Bounded floating panel: settings stay compact while the paper remains visible. */
@Composable internal fun EditorPanel(title:String,subtitle:String,dismiss:()->Unit,tag:String,
    footer:@Composable ()->Unit={},content:@Composable ColumnScope.()->Unit){
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),contentAlignment=Alignment.CenterEnd){
            Surface(Modifier.widthIn(max=380.dp).fillMaxWidth().heightIn(max=600.dp).testTag(tag),color=MaterialTheme.colorScheme.surface,
                shape=RoundedCornerShape(16.dp)){
                Column(Modifier.fillMaxWidth()){
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp,top=2.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(top=4.dp))}
                        IconButton(onClick=dismiss,modifier=Modifier.describedAs("关闭$title")){Glyph("close")}
                    }
                    HorizontalDivider(color=Line)
                    Column(Modifier.weight(1f,fill=false).fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),content=content)
                    Box(Modifier.fillMaxWidth().background(Side).padding(horizontal=12.dp,vertical=4.dp)){footer()}
                }
            }
        }
    }
}
