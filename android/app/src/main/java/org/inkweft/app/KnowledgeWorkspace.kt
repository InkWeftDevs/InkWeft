// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import java.util.UUID

internal data class KnowledgeUi(val rows:List<KnowledgeRow> = emptyList(),val cards:List<StudyCardRow> = emptyList(),val notes:List<NoteRow> = emptyList(),val pages:List<NotebookPageRow> = emptyList(),
    val busy:Boolean=false,val unknown:Boolean=false,val message:String?=null,val completed:String?=null,val canUndoProperties:Boolean=false)
internal class KnowledgeViewModel(private val repo:KnowledgeRepository,private val saved:SavedStateHandle):ViewModel(){
    private fun restored():KnowledgeCommand?{val fields=saved.get<ArrayList<String>>("knowledge.request")?:return null;require(fields.size==5);return KnowledgeCommand(fields[0],fields[1],fields[2],fields[3].toLong(),KnowledgeCodec.decode(requireNotNull(saved.get<ByteArray>("knowledge.payload"))),fields[4].toBooleanStrict())}
    private var pending=restored();private val state=MutableStateFlow(KnowledgeUi(unknown=pending!=null));val ui=state.asStateFlow()
    init{viewModelScope.launch{try{combine(repo.observe(),repo.cards(),repo.notes(),repo.pages()){r,c,n,p->KnowledgeUi(rows=r,cards=c,notes=n,pages=p)}.collect{snapshot->state.update{it.copy(rows=snapshot.rows,cards=snapshot.cards,notes=snapshot.notes,pages=snapshot.pages)}}}catch(c:CancellationException){throw c}catch(_:Exception){state.update{it.copy(message="知识资料读取失败，请关闭后重试；没有清空原资料。")}}}}
    private var pendingUndo:KnowledgeCommand?=null
    private var undoRequest:KnowledgeCommand?=null
    fun undoProperties(){if(ui.value.busy||pending!=null)return;pending=undoRequest?:return;undoRequest=null;pendingUndo=null;state.update{it.copy(canUndoProperties=false)};persist();retry()}
    private fun persist(){saved["knowledge.request"]=pending?.let{arrayListOf(it.operationId,it.notebookId,it.id,it.expectedRevision.toString(),it.removed.toString())};saved["knowledge.payload"]=pending?.payload}
    fun submit(book:String,data:KnowledgeData,old:KnowledgeRow?=null,remove:Boolean=false){if(state.value.busy||pending!=null)return;pending=KnowledgeCommand(UUID.randomUUID().toString(),book,old?.id?:UUID.randomUUID().toString(),old?.revision?:0,data,remove);pendingUndo=if(data is KnowledgeData.Properties)KnowledgeCommand(UUID.randomUUID().toString(),book,pending!!.id,(old?.revision?:0)+1,old?.data()?:data,old==null)else null;persist();retry()}
    fun retry(){val c=pending?:return;if(state.value.busy)return;state.update{it.copy(busy=true,message=null,completed=null)}
        viewModelScope.launch{try{val id=withContext(Dispatchers.IO){repo.submit(c)};pending=null;persist();pendingUndo?.let{undoRequest=it};pendingUndo=null;state.update{it.copy(busy=false,unknown=false,completed=id,message="已保存",canUndoProperties=undoRequest!=null)}}
        catch(c:CancellationException){state.update{it.copy(busy=false,unknown=true)};throw c}
        catch(_:IllegalArgumentException){pending=null;persist();state.update{it.copy(busy=false,unknown=false,message="未提交：来源、版本或引用已变化，或内容重复。请重新核对。")}}
        catch(_:Exception){state.update{it.copy(busy=false,unknown=true,message="结果待核对，请重试原操作。")}}}
    }
    fun consumed(){state.update{it.copy(completed=null)}}
    class Factory(private val repo:KnowledgeRepository):ViewModelProvider.Factory{override fun<T:ViewModel>create(modelClass:Class<T>,extras:CreationExtras):T{require(modelClass.isAssignableFrom(KnowledgeViewModel::class.java));@Suppress("UNCHECKED_CAST")return KnowledgeViewModel(repo,extras.createSavedStateHandle()) as T}}
}

@Composable internal fun KnowledgeWorkspace(book:String,initialFocus:TargetRef,initialAnchor:KnowledgeData.Anchor?=null,initialTab:Int=0,dismiss:()->Unit,openTarget:(TargetRef)->Unit){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    val vm:KnowledgeViewModel=viewModel(key="knowledge-$book",factory=KnowledgeViewModel.Factory(app.knowledge));val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by remember{mutableIntStateOf(initialTab)};var focus by remember{mutableStateOf(initialFocus)}
    var query by remember{mutableStateOf("")};var picker by remember{mutableStateOf(false)};var preview by remember{mutableStateOf<Pair<TargetRef,Long?>?>(null)}
    var relation by remember{mutableStateOf(RelationKind.REFERENCE)};var pinned by remember{mutableStateOf(false)}
    var afterAnchorPicker by remember{mutableStateOf(false)}
    var waitingAnchor by remember{mutableStateOf(false)};var anchorCopied by remember{mutableStateOf(false)}
    var editCard by remember{mutableStateOf<StudyCardRow?>(null)};var newCollection by remember{mutableStateOf(false)};var review by remember{mutableStateOf(false)}
    var chosenCollection by remember{mutableStateOf<KnowledgeData.Collection?>(null)}
    var hops by remember{mutableIntStateOf(1)};var pendingExport by remember{mutableStateOf<String?>(null)};var localMessage by remember{mutableStateOf<String?>(null)}
    val activeBooks=ui.notes.map{it.id}.toSet()
    val rows=ui.rows.filter{!it.removed&&it.notebookId in activeBooks};val cards=ui.cards.filter{it.trashedAt==null&&it.notebookId in activeBooks}
    val values=rows.associate{it.id to it.data()};val links=rows.mapNotNull{r->(values[r.id] as? KnowledgeData.Link)?.let{r to it}}
    val properties=values.values.filterIsInstance<KnowledgeData.Properties>().associateBy{it.cardId}
    val bookCards=cards.filter{it.notebookId==book}
    val focusBook=when(focus.kind){TargetKind.NOTE->focus.id;TargetKind.PAGE->ui.pages.find{it.id==focus.id}?.notebookId;TargetKind.CARD->cards.find{it.id==focus.id}?.notebookId;TargetKind.ANCHOR->rows.find{it.id==focus.id}?.notebookId}?:book
    val enabled=!ui.busy&&!ui.unknown
    fun label(ref:TargetRef):String=when(ref.kind){TargetKind.NOTE->ui.notes.find{it.id==ref.id}?.title?:"来源已回收或不可用";TargetKind.CARD->cards.find{it.id==ref.id}?.title?:"卡片已回收或不可用";TargetKind.PAGE->ui.pages.find{it.id==ref.id&&it.trashedAt==null}?.let{p->(ui.notes.find{it.id==p.notebookId}?.title?:"来源已回收")+" · 第 ${p.position+1} 页"}?:"页面已回收或不可用";TargetKind.ANCHOR->"手写区域链接"}
    fun sourceText():String=when(focus.kind){TargetKind.NOTE->ui.notes.find{it.id==focus.id}?.text.orEmpty();TargetKind.CARD->cards.find{it.id==focus.id}?.body.orEmpty();else->""}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->val text=pendingExport;pendingExport=null
        if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(text)}};localMessage="关联快照已导出；不是完整备份。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认，原资料保留。"}}}
    val canvasExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->val text=pendingExport;pendingExport=null
        if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(text)}};localMessage="JSON Canvas 快照已保存，未包含目标和降级说明在文件中。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认，原资料保留。"}}}
    LaunchedEffect(ui.completed,ui.rows){val id=ui.completed?:return@LaunchedEffect
        if(waitingAnchor){val result=ui.rows.find{it.id==id}?:return@LaunchedEffect;if(result.data() !is KnowledgeData.Anchor){waitingAnchor=false;vm.consumed();return@LaunchedEffect};focus=TargetRef(TargetKind.ANCHOR,id);waitingAnchor=false;anchorCopied=true;if(afterAnchorPicker){picker=true;afterAnchorPicker=false}
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("墨织区域链接","inkweft://region/$id"))}
        editCard=null;newCollection=false;vm.consumed()
    }
    LaunchedEffect(ui.busy,ui.unknown,ui.message){if(!ui.busy&&!ui.unknown&&ui.message?.startsWith("未提交")==true){waitingAnchor=false;afterAnchorPicker=false}}
    Dialog(onDismissRequest={if(enabled)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        org.inkweft.app.ui.components.ContextPanel(tab==0,{if(enabled)dismiss()}){Column(Modifier.safeDrawingPadding().imePadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text("知识与关联",Modifier.weight(1f),fontSize=24.sp);TextButton(onClick=dismiss,enabled=enabled){Text("返回笔记")}}
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("关联","属性与集合","局部关联图","白板与脑图","手动回忆").forEachIndexed{i,t->FilterChip(tab==i,{tab=i},enabled=enabled,label={Text(t)},modifier=Modifier.testTag("knowledge-tab-$i"))}}
            if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            (localMessage?:ui.message)?.let{Text(it,Modifier.padding(horizontal=16.dp),fontSize=12.sp)}
            if(ui.canUndoProperties)TextButton(onClick=vm::undoProperties,enabled=enabled){Text("撤销属性修改")}
            if(ui.unknown)TextButton(onClick=vm::retry,enabled=!ui.busy){Text("核对原操作")}
            when(tab){
                0->LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Text("作用域 · ${label(focus)}",fontSize=20.sp)}
                    item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(focus==initialFocus,{focus=initialFocus},label={Text("本页 / 初始对象")})
                        FilterChip(focus==TargetRef(TargetKind.NOTE,book),{focus=TargetRef(TargetKind.NOTE,book)},label={Text("笔记全文")})
                        bookCards.forEach{c->FilterChip(focus==TargetRef(TargetKind.CARD,c.id),{focus=TargetRef(TargetKind.CARD,c.id)},label={Text(c.title)})}
                    }}
                    if(initialAnchor!=null&&!anchorCopied)item{OutlinedButton(onClick={waitingAnchor=true;vm.submit(book,initialAnchor)},enabled=enabled,modifier=Modifier.testTag("create-region-link")){Text("复制区域链接 · 不创建卡片")}}
                    item{Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick={if(initialAnchor!=null&&!anchorCopied){afterAnchorPicker=true;waitingAnchor=true;vm.submit(book,initialAnchor)}else picker=true},enabled=enabled,modifier=Modifier.testTag("add-knowledge-link")){Text("关联已有知识")};TextButton(onClick={scope.launch{try{pendingExport=withContext(Dispatchers.IO){knowledgeMarkdown(app,book,ui.notes,cards,rows)};export.launch("墨织关联快照.md")}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出读取失败，原资料保留。"}}},enabled=enabled){Text("导出关联快照")}}}
                    item{TextButton(onClick={pendingExport=KnowledgeCanvasExport.encode(book,cards,rows);canvasExport.launch("墨织知识.canvas")},enabled=enabled){Text("导出 JSON Canvas 快照")}}
                    item{Text("主动引用与语义关系",fontSize=16.sp)}
                    items(links.filter{it.second.source==focus},key={it.first.id}){(r,l)->OutlinedCard(Modifier.fillMaxWidth()){
                        Column(Modifier.padding(12.dp)){Text(l.relation.label+" → "+label(l.target));Text(if(l.pinnedRevision==null)"实时引用 · 已确认内容"else"固定摘录 · 修订 ${l.pinnedRevision}",fontSize=12.sp,color=Quiet)
                            Row{TextButton(onClick={preview=l.target to l.pinnedRevision}){Text("预览")};TextButton(onClick={vm.submit(r.notebookId,l,r,true)},enabled=enabled){Text("移除此关联")}}}
                    }}
                    item{Text("反向引用 · 由正式引用派生",fontSize=16.sp)}
                    items(links.filter{it.second.target==focus&&it.second.relation==RelationKind.REFERENCE},key={"back-"+it.first.id}){(_,l)->OutlinedButton(onClick={preview=l.source to null}){Text(label(l.source)+" 引用了这里")}}
                    item{Text("未确认提及",fontSize=16.sp);Text("只检查已覆盖的键入文字／摘要与名称；不代表识别了手写。确认前不进入反向引用和关联图。",fontSize=12.sp,color=Quiet)}
                    val aliases=values.values.filterIsInstance<KnowledgeData.Alias>()
                    val candidates=cards.filter{it.id!=focus.id&&(sourceText().contains(it.title,true)||aliases.any{a->a.cardId==it.id&&sourceText().contains(a.name,true)})&&links.none{l->l.second.source==focus&&l.second.target==TargetRef(TargetKind.CARD,it.id)}}
                    items(candidates,key={"candidate-"+it.id}){c->Row(verticalAlignment=Alignment.CenterVertically){Text(c.title+" · "+(ui.notes.find{it.id==c.notebookId}?.title?:""),Modifier.weight(1f));TextButton(onClick={vm.submit(focusBook,KnowledgeData.Link(focus,TargetRef(TargetKind.CARD,c.id)))},enabled=enabled){Text("确认关联")}}}
                    if(candidates.isEmpty())item{Text("当前覆盖范围没有候选提及",color=Quiet)}
                }
                1->LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{OutlinedTextField(query,{query=it},label={Text("搜索我的总结或标题")},modifier=Modifier.fillMaxWidth());Text("范围：本笔记的独立摘要；不包含未识别手写。",fontSize=12.sp,color=Quiet)}
                    item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(chosenCollection==null,{chosenCollection=null},label={Text("全部摘要")})
                        rows.filter{it.notebookId==book}.forEach{r->(values[r.id] as? KnowledgeData.Collection)?.let{c->FilterChip(chosenCollection==c,{chosenCollection=c},label={Text(c.title)})}}
                        TextButton(onClick={newCollection=true},enabled=enabled){Text("保存筛选集合")}}}
                    val shown=bookCards.filter{c->(query.isBlank()||c.title.contains(query,true)||c.body.contains(query,true))&&(chosenCollection?.let{KnowledgeQueries.matches(it,properties[c.id]?:KnowledgeData.Properties(c.id))}!=false)}
                    item{Text("${shown.size} 张卡片 · 筛选不复制内容",fontSize=12.sp,color=Quiet)}
                    items(shown,key={it.id}){c->OutlinedCard(onClick={editCard=c},modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(16.dp)){Text(c.title,fontSize=18.sp);Text(c.body,maxLines=3);Text("我的总结 · 手工状态："+(properties[c.id]?.state?:ManualState.INBOX).label,fontSize=12.sp,color=Quiet)}}}
                }
                2->{val graph=KnowledgeQueries.graph(focus,links.map{it.second},hops)
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(hops==1,{hops=1},label={Text("一跳")});FilterChip(hops==2,{hops=2},label={Text("二跳")});Text("${graph.nodes.size} 个对象 / ${graph.edges.size} 条边",fontSize=12.sp)}
                    Text(if(graph.truncated)"仅展示部分 · 上限 100 个对象 / 200 条边"else"当前对象的局部范围，非全库图",Modifier.padding(horizontal=16.dp),fontSize=12.sp,color=Quiet)
                    KnowledgeGraph(book,graph,::label){focus=it}
                }
                3->KnowledgeBoard(book,bookCards,rows,enabled,{data,old->vm.submit(book,data,old)},{old->vm.submit(book,old.data(),old,true)})
                4->Column(Modifier.weight(1f).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("手动回忆",fontSize=24.sp);Text("从摘要卡添加独立问题，再显示答案。手工标记不代表到期调度或算法熟练度。",color=Quiet)
                    Button(onClick={review=true},enabled=enabled&&values.values.filterIsInstance<KnowledgeData.Question>().any{q->bookCards.any{it.id==q.cardId}},modifier=Modifier.testTag("manual-review-start")){Text("开始回忆")}
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(bookCards,key={it.id}){c->OutlinedButton(onClick={editCard=c},modifier=Modifier.fillMaxWidth()){Text("添加问题 · ${c.title}")}}}
                }
            }
        }}
    }
    if(picker)AlertDialog(onDismissRequest={picker=false},title={Text("选择关联目标")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(query,{query=it},label={Text("按标题查找，核对所属笔记")})
        Row(Modifier.horizontalScroll(rememberScrollState())){RelationKind.entries.forEach{r->FilterChip(relation==r,{relation=r},label={Text(r.label)})}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pinned,{pinned=it});Text("固定卡片当前版本")}
        cards.filter{it.id!=focus.id&&(query.isBlank()||it.title.contains(query,true))}.sortedBy{if(it.notebookId==book)0 else 1}.take(80).forEach{c->TextButton(onClick={vm.submit(focusBook,KnowledgeData.Link(focus,TargetRef(TargetKind.CARD,c.id),relation,if(pinned)c.revision else null));picker=false},enabled=enabled){Text("${c.title} · ${ui.notes.find{it.id==c.notebookId}?.title.orEmpty()} · ${c.id.take(6)}")}}
        if(!pinned)ui.notes.filter{it.id!=focus.id&&(query.isBlank()||it.title.contains(query,true))}.take(50).forEach{n->TextButton(onClick={vm.submit(focusBook,KnowledgeData.Link(focus,TargetRef(TargetKind.NOTE,n.id),relation));picker=false},enabled=enabled){Text("笔记 · ${n.title} · ${n.id.take(6)}")}}
    }},confirmButton={TextButton(onClick={picker=false}){Text("取消")}})
    preview?.let{(ref,revision)->TargetPreview(ref,revision,ui,app,{preview=null}){openTarget(ref)}}
    editCard?.let{card->CardPropertiesDialog(card,properties[card.id],enabled,{editCard=null}){data->val old=if(data is KnowledgeData.Properties)rows.find{(it.data() as? KnowledgeData.Properties)?.cardId==card.id}else null;vm.submit(book,data,old)}}
    if(newCollection)CollectionDialog({newCollection=false}){vm.submit(book,it)}
    if(review)ManualReview(rows.filter{it.notebookId==book&&!it.removed},bookCards,ui,vm::retry,{review=false}){r,q->vm.submit(book,q,r)}
}

@Composable private fun CardPropertiesDialog(card:StudyCardRow,current:KnowledgeData.Properties?,enabled:Boolean,dismiss:()->Unit,save:(KnowledgeData)->Unit){
    var state by remember{mutableStateOf(current?.state?:ManualState.INBOX)};var tags by remember{mutableStateOf(current?.tags?.joinToString(",").orEmpty())};var question by remember{mutableStateOf("")};var alias by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=dismiss,title={Text(card.title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text("手工状态");ManualState.entries.forEach{s->FilterChip(state==s,{state=s},label={Text(s.label)})}
        OutlinedTextField(tags,{if(it.length<=240)tags=it},label={Text("标签，逗号分隔")})
        OutlinedTextField(alias,{if(it.length<=120)alias=it},label={Text("别名 · 用于候选提及")})
        TextButton(onClick={save(KnowledgeData.Alias(card.id,alias.trim()))},enabled=enabled&&alias.isNotBlank()){Text("添加别名")}
        OutlinedTextField(question,{if(it.length<=2000)question=it},label={Text("独立复习问题")})
        TextButton(onClick={save(KnowledgeData.Question(card.id,question.trim()))},enabled=enabled&&question.isNotBlank()){Text("添加回忆题")}
    }},confirmButton={TextButton(onClick={val values=tags.split(',', '，').map{it.trim()}.filter{it.isNotEmpty()}.distinct();save(KnowledgeData.Properties(card.id,state,values))},enabled=enabled&&tags.split(',', '，').filter{it.isNotBlank()}.let{it.size<=12&&it.all{tag->tag.trim().length<=24}}){Text("保存属性")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

@Composable private fun CollectionDialog(dismiss:()->Unit,save:(KnowledgeData.Collection)->Unit){
    var title by remember{mutableStateOf("")};var tag by remember{mutableStateOf("")};var state by remember{mutableStateOf<ManualState?>(null)};var any by remember{mutableStateOf(false)}
    AlertDialog(onDismissRequest=dismiss,title={Text("保存智能集合")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(title,{if(it.length<=120)title=it},label={Text("集合名称")});OutlinedTextField(tag,{if(it.length<=24)tag=it},label={Text("包含标签，留空不限")})
        FilterChip(state==null,{state=null},label={Text("所有手工状态")});ManualState.entries.forEach{s->FilterChip(state==s,{state=s},label={Text(s.label)})}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(any,{any=it});Text(if(any)"满足任一条件（或）"else"满足全部条件（且）")}
        Text("范围为当前笔记的摘要卡；内容和文件夹保持原位置。",fontSize=12.sp,color=Quiet)
    }},confirmButton={TextButton(onClick={save(KnowledgeData.Collection(title.trim(),tag.trim(),state,any))},enabled=title.isNotBlank()){Text("保存集合")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})
}

@Composable private fun TargetPreview(ref:TargetRef,revision:Long?,ui:KnowledgeUi,app:InkWeftApplication,dismiss:()->Unit,open:()->Unit){
    var text by remember{mutableStateOf("正在读取…")};var available by remember{mutableStateOf(false)}
    LaunchedEffect(ref,revision){try{withContext(Dispatchers.IO){available=app.knowledge.available(ref);text=if(!available)"来源已回收或不可用"else when(ref.kind){
        TargetKind.CARD->{val c=ui.cards.find{it.id==ref.id};if(revision==null)c?.let{it.title+"\n"+it.body}.orEmpty()else app.knowledge.cardVersion(ref.id,revision)?.let{it.title+"\n"+it.body}?:"固定版本不可用"}
        TargetKind.NOTE->ui.notes.find{it.id==ref.id}?.let{it.title+"\n"+it.text}.orEmpty()
        TargetKind.ANCHOR->{val a=ui.rows.find{it.id==ref.id}?.data() as? KnowledgeData.Anchor;if(a!=null&&app.pages.inkRevision(a.pageId)!=a.inkRevision)"来源已变化：仅可查看原区域位置，需重新核对关联。"else"手写区域，打开后定位原页。"}
        TargetKind.PAGE->"打开来源页面"
    }}}catch(c:CancellationException){throw c}catch(_:Exception){text="读取失败，请关闭重试。"}}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(revision==null)"实时引用预览"else"固定摘录 · 修订 $revision")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text(text);Text("预览不递归展开其他引用。",fontSize=12.sp,color=Quiet)}},confirmButton={TextButton(onClick={dismiss();open()},enabled=available){Text("打开来源")}},dismissButton={TextButton(onClick=dismiss){Text("关闭")}})
}

@Composable private fun ManualReview(rows:List<KnowledgeRow>,cards:List<StudyCardRow>,ui:KnowledgeUi,retry:()->Unit,dismiss:()->Unit,rate:(KnowledgeRow,KnowledgeData.Question)->Unit){
    val questions=rows.mapNotNull{r->(r.data() as? KnowledgeData.Question)?.takeIf{q->cards.any{it.id==q.cardId}}?.let{r to it}}
    var index by remember{mutableIntStateOf(0)};var revealed by remember(index){mutableStateOf(false)}
    val app=LocalContext.current.applicationContext as InkWeftApplication
    var sourceOpen by remember{mutableStateOf(false)}
    val questionCard=questions.getOrNull(index)?.second?.cardId
    val source by produceState<StudySourceRow?>(null,questionCard){value=null;if(questionCard!=null)try{value=withContext(Dispatchers.IO){app.study.source(questionCard)}}catch(c:CancellationException){throw c}catch(_:Exception){value=null}}
    var pendingRating by remember{mutableStateOf<Triple<String,Long,ManualState>?>(null)}
    LaunchedEffect(rows,ui.busy,ui.unknown,ui.message){if(!ui.busy&&!ui.unknown&&ui.message=="已保存")pendingRating?.let{(id,revision,state)->if(rows.any{it.id==id&&it.revision>revision&&(it.data() as? KnowledgeData.Question)?.state==state}){index++;pendingRating=null}}}
    Dialog(onDismissRequest={if(!ui.busy&&!ui.unknown)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize().testTag("manual-review")){Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            Row(Modifier.fillMaxWidth()){Text("手动回忆 · ${if(questions.isEmpty())0 else index.coerceAtMost(questions.lastIndex)+1} / ${questions.size}",Modifier.weight(1f));TextButton(onClick=dismiss,enabled=!ui.busy&&!ui.unknown){Text("退出回忆")}}
            if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(ui.unknown){Text("评级结果待核对，不会自动进入下一题。");TextButton(onClick=retry,enabled=!ui.busy){Text("核对原操作")}}
            if(!ui.busy&&!ui.unknown&&pendingRating!=null&&ui.message!="已保存"){Text(ui.message.orEmpty());TextButton(onClick={pendingRating=null}){Text("重新选择标记")}}
            val pair=questions.getOrNull(index)
            if(pair==null)Text("本轮已结束")else{val(r,q)=pair
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(24.dp)){
                    Text(q.prompt,fontSize=24.sp,modifier=Modifier.testTag("review-question"))
                    if(revealed)Text(cards.find{it.id==q.cardId}?.body.orEmpty(),fontSize=18.sp,modifier=Modifier.testTag("review-answer"))
                }
                if(revealed){Text("手工标记，不安排到期时间",fontSize=12.sp,color=Quiet)
                    if(source!=null)OutlinedButton(onClick={sourceOpen=true},modifier=Modifier.testTag("review-open-source")){Text("查看原页 · 返回后继续此题")}
                    Row{TextButton(onClick={pendingRating=Triple(r.id,r.revision,ManualState.REVIEW);rate(r,q.copy(state=ManualState.REVIEW))},enabled=!ui.busy&&!ui.unknown&&pendingRating==null){Text("仍需复习")};Button(onClick={pendingRating=Triple(r.id,r.revision,ManualState.UNDERSTOOD);rate(r,q.copy(state=ManualState.UNDERSTOOD))},enabled=!ui.busy&&!ui.unknown&&pendingRating==null){Text("本次已理解")}}}
                else Button(onClick={revealed=true},modifier=Modifier.testTag("reveal-answer")){Text("显示答案")}
            }
        }}
    }
    if(sourceOpen)source?.let{ReviewSourceDialog(it){sourceOpen=false}}
}

private suspend fun knowledgeMarkdown(app:InkWeftApplication,book:String,notes:List<NoteRow>,cards:List<StudyCardRow>,rows:List<KnowledgeRow>):String {
    val pinned=linkedMapOf<String,StudyCardRevisionRow?>()
    for(row in rows) (row.data() as? KnowledgeData.Link)?.let{l->l.pinnedRevision?.let{pinned[row.id]=app.knowledge.cardVersion(l.target.id,it)}}
    return buildString{
    fun clean(s:String)=s.replace("\r","").replace("<","&lt;").replace(">","&gt;")
    appendLine("# 关联快照");appendLine("仅导出当前笔记的关系、摘要与属性。原笔迹、历史及完整引用恢复请使用资料库备份。")
    val included=cards.filter{it.notebookId==book};included.forEach{c->appendLine("\n<a id=\"card-${c.id}\"></a>\n## ${clean(c.title)}\n${clean(c.body)}")}
    appendLine("\n## 关系映射")
    rows.filter{it.notebookId==book}.forEach{r->when(val d=r.data()){
        is KnowledgeData.Link->{val c=cards.find{it.id==d.target.id};val title=c?.title?:notes.find{it.id==d.target.id}?.title?:"来源已回收或不可用"
            appendLine("- ${d.source.kind}/${d.source.id} → ${d.relation.label} → ${clean(title)} (${d.target.kind}/${d.target.id}) · ${d.pinnedRevision?.let{"固定修订 $it"}?:"实时"}")
            if(d.pinnedRevision!=null){val frozen=pinned[r.id];appendLine("  固定摘录："+(frozen?.let{clean(it.title)+"\n\n"+clean(it.body)}?:"固定版本不可用；未用当前正文代替。"))}
            else if(c in included)appendLine("  [目标摘要](#card-${c!!.id})")else appendLine("  目标未包含在此文件；保留身份供映射，不宣称迁移完整。")}
        is KnowledgeData.Anchor->appendLine("- 区域 ${r.id}：页面 ${d.pageId} / 修订 ${d.inkRevision} / ${d.bounds}；不含笔迹采样")
        is KnowledgeData.Properties->appendLine("- 属性 ${d.cardId}：手工${d.state.label} / ${d.tags.joinToString()}")
        else->Unit
    }}
}

}
