// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.inkweft.core.*

internal fun NotebookCover.label()=when(this){
    NotebookCover.AUTO->"自动配色";NotebookCover.CONTENT->"页面预览";NotebookCover.FOREST->"松绿";NotebookCover.INK->"深海";
    NotebookCover.SAND->"砂岩";NotebookCover.ROSE->"烟粉";NotebookCover.LILAC->"暮紫";NotebookCover.GRID->"格线";NotebookCover.WAVE->"山岚";NotebookCover.CUSTOM->"自定义"
}

/** Original vector-only jackets. No downloaded images, font files or paper mutation. */
@Composable
internal fun NotebookCoverArt(style:NotebookCover,noteId:String,title:String,world:Boolean,modifier:Modifier=Modifier){
    val resolved=style.resolved(noteId)
    val base=when(resolved){NotebookCover.FOREST->Color(0xffdceae0);NotebookCover.INK->Color(0xffe5eaf4);NotebookCover.SAND->Color(0xffefe9dc);NotebookCover.ROSE->Color(0xfff0e5e5);NotebookCover.LILAC->Color(0xffeae6f5);NotebookCover.GRID->Color(0xffe4e9e5);NotebookCover.WAVE->Color(0xffdae7e3);else->Color(0xffeef2ef)}
    if(resolved in listOf(NotebookCover.FOREST,NotebookCover.INK,NotebookCover.SAND,NotebookCover.ROSE,NotebookCover.LILAC)){
        CustomCoverArt(CustomCover(color=base.toArgb()),title,modifier);return
    }
    val ink=when(resolved){NotebookCover.INK->Color(0xff354b69);NotebookCover.SAND->Color(0xff635946);NotebookCover.ROSE->Color(0xff704e50);NotebookCover.LILAC->Color(0xff5c547b);else->Color(0xff27483e)}
    Box(modifier.clip(RoundedCornerShape(7.dp)).background(base).clearAndSetSemantics{}){
        Canvas(Modifier.fillMaxSize()){
            val w=size.width;val h=size.height
            drawRect(Color.Black.copy(alpha=.12f),size=Size(w*.06f,h))
            drawLine(Color.White.copy(alpha=.20f),Offset(w*.065f,0f),Offset(w*.065f,h),1.dp.toPx())
            when(resolved){
                NotebookCover.GRID->{for(i in 1..10)drawLine(Color(0xff9bb4a5).copy(alpha=.40f),Offset(w*i/10,0f),Offset(w*i/10,h),.6.dp.toPx());for(i in 1..15)drawLine(Color(0xff9bb4a5).copy(alpha=.40f),Offset(0f,h*i/15),Offset(w,h*i/15),.6.dp.toPx())}
                NotebookCover.WAVE->{
                    val p=Path().apply{moveTo(0f,h*.7f);cubicTo(w*.25f,h*.35f,w*.57f,h*.95f,w,h*.53f);lineTo(w,h);lineTo(0f,h);close()};drawPath(p,Color(0xff89aa9b))
                    val q=Path().apply{moveTo(0f,h*.84f);cubicTo(w*.45f,h*.62f,w*.6f,h*.91f,w,h*.71f);lineTo(w,h);lineTo(0f,h);close()};drawPath(q,Color(0xff436f5e))
                }
                NotebookCover.CONTENT->{for(i in 0..6)drawLine(Color(0xffc3d1c9),Offset(w*.17f,h*(.32f+i*.075f)),Offset(w*.87f,h*(.32f+i*.075f)),1.dp.toPx())}
                else->{drawRect(ink.copy(alpha=.52f),Offset(w*.15f,h*.1f),Size(w*.70f,h*.74f),style=Stroke(.6.dp.toPx()));drawLine(ink.copy(alpha=.55f),Offset(w*.24f,h*.30f),Offset(w*.76f,h*.30f),.8.dp.toPx());drawCircle(ink.copy(alpha=.08f),w*.46f,Offset(w*.84f,h*.9f))}
            }
        }
        Column(Modifier.fillMaxSize().padding(start=18.dp,end=14.dp,top=20.dp,bottom=15.dp),verticalArrangement=Arrangement.SpaceBetween){
            Text(if(world)"自由记录"else"学习笔记",fontSize=9.sp,letterSpacing=1.sp,color=ink.copy(alpha=.84f),maxLines=1)
            Text(title.ifBlank{"我的笔记"},fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.Medium,color=ink,maxLines=3,overflow=TextOverflow.Ellipsis)
            Text("墨织  /  INKWEFT",fontSize=7.sp,letterSpacing=.5.sp,color=ink.copy(alpha=.8f),maxLines=1)
        }
    }
}

@Composable
internal fun CoverChoices(selected:NotebookCover,title:String,world:Boolean,onSelected:(NotebookCover)->Unit){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        NotebookCover.entries.filter{it!=NotebookCover.CUSTOM}.forEach { style ->
            Column(Modifier.width(92.dp).selectable(selected==style,role=Role.RadioButton,onClick={onSelected(style)}).testTag("cover-choice-${style.key}").padding(3.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Box(Modifier.height(120.dp).fillMaxWidth().border(if(selected==style)2.dp else 1.dp,if(selected==style)Forest else Line,RoundedCornerShape(8.dp)).padding(3.dp)){
                    NotebookCoverArt(style,"preview",title,world,Modifier.fillMaxSize())
                }
                Text(style.label(),fontSize=11.sp,color=if(selected==style)Forest else Quiet,modifier=Modifier.padding(vertical=8.dp))
            }
        }
    }
}

@Composable
internal fun RenameNoteDialog(state:RenameUi,edit:(String)->Unit,save:()->Unit,dismiss:()->Unit){
    val busy=state.phase==SavePhase.SAVING;val unknown=state.phase==SavePhase.UNKNOWN
    AlertDialog(onDismissRequest=dismiss,modifier=Modifier.testTag("rename-dialog"),title={Text("重命名笔记")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(state.value,edit,enabled=state.phase==SavePhase.EDITING,singleLine=true,label={Text("笔记名称")},supportingText={Text("${state.value.length}/120")},isError=!RenameNote.validTitle(state.value.trim()),modifier=Modifier.fillMaxWidth().testTag("rename-title"))
            Text("只保存名称。笔记身份、页内文字、手写、分类和来源不变；未保存的正文仍作为草稿保留。",fontSize=12.sp,lineHeight=20.sp,color=Quiet)
            if(state.message!=null)Text(state.message,color=Color(0xff9a5128),fontSize=12.sp,modifier=Modifier.testTag("rename-message"))
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(onClick=save,enabled=state.phase in listOf(SavePhase.EDITING,SavePhase.UNKNOWN)&&RenameNote.validTitle(state.value.trim()),modifier=Modifier.testTag("confirm-rename")){Text(if(unknown)"核对重试"else"保存名称")}},dismissButton={TextButton(onClick=dismiss,enabled=!busy&&!unknown,modifier=Modifier.testTag("cancel-rename")){Text("取消")}})
}
