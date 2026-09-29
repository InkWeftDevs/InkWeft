// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import org.inkweft.app.ui.designsystem.InkTheme

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Frozen target. No DB object is created until the name is confirmed. */
internal data class NodeTitleDraft(val token:String,val mapId:String?,val nodeId:String,val cardId:String,
    val revision:Long,val title:String,val body:String,val parentId:String?,val x:Double,val y:Double,
    val creating:Boolean=false,val structural:Boolean=false,val graph:String="",val anchorId:String=nodeId){
    companion object {
        val Saver=listSaver<NodeTitleDraft?,String>(save={if(it==null)emptyList()else listOf(it.token,it.mapId.orEmpty(),it.nodeId,it.cardId,it.revision.toString(),it.title,it.body,it.parentId.orEmpty(),it.x.toString(),it.y.toString(),it.creating.toString(),it.structural.toString(),it.graph,it.anchorId)},restore={if(it.isEmpty())null else NodeTitleDraft(it[0],it[1].ifEmpty{null},it[2],it[3],it[4].toLong(),it[5],it[6],it[7].ifEmpty{null},it[8].toDouble(),it[9].toDouble(),it[10].toBoolean(),it[11].toBoolean(),it[12],it[13])})
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MapActionIcon(label:String,icon:String,tag:String,enabled:Boolean=true,click:()->Unit){
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(),{PlainTooltip{Text(label)}},rememberTooltipState()){
        IconButton(click,enabled=enabled,modifier=Modifier.size(48.dp).testTag(tag).describedAs(label)){Glyph(icon)}
    }
}
@Composable internal fun NodeActions(enabled:Boolean,rename:()->Unit,child:()->Unit,sibling:()->Unit,more:()->Unit){
    Surface(shape=InkTheme.ToolShape,color=InkTheme.Surface,shadowElevation=InkTheme.ToolElevation,modifier=Modifier.testTag("node-actions")){
        Row{
            MapActionIcon("修改标题","pen","node-rename",enabled,rename)
            MapActionIcon("添加子主题","node-child","node-add-child",enabled,child)
            MapActionIcon("添加同级主题","node-sibling","node-add-sibling",enabled,sibling)
            MapActionIcon("更多","more","node-more",enabled,more)
        }
    }
}
@Composable internal fun NodeTitleEditor(draft:NodeTitleDraft,sharedCount:Int,busy:Boolean,unknown:Boolean,message:String?,
    modifier:Modifier=Modifier,text:TextFieldValue,onText:(TextFieldValue)->Unit,cancel:()->Unit,submit:(String)->Unit,retry:()->Unit){
    val requester=remember{FocusRequester()}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(draft.token){requester.requestFocus();keyboard?.show()}
    fun commit(){if(!busy&&!unknown&&text.composition==null&&text.text.isNotBlank())submit(text.text.trim())}
    androidx.activity.compose.BackHandler{if(!busy&&!unknown)cancel()}
    Surface(modifier.testTag("node-title-editor"),shape=InkTheme.ToolShape,color=InkTheme.Surface,shadowElevation=InkTheme.FloatingElevation){
        Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())){
            OutlinedTextField(text,{if(it.text.length<=120)onText(it)},enabled=!busy&&!unknown,label={Text(if(draft.creating)"新主题标题"else"修改标题")},singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={commit()}),modifier=Modifier.fillMaxWidth().focusRequester(requester).testTag("node-title-input"))
            if(!draft.creating&&!draft.structural)Text("共享标题 · $sharedCount 个引用位置",style=MaterialTheme.typography.labelSmall,color=Quiet)
            message?.let{Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("node-title-error"))}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                TextButton(cancel,enabled=!busy&&!unknown,modifier=Modifier.heightIn(min=48.dp).testTag("node-title-cancel")){Text("取消")}
                TextButton({if(unknown)retry()else commit()},enabled=!busy&&(unknown||(text.text.isNotBlank()&&text.composition==null)),modifier=Modifier.heightIn(min=48.dp).testTag("node-title-save")){Text(if(unknown)"核对原操作"else"完成")}
            }
        }
    }
}
@Composable internal fun MapMenuSection(label:String,content:@Composable ColumnScope.()->Unit){
    Text(label,Modifier.padding(horizontal=16.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium,color=Quiet)
    Column(content=content)
}

/** Keep optional 48dp affordances outside node text and the primary toolbar. */
internal fun mapAccessoryBounds(node:android.graphics.RectF,blocked:List<android.graphics.RectF>,width:Float,height:Float,edge:Float,gap:Float,leftFirst:Boolean):android.graphics.RectF?{
    val sides=listOf(node.left-edge-gap to node.centerY()-edge/2,node.right+gap to node.centerY()-edge/2)
    val candidates=(if(leftFirst)sides else sides.reversed())+listOf(node.left to node.top-edge-gap,node.right-edge to node.top-edge-gap,node.left to node.bottom+gap,node.right-edge to node.bottom+gap)
    return candidates.map{(x,y)->android.graphics.RectF(x,y,x+edge,y+edge)}.firstOrNull{b->b.left>=0&&b.top>=0&&b.right<=width&&b.bottom<=height&&blocked.none{android.graphics.RectF.intersects(b,it)}}
}
