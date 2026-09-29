// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

/** Debug laboratory only. All author reads and writes come from the required session. */
@Composable internal fun ShadowReplicaPanel(session:ShadowAuthorSession,book:String,refresh:Int=0,onBusy:(Boolean)->Unit={}){
    val scope=rememberCoroutineScope();var changed by remember{mutableIntStateOf(0)}
    var pages by remember{mutableStateOf(emptyList<NotebookPageRow>())};var pageId by remember{mutableStateOf(book)}
    var ink by remember{mutableStateOf<InkPage?>(null)};var objects by remember{mutableStateOf(emptyList<PageObject>())}
    var tab by remember{mutableIntStateOf(0)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    SideEffect{onBusy(busy)}
    var unsupported by remember{mutableIntStateOf(0)}
    var conflicts by remember{mutableStateOf(emptyList<ShadowConflict>())};var editor by remember{mutableStateOf<StudyCardRow?>(null)}
    var source by remember{mutableStateOf<StudySourceRow?>(null)}
    val scenes by remember(session,book){session.maps.observe(book)}.collectAsState(emptyList())
    var mapId by remember{mutableStateOf<String?>(null)}
    fun work(action:suspend()->Unit){if(busy)return;busy=true;error=null;scope.launch{try{withContext(Dispatchers.IO){action()};changed++}catch(c:CancellationException){throw c}catch(e:Exception){error=when(e.message){"SHADOW_CONFLICT_CHANGED"->"版本已变化，请重新核对双方内容。";"SHADOW_RESOLUTION_REQUIRED"->"此内容有两个版本，请先处理冲突。";else->"操作未确认，原资料保留，请重试原操作。"}}finally{busy=false}}}
    LaunchedEffect(session,book,pageId,refresh,changed){try{withContext(Dispatchers.IO){pages=session.pages.activePages(book);ink=session.ink.read(pageId);objects=session.objects.read(pageId).objects;conflicts=session.replica.semanticConflicts();unsupported=session.replica.unsupportedConflicts()}}catch(c:CancellationException){throw c}catch(_:Exception){error="页面不可用，旧资料保留。"}}
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White).safeDrawingPadding().testTag("shadow-native-panel")){
        Text("隔离接收库 · 合成资料",Modifier.padding(12.dp),style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){listOf("页面","导图","版本冲突").forEachIndexed{i,title->FilterChip(tab==i,{tab=i},label={Text(title)},modifier=Modifier.padding(horizontal=4.dp).testTag("shadow-tab-$i"))}}
        error?.let{Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error)}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        when(tab){
            0->{
                Row(Modifier.horizontalScroll(rememberScrollState())){pages.forEach{p->TextButton({pageId=p.id},enabled=!busy,modifier=Modifier.testTag("shadow-page-${p.position}")){Text("第 ${p.position+1} 页")}}}
                val page=pages.find{it.id==pageId};val loaded=ink
                if(page!=null&&loaded!=null)Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=androidx.compose.ui.Alignment.Center){AndroidView(factory={InkCanvasView(it).apply{authorSession=session;tag="shadow-page-canvas"}},update={v->
                    v.configure(page.world,PaperStyle.entries[page.paper],null);v.showDocument(page.id);v.showStrokes(InkSession(loaded).visibleDraft());v.showObjects(objects);v.allowInput=false
                },modifier=Modifier.fillMaxHeight().aspectRatio(1000f/1414f,matchHeightConstraintsFirst=true).testTag("shadow-paper"))}
            }
            1->{
                Row(Modifier.horizontalScroll(rememberScrollState())){scenes.filter{it.available}.forEach{s->TextButton({mapId=s.ref.mapId},modifier=Modifier.testTag("shadow-map-${s.ref.mapId?:"main"}")){Text(s.title)}}}
                val scene=scenes.find{it.ref.mapId==mapId}
                if(scene!=null)AndroidView(factory={MindMapView(it).apply{tag="shadow-map-canvas"}},update={v->
                    val nodes=scene.nodes.map{StudyNodeRow(it.id,book,it.cardId?:it.id,it.parentId,it.x,it.y,it.revision)}
                    val cards=scene.nodes.map{StudyCardRow(it.cardId?:it.id,book,it.contentRevision,it.title,it.body)}
                    v.show(nodes,cards);v.enabledInput=!busy
                    v.onOpen={node->work{editor=session.study.cards(book).first().find{it.id==node.cardId}}}
                },modifier=Modifier.fillMaxWidth().weight(1f).testTag("shadow-map"))
            }
            2->Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)){
                if(conflicts.isEmpty()&&unsupported==0)Text("没有可处理的版本冲突")
                if(unsupported>0)Text("仍有复杂关联冲突，双方均保留；本轮暂不能处理，请停止编辑此内容。")
                conflicts.forEach{conflict->
                    Text("${conflict.kind} · ${conflict.title}",style=MaterialTheme.typography.titleMedium)
                    Text("选择一份完整版本，另一份保留在同步历史中。",style=MaterialTheme.typography.bodySmall)
                    conflict.choices.forEach{choice->
                        val operation=remember(conflict.frozen,choice.revision){UUID.randomUUID().toString()}
                        OutlinedCard(Modifier.fillMaxWidth().padding(vertical=8.dp)){
                            Column(Modifier.padding(12.dp)){
                                Text(choice.title);if(choice.detail.isNotEmpty())Text(choice.detail)
                                TextButton({work{session.replica.resolve(conflict,choice.revision,operation)}},enabled=!busy,modifier=Modifier.testTag("shadow-choice-${if(choice.local)"local"else"other"}")){Text(if(choice.local)"保留本机版本"else"采用另一版本")}
                            }
                        }
                    }
                }
            }
        }
    }
    editor?.let{card->
        var title by remember(card){mutableStateOf(card.title)};var body by remember(card){mutableStateOf(card.body)}
        val operation=remember(card){UUID.randomUUID().toString()}
        StudyDialog(false,{if(!busy)editor=null},title={Text("知识卡")},text={Column(Modifier.verticalScroll(rememberScrollState())){
            OutlinedTextField(title,{title=it},label={Text("标题")},modifier=Modifier.testTag("shadow-card-title"))
            OutlinedTextField(body,{body=it},label={Text("内容")},modifier=Modifier.testTag("shadow-card-body"))
            TextButton({work{source=session.study.source(card.id)}},enabled=!busy,modifier=Modifier.testTag("shadow-card-source")){Text("查看来源")}
        }},confirmButton={TextButton({work{session.replica.author{db->StudyRepository(db).submit(StudyCommand(operation,book,StudyAction.EDIT,cardId=card.id,expectedRevision=card.revision,title=title,body=body))};editor=null}},enabled=!busy,modifier=Modifier.testTag("shadow-card-save")){Text("保存")}},dismissButton={TextButton({editor=null},enabled=!busy){Text("取消")}})
    }
    source?.let{ReviewSourceDialog(it,{source=null},session)}
}
