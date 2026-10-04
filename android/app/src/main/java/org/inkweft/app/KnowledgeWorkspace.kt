// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*
import org.inkweft.data.*
import org.inkweft.app.ui.designsystem.InkTheme
import java.util.UUID

internal data class KnowledgeUi(val rows:List<KnowledgeRow> = emptyList(),val cards:List<StudyCardRow> = emptyList(),val notes:List<NoteRow> = emptyList(),val pages:List<NotebookPageRow> = emptyList(),
    val loading:Boolean=true,val readFailed:Boolean=false,val busy:Boolean=false,val unknown:Boolean=false,val message:String?=null,val completed:String?=null,val canUndoProperties:Boolean=false,val completedOperation:String?=null,val rejectedOperation:String?=null)
internal class KnowledgeViewModel(private val repo:KnowledgeRepository,private val saved:SavedStateHandle,private val packs:ResourcePacks):ViewModel(){
    var authorAllowed:(String)->Boolean={true}
    val pendingBook:String? get()=pending?.notebookId
    val pendingOperationId:String? get()=pending?.operationId
    private fun restored():KnowledgeCommand?{val fields=saved.get<ArrayList<String>>("knowledge.request")?:return null;require(fields.size==5);return KnowledgeCommand(fields[0],fields[1],fields[2],fields[3].toLong(),KnowledgeCodec.decode(requireNotNull(saved.get<ByteArray>("knowledge.payload"))),fields[4].toBooleanStrict())}
    private var pending=restored();private val state=MutableStateFlow(KnowledgeUi(unknown=pending!=null,completed=saved["knowledge.completed"],completedOperation=saved["knowledge.completedOperation"],rejectedOperation=saved["knowledge.rejectedOperation"],message=saved["knowledge.rejectedMessage"]));val ui=state.asStateFlow()
    private var readJob:Job?=null
    init{reload()}
    fun reload(){readJob?.cancel();state.update{it.copy(loading=true,readFailed=false)}
        readJob=viewModelScope.launch{try{combine(repo.observe(),repo.cards(),repo.notes(),repo.pages()){r,c,n,p->KnowledgeUi(rows=r,cards=c,notes=n,pages=p)}.collect{snapshot->state.update{it.copy(rows=snapshot.rows,cards=snapshot.cards,notes=snapshot.notes,pages=snapshot.pages,loading=false,readFailed=false)}}}catch(c:CancellationException){throw c}catch(_:Exception){state.update{it.copy(loading=false,readFailed=true)}}}
    }
    private var pendingUndo:KnowledgeCommand?=null
    private var undoRequest:KnowledgeCommand?=null
    fun undoProperties(){if(ui.value.busy||pending!=null)return;val request=undoRequest?:return;if(!authorAllowed(request.notebookId)){state.update{it.copy(message="当前为阅读模式，请返回书写后编辑。")};return};pending=request;undoRequest=null;pendingUndo=null;state.update{it.copy(canUndoProperties=false)};persist();retry()}
    private fun persist(){if(pending==null){saved.set<ArrayList<String>?>("knowledge.template",null);saved.set<Long?>("knowledge.reviewCardRevision",null)};saved["knowledge.request"]=pending?.let{arrayListOf(it.operationId,it.notebookId,it.id,it.expectedRevision.toString(),it.removed.toString())};saved["knowledge.payload"]=pending?.payload}
    private fun persistResult(id:String?=null,operation:String?=null,rejectedOperation:String?=null,message:String?=null){saved["knowledge.completed"]=id;saved["knowledge.completedOperation"]=operation;saved["knowledge.rejectedOperation"]=rejectedOperation;saved["knowledge.rejectedMessage"]=message}
    fun submitReview(book:String,old:KnowledgeRow,data:KnowledgeData.Question,cardRevision:Long):String?=submitInternal(book,data,old,reviewCardRevision=cardRevision,review=true)
    fun submit(book:String,data:KnowledgeData,old:KnowledgeRow?=null,remove:Boolean=false,template:TemplateRef?=null):String?=submitInternal(book,data,old,remove,template)
    private fun submitInternal(book:String,data:KnowledgeData,old:KnowledgeRow?=null,remove:Boolean=false,template:TemplateRef?=null,reviewCardRevision:Long?=null,review:Boolean=false):String?{
        if(state.value.busy||pending!=null)return null
        if(!review&&!authorAllowed(book)){state.update{it.copy(message="当前为阅读模式，请返回书写后编辑。")};return null}
        val operation=UUID.randomUUID().toString()
        // Encoding validates structural counts before a repository request exists.
        val command=try{KnowledgeCommand(operation,book,old?.id?:UUID.randomUUID().toString(),old?.revision?:0,data,remove)}
        catch(e:IllegalArgumentException){
            val message=studyCapacityRejection(e.message.orEmpty())?:throw e
            pendingUndo=null;persist();persistResult(rejectedOperation=operation,message=message)
            state.update{it.copy(busy=false,unknown=false,completed=null,completedOperation=null,rejectedOperation=operation,message=message)}
            return null
        }
        val inverse=if(data is KnowledgeData.Properties)KnowledgeCommand(UUID.randomUUID().toString(),book,command.id,(old?.revision?:0)+1,old?.data()?:data,old==null)else null
        saved["knowledge.template"]=template?.let{arrayListOf(it.hash,it.id)};saved["knowledge.reviewCardRevision"]=reviewCardRevision
        pending=command;pendingUndo=inverse;persist();retry();return command.operationId
    }
    fun retry(){val c=pending?:return;if(state.value.busy)return;persistResult();state.update{it.copy(busy=true,message=null,completed=null,completedOperation=null,rejectedOperation=null)}
        viewModelScope.launch{try{when(val result=withContext(Dispatchers.IO){val ref=saved.get<ArrayList<String>>("knowledge.template");if(ref==null)saved.get<Long>("knowledge.reviewCardRevision")?.let{repo.reviewOutcome(c,it)}?:repo.outcome(c)else try{KnowledgeOutcome.Success(packs.map(TemplateRef(ref[0],ref[1]),c))}catch(e:KnowledgeRejected){KnowledgeOutcome.Rejected(e.reason)}catch(e:IllegalArgumentException){KnowledgeOutcome.Rejected(when(e.message){
            "STUDY_NODE_BUDGET"->KnowledgeRejection.STUDY_NODE_BUDGET
            "STUDY_NODE_RECORD_BUDGET"->KnowledgeRejection.STUDY_NODE_RECORD_BUDGET
            "KNOWLEDGE_BUDGET"->KnowledgeRejection.KNOWLEDGE_BUDGET
            else->KnowledgeRejection.INVALID
        })}}){
            is KnowledgeOutcome.Success->{persistResult(result.id,c.operationId);pending=null;persist();pendingUndo?.let{undoRequest=it};pendingUndo=null;state.update{it.copy(busy=false,unknown=false,completed=result.id,completedOperation=c.operationId,rejectedOperation=null,message="已保存",canUndoProperties=undoRequest!=null)}}
            is KnowledgeOutcome.Rejected->{val message=studyCapacityRejection(result.reason.name)?:"未提交：来源、版本或引用已变化，或内容重复。请重新核对。";persistResult(rejectedOperation=c.operationId,message=message);pending=null;pendingUndo=null;persist();state.update{it.copy(busy=false,unknown=false,rejectedOperation=c.operationId,message=message)}}
            KnowledgeOutcome.Unknown->state.update{it.copy(busy=false,unknown=true,message="结果待核对，请重试原操作。")}
        }}
        catch(c:CancellationException){state.update{it.copy(busy=false,unknown=true)};throw c}
        catch(_:Exception){state.update{it.copy(busy=false,unknown=true,message="结果待核对，请重试原操作。")}}}
    }
    fun consumed(){saved.set<String?>("knowledge.completed",null);saved.set<String?>("knowledge.completedOperation",null);state.update{it.copy(completed=null,completedOperation=null)}}
    fun consumed(operationId:String){if(state.value.completedOperation==operationId)consumed()}
    fun consumedRejection(operationId:String){if(state.value.rejectedOperation!=operationId)return;saved.set<String?>("knowledge.rejectedOperation",null);saved.set<String?>("knowledge.rejectedMessage",null);state.update{it.copy(rejectedOperation=null)}}
    class Factory(private val repo:KnowledgeRepository,private val packs:ResourcePacks):ViewModelProvider.Factory{override fun<T:ViewModel>create(modelClass:Class<T>,extras:CreationExtras):T{require(modelClass.isAssignableFrom(KnowledgeViewModel::class.java));@Suppress("UNCHECKED_CAST")return KnowledgeViewModel(repo,extras.createSavedStateHandle(),packs) as T}}
}

private data class QuestionEditDraft(val row:KnowledgeRow,val prompt:String,val removing:Boolean=false,val operationId:String?=null,val message:String?=null,val editorId:String=UUID.randomUUID().toString())
private val QuestionEditDraftSaver=Saver<QuestionEditDraft?,List<String>>(
    save={draft->draft?.let{listOf(it.row.id,it.row.notebookId,it.row.revision.toString(),Base64.encodeToString(it.row.payload,Base64.NO_WRAP),it.prompt,it.removing.toString(),it.operationId.orEmpty(),it.message.orEmpty(),it.editorId)}?:emptyList()},
    restore={fields->if(fields.isEmpty())null else{require(fields.size==9);val row=KnowledgeRow(fields[0],fields[1],fields[2].toLong(),Base64.decode(fields[3],Base64.NO_WRAP));require(row.revision>0&&row.data() is KnowledgeData.Question);QuestionEditDraft(row,fields[4],fields[5].toBooleanStrict(),fields[6].ifEmpty{null},fields[7].ifEmpty{null},fields[8])}}
)

private data class LinkPreviewSelection(val focus:TargetRef,val id:String,val revision:Long,val incoming:Boolean)
private val LinkPreviewSaver=Saver<LinkPreviewSelection?,List<String>>(
    save={it?.let{v->listOf(v.focus.kind.name,v.focus.id,v.id,v.revision.toString(),v.incoming.toString())}?:emptyList()},
    restore={if(it.isEmpty())null else LinkPreviewSelection(TargetRef(TargetKind.valueOf(it[0]),it[1]),it[2],it[3].toLong(),it[4].toBooleanStrict())}
)

/** Knowledge writers can address several books; consult the requested book's live session. */
@Composable internal fun BindKnowledgeReadLock(vm:KnowledgeViewModel){
    val owner=requireNotNull(LocalViewModelStoreOwner.current)
    val locks=remember(owner){ViewModelProvider(owner,BookReadLockViewModel.Factory())}
    SideEffect{vm.authorAllowed={book->locks["read-lock-$book",BookReadLockViewModel::class.java].canWrite}}
}

@Composable internal fun KnowledgeWorkspace(book:String,initialFocus:TargetRef,initialAnchor:KnowledgeData.Anchor?=null,initialTab:Int=0,initialCollection:String?=null,initialBacklinks:Boolean=false,includeAllRelationKinds:Boolean=false,dismiss:()->Unit,openTarget:(TargetRef)->Unit){
    val context=LocalContext.current;val app=context.applicationContext as InkWeftApplication;val scope=rememberCoroutineScope()
    val focusManager=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val vm:KnowledgeViewModel=viewModel(key="knowledge-$book",factory=KnowledgeViewModel.Factory(app.knowledge,app.resourcePacks));val ui by vm.ui.collectAsStateWithLifecycle()
    BindKnowledgeReadLock(vm)
    val readLock=rememberBookReadLock(book);val readOnly by readLock.readOnly.collectAsStateWithLifecycle();val hasDraft by readLock.hasDraft.collectAsStateWithLifecycle()
    var tab by rememberSaveable{mutableIntStateOf(initialTab)};var focus by rememberSaveable(stateSaver=Saver<TargetRef,List<String>>({listOf(it.kind.name,it.id)},{TargetRef(TargetKind.valueOf(it[0]),it[1])})){mutableStateOf(initialFocus)}
    var query by rememberSaveable{mutableStateOf("")};var picker by rememberSaveable{mutableStateOf(false)};var preview by rememberSaveable(stateSaver=LinkPreviewSaver){mutableStateOf<LinkPreviewSelection?>(null)}
    var incoming by rememberSaveable{mutableStateOf(initialBacklinks)}
    var initialDirectionResolved by rememberSaveable(book,initialFocus,initialBacklinks,includeAllRelationKinds){mutableStateOf(!initialBacklinks||!includeAllRelationKinds)}
    var showExports by rememberSaveable{mutableStateOf(false)}
    var showMentions by rememberSaveable{mutableStateOf(false)}
    var scopeMenu by remember{mutableStateOf(false)}
    var relation by rememberSaveable{mutableStateOf(RelationKind.REFERENCE)};var pinned by rememberSaveable{mutableStateOf(false)}
    var afterAnchorPicker by remember{mutableStateOf(false)}
    var waitingAnchor by remember{mutableStateOf(false)};var anchorCopied by remember{mutableStateOf(false)}
    var editCardId by rememberSaveable{mutableStateOf<String?>(null)};val editCard=ui.cards.find{it.id==editCardId&&it.notebookId==book};var newCollection by rememberSaveable{mutableStateOf(false)}
    var questionEdit by rememberSaveable(book,stateSaver=QuestionEditDraftSaver){mutableStateOf<QuestionEditDraft?>(null)}
    var reviewPlan by rememberSaveable(stateSaver=BranchReviewPlanSaver){mutableStateOf<BranchReviewPlan?>(null)}
    var reviewWithSummary by rememberSaveable(book){mutableStateOf(false)}
    var reviewCollectionScope by rememberSaveable(book){mutableStateOf(false)}
    var reviewQuestionScope by rememberSaveable(book){mutableStateOf(ReviewQuestionScope.ALL)}
    var chosenCollectionId by rememberSaveable(book){mutableStateOf<String?>(initialCollection)}
    var reviewPreparation by remember(book){mutableStateOf<Pair<List<Any?>,Job>?>(null)}
    var collectionReviewMessage by remember(book,chosenCollectionId){mutableStateOf<String?>(null)}
    var hops by remember{mutableIntStateOf(1)};var pendingExport by remember{mutableStateOf<String?>(null)};var localMessage by remember{mutableStateOf<String?>(null)}
    val activeBooks=ui.notes.map{it.id}.toSet()
    val rows=ui.rows.filter{!it.removed&&it.notebookId in activeBooks};val cards=ui.cards.filter{it.trashedAt==null&&it.notebookId in activeBooks}
    val values=rows.associate{it.id to it.data()};val links=rows.mapNotNull{r->(values[r.id] as? KnowledgeData.Link)?.let{r to it}}
    val collectionRows=rows.filter{it.notebookId==book&&values[it.id] is KnowledgeData.Collection}
    val chosenCollectionRow=collectionRows.find{it.id==chosenCollectionId}
    val chosenCollection=chosenCollectionRow?.let{values[it.id] as? KnowledgeData.Collection}
    val properties=rows.filter{it.notebookId==book}.mapNotNull{values[it.id] as? KnowledgeData.Properties}.associateBy{it.cardId}
    val bookCards=cards.filter{it.notebookId==book}
    val focusBook=when(focus.kind){TargetKind.NOTE->focus.id;TargetKind.PAGE->ui.pages.find{it.id==focus.id}?.notebookId;TargetKind.CARD->cards.find{it.id==focus.id}?.notebookId;TargetKind.ANCHOR->rows.find{it.id==focus.id}?.notebookId}?:book
    val enabled=!ui.busy&&!ui.unknown
    val editable=enabled&&!readOnly
    val focusLock=rememberBookReadLock(focusBook);val focusReadOnly by focusLock.readOnly.collectAsStateWithLifecycle()
    val focusEditable=enabled&&!focusReadOnly
    val guardKey=remember(vm){"knowledge-${UUID.randomUUID()}"}
    val authorDraft=picker||newCollection||((editCardId!=null||questionEdit!=null)&&!readOnly)
    ReadLockGuard(readLock,guardKey,blocked=ui.busy||ui.unknown||authorDraft,draft=authorDraft)
    if(focusBook!=book)ReadLockGuard(focusLock,"$guardKey-focus",blocked=picker,draft=picker)
    val pendingLock=rememberBookReadLock(vm.pendingBook?:book)
    ReadLockGuard(pendingLock,"$guardKey-pending",blocked=ui.busy||ui.unknown)
    val canDismiss=enabled&&!hasDraft&&questionEdit==null
    fun reviewPreparationContext(snapshot:KnowledgeUi):List<Any?> = listOf(book,tab,chosenCollectionId,reviewQuestionScope,
        snapshot.notes.any{it.id==book},snapshot.busy,snapshot.unknown,readLock.hasDraft.value,
        picker||newCollection||((editCardId!=null||questionEdit!=null)&&!readLock.readOnly.value),
        snapshot.rows.filter{it.notebookId==book}.map{Triple(it.id,it.revision,it.removed)}.sortedBy{it.first},
        snapshot.cards.filter{it.notebookId==book}.map{Triple(it.id,it.revision,it.trashedAt)}.sortedBy{it.first})
    fun cancelReviewPreparation(expectedContext:List<Any?>?=null){
        val pending=reviewPreparation?:return
        if(expectedContext!=null&&pending.first!=expectedContext)return
        reviewPreparation=null;pending.second.cancel()
    }
    fun chooseReviewScope(value:ReviewQuestionScope){val live=vm.ui.value;if(live.busy||live.unknown||readLock.hasDraft.value)return;if(reviewQuestionScope!=value){cancelReviewPreparation();reviewQuestionScope=value}}
    fun chooseCollection(id:String?){cancelReviewPreparation();collectionReviewMessage=null;chosenCollectionId=id}
    fun closeWorkspace(){val live=vm.ui.value;if(canDismiss&&questionEdit==null&&!live.busy&&!live.unknown&&!readLock.hasDraft.value){cancelReviewPreparation();dismiss()}}
    val preparationContext=reviewPreparationContext(ui)
    DisposableEffect(book,preparationContext){onDispose{cancelReviewPreparation(preparationContext)}}
    DisposableEffect(book){onDispose{cancelReviewPreparation()}}
    fun prepareReview(row:KnowledgeRow?){
        val requestedTab=if(row==null)4 else 1
        val live=vm.ui.value
        if(tab!=requestedTab||live.busy||live.unknown||readLock.hasDraft.value||picker||newCollection||((editCardId!=null||questionEdit!=null)&&!readLock.readOnly.value)||reviewPreparation!=null||live.notes.none{it.id==book})return
        if(row!=null){
            val current=live.rows.find{it.id==row.id&&it.notebookId==book&&!it.removed}
            if(chosenCollectionId!=row.id||current==null||current.revision!=row.revision||current.data() !is KnowledgeData.Collection){collectionReviewMessage="集合已变化，未打开回忆。请核对后重试。";return}
            collectionReviewMessage=null
        }
        val selectedScope=reviewQuestionScope
        val origin=reviewPreparationContext(live)
        val job=scope.launch(start=CoroutineStart.LAZY){
            val request=currentCoroutineContext().job
            try{
                val plan=withContext(Dispatchers.IO){if(row==null)app.branchReview.prepareNotebook(book,selectedScope)
                    else app.branchReview.prepareCollection(book,row.id,row.revision,selectedScope)}
                currentCoroutineContext().ensureActive()
                if(reviewPreparation?.second===request&&reviewPreparationContext(vm.ui.value)==origin){
                    reviewWithSummary=row!=null||selectedScope==ReviewQuestionScope.REVIEW_ONLY
                    reviewCollectionScope=row!=null;reviewPlan=plan
                }
            }catch(c:CancellationException){throw c}
            catch(_:Exception){if(reviewPreparation?.second===request&&reviewPreparationContext(vm.ui.value)==origin){
                if(row==null)localMessage="问题读取失败，请重试；原资料未改变。"
                else collectionReviewMessage="集合已变化或读取失败，未打开回忆。请核对后重试；原资料未改变。"
            }}
            finally{if(reviewPreparation?.second===request)reviewPreparation=null}
        }
        reviewPreparation=origin to job;job.start()
    }
    fun prepareCollectionReview(){chosenCollectionRow?.let{prepareReview(it)}}
    fun openQuestion(row:KnowledgeRow,removing:Boolean){
        val live=vm.ui.value
        if(live.busy||live.unknown||live.completed!=null||questionEdit!=null||!readLock.canWrite)return
        val question=row.data() as? KnowledgeData.Question?:return
        if(row.notebookId!=book||row.removed||question.cardId!=editCardId)return
        questionEdit=QuestionEditDraft(row.copy(payload=row.payload.copyOf()),question.prompt,removing)
    }
    fun closeQuestion(editorId:String){
        val live=vm.ui.value
        if(live.busy||live.unknown||questionEdit?.editorId!=editorId||questionEdit?.operationId!=null)return
        questionEdit=null;focusManager.clearFocus();keyboard?.hide()
    }
    fun closeProperties(){val live=vm.ui.value;if(!live.busy&&!live.unknown&&questionEdit==null)editCardId=null}
    fun submitQuestion(editorId:String){
        val draft=questionEdit?:return
        if(draft.editorId!=editorId)return
        val live=vm.ui.value
        if(live.busy||live.unknown||live.completed!=null||draft.operationId!=null)return
        val question=draft.row.data() as KnowledgeData.Question
        val current=live.rows.find{it.id==draft.row.id&&it.notebookId==book&&!it.removed}
        val currentQuestion=current?.data() as? KnowledgeData.Question
        val activeCard=live.cards.any{it.id==question.cardId&&it.notebookId==book&&it.trashedAt==null}
        if(!activeCard||live.notes.none{it.id==book}||currentQuestion?.cardId!=question.cardId){questionEdit=draft.copy(message="题目或摘要卡尚未载入、已移除或不可用；草稿仍保留。请核对后重试。");return}
        if(!draft.removing&&(draft.prompt.isBlank()||draft.prompt.length>2000))return
        val data=if(draft.removing)question else question.copy(prompt=draft.prompt)
        val operation=vm.submit(book,data,draft.row,remove=draft.removing)
        questionEdit=if(operation==null)draft.copy(message=vm.ui.value.message?:"当前操作尚未就绪，未提交；草稿仍保留。")else draft.copy(operationId=operation,message=null)
    }
    fun label(ref:TargetRef):String=when(ref.kind){TargetKind.NOTE->ui.notes.find{it.id==ref.id}?.title?:"来源已回收或不可用";TargetKind.CARD->cards.find{it.id==ref.id}?.title?:"卡片已回收或不可用";TargetKind.PAGE->ui.pages.find{it.id==ref.id&&it.trashedAt==null}?.let{p->(ui.notes.find{it.id==p.notebookId}?.title?:"来源已回收")+" · 第 ${p.position+1} 页"}?:"页面已回收或不可用";TargetKind.ANCHOR->"手写区域链接"}
    fun targetContext(ref:TargetRef):String{
        val owner=when(ref.kind){TargetKind.NOTE->ref.id;TargetKind.PAGE->ui.pages.find{it.id==ref.id}?.notebookId;TargetKind.CARD->ui.cards.find{it.id==ref.id}?.notebookId;TargetKind.ANCHOR->ui.rows.find{it.id==ref.id}?.notebookId}
        val kind=when(ref.kind){TargetKind.NOTE->"笔记全文";TargetKind.PAGE->"笔记页面";TargetKind.CARD->"摘要卡";TargetKind.ANCHOR->"手写区域"}
        return "$kind · ${ui.notes.find{it.id==owner}?.title?:"所属笔记不可用"}"
    }
    fun summary(ref:TargetRef):String=when(ref.kind){
        TargetKind.CARD->ui.cards.find{it.id==ref.id}?.body?.ifBlank{null}?:"尚未填写摘要，点击预览此卡片"
        TargetKind.NOTE->ui.notes.find{it.id==ref.id}?.text?.ifBlank{null}?:"点击预览笔记全文"
        TargetKind.PAGE->"点击预览后可打开来源页"
        TargetKind.ANCHOR->"点击预览并核对原区域位置"
    }
    val outgoingLinks=links.filter{it.second.source==focus}
    val incomingLinks=links.filter{it.second.target==focus&&(includeAllRelationKinds||it.second.relation==RelationKind.REFERENCE)}
    LaunchedEffect(initialDirectionResolved,ui.loading,ui.readFailed,focus){
        if(!initialDirectionResolved){
            if(focus!=initialFocus)initialDirectionResolved=true
            else if(!ui.loading&&!ui.readFailed){
                incoming=incomingLinks.isNotEmpty()||outgoingLinks.isEmpty()
                initialDirectionResolved=true
            }
        }
    }
    fun sourceText():String=when(focus.kind){TargetKind.NOTE->ui.notes.find{it.id==focus.id}?.text.orEmpty();TargetKind.CARD->cards.find{it.id==focus.id}?.body.orEmpty();else->""}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->val text=pendingExport;pendingExport=null
        if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(text)}};localMessage="关联快照已导出；不是完整备份。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认，原资料保留。"}}}
    val canvasExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->val text=pendingExport;pendingExport=null
        if(uri!=null&&text!=null)scope.launch{try{withContext(Dispatchers.IO){requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter().use{it.write(text)}};localMessage="JSON Canvas 快照已保存，未包含目标和降级说明在文件中。"}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出未确认，原资料保留。"}}}
    LaunchedEffect(ui.completed,ui.completedOperation,ui.rows){val live=vm.ui.value;val id=live.completed?:return@LaunchedEffect
        val draft=questionEdit
        if(draft?.operationId!=null&&live.completedOperation==draft.operationId&&id==draft.row.id){questionEdit=null;focusManager.clearFocus();keyboard?.hide();vm.consumed();return@LaunchedEffect}
        if(waitingAnchor){val result=ui.rows.find{it.id==id}?:return@LaunchedEffect;if(result.data() !is KnowledgeData.Anchor){waitingAnchor=false;vm.consumed();return@LaunchedEffect};focus=TargetRef(TargetKind.ANCHOR,id);waitingAnchor=false;anchorCopied=true;if(afterAnchorPicker){picker=true;afterAnchorPicker=false}
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("墨织区域链接","inkweft://region/$id"))}
        editCardId=null;newCollection=false;vm.consumed()
    }
    LaunchedEffect(ui.busy,ui.unknown,ui.message,ui.rejectedOperation,questionEdit?.operationId){
        val draft=questionEdit?:return@LaunchedEffect
        val operation=draft.operationId?:return@LaunchedEffect
        val live=vm.ui.value
        if(!live.busy&&!live.unknown&&live.rejectedOperation==operation){questionEdit=draft.copy(operationId=null,message=live.message?:"未提交；原文与原版次仍保留，请重新核对。");vm.consumedRejection(operation)}
    }
    LaunchedEffect(ui.busy,ui.unknown,ui.message){if(!ui.busy&&!ui.unknown&&ui.message?.startsWith("未提交")==true){waitingAnchor=false;afterAnchorPicker=false}}
    Dialog(onDismissRequest={closeWorkspace()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        org.inkweft.app.ui.components.ContextPanel(tab==0,{closeWorkspace()}){Column(Modifier.safeDrawingPadding().imePadding()){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
                Text(listOf("关联","属性与集合","局部关联图","白板与脑图","手动回忆")[tab],Modifier.weight(1f),style=InkTheme.PanelTitle)
                TextButton(onClick={closeWorkspace()},enabled=canDismiss,modifier=Modifier.heightIn(min=48.dp).testTag("knowledge-close")){Text(if(initialBacklinks)"返回摘要"else"返回笔记")}
            }
            HorizontalDivider(color=InkTheme.Divider)
            if(ui.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(ui.readFailed)Column(Modifier.padding(horizontal=16.dp)){
                Text("知识资料暂不可用；原资料没有改变。",color=Quiet)
                TextButton(onClick=vm::reload,modifier=Modifier.heightIn(min=48.dp).testTag("knowledge-links-retry")){Text("重试读取关联")}
            }
            (localMessage?:ui.message)?.let{Text(it,Modifier.padding(horizontal=16.dp),fontSize=12.sp)}
            if(ui.canUndoProperties)TextButton(onClick=vm::undoProperties,enabled=editable){Text("撤销属性修改")}
            if(ui.unknown)TextButton(onClick=vm::retry,enabled=!ui.busy){Text("核对原操作")}
            when(tab){
                0->LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("knowledge-links-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Surface(color=InkTheme.Navigation,shape=MaterialTheme.shapes.medium){
                        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text("正在查看",style=MaterialTheme.typography.labelMedium,color=Quiet)
                            Text(label(focus),style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("knowledge-link-focus"))
                            Text(targetContext(focus),style=MaterialTheme.typography.bodySmall,color=Quiet)
                        }
                    }}
                    item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(focus==initialFocus,{focus=initialFocus},label={Text(when(initialFocus.kind){TargetKind.PAGE->"本页";TargetKind.CARD->"本卡片";TargetKind.ANCHOR->"本区域";TargetKind.NOTE->"笔记全文"})},modifier=Modifier.heightIn(min=48.dp).testTag("knowledge-scope-initial"))
                        if(initialFocus!=TargetRef(TargetKind.NOTE,book))FilterChip(focus==TargetRef(TargetKind.NOTE,book),{focus=TargetRef(TargetKind.NOTE,book)},label={Text("笔记全文")},modifier=Modifier.heightIn(min=48.dp).testTag("knowledge-scope-note"))
                        val otherCards=bookCards.filter{initialFocus!=TargetRef(TargetKind.CARD,it.id)}
                        if(otherCards.isNotEmpty())Box{
                            TextButton(onClick={scopeMenu=true},modifier=Modifier.heightIn(min=48.dp).testTag("knowledge-scope-cards")){Text("切换卡片")}
                            DropdownMenu(scopeMenu,{scopeMenu=false}){otherCards.forEach{c->
                                DropdownMenuItem(text={Text(c.title,maxLines=2,overflow=TextOverflow.Ellipsis)},onClick={focus=TargetRef(TargetKind.CARD,c.id);scopeMenu=false})
                            }}
                        }
                    }}
                    item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(!incoming,{initialDirectionResolved=true;incoming=false},label={Text(if(includeAllRelationKinds)"从这里关联 · ${outgoingLinks.size}"else"我引用的 · ${outgoingLinks.size}")},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("knowledge-links-outgoing"))
                        FilterChip(incoming,{initialDirectionResolved=true;incoming=true},label={Text(if(includeAllRelationKinds)"关联到这里 · ${incomingLinks.size}"else"引用我的 · ${incomingLinks.size}")},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("knowledge-links-incoming"))
                    }}
                    item{Text(if(incoming&&includeAllRelationKinds)"这些内容以不同关系指向这里。先看来源预览，再决定是否打开。"else if(incoming)"这些内容引用了这里。先看来源预览，再决定是否打开。"else"这里引用或关联的内容。先预览，再打开；固定版本会单独标记。",style=MaterialTheme.typography.bodySmall,color=Quiet)}
                    if(ui.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在读取关联…",color=Quiet)}
                    else if(!ui.readFailed){
                        val shown=if(incoming)incomingLinks else outgoingLinks
                        if(shown.isEmpty())item{Column(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text(if(incoming&&includeAllRelationKinds)"还没有内容关联到这里"else if(incoming)"还没有内容引用这里"else"还没有关联内容",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("knowledge-links-empty"))
                            Text(if(!incoming)"点“添加关联”，选择一份笔记或卡片，下次就能从这里继续阅读。"
                                else if(includeAllRelationKinds)"打开其他内容的“关联”，选择这里作为目标，可在此查看关系来源。"
                                else if(focus.kind==TargetKind.NOTE||focus.kind==TargetKind.CARD)"打开另一笔记或卡片的“关联”，选择这里作为引用目标，即可在此查看来源。"
                                else "这里只列出当前${if(focus.kind==TargetKind.PAGE)"页面"else"区域"}的引用。要建立整本笔记引用，可切换“笔记全文”，再从其他笔记或卡片的“关联”中选择这本笔记。",
                                style=MaterialTheme.typography.bodyMedium,color=Quiet)
                        }}
                        items(shown,key={"${if(incoming)"in"else"out"}-${it.first.id}"}){(row,link)->
                            val target=if(incoming)link.source else link.target
                            KnowledgeConnectionCard(label(target),targetContext(target),
                                if(incoming&&includeAllRelationKinds)link.relation.label+" · 指向这里 · 当前来源"else if(incoming)"引用了这里 · 当前来源"else link.relation.label+" · "+(link.pinnedRevision?.let{"固定修订 $it"}?:"实时链接"),
                                if(!incoming&&link.pinnedRevision!=null)"保留所选修订，点击查看固定摘录；卡片名称为当前名称。"else summary(target),
                                "knowledge-${if(incoming)"incoming"else"outgoing"}-${row.id}",enabled,
                                open={preview=LinkPreviewSelection(focus,row.id,row.revision,incoming)},
                                remove=if(!incoming&&focusEditable)({vm.submit(row.notebookId,link,row,true);Unit})else null)
                        }
                    }
                    if(initialAnchor!=null&&!anchorCopied)item{OutlinedButton(onClick={waitingAnchor=true;vm.submit(book,initialAnchor)},enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("create-region-link")){Text("复制区域链接 · 不创建卡片")}}
                    item{Button(onClick={if(initialAnchor!=null&&!anchorCopied){afterAnchorPicker=true;waitingAnchor=true;vm.submit(book,initialAnchor)}else picker=true},enabled=focusEditable&&!ui.loading&&!ui.readFailed,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("add-knowledge-link")){Glyph("link");Spacer(Modifier.width(8.dp));Text(if(incoming)"从这里添加引用"else"添加关联")}}
                    if(!incoming){
                        val aliases=values.values.filterIsInstance<KnowledgeData.Alias>()
                        val candidates=cards.filter{TargetRef(TargetKind.CARD,it.id)!=focus&&(sourceText().contains(it.title,true)||aliases.any{a->a.cardId==it.id&&sourceText().contains(a.name,true)})&&links.none{l->l.second.source==focus&&l.second.target==TargetRef(TargetKind.CARD,it.id)}}
                        item{HorizontalDivider();TextButton(onClick={showMentions=!showMentions},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("knowledge-mentions-toggle")){Text((if(showMentions)"收起未确认提及"else"发现未确认提及")+" · ${candidates.size}")}}
                        if(showMentions){
                            item{Text("仅检查键入文字、摘要与名称，不识别手写；确认后才建立关联。",style=MaterialTheme.typography.bodySmall,color=Quiet)}
                            items(candidates,key={"candidate-"+it.id}){c->Row(verticalAlignment=Alignment.CenterVertically){Text(c.title+" · "+(ui.notes.find{it.id==c.notebookId}?.title?:""),Modifier.weight(1f));TextButton(onClick={vm.submit(focusBook,KnowledgeData.Link(focus,TargetRef(TargetKind.CARD,c.id)))},enabled=focusEditable,modifier=Modifier.heightIn(min=48.dp)){Text("确认关联")}}}
                            if(candidates.isEmpty()&&!ui.loading&&!ui.readFailed)item{Text("当前覆盖范围没有候选提及",style=MaterialTheme.typography.bodySmall,color=Quiet)}
                        }
                    }
                    item{HorizontalDivider();TextButton(onClick={showExports=!showExports},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("knowledge-link-export-options")){Text(if(showExports)"收起导出选项"else"导出关联快照")}}
                    if(showExports){
                        item{TextButton(onClick={scope.launch{try{pendingExport=withContext(Dispatchers.IO){knowledgeMarkdown(app,book,ui.notes,cards,rows)};export.launch("墨织关联快照.md")}catch(c:CancellationException){throw c}catch(_:Exception){localMessage="导出读取失败，原资料保留。"}}},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("导出 Markdown 快照")}}
                        item{TextButton(onClick={pendingExport=KnowledgeCanvasExport.encode(book,cards,rows);canvasExport.launch("墨织知识.canvas")},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("导出 JSON Canvas 快照")}}
                    }
                }
                1->LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{OutlinedTextField(query,{query=it},label={Text("搜索我的总结或标题")},modifier=Modifier.fillMaxWidth().testTag("collection-search"));Text("范围：本笔记的独立摘要；不包含未识别手写。",fontSize=12.sp,color=Quiet)}
                    item{Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        FilterChip(chosenCollectionId==null,{chooseCollection(null)},label={Text("全部摘要")},modifier=Modifier.heightIn(min=48.dp).testTag("collection-filter-all"))
                        collectionRows.forEach{r->(values[r.id] as? KnowledgeData.Collection)?.let{c->FilterChip(chosenCollectionId==r.id,{chooseCollection(r.id)},label={Text(c.title)},modifier=Modifier.heightIn(min=48.dp).testTag("collection-filter-${r.id}"))}}
                        TextButton(onClick={newCollection=true},enabled=editable){Text("保存筛选集合")}}}
                    if(chosenCollectionId!=null)item{
                        if(chosenCollection==null)Text("集合尚未载入、已移除或不可用；未改为全部摘要。可显式选择“全部摘要”。",color=Quiet,modifier=Modifier.testTag("collection-review-unavailable"))
                        else{
                            Text("复习范围：${chosenCollection.title}。搜索词只查卡片，不改变集合复习范围。",color=Quiet,modifier=Modifier.testTag("collection-review-scope"))
                            ReviewScopeSelector(reviewQuestionScope,enabled&&!hasDraft,"collection-review",{chooseReviewScope(it)})
                            Button(onClick={prepareCollectionReview()},enabled=enabled&&!hasDraft&&reviewPreparation==null,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("collection-review-start")){Text(if(reviewPreparation!=null)"正在读取…"else"复习此集合")}
                            collectionReviewMessage?.let{Text(it,color=Quiet,modifier=Modifier.testTag("collection-review-unavailable"))}
                        }
                    }
                    val shown=if(chosenCollectionId!=null&&chosenCollection==null)emptyList()else bookCards.filter{c->(query.isBlank()||c.title.contains(query,true)||c.body.contains(query,true))&&(chosenCollection?.let{KnowledgeQueries.matches(it,properties[c.id]?:KnowledgeData.Properties(c.id))}!=false)}
                    item{Text("${shown.size} 张卡片 · 筛选不复制内容",fontSize=12.sp,color=Quiet)}
                    items(shown,key={it.id}){c->OutlinedCard(onClick={editCardId=c.id},modifier=Modifier.fillMaxWidth()){
                        Column(Modifier.padding(16.dp)){Text(c.title,fontSize=18.sp);Text(c.body,maxLines=3);Text("我的总结 · 手工状态："+(properties[c.id]?.state?:ManualState.INBOX).label,fontSize=12.sp,color=Quiet)}}}
                }
                2->{val graph=KnowledgeQueries.graph(focus,links.map{it.second},hops)
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(hops==1,{hops=1},label={Text("一层")});FilterChip(hops==2,{hops=2},label={Text("两层")});Text("${graph.nodes.size} 个对象 / ${graph.edges.size} 条边",fontSize=12.sp)}
                    Text(if(graph.truncated)"仅展示部分 · 上限 100 个对象 / 200 条边"else"围绕当前对象查看已建立的关联；点对象可切换中心",Modifier.padding(horizontal=16.dp),fontSize=12.sp,color=Quiet)
                    KnowledgeGraph(book,graph,::label){focus=it}
                }
                3->KnowledgeBoard(book,bookCards,rows,editable,{data,old->vm.submit(book,data,old)},{old->vm.submit(book,old.data(),old,true)},browseReady=enabled)
                4->LazyColumn(Modifier.weight(1f).testTag("notebook-review-panel"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{Text("手动回忆",fontSize=24.sp);Text("从摘要卡添加独立问题，再显示答案。手工标记不代表到期调度或算法熟练度。",color=Quiet)}
                    item{ReviewScopeSelector(reviewQuestionScope,enabled&&!hasDraft,"notebook-review",{chooseReviewScope(it)})}
                    item{Button(onClick={prepareReview(null)},enabled=enabled&&!hasDraft&&reviewPreparation==null&&(reviewQuestionScope==ReviewQuestionScope.REVIEW_ONLY||values.values.filterIsInstance<KnowledgeData.Question>().any{q->bookCards.any{it.id==q.cardId}}),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("manual-review-start")){Text(if(reviewPreparation!=null)"正在读取…"else"开始回忆")}}
                    items(bookCards,key={it.id}){c->OutlinedButton(onClick={editCardId=c.id},modifier=Modifier.fillMaxWidth()){Text(if(readOnly)"查看属性 · ${c.title}"else"添加问题 · ${c.title}")}}
                }
            }
            HorizontalDivider(color=InkTheme.Divider)
            Row(Modifier.fillMaxWidth().background(InkTheme.Navigation).horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("关联","集合","关联图","卡片白板","复习").forEachIndexed{i,t->
                    FilterChip(tab==i,{if(tab!=i)cancelReviewPreparation();tab=i},enabled=enabled,label={Text(t)},modifier=Modifier.heightIn(min=48.dp).testTag("knowledge-tab-$i"))
                }
            }
        }}
    }
    if(picker)AlertDialog(onDismissRequest={picker=false},modifier=Modifier.safeDrawingPadding().imePadding(),properties=DialogProperties(decorFitsSystemWindows=false),title={Text("选择关联目标")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(query,{query=it},label={Text("按标题查找，核对所属笔记")})
        Row(Modifier.horizontalScroll(rememberScrollState())){RelationKind.entries.forEach{r->FilterChip(relation==r,{relation=r},enabled=focusEditable,label={Text(r.label)})}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pinned,{pinned=it},enabled=focusEditable);Text("固定卡片当前版本")}
        val matchingCards=cards.filter{TargetRef(TargetKind.CARD,it.id)!=focus&&(query.isBlank()||it.title.contains(query,true))}
        val matchingNotes=if(pinned)emptyList()else ui.notes.filter{TargetRef(TargetKind.NOTE,it.id)!=focus&&(query.isBlank()||it.title.contains(query,true))}
        if(matchingCards.isEmpty()&&matchingNotes.isEmpty())Text(if(query.isBlank())"暂无可关联内容。先新建笔记或摘要卡，再回来添加。"else"没有匹配结果，请缩短标题或清空搜索。",style=MaterialTheme.typography.bodyMedium,color=Quiet,modifier=Modifier.testTag("knowledge-picker-empty"))
        matchingCards.sortedBy{if(it.notebookId==book)0 else 1}.take(80).forEach{c->TextButton(onClick={vm.submit(focusBook,KnowledgeData.Link(focus,TargetRef(TargetKind.CARD,c.id),relation,if(pinned)c.revision else null));picker=false},enabled=focusEditable){Text("${c.title} · ${ui.notes.find{it.id==c.notebookId}?.title.orEmpty()} · ${c.id.take(6)}")}}
        matchingNotes.take(50).forEach{n->TextButton(onClick={vm.submit(focusBook,KnowledgeData.Link(focus,TargetRef(TargetKind.NOTE,n.id),relation));picker=false},enabled=focusEditable){Text("笔记 · ${n.title} · ${n.id.take(6)}")}}
    }},confirmButton={TextButton(onClick={picker=false}){Text("取消")}})
    preview?.let{selection->key(selection,includeAllRelationKinds){KnowledgeLinkPreview(selection.focus,selection.id,selection.revision,selection.incoming,
        enabled=canDismiss,includeAllRelationKinds=includeAllRelationKinds,returnLabel="返回关联",dismiss={preview=null},onOpenTarget=openTarget)}}
    editCard?.let{card->
        val available=card.trashedAt==null&&book in activeBooks
        val questions=rows.filter{it.notebookId==book&&(it.data() as? KnowledgeData.Question)?.cardId==card.id}
        CardPropertiesDialog(card,properties[card.id],questions,editable&&available&&questionEdit==null,enabled&&questionEdit==null,available,{closeProperties()},{row,remove->openQuestion(row,remove)}){data->if(questionEdit==null){val old=if(data is KnowledgeData.Properties)rows.find{it.notebookId==book&&(it.data() as? KnowledgeData.Properties)?.cardId==card.id}else null;vm.submit(book,data,old)}}
    }
    if(editCardId!=null&&editCard==null)AlertDialog(onDismissRequest={closeProperties()},title={Text("卡片不可用")},text={Text("卡片已变化或尚未读取，未保存的编辑没有提交。请关闭后重新核对。")},confirmButton={TextButton({closeProperties()},enabled=enabled&&questionEdit==null){Text("取消编辑")}})
    if(newCollection)CollectionDialog(editable,enabled,{newCollection=false}){vm.submit(book,it)}
    questionEdit?.let{draft->
        val question=draft.row.data() as KnowledgeData.Question
        val current=rows.find{it.id==draft.row.id&&it.notebookId==book&&(it.data() as? KnowledgeData.Question)?.cardId==question.cardId}
        val available=current!=null&&bookCards.any{it.id==question.cardId}
        QuestionMaintenanceDialog(draft,editCard?.title?:"摘要卡不可用",!readOnly,ui.busy,ui.unknown,available,current?.revision!=draft.row.revision,vm.pendingOperationId==draft.operationId&&draft.operationId!=null,
            {value->val live=vm.ui.value;if(!live.busy&&!live.unknown&&readLock.canWrite&&questionEdit?.editorId==draft.editorId&&questionEdit?.operationId==null&&value.length<=2000)questionEdit=questionEdit?.copy(prompt=value,message=null)},
            {submitQuestion(draft.editorId)},{closeQuestion(draft.editorId)},{val live=vm.ui.value;if(live.unknown&&!live.busy&&questionEdit?.editorId==draft.editorId&&vm.pendingOperationId==questionEdit?.operationId&&questionEdit?.operationId!=null)vm.retry()})
    }
    reviewPlan?.let{plan->BranchReviewDialog(plan,{reviewPlan=null},showSummary=reviewWithSummary,showCollectionScope=reviewCollectionScope)}
}

@Composable private fun QuestionMaintenanceDialog(draft:QuestionEditDraft,cardTitle:String,writable:Boolean,busy:Boolean,unknown:Boolean,available:Boolean,revisionChanged:Boolean,canRetry:Boolean,
    changePrompt:(String)->Unit,submit:()->Unit,dismiss:()->Unit,retry:()->Unit){
    val canClose=!busy&&!unknown&&draft.operationId==null
    val canEdit=canClose&&writable&&available
    val savedQuestion=draft.row.data() as KnowledgeData.Question
    AlertDialog(onDismissRequest={if(canClose)dismiss()},
        modifier=Modifier.widthIn(max=560.dp).fillMaxWidth().padding(horizontal=16.dp).imePadding().testTag(if(draft.removing)"question-remove-dialog"else"question-edit-dialog"),
        properties=DialogProperties(usePlatformDefaultWidth=false),
        title={Text(if(draft.removing)"移除这道回忆题？"else"编辑回忆题")},
        text={Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(cardTitle);Text("手工状态：${savedQuestion.state.label} · 原修订 ${draft.row.revision}",color=Quiet)
            if(draft.removing){
                Text(savedQuestion.prompt,modifier=Modifier.testTag("question-remove-prompt"))
                Text("只移除这道题，摘要卡、答案和来源保留。已经开始的回忆仍可显示原题。",color=Quiet)
            }else OutlinedTextField(draft.prompt,changePrompt,enabled=canClose,readOnly=!writable||!available,label={Text("问题原文")},minLines=3,maxLines=10,modifier=Modifier.fillMaxWidth().testTag("question-edit-prompt"))
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(unknown)Text("结果待核对；原文与原操作已保留。请核对原操作后再退出。",color=Quiet)
            else if(!busy){
                if(!available)Text("题目或摘要卡尚未载入、已移除或不可用；草稿仍保留。",color=Quiet,modifier=Modifier.testTag("question-edit-unavailable"))
                val message=draft.message?:if(available&&revisionChanged)"题目版本已变化；草稿与原版次仍保留，不会覆盖新版本。请取消后重新核对。"else null
                message?.let{Text(it,color=Quiet,modifier=Modifier.testTag("question-edit-conflict"))}
                if(!writable)Text("当前为阅读模式，请返回书写后编辑。",color=Quiet)
            }
        }},
        confirmButton={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick=submit,enabled=canEdit&&(draft.removing||draft.prompt.isNotBlank()),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(if(draft.removing)"question-remove-confirm"else"question-edit-save")){Text(if(busy)"正在核对…"else if(draft.removing)"移除这道题"else"保存题目")}
            if(unknown)OutlinedButton(onClick=retry,enabled=!busy&&canRetry,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("question-edit-retry")){Text("核对原操作")}
            TextButton(onClick=dismiss,enabled=canClose,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(if(draft.removing)"question-remove-cancel"else"question-edit-cancel")){Text("取消")}
        }})
}

@Composable private fun CollectionDialog(enabled:Boolean,canClose:Boolean,dismiss:()->Unit,save:(KnowledgeData.Collection)->Unit){
    var title by rememberSaveable{mutableStateOf("")};var tag by rememberSaveable{mutableStateOf("")};var state by rememberSaveable{mutableStateOf<ManualState?>(null)};var any by rememberSaveable{mutableStateOf(false)}
    AlertDialog(onDismissRequest={if(canClose)dismiss()},title={Text("保存智能集合")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        OutlinedTextField(title,{if(it.length<=120)title=it},enabled=enabled,label={Text("集合名称")});OutlinedTextField(tag,{if(it.length<=24)tag=it},enabled=enabled,label={Text("包含标签，留空不限")})
        FilterChip(state==null,{state=null},enabled=enabled,label={Text("所有手工状态")});ManualState.entries.forEach{s->FilterChip(state==s,{state=s},enabled=enabled,label={Text(s.label)})}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(any,{any=it},enabled=enabled);Text(if(any)"满足任一条件（或）"else"满足全部条件（且）")}
        Text("范围为当前笔记的摘要卡；内容和文件夹保持原位置。",fontSize=12.sp,color=Quiet)
    }},confirmButton={TextButton(onClick={save(KnowledgeData.Collection(title.trim(),tag.trim(),state,any))},enabled=enabled&&title.isNotBlank()){Text("保存集合")}},dismissButton={TextButton(onClick=dismiss,enabled=canClose){Text("取消")}})
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
        is KnowledgeData.MapPortal->appendLine("- 跨图入口 ${r.id}：图 ${d.sourceMapId?:"主图"} / 节点 ${d.sourceNodeId} → 整图 ${d.targetMapId?:"主图"} · ${if(r.removed)"已移除"else"保留身份"}；此文本不还原入口关系，完整恢复使用资料库备份。")
        else->Unit
    }}
}

}
