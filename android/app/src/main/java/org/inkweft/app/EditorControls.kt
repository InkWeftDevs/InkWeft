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
import androidx.compose.runtime.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
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

internal val LocalEditorAnchor=staticCompositionLocalOf<IntRect?>{null}

/** Bounded floating panel: settings stay compact while the paper remains visible. */
@Composable internal fun EditorPanel(title:String,subtitle:String,dismiss:()->Unit,tag:String,
    footer:@Composable ()->Unit={},content:@Composable ColumnScope.()->Unit){
    val small=tag in setOf("eraser-settings","selection-settings","excerpt-settings","beauty-settings")
    val maxPanelHeight=if(small)minOf(360.dp,LocalConfiguration.current.screenHeightDp.dp*.55f)else minOf(600.dp,LocalConfiguration.current.screenHeightDp.dp-64.dp)
    val anchor=LocalEditorAnchor.current
    val density=LocalDensity.current.density
    val position=remember(anchor,density){object:PopupPositionProvider{
        override fun calculatePosition(a:IntRect,w:IntSize,d:LayoutDirection,p:IntSize):IntOffset{
            val margin=(8*density).toInt();val origin=anchor?:a
            val maxX=(w.width-p.width-margin).coerceAtLeast(margin);val maxY=(w.height-p.height-margin).coerceAtLeast(margin)
            val below=origin.bottom+margin
            val side=anchor!=null&&origin.width>origin.height*1.5f
            val x=if(anchor==null)maxX else if(side){if(origin.right+margin+p.width<=w.width-margin)origin.right+margin else origin.left-margin-p.width}else (origin.left+origin.right-p.width)/2
            val y=if(anchor==null)margin+(48*density).toInt() else if(side)(origin.top+origin.bottom-p.height)/2 else if(below+p.height<=w.height-margin)below else origin.top-p.height-margin
            return IntOffset(x.coerceIn(margin,maxX),y.coerceIn(margin,maxY))
        }
    }}
    Popup(position,onDismissRequest=dismiss,properties=PopupProperties(focusable=true)){
        Box(Modifier.widthIn(max=320.dp).padding(4.dp)){
            Surface(Modifier.widthIn(max=320.dp).fillMaxWidth().heightIn(max=maxPanelHeight).testTag(tag),color=MaterialTheme.colorScheme.surface,
                shape=RoundedCornerShape(16.dp),shadowElevation=8.dp,border=BorderStroke(1.dp,Line)){
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
