// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal data class StudyUi(val cards:List<StudyCardRow> = emptyList(),val nodes:List<StudyNodeRow> = emptyList(),val loading:Boolean=true,val busy:Boolean=false,val message:String?=null,val unknown:Boolean=false,val completed:String?=null)
internal class StudyViewModel(val book:String,val repo:StudyRepository,private val saved:SavedStateHandle):ViewModel(){
    private var pending:StudyCommand?=restorePending()
    private val state=MutableStateFlow(StudyUi(unknown=pending!=null,message=if(pending!=null)"上次摘要操作待核对，请重试原操作。"else null));val ui=state.asStateFlow()
    init{viewModelScope.launch{try{combine(repo.cards(book),repo.nodes(book)){c,n->c to n}.collect{(c,n)->state.update{it.copy(cards=c,nodes=n,loading=false)}}}catch(c:CancellationException){throw c}catch(_:Exception){state.update{it.copy(loading=false,message="学习资料读取失败；原笔记保留。")}}}}
    fun submit(c:StudyCommand){if(ui.value.busy||pending!=null)return;pending=c;persistPending();execute()}
    fun retry(){if(!ui.value.busy&&pending!=null)execute()}
    private fun execute(){val c=pending?:return;state.update{it.copy(busy=true,message=null,completed=null)}
        viewModelScope.launch{try{val id=withContext(Dispatchers.IO){repo.submit(c)};pending=null;persistPending();state.update{it.copy(busy=false,unknown=false,completed=id)}}
        catch(c:CancellationException){state.update{it.copy(busy=false,unknown=true)};throw c}
        catch(_:IllegalArgumentException){pending=null;persistPending();state.update{it.copy(busy=false,unknown=false,message="未提交：内容版本、来源、层级或容量不符。请重新核对；有子节点时先整理子节点，卡片回收前先移除它的节点。")}}
        catch(_:Exception){state.update{it.copy(busy=false,unknown=true,message="操作结果待核对。重试会查询同一操作，不重复建卡。")}}}
    }
    fun clear(){state.update{it.copy(message=null,completed=null)}}
    // Lifecycle restoration, not a promise to preserve an unsaved editor after force-stop.
    // Snapshot ink stays in the DB; only the user-confirmed bounded request is in saved state.
    private fun persistPending(){
        val c=pending
        saved.set<ArrayList<String>?>("study.command",c?.let{arrayListOf(it.id,it.notebookId,it.action.name,it.cardId.orEmpty(),it.nodeId.orEmpty(),it.expectedRevision.toString(),it.parentId.orEmpty(),it.title,it.body,it.x.toString(),it.y.toString(),it.expectedGraph)})
        saved.set<ArrayList<String>?>("study.source",c?.source?.let{arrayListOf(it.pageId,it.inkRevision.toString(),it.bounds.left.toString(),it.bounds.top.toString(),it.bounds.right.toString(),it.bounds.bottom.toString(),*it.strokeIds.toTypedArray())})
    }
    private fun restorePending():StudyCommand?{
        val values=saved.get<ArrayList<String>>("study.command")?:return null
        require(values.size==12&&values[1]==book)
        val s=saved.get<ArrayList<String>>("study.source")?.let{require(it.size in 7..262);StudySourceDraft(it[0],it[1].toLong(),CanvasBounds(it[2].toDouble(),it[3].toDouble(),it[4].toDouble(),it[5].toDouble()),it.drop(6))}
        return StudyCommand(values[0],book,StudyAction.valueOf(values[2]),values[3].ifEmpty{null},values[4].ifEmpty{null},values[5].toLong(),values[6].ifEmpty{null},values[7],values[8],values[9].toDouble(),values[10].toDouble(),s,values[11])
    }
    class Factory(val book:String,val repo:StudyRepository):ViewModelProvider.Factory{override fun<T:ViewModel>create(c:Class<T>,extras:CreationExtras):T{require(c.isAssignableFrom(StudyViewModel::class.java));@Suppress("UNCHECKED_CAST")return StudyViewModel(book,repo,extras.createSavedStateHandle()) as T}}
}
private data class CardEditor(val card:StudyCardRow?=null,val parent:StudyNodeRow?=null,val source:StudySourceDraft?=null)
@Composable
internal fun StudyWorkspace(note:NoteDraft,initialSource:StudySourceDraft?,dismiss:()->Unit,openSource:(StudySourceRow)->Boolean){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication
    val focus=LocalFocusManager.current
    val vm:StudyViewModel=viewModel(key="study-${note.base.id}",factory=StudyViewModel.Factory(note.base.id,app.study))
    val ui by vm.ui.collectAsStateWithLifecycle();val scope=rememberCoroutineScope()
    var query by remember{mutableStateOf("")}
    var tab by remember{mutableIntStateOf(0)};var showTrash by remember{mutableStateOf(false)}
    var editor by remember(initialSource){mutableStateOf(if(initialSource!=null)CardEditor(source=initialSource)else null)}
    var chosenNode by remember{mutableStateOf<StudyNodeRow?>(null)};var chosenCard by remember{mutableStateOf<StudyCardRow?>(null)}
    var source by remember{mutableStateOf<StudySourceRow?>(null)};var stale by remember{mutableStateOf(false)}
    var map by remember{mutableStateOf<MindMapView?>(null)};var dragging by remember{mutableStateOf(false)}
    var pendingExport by remember{mutableStateOf<String?>(null)};var localMessage by remember{mutableStateOf<String?>(null)}
    var reparent by remember{mutableStateOf<StudyNodeRow?>(null)}
    val id={UUID.randomUUID().toString()}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->
        val text=pendingExport;pendingExport=null;if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){checkNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use{it.write(text)}};localMessage="大纲与摘要已导出，共享卡片正文只保留一份。图形布局与来源原迹请用资料库备份保存。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认；卡片仍保留在本机。"}}
    }
    LaunchedEffect(chosenCard?.id){source=null;stale=false;val card=chosenCard?:return@LaunchedEffect
        try{source=withContext(Dispatchers.IO){app.study.source(card.id)};source?.let{s->stale=withContext(Dispatchers.IO){app.pages.inkRevision(s.pageId)!=s.inkRevision}}}
        catch(c:CancellationException){throw c}catch(_:Exception){localMessage="来源快照读取失败，卡片文字仍保留。"}}
    LaunchedEffect(ui.completed){if(ui.completed!=null){editor=null;chosenNode=null;chosenCard=null;reparent=null;vm.clear()}}
    fun openCard(card:StudyCardRow,node:StudyNodeRow?=null){focus.clearFocus();chosenNode=node;chosenCard=card}
    Dialog(onDismissRequest={if(!ui.busy&&!ui.unknown)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.95f),color=androidx.compose.ui.graphics.Color.White){Column(Modifier.fillMaxSize().padding(16.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text("学习工作台",fontSize=21.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                TextButton(onClick={editor=CardEditor()},enabled=!ui.busy&&!ui.unknown,modifier=Modifier.testTag("study-add-card")){Text("＋ 新摘要卡")}
                TextButton(onClick={pendingExport=StudyText.markdown(note.title,ui.cards.filter{it.trashedAt==null}.map{StudyTextCard(it.id,it.title,it.body)},ui.nodes.map{it.model()});export.launch("墨织摘要.md")},enabled=!ui.busy&&ui.cards.isNotEmpty()){Text("导出大纲与摘要")}
                TextButton(onClick=dismiss,enabled=!ui.busy&&!ui.unknown,modifier=Modifier.testTag("study-close")){Text("返回笔记")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("摘要卡","大纲","思维导图").forEachIndexed{i,label->FilterChip(selected=tab==i,onClick={focus.clearFocus();tab=i},label={Text(label)},modifier=Modifier.testTag("study-tab-$i"))}
                if(tab==0)FilterChip(selected=showTrash,onClick={showTrash=!showTrash},label={Text("卡片回收区")})
                if(tab==2){TextButton(onClick={map?.fit()}){Text("适配全部")};TextButton(onClick={map?.zoom(1.2f)}){Text("＋")};TextButton(onClick={map?.zoom(1/1.2f)}){Text("−")}
                    TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.ARRANGE,expectedGraph=StudyGraph.orderHash(ui.nodes.map{it.model()})))},enabled=!ui.busy&&!ui.unknown&&!dragging,modifier=Modifier.testTag("study-arrange")){Text("自动排布")}}
            }
            val msg=ui.message?:localMessage
            if(msg!=null)Text(msg,fontSize=12.sp,color=Forest,modifier=Modifier.testTag("study-message"))
            if(ui.unknown)TextButton(onClick=vm::retry,enabled=!ui.busy,modifier=Modifier.testTag("study-retry")){Text("核对原操作")}
            if(ui.loading||ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(tab==0)OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索摘要标题或正文")},
                trailingIcon={if(query.isNotEmpty())TextButton(onClick={query=""}){Text("清除")}},modifier=Modifier.fillMaxWidth().testTag("study-search"))
            val cards=ui.cards.filter{(it.trashedAt!=null)==showTrash&&StudyText.matches(StudyTextCard(it.id,it.title,it.body),query)};val active=ui.nodes.filter{!it.removed};val cardById=ui.cards.associateBy{it.id}
            if(tab==2)AndroidView(factory={MindMapView(it).also{v->map=v}},update={v->v.enabledInput=!ui.busy&&!ui.unknown;v.show(active,ui.cards);v.onActive={dragging=it};v.onOpen={n->cardById[n.cardId]?.let{openCard(it,n)}};v.onMove={n,x,y->if(!ui.busy&&!ui.unknown)vm.submit(StudyCommand(id(),note.base.id,StudyAction.MOVE,nodeId=n.id,expectedRevision=n.revision,x=x,y=y))}},modifier=Modifier.fillMaxWidth().weight(1f).testTag("study-map"))
            else LazyColumn(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)){
                if(tab==0){items(cards,key={it.id}){card->OutlinedCard(onClick={openCard(card)},modifier=Modifier.fillMaxWidth().testTag("study-card-${card.id}")){
                    Column(Modifier.padding(16.dp)){Text(card.title,fontWeight=FontWeight.SemiBold);if(card.body.isNotBlank())Text(card.body,maxLines=4,fontSize=14.sp,modifier=Modifier.padding(top=8.dp));Text("摘要卡 · ${active.count{it.cardId==card.id}} 个脑图位置",fontSize=11.sp,color=Quiet)}}}
                    if(cards.isEmpty())item{Text(if(query.isNotBlank())"没有匹配的摘要卡"else if(showTrash)"卡片回收区为空"else"框选手写摘录，或新建摘要卡。摘要由你填写，不会自动发送到云端。",color=Quiet)}}
                else{val ordered=outline(active);items(ordered,key={it.first.id}){(node,depth)->val card=cardById[node.cardId]
                    if(card!=null)OutlinedCard(onClick={openCard(card,node)},modifier=Modifier.fillMaxWidth().padding(start=(depth.coerceAtMost(10)*20).dp).testTag("outline-node-${node.id}")){
                        Column(Modifier.padding(12.dp)){Text(card.title,fontWeight=FontWeight.Medium);if(card.body.isNotBlank())Text(card.body,maxLines=2,fontSize=12.sp,color=Quiet)}}
                };if(active.isEmpty())item{Text("大纲与脑图使用同一组节点和摘要卡，不另存一份正文。",color=Quiet)}}
            }
        }}
    }
    editor?.let{e->key(e.card?.id,e.parent?.id,e.source?.pageId){
        var title by remember{mutableStateOf(e.card?.title.orEmpty())};var text by remember{mutableStateOf(e.card?.body.orEmpty())}
        AlertDialog(onDismissRequest={if(!ui.busy&&!ui.unknown)editor=null},modifier=Modifier.testTag("study-card-editor"),title={Text(if(e.card!=null)"编辑共享摘要卡"else"新建摘要卡")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(e.source!=null)Text("保留框选原迹快照及回源位置。下方是你的摘要，不是自动识别或 AI 生成。",fontSize=12.sp,color=Quiet)
            OutlinedTextField(title,{if(it.length<=120)title=it},label={Text("标题")},modifier=Modifier.fillMaxWidth().testTag("study-card-title"))
            OutlinedTextField(text,{if(it.length<=20000)text=it},label={Text("我的理解 / 摘要")},modifier=Modifier.fillMaxWidth().heightIn(min=160.dp).testTag("study-card-body"))
            if(ui.message!=null)Text(ui.message!!,fontSize=12.sp)
        }},confirmButton={TextButton(onClick={val old=e.card;val parent=e.parent
            vm.submit(StudyCommand(id(),note.base.id,if(old==null)StudyAction.CREATE else StudyAction.EDIT,cardId=old?.id?:id(),nodeId=if(old==null)id()else null,
                expectedRevision=old?.revision?:0L,parentId=parent?.id,title=title.trim(),body=text,x=(if(parent==null)40.0 else parent.x+260).coerceIn(-40000.0,40000.0),y=(if(parent==null)ui.nodes.count{!it.removed}*128.0+80 else parent.y+128).coerceIn(-40000.0,40000.0),source=e.source))
        },enabled=title.isNotBlank()&&!ui.busy&&!ui.unknown,modifier=Modifier.testTag("study-save-card")){Text("保存")}},dismissButton={TextButton(onClick={editor=null},enabled=!ui.busy&&!ui.unknown){Text("取消")}})
    }}
    chosenCard?.let{chosen->val card=ui.cards.find{it.id==chosen.id}?:chosen;val node=chosenNode?.let{n->ui.nodes.find{it.id==n.id}?:n}
        AlertDialog(onDismissRequest={chosenCard=null;chosenNode=null},modifier=Modifier.testTag("study-card-details"),title={Text(card.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(card.body.ifBlank{"尚未填写摘要"})
            if(ui.message!=null)Text(ui.message!!,fontSize=12.sp)
            if(localMessage!=null)Text(localMessage!!,fontSize=12.sp)
            source?.let{s->val strokes by produceState<List<InkStroke>>(emptyList(),s.cardId){value=withContext(Dispatchers.Default){InkPageFile.decode(s.snapshot).strokes}}
                Text(if(stale)"来源页面已变化，下方保留摘录时快照。"else"摘录时的原迹快照",fontSize=12.sp,color=Quiet)
                AndroidView(factory={InkCanvasView(it).apply{preview=true}},update={it.configure(true,PaperStyle.BLANK,null);it.showStrokes(strokes)},modifier=Modifier.fillMaxWidth().height(150.dp))
                TextButton(onClick={if(openSource(s)){chosenCard=null;chosenNode=null;dismiss()}else localMessage="来源页已回收或不可用；原迹快照仍保留。"},modifier=Modifier.testTag("study-open-source")){Text("返回来源区域")}}
            if(card.trashedAt==null){
                TextButton(onClick={editor=CardEditor(card);chosenCard=null},modifier=Modifier.testTag("study-edit-card")){Text("编辑内容（全部引用同步）")}
                TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REUSE,cardId=card.id,nodeId=id(),y=ui.nodes.count{!it.removed}*128.0+80))},modifier=Modifier.testTag("study-reuse-card")){Text("复用到脑图新位置")}
                if(node!=null){
                    TextButton(onClick={editor=CardEditor(parent=node);chosenCard=null},modifier=Modifier.testTag("study-add-child")){Text("添加子主题")}
                    TextButton(onClick={reparent=node;chosenCard=null}){Text("修改上级主题")}
                    TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REMOVE_NODE,nodeId=node.id,expectedRevision=node.revision))},modifier=Modifier.testTag("study-remove-node")){Text("移除此节点（保留摘要卡）")}
                }
                TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.TRASH_CARD,cardId=card.id,expectedRevision=card.revision))},enabled=ui.nodes.none{it.cardId==card.id&&!it.removed}){Text("移入卡片回收区")}
            }else TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.RESTORE_CARD,cardId=card.id,expectedRevision=card.revision))}){Text("恢复卡片")}
        }},confirmButton={TextButton(onClick={chosenCard=null;chosenNode=null}){Text("关闭")}})
    }
    reparent?.let{node->AlertDialog(onDismissRequest={reparent=null},title={Text("选择上级主题")},text={Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState())){
        val active=ui.nodes.filter{!it.removed&&it.id!=node.id};val options=listOf<StudyNodeRow?>(null)+active
        options.forEach{p->TextButton(onClick={vm.submit(StudyCommand(id(),note.base.id,StudyAction.REPARENT,nodeId=node.id,expectedRevision=node.revision,parentId=p?.id))}){Text(p?.let{n->ui.cards.find{it.id==n.cardId}?.title}?:"无上级（根主题）")}}
    }},confirmButton={TextButton(onClick={reparent=null}){Text("取消")}})}
}
private fun outline(nodes:List<StudyNodeRow>):List<Pair<StudyNodeRow,Int>>{
    val groups=nodes.groupBy{it.parentId};val out=ArrayList<Pair<StudyNodeRow,Int>>();val seen=mutableSetOf<String>()
    fun walk(parent:String?,depth:Int){if(depth>128)return;groups[parent].orEmpty().forEach{n->if(seen.add(n.id)){out+=n to depth;walk(n.id,depth+1)}}}
    walk(null,0);return out
}
