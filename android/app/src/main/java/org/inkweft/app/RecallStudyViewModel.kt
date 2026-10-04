// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal data class RecallUi(val session:RecallLoadedSession?=null,val queue:List<RecallQueueItem> = emptyList(),val loading:Boolean=true,
    val busy:Boolean=false,val pending:Boolean=false,val error:String?=null,val draftText:String="",val draftInk:ByteArray=byteArrayOf(),
    val draftSaved:Boolean=true,val draftDirty:Boolean=false,val navigationReady:Boolean=false)
private data class RecallIntent(val raw:String){val value get()=JSONObject(raw);val id get()=value.getString("id");val kind get()=value.getString("kind")}
private class RecallJournal(context:Context,book:String){
    private val pending=AtomicFile(File(context.filesDir,"recall-$book.pending"))
    private val draft=AtomicFile(File(context.filesDir,"recall-$book.draft"))
    private fun read(file:AtomicFile):String?=try{file.readFully().also{require(it.size<=4_000_000)}.toString(Charsets.UTF_8)}catch(_:FileNotFoundException){null}
    private fun write(file:AtomicFile,text:String){val bytes=text.toByteArray();require(bytes.size<=4_000_000){"RECALL_LOCAL_DRAFT_BUDGET"};val stream=file.startWrite();try{stream.write(bytes);file.finishWrite(stream)}catch(t:Throwable){file.failWrite(stream);throw t}}
    fun pending()=read(pending)?.let(::RecallIntent)
    fun save(intent:RecallIntent){val old=pending();require(old==null||old.raw==intent.raw){"RECALL_PENDING_OPERATION"};write(pending,intent.raw)}
    fun clear(intent:RecallIntent){if(pending()?.id==intent.id)pending.delete()}
    fun draft()=read(draft)?.let(::JSONObject)
    fun draft(value:JSONObject)=write(draft,value.toString())
    fun clearDraft(attempt:String){if(draft()?.optString("attempt")==attempt)draft.delete()}
}

/** Only bounded IDs enter SavedState. Pending commands and unsubmitted answer drafts use private atomic files. */
internal class RecallStudyViewModel(private val repository:RecallStudyRepository,private val book:String,context:Context,private val saved:SavedStateHandle):ViewModel(){
    private val state=MutableStateFlow(RecallUi());val ui=state.asStateFlow()
    private val journal=RecallJournal(context.applicationContext,book);private val disk=Mutex();private val loadedState=Mutex()
    private var pending:RecallIntent?=null;private var observer:Job?=null;private var plan:BranchReviewPlan?=null
    @Volatile private var draftAttempt:String?=null
    @Volatile private var draftGeneration=0L
    private var navigation:Pair<String,()->Unit>?=null
    private var afterWrite:(()->Unit)?=null
    val sessionId get()=saved.get<String>("session")
    fun open(value:BranchReviewPlan){
        if(plan===value)return;plan=value
        viewModelScope.launch{
            state.value=state.value.copy(loading=true,error=null)
            try{
                val intent=withContext(Dispatchers.IO){disk.withLock{journal.pending()}};pending=intent
                state.value=state.value.copy(pending=intent!=null)
                val selected=sessionId?.let{runCatching{withContext(Dispatchers.IO){repository.loadSession(it)}}.getOrNull()?.row?.takeUnless{it.closed}}
                    ?:withContext(Dispatchers.IO){repository.resume(book)}
                if(selected!=null)observe(selected.id)
                else{observer?.cancel();saved["session"]=null;state.value=state.value.copy(session=null,queue=withContext(Dispatchers.IO){repository.queue(value)},loading=false)}
            }catch(c:CancellationException){throw c}catch(e:Exception){state.value=state.value.copy(loading=false,error=recallError(e.message))}
        }
    }
    fun newRound(){if(state.value.busy||pending!=null||state.value.session?.row?.closed!=true)return;observer?.cancel();saved["session"]=null;draftAttempt=null;navigation=null;state.value=RecallUi(loading=false);refreshQueue()}
    fun refreshQueue(){val value=plan?:return;viewModelScope.launch{try{state.value=state.value.copy(queue=withContext(Dispatchers.IO){val fresh=repository.refreshReferences(value);plan=fresh;repository.queue(fresh)},error=null)}catch(e:Exception){state.value=state.value.copy(error=recallError(e.message))}}}
    private fun observe(id:String){
        saved["session"]=id;observer?.cancel()
        observer=viewModelScope.launch{
            repository.observeSession(id).flowOn(Dispatchers.IO).collect{acceptLoaded(it)}
        }
        observer?.invokeOnCompletion{error->if(error!=null&&error !is CancellationException)state.value=state.value.copy(loading=false,error=recallError(error.message))}
    }
    private suspend fun acceptLoaded(loaded:RecallLoadedSession)=loadedState.withLock{
        val previous=state.value.session
        if(previous?.row?.id==loaded.row.id&&(previous.row.revision>loaded.row.revision||
            previous.current?.row?.id==loaded.current?.row?.id&&(previous.current?.row?.revision?:0)>(loaded.current?.row?.revision?:0)))return@withLock
                val current=loaded.current?.row
                if(current?.id!=draftAttempt){
                    val local=withContext(Dispatchers.IO){disk.withLock{journal.draft()}}
                    draftAttempt=current?.id;draftGeneration++
                    val recover=current!=null&&!current.answerRevealed&&local?.optString("attempt")==current.id
                    val text=if(recover)local!!.getString("text")else current?.answerText.orEmpty()
                    val ink=if(recover)Base64.decode(local!!.getString("ink"),Base64.NO_WRAP)else current?.answerInk?:byteArrayOf()
                    state.value=state.value.copy(session=loaded,loading=false,draftText=text,draftInk=ink,draftSaved=true,
                        draftDirty=current!=null&&(text!=current.answerText||!ink.contentEquals(current.answerInk)))
                }else{
                    val keepDraft=current!=null&&!current.answerRevealed&&(state.value.draftDirty||!state.value.draftSaved)
                    val text=if(keepDraft)state.value.draftText else current?.answerText.orEmpty()
                    val ink=if(keepDraft)state.value.draftInk else current?.answerInk?:byteArrayOf()
                    val dirty=current!=null&&(text!=current.answerText||!ink.contentEquals(current.answerInk))
                    state.value=state.value.copy(session=loaded,loading=false,draftText=text,draftInk=ink,draftDirty=dirty)
                }
    }
    fun draft(text:String=state.value.draftText,ink:ByteArray=state.value.draftInk){
        val row=state.value.session?.current?.row?:return
        if(row.answerRevealed||state.value.loading||state.value.busy||pending!=null)return
        val bytes=ink.copyOf();val generation=++draftGeneration
        state.value=state.value.copy(draftText=text,draftInk=bytes,draftDirty=text!=row.answerText||!bytes.contentEquals(row.answerInk),draftSaved=false,error=null)
        persistDraft(row.id,text,bytes,generation)
    }
    /** A live-stroke checkpoint goes only to this attempt's private draft, never an author page. */
    fun checkpoint(stroke:InkStroke){
        val row=state.value.session?.current?.row?:return
        if(row.answerRevealed||state.value.loading||state.value.busy||pending!=null)return
        val strokes=answerStrokes(state.value.draftInk).filterNot{it.id==stroke.id}+stroke
        val bytes=InkPageFile("回忆作答","",strokes,false,PaperStyle.BLANK).encode()
        persistDraft(row.id,state.value.draftText,bytes,++draftGeneration)
    }
    fun cancelCheckpoint(){val row=state.value.session?.current?.row?:return;persistDraft(row.id,state.value.draftText,state.value.draftInk,++draftGeneration)}
    private fun persistDraft(attempt:String,text:String,ink:ByteArray,generation:Long){
        state.value=state.value.copy(draftSaved=false)
        viewModelScope.launch{
            try{withContext(Dispatchers.IO){disk.withLock{if(draftGeneration==generation&&draftAttempt==attempt)journal.draft(JSONObject().put("attempt",attempt).put("text",text).put("ink",Base64.encodeToString(ink,Base64.NO_WRAP)))}}
                if(draftAttempt==attempt&&draftGeneration==generation)state.value=state.value.copy(draftSaved=true)
            }catch(c:CancellationException){throw c}catch(e:Exception){if(draftGeneration==generation)state.value=state.value.copy(draftSaved=false,error=recallError(e.message))}
        }
    }
    fun start(mode:RecallMode){
        if(state.value.loading||state.value.session?.row?.closed==false)return
        val plan=plan?:return
        val json=base("START",UUID.randomUUID().toString(),0).put("mode",mode.name).put("plan",encodePlan(plan))
        submit(RecallIntent(json.toString()))
    }
    fun save(after:(()->Unit)?=null){
        if(state.value.loading||state.value.busy||pending!=null||!state.value.draftSaved)return
        val row=state.value.session?.current?.row?:return
        if(!state.value.draftDirty){after?.invoke();return}
        try{RecallCodec.validateAnswer(state.value.draftText,state.value.draftInk)}catch(e:Exception){state.value=state.value.copy(error=recallError(e.message));return}
        val json=base("ANSWER",row.id,row.revision).put("text",state.value.draftText).put("ink",Base64.encodeToString(state.value.draftInk,Base64.NO_WRAP))
        submit(RecallIntent(json.toString()),after)
    }
    fun hint(kind:RecallHint,index:Int?=null){save{
        val row=state.value.session?.current?.row?:return@save
        submit(RecallIntent(base("HINT",row.id,row.revision).put("hint",kind.name).put("index",index?:-1).toString()))
    }}
    fun reveal(){save{val row=state.value.session?.current?.row?:return@save;submit(RecallIntent(base("REVEAL",row.id,row.revision).toString()))}}
    fun grade(quality:Int,asPractice:Boolean=false){val row=state.value.session?.current?.row?:return;submit(RecallIntent(base("GRADE",row.id,row.revision).put("quality",quality).put("practice",asPractice).toString()))}
    fun skip(){save{val row=state.value.session?.current?.row?:return@save;submit(RecallIntent(base("SKIP",row.id,row.revision).toString()))}}
    fun closeSession(){save{val row=state.value.session?.row?:return@save;submit(RecallIntent(base("CLOSE",row.id,row.revision).toString()))}}
    fun withdraw(entry:RecallHistoryEntry,reason:String){submit(RecallIntent(base("WITHDRAW",entry.row.id,entry.scheduleRevision).put("reason",reason).toString()))}
    fun original(after:()->Unit):Boolean {
        val current=state.value.session?.current?:return false
        if(state.value.loading||state.value.busy||!state.value.draftSaved)return false
        val expected=current.row.id
        navigation=expected to after
        if(pending!=null){
            if(pending!!.kind=="ORIGINAL"&&pending!!.value.getString("target")==expected){retry();return true}
            navigation=null;return false
        }
        val fact=if(current.row.answerRevealed)"ORIGINAL_COMPARE"else RecallHint.ORIGINAL.name
        if(current.hints.any{it.kind==fact}){state.value=state.value.copy(navigationReady=true);return true}
        save{
            val row=state.value.session?.current?.row
            if(row?.id==expected)submit(RecallIntent(base("ORIGINAL",row.id,row.revision).toString()))else navigation=null
        }
        return true
    }
    /** Called after UI pending guards have observed success; stale or disposed continuations are dropped. */
    fun finishNavigation(){
        val next=navigation;navigation=null;state.value=state.value.copy(navigationReady=false)
        if(next!=null&&state.value.session?.current?.row?.id==next.first&&!state.value.busy&&pending==null)next.second()
    }
    fun cancelNavigation(){navigation=null;state.value=state.value.copy(navigationReady=false)}
    fun retry(){pending?.let{submit(it,afterWrite)}}
    private fun base(kind:String,target:String,revision:Long)=JSONObject().put("id",UUID.randomUUID().toString()).put("kind",kind).put("book",book).put("target",target).put("revision",revision).put("at",System.currentTimeMillis())
    private fun submit(intent:RecallIntent,after:(()->Unit)?=null){
        if(state.value.busy||pending!=null&&pending!!.raw!=intent.raw)return
        pending=intent;afterWrite=after;state.value=state.value.copy(busy=true,pending=true,error=null)
        viewModelScope.launch{
            try{
                withContext(Dispatchers.IO){disk.withLock{journal.save(intent)}}
                val result=withContext(Dispatchers.IO){execute(intent)}
                when(result){
                    is RecallOutcome.Success->{
                        withContext(Dispatchers.IO){disk.withLock{journal.clear(intent)}};pending=null;afterWrite=null
                        if(intent.kind=="START")observe(result.id)
                        else sessionId?.let{id->val loaded=withContext(Dispatchers.IO){repository.loadSession(id)};acceptLoaded(loaded)}
                        if(intent.kind=="ANSWER"){
                            val current=state.value.session?.current?.row
                            if(current?.id==intent.value.getString("target")){
                                state.value=state.value.copy(draftDirty=false)
                                withContext(Dispatchers.IO){disk.withLock{journal.clearDraft(current.id)}}
                            }
                        }
                        state.value=state.value.copy(busy=false,pending=false,error=null,navigationReady=intent.kind=="ORIGINAL"&&navigation!=null)
                        after?.invoke()
                    }
                    is RecallOutcome.Rejected->{
                        withContext(Dispatchers.IO){disk.withLock{journal.clear(intent)}};pending=null;afterWrite=null;navigation=null
                        state.value=state.value.copy(busy=false,pending=false,error=recallError(result.reason))
                    }
                    RecallOutcome.Unknown->state.value=state.value.copy(busy=false,error="结果待核对，请重试同一次操作；不会重复记分")
                }
            }catch(c:CancellationException){throw c}catch(e:Exception){state.value=state.value.copy(busy=false,pending=true,error=recallError(e.message)+"；保留原操作编号供重试")}
        }
    }
    private suspend fun execute(intent:RecallIntent):RecallOutcome {
        val c=intent.value;require(c.getString("book")==book)
        val id=c.getString("id");val target=c.getString("target");val revision=c.getLong("revision");val at=c.getLong("at")
        return when(intent.kind){
            "START"->repository.start(id,target,decodePlan(c.getJSONObject("plan")),RecallMode.valueOf(c.getString("mode")),at)
            "ANSWER"->repository.saveAnswer(id,book,target,revision,c.getString("text"),Base64.decode(c.getString("ink"),Base64.NO_WRAP))
            "HINT"->repository.hint(id,book,target,revision,RecallHint.valueOf(c.getString("hint")),c.getInt("index").takeIf{it>=0},at)
            "ORIGINAL"->repository.consultOriginal(id,book,target,revision,at)
            "REVEAL"->repository.revealAnswer(id,book,target,revision,at)
            "GRADE"->repository.grade(id,book,target,revision,c.getInt("quality"),at,c.getBoolean("practice"))
            "SKIP"->repository.skip(id,book,target,revision,at)
            "CLOSE"->repository.closeSession(id,book,target,revision,at)
            "WITHDRAW"->repository.withdraw(id,book,target,revision,c.getString("reason"),at)
            else->error("RECALL_PENDING_KIND")
        }
    }
    class Factory(private val repository:RecallStudyRepository,private val book:String,private val context:Context):ViewModelProvider.Factory{
        override fun<T:ViewModel>create(modelClass:Class<T>,extras:CreationExtras):T{@Suppress("UNCHECKED_CAST")return RecallStudyViewModel(repository,book,context,extras.createSavedStateHandle()) as T}
    }
    companion object{
        fun hasPending(context:Context,book:String)=RecallJournal(context.applicationContext,book).pending()!=null
        fun answerStrokes(bytes:ByteArray)=if(bytes.isEmpty())emptyList()else InkPageFile.decode(bytes).strokes
        private fun encodePlan(plan:BranchReviewPlan)=JSONObject().put("book",plan.ref.notebookId).put("map",plan.ref.mapId.orEmpty()).put("branch",plan.branchId.orEmpty()).put("title",plan.title)
            .put("cards",plan.cardCount).put("missing",plan.withoutQuestionCount).put("scope",plan.scope.name).put("total",plan.totalQuestionCount).put("other",plan.otherStateOnlyCardCount)
            .put("entries",JSONArray().apply{plan.entries.forEach{put(JSONArray(listOf(it.questionId,it.questionRevision,it.cardId,it.cardRevision)))}})
        private fun decodePlan(json:JSONObject):BranchReviewPlan {
            val entries=json.getJSONArray("entries");require(entries.length()<=BranchReview.MAX_QUESTIONS)
            return BranchReviewPlan(MapRef(json.getString("book"),json.getString("map").ifEmpty{null}),json.getString("branch").ifEmpty{null},json.getString("title"),json.getInt("cards"),json.getInt("missing"),
                List(entries.length()){entries.getJSONArray(it).let{e->BranchReviewEntryRef(e.getString(0),e.getLong(1),e.getString(2),e.getLong(3))}},ReviewQuestionScope.valueOf(json.getString("scope")),json.getInt("total"),json.getInt("other"))
        }
    }
}
internal fun recallError(reason:String?)=when(reason){
    "RECALL_SPEC_NEEDS_REVIEW"->"题目、知识或来源已变化，请核对并保存新的固定答案；当前旧答案仍保留，可明确改存临时练习"
    "RECALL_SCHEDULE_CHANGED","RECALL_ATTEMPT_CHANGED"->"另一处已更新此题，请重新读取；本次未覆盖新记录"
    "RECALL_CORRECTION_HAS_SUCCESSOR"->"此评分之后已有有效评分或题目重设，请先处理后继记录，不能覆盖它们撤销"
    "RECALL_QUEUE_EMPTY"->"这个范围目前没有到期题，可选择临时练习"
    "RECALL_RESUME_PENDING_SESSION"->"请先继续或结束本笔记未完成的复习"
    "RECALL_ANSWER_TEXT_BUDGET","RECALL_ANSWER_INK_BUDGET","RECALL_ANSWER_LIBRARY_BUDGET"->"作答超过保存容量，本机草稿仍保留，请缩短文字或减少笔迹后再保存"
    "RECALL_ATTEMPT_BUDGET","RECALL_SESSION_BUDGET","RECALL_COMMAND_BUDGET"->"复习记录达到明确容量上限；已有答案与历史保留，未清空或覆盖"
    else->"暂时无法完成，请核对后重试"+(reason?.let{"（$it）"}?:"")
}
