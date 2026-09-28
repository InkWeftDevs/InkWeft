// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

@Composable internal fun SourceThumbnail(card:String,modifier:Modifier=Modifier){
    val app=LocalContext.current.applicationContext as InkWeftApplication
    val snapshot by produceState<InkPageFile?>(null,card){value=withContext(Dispatchers.IO){runCatching{app.study.source(card)?.let{InkPageFile.decode(it.snapshot)}}.getOrNull()}}
    Box(modifier){snapshot?.let{file->AndroidView(factory={InkCanvasView(it).apply{preview=true}},update={v->v.configure(true,PaperStyle.BLANK,null);v.showStrokes(file.strokes);v.showObjects(file.objects)},modifier=Modifier.fillMaxSize().testTag("excerpt-preview-$card"))}}
}

/** Excerpts keep their source image; map placement is an optional follow-up. */
@Composable internal fun ColumnScope.ExcerptCollection(vm:StudyViewModel,pages:List<NotebookPageRow>,ready:Boolean,open:(StudySourceRow)->Unit,map:(String)->Unit){
    val ui by vm.ui.collectAsStateWithLifecycle()
    val regions by remember(vm){vm.repo.excerpts(vm.book)}.collectAsStateWithLifecycle(initialValue=emptyList())
    var query by rememberSaveable{mutableStateOf("")}
    var editing by rememberSaveable{mutableStateOf<String?>(null)}
    val enabled=ready&&!ui.busy&&!ui.unknown&&!ui.loading&&!ui.readFailed
    LaunchedEffect(ui.completed){if(ui.completed!=null){editing=null;vm.clear()}}
    OutlinedTextField(query,{query=it},placeholder={Text("查找摘录或备注")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(8.dp).testTag("excerpt-search"))
    if(ui.busy||ui.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
    ui.message?.let{Text(it,Modifier.padding(8.dp),style=MaterialTheme.typography.bodySmall)}
    if(ui.unknown)TextButton(vm::retry,enabled=!ui.busy,modifier=Modifier.testTag("excerpt-retry")){Text("核对本次摘录")}
    if(ui.readFailed)TextButton(vm::refresh){Text("重新读取")}
    val positions=regions.associateBy{it.id}
    val cards=ui.cards.filter{it.id in positions&&it.trashedAt==null&&(it.title.contains(query,true)||it.body.contains(query,true))}
        .sortedWith(compareBy<StudyCardRow>{c->pages.find{it.id==positions[c.id]?.pageId}?.position?:Int.MAX_VALUE}.thenBy{positions[it.id]?.top}.thenBy{positions[it.id]?.left})
    LazyColumn(Modifier.weight(1f).testTag("excerpt-list"),contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        if(cards.isEmpty())item{Text(if(query.isBlank())"使用摘要笔框选页面区域"else"没有匹配的摘录",color=Quiet,modifier=Modifier.padding(12.dp))}
        items(cards,key={it.id}){card->
            val source by produceState<StudySourceRow?>(null,card.id){value=withContext(Dispatchers.IO){vm.repo.source(card.id)}}
            source?.let{original->
                Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),border=BorderStroke(1.dp,Line),color=androidx.compose.ui.graphics.Color.White){Column(Modifier.padding(10.dp).testTag("excerpt-item-${card.id}")){
                    SourceThumbnail(card.id,Modifier.fillMaxWidth().height(150.dp).clickable(enabled=enabled&&pages.any{it.id==original.pageId}){open(original)})
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text(pages.find{it.id==original.pageId}?.let{"第${it.position+1}页"}?:"来源页已回收",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=Quiet)
                        TextButton({editing=if(editing==card.id)null else card.id},enabled=enabled,modifier=Modifier.testTag("excerpt-comment-${card.id}")){Text("备注")}
                        var menu by remember{mutableStateOf(false)}
                        Box{IconButton({menu=true},enabled=enabled,modifier=Modifier.testTag("excerpt-menu-${card.id}").describedAs("摘录操作")){Glyph("more")}
                            DropdownMenu(menu,{menu=false}){
                                DropdownMenuItem(text={Text("返回原文")},enabled=pages.any{it.id==original.pageId},onClick={menu=false;open(original)})
                                DropdownMenuItem(text={Text("整理到导图")},onClick={menu=false;map(card.id)})
                                DropdownMenuItem(text={Text("删除摘录")},onClick={menu=false;vm.submit(StudyCommand(UUID.randomUUID().toString(),vm.book,StudyAction.TRASH_CARD,cardId=card.id,expectedRevision=card.revision))},modifier=Modifier.testTag("excerpt-delete"))
                            }
                        }
                    }
                    if(editing==card.id){
                        var draft by rememberSaveable(card.id,card.revision){mutableStateOf(card.body)}
                        OutlinedTextField(draft,{if(it.length<=20000)draft=it},enabled=enabled,placeholder={Text("添加备注")},modifier=Modifier.fillMaxWidth().testTag("excerpt-comment-input"))
                        TextButton({vm.submit(StudyCommand(UUID.randomUUID().toString(),vm.book,StudyAction.EDIT,cardId=card.id,expectedRevision=card.revision,title=card.title,body=draft))},enabled=enabled,modifier=Modifier.testTag("excerpt-comment-save")){Text("保存备注")}
                    }else if(card.body.isNotBlank())Text(card.body,modifier=Modifier.padding(top=4.dp),style=MaterialTheme.typography.bodyMedium)
                }}
            }
        }
    }
}

@Composable internal fun ExcerptMarkers(rows:List<ExcerptRow>,viewport:CanvasViewport){
    val density=LocalDensity.current.density.toDouble()
    Canvas(Modifier.fillMaxSize().testTag("excerpt-markers-overlay")){
        rows.forEach{r->
            val a=viewport.worldToScreen(r.left,r.top,size.width.toDouble(),size.height.toDouble(),density)
            val b=viewport.worldToScreen(r.right,r.bottom,size.width.toDouble(),size.height.toDouble(),density)
            drawRect(androidx.compose.ui.graphics.Color(0xffd15ba8),androidx.compose.ui.geometry.Offset(a.x.toFloat(),a.y.toFloat()),androidx.compose.ui.geometry.Size((b.x-a.x).toFloat(),(b.y-a.y).toFloat()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}
