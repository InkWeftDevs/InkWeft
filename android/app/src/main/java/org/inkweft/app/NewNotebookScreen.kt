// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.inkweft.core.*

/** Draft-only screen: no author writes until the single create action. */
@Composable
internal fun NewNotebookScreen(title:String,onTitle:(String)->Unit,world:Boolean,onWorld:(Boolean)->Unit,
    paper:PaperStyle,onPaper:(PaperStyle)->Unit,cover:NotebookCover,onCover:(NotebookCover)->Unit,
    busy:Boolean,cancel:()->Unit,create:()->Unit){
    var category by rememberSaveable{mutableStateOf("全部")}
    Dialog(onDismissRequest={if(!busy)cancel()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("new-notebook-screen"),color=Color.White){Column(Modifier.safeDrawingPadding().imePadding()){
            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick=cancel,enabled=!busy){Glyph("back");Text("取消")}
                Text("新建笔记",Modifier.weight(1f),fontSize=24.sp)
                Button(onClick=create,enabled=!busy,modifier=Modifier.testTag("create-note")){Glyph("check");Spacer(Modifier.width(8.dp));Text("创建")}
            }
            HorizontalDivider(color=Line)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                val wide=maxWidth>=840.dp
                LazyVerticalGrid(columns=GridCells.Adaptive(148.dp),modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(if(wide)32.dp else 16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    item(span={GridItemSpan(maxLineSpan)}){
                        val preview:@Composable ()->Unit={Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){NotebookCoverArt(cover,"preview",title.ifBlank{"我的笔记"},world,Modifier.width(112.dp).height(158.dp));Text("封面 · 不占正文页",fontSize=12.sp,color=Quiet)}
                            Column(horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.width(112.dp).height(158.dp).border(1.dp,Line)){PaperThumbnail(world,paper)};Text(PaperTemplates.title(paper),fontSize=12.sp,color=Quiet)}
                        }}
                        val properties:@Composable ()->Unit={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                            OutlinedTextField(title,{if(it.length<=120)onTitle(it)},enabled=!busy,singleLine=true,label={Text("笔记标题 · 可以稍后再改")},placeholder={Text("未命名笔记")},modifier=Modifier.fillMaxWidth().testTag("new-title"))
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                FilterChip(!world,{onWorld(false)},enabled=!busy,label={Text("普通多页笔记")},modifier=Modifier.testTag("create-page"))
                                FilterChip(world,{onWorld(true)},enabled=!busy,label={Text("无界笔记")},modifier=Modifier.testTag("create-world"))
                            }
                            Text(if(world)"向四周展开，手指导航，触控笔书写。"else"标准竖页 · 封面与正文分开，创建后可插页。",fontSize=14.sp,color=Quiet)
                            CoverSwatches(cover,onCover)
                        }}
                        if(wide)Row(horizontalArrangement=Arrangement.spacedBy(24.dp),verticalAlignment=Alignment.CenterVertically){preview();Box(Modifier.weight(1f)){properties()}}
                        else Column(verticalArrangement=Arrangement.spacedBy(16.dp)){properties();preview()}
                    }
                    item(span={GridItemSpan(maxLineSpan)}){Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                        HorizontalDivider(color=Line)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)){listOf("全部","基础","学习","计划").forEach{c->FilterChip(category==c,{category=c},label={Text(c)})}}
                        Text("选择纸面",fontSize=20.sp);Text("预览和内页共用同一纸面定义。",fontSize=12.sp,color=Quiet)
                    }}
                    items(PaperStyle.entries.filter{(!world||it.ordinal<4)&&(category=="全部"||PaperTemplates.category(it)==category)},key={it.name}){style->
                        Surface(onClick={onPaper(style)},enabled=!busy,color=if(paper==style)Leaf else Color.White,shape=RoundedCornerShape(12.dp),border=BorderStroke(if(paper==style)2.dp else 1.dp,if(paper==style)Forest else Line),modifier=Modifier.testTag("template-${style.name.lowercase()}")){
                            Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.fillMaxWidth().height(150.dp).border(1.dp,Line)){PaperThumbnail(world,style)};Text(PaperTemplates.title(style),Modifier.padding(top=12.dp),fontSize=14.sp)}
                        }
                    }
                }
            }
        }}
    }
}

@Composable private fun CoverSwatches(selected:NotebookCover,choose:(NotebookCover)->Unit){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        NotebookCover.entries.forEach{style->OutlinedButton(onClick={choose(style)},border=BorderStroke(if(style==selected)2.dp else 1.dp,if(style==selected)Forest else Line),contentPadding=PaddingValues(8.dp),modifier=Modifier.heightIn(min=48.dp).testTag("cover-choice-${style.key}")){Text(style.label(),fontSize=14.sp)}}
    }
}
