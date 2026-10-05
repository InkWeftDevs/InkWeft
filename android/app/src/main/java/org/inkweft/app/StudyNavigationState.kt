// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import org.inkweft.core.BranchReviewPlan
import org.inkweft.data.*

/** Transient navigation belongs to this mounted workspace, never to the saved return stacks. */
internal class StudyNavigationState(
    private val scope:CoroutineScope,
    private val readSources:suspend (String,Long?)->FrozenStudySources,
) {
    data class NodeOwner(
        val mapId:String?,val selectedNodeId:String?,val tab:Int,val cardId:String?,
        val knowledgeCardId:String?,val reviewPlan:BranchReviewPlan?,val titleToken:String?,val editor:CardEditor?,
    )
    data class CardOwner(val mapId:String?,val cardId:String,val nodeId:String?)

    var source by mutableStateOf<StudySourceRow?>(null)
        private set
    var sourceVersions by mutableStateOf<Pair<StudyCardRow,FrozenStudySources>?>(null)
        private set
    var sourceLoadFailed by mutableStateOf(false)
        private set
    private var sourceRead:Any?=null
    private var nodeJob by mutableStateOf<Job?>(null)
    private var cardJob by mutableStateOf<Job?>(null)
    private var nodeOwner:NodeOwner?=null
    private var cardOwner:CardOwner?=null
    val nodeSourceOpening get()=nodeJob!=null
    val cardSourceOpening get()=cardJob!=null

    suspend fun loadCardSources(card:StudyCardRow?,structural:Boolean){
        val request=Any();sourceRead=request
        source=null;sourceVersions=null;sourceLoadFailed=false
        card?:return
        try{
            val versions=if(structural)FrozenStudySources(emptyList(),emptyList(),true)
                else withContext(Dispatchers.IO){readSources(card.id,card.revision)}
            currentCoroutineContext().ensureActive()
            if(sourceRead===request){sourceVersions=card to versions;source=versions.singleLegacy(card.id)}
        }catch(c:CancellationException){throw c}
        catch(_:Exception){currentCoroutineContext().ensureActive();if(sourceRead===request)sourceLoadFailed=true}
    }

    fun selectSource(value:StudySourceRow?){source=value}

    fun openNodeSource(
        node:StudyNodeRow,owner:NodeOwner,currentOwner:()->NodeOwner,nodeIsCurrent:()->Boolean,
        documentReady:()->Boolean,openSource:suspend (StudySourceRow)->Boolean,
        chooseSources:()->Unit,message:(String)->Unit,
    ):Job? {
        if(nodeSourceOpening)return null
        nodeOwner=owner
        val job=scope.launch(start=CoroutineStart.LAZY){
            val request=currentCoroutineContext().job
            fun current()=nodeJob===request&&currentOwner()==owner&&nodeIsCurrent()
            try{
                val originals=withContext(Dispatchers.IO){readSources(node.cardId,null)}
                currentCoroutineContext().ensureActive()
                if(!current()||!documentReady())return@launch
                if(!originals.complete||originals.sources.size>1){chooseSources();return@launch}
                val original=originals.singleLegacy(node.cardId)
                val opened=original!=null&&openSource(original)
                currentCoroutineContext().ensureActive()
                if(current()&&!opened)message(SOURCE_UNAVAILABLE)
            }catch(c:CancellationException){throw c}
            catch(_:Exception){currentCoroutineContext().ensureActive();if(current())message(SOURCE_READ_FAILED)}
            finally{if(nodeJob===request){nodeJob=null;nodeOwner=null}}
        }
        nodeJob=job;job.start();return job
    }

    fun openCardSource(
        owner:CardOwner,source:StudySourceRow,currentOwner:()->CardOwner?,
        openSource:suspend (StudySourceRow)->Boolean,opened:()->Unit,message:(String)->Unit,
    ):Job? {
        if(cardSourceOpening||source.cardId!=owner.cardId||currentOwner()!=owner)return null
        cardOwner=owner
        val job=scope.launch(start=CoroutineStart.LAZY){
            val request=currentCoroutineContext().job
            try{
                if(cardJob!==request||currentOwner()!=owner)return@launch
                val success=openSource(source)
                currentCoroutineContext().ensureActive()
                if(cardJob===request&&currentOwner()==owner){
                    if(success)opened()else message(SOURCE_UNAVAILABLE)
                }
            }finally{if(cardJob===request){cardJob=null;cardOwner=null}}
        }
        cardJob=job;job.start();return job
    }

    fun cancelNodeSource(owner:NodeOwner?=null){
        if(owner!=null&&nodeOwner!=owner)return
        val job=nodeJob;nodeJob=null;nodeOwner=null;job?.cancel()
    }
    fun cancelCardSource(owner:CardOwner?=null){
        if(owner!=null&&cardOwner!=owner)return
        val job=cardJob;cardJob=null;cardOwner=null;job?.cancel()
    }
    fun cancelSourceNavigation(){cancelNodeSource();cancelCardSource()}

    companion object {
        const val SOURCE_UNAVAILABLE="来源页已回收或不可用；原迹快照仍保留。"
        const val SOURCE_READ_FAILED="来源读取失败，请重试；卡片内容仍保留。"
    }
}
