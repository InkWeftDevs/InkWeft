// SPDX-License-Identifier: AGPL-3.0-or-later
// Compact, icon-led editor chrome. Document color and geometry are independent.
package org.inkweft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import org.inkweft.app.ui.designsystem.InkTheme

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

internal enum class PanelKind { CONTENT, SETTINGS, BEAUTY_REVIEW }
internal data class PanelPresentationPolicy(val bottom:Boolean,val maxHeight:Dp)
internal fun panelPresentation(kind:PanelKind,width:Dp,height:Dp):PanelPresentationPolicy {
    val bottom=kind==PanelKind.BEAUTY_REVIEW&&width<720.dp
    val maximum=when{
        bottom->minOf(480.dp,height*.55f)
        kind==PanelKind.SETTINGS->minOf(360.dp,height*.55f)
        else->minOf(600.dp,(height-64.dp).coerceAtLeast(48.dp))
    }
    return PanelPresentationPolicy(bottom,maximum)
}

internal fun Modifier.editorSelected(selected:Boolean)=drawBehind {
    if(selected){val inset=4.dp.toPx();drawRoundRect(InkTheme.Selected,Offset(inset,inset),Size(size.width-2*inset,size.height-2*inset),CornerRadius(10.dp.toPx()))}
}

@Composable internal fun EditorSegments(labels:List<String>,selected:Int,tags:List<String>,choose:(Int)->Unit){
    Surface(shape=InkTheme.ToolShape,color=InkTheme.Navigation){
        Row(Modifier.fillMaxWidth().padding(4.dp)){
            labels.forEachIndexed{i,label->
                Surface(Modifier.weight(1f).heightIn(min=48.dp).testTag(tags[i]).selectable(i==selected,role=Role.Tab){choose(i)},
                    shape=RoundedCornerShape(9.dp),color=if(i==selected)InkTheme.Surface else InkTheme.Navigation,
                    contentColor=if(i==selected)InkTheme.Accent else InkTheme.Secondary){
                    Box(Modifier.padding(horizontal=8.dp,vertical=10.dp),contentAlignment=Alignment.Center){Text(label,style=MaterialTheme.typography.labelLarge)}
                }
            }
        }
    }
}

/** Bounded floating panel: settings stay compact while the paper remains visible. */
@Composable internal fun EditorPanel(title:String,subtitle:String,dismiss:()->Unit,tag:String,
    kind:PanelKind=PanelKind.CONTENT,footer:(@Composable ()->Unit)?=null,content:@Composable ColumnScope.()->Unit){
    val window=LocalWindowInfo.current.containerSize
    val windowWidth=with(LocalDensity.current){window.width.toDp()}
    val windowHeight=with(LocalDensity.current){window.height.toDp()}
    val policy=panelPresentation(kind,windowWidth,windowHeight)
    val reviewBottom=policy.bottom
    val maxPanelHeight=policy.maxHeight
    val anchor=LocalEditorAnchor.current
    val density=LocalDensity.current.density
    val position=remember(anchor,density,reviewBottom){object:PopupPositionProvider{
        override fun calculatePosition(a:IntRect,w:IntSize,d:LayoutDirection,p:IntSize):IntOffset{
            val margin=(8*density).toInt();val origin=anchor?:a
            val maxX=(w.width-p.width-margin).coerceAtLeast(margin);val maxY=(w.height-p.height-margin).coerceAtLeast(margin)
            val below=origin.bottom+margin
            val side=anchor!=null&&origin.width>origin.height*1.5f
            val x=if(reviewBottom)(w.width-p.width)/2 else if(anchor==null)maxX else if(side){if(origin.right+margin+p.width<=w.width-margin)origin.right+margin else origin.left-margin-p.width}else (origin.left+origin.right-p.width)/2
            val y=if(reviewBottom)maxY else if(anchor==null)margin+(48*density).toInt() else if(side)(origin.top+origin.bottom-p.height)/2 else if(below+p.height<=w.height-margin)below else origin.top-p.height-margin
            return IntOffset(x.coerceIn(margin,maxX),y.coerceIn(margin,maxY))
        }
    }}
    Popup(position,onDismissRequest=dismiss,properties=PopupProperties(focusable=true)){
        Box(Modifier.widthIn(max=320.dp).padding(4.dp)){
            Surface(Modifier.widthIn(max=320.dp).fillMaxWidth().heightIn(max=maxPanelHeight).testTag(tag),color=MaterialTheme.colorScheme.surface,
                shape=InkTheme.FloatingShape,shadowElevation=InkTheme.FloatingElevation){
                Column(Modifier.fillMaxWidth()){
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=4.dp,top=2.dp,bottom=2.dp),verticalAlignment=Alignment.CenterVertically){
                        Column(Modifier.weight(1f)){Text(title,style=InkTheme.PanelTitle);if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Quiet,modifier=Modifier.padding(top=4.dp))}
                        IconButton(onClick=dismiss,modifier=Modifier.describedAs("关闭$title")){Glyph("close")}
                    }
                    Column(Modifier.weight(1f,fill=false).fillMaxWidth().padding(horizontal=InkTheme.PanelInset,vertical=8.dp),content=content)
                    if(footer!=null)Box(Modifier.fillMaxWidth().background(InkTheme.Navigation).padding(horizontal=12.dp,vertical=4.dp)){footer()}
                }
            }
        }
    }
}

/** Shared native panel heading; body and dialog actions keep their own workflow. */
@Composable internal fun PanelHeading(title:String,actionLabel:String,action:()->Unit,back:Boolean=false){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
        if(back)IconButton(action,modifier=Modifier.size(48.dp).describedAs(actionLabel)){Glyph("back")}
        Text(title,Modifier.weight(1f),style=InkTheme.PanelTitle)
        if(!back)IconButton(action,modifier=Modifier.size(48.dp).describedAs(actionLabel)){Glyph("close")}
    }
}
