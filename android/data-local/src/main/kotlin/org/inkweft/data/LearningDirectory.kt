// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.inkweft.core.*

data class LearningNoteRow(val id:String,val title:String,val favorite:Boolean,val trashedAt:Long?)
data class LearningCardRow(val id:String,val notebookId:String,val title:String,val trashedAt:Long?)
data class LearningPageRow(val id:String,val notebookId:String,val position:Int,val trashedAt:Long?)
data class LearningNodeRow(val id:String,val notebookId:String,val cardId:String,val removed:Boolean)
data class LearningEntry(val target:StableTargetRef,val title:String,val subtitle:String,val available:Boolean=true)
data class LearningDirectoryState(val notes:List<LearningNoteRow>,val maps:List<LearningEntry>,val collections:List<LearningEntry>,val inbox:List<LearningEntry>,val cards:List<LearningCardRow>,val pages:List<LearningPageRow>,val branches:List<LearningEntry>)

/** Metadata only: no stroke, attachment, source snapshot, body text or editing canvas loads. */
class LearningDirectory(private val db:NoteDatabase){
    fun observe():Flow<LearningDirectoryState> = combine(db.notes().observeLearningNotes().distinctUntilChanged(),
        db.study().observeLearningCards().distinctUntilChanged(),db.knowledge().observe(),db.pages().observeLearningPages().distinctUntilChanged(),db.study().observeLearningNodes().distinctUntilChanged()) { notes,cards,records,pages,nodes ->
        val books=notes.associateBy{it.id}
        val parsed=records.filterNot{it.removed}.mapNotNull{r->runCatching{r to r.data()}.getOrNull()}
        val usedMainBooks=nodes.filterNot{it.removed}.map{it.notebookId}.toSet()
        val maps=notes.filter{it.id in usedMainBooks}.map{n->LearningEntry(StableTargetRef(LearningTargetKind.MAP,n.id),"主图",n.title,n.trashedAt==null)}+
            parsed.mapNotNull{(r,d)->(d as? KnowledgeData.MapDefinition)?.let{LearningEntry(StableTargetRef(LearningTargetKind.MAP,r.notebookId,r.id),it.title,books[r.notebookId]?.title.orEmpty(),books[r.notebookId]?.trashedAt==null)}}
        val collections=parsed.mapNotNull{(r,d)->(d as? KnowledgeData.Collection)?.let{LearningEntry(StableTargetRef(LearningTargetKind.COLLECTION,r.notebookId,r.id),it.title,books[r.notebookId]?.title.orEmpty(),books[r.notebookId]?.trashedAt==null)}}
        val properties=parsed.mapNotNull{it.second as? KnowledgeData.Properties}.associateBy{it.cardId}
        // The existing manual state defaults to INBOX. Graph membership never changes it.
        val inbox=cards.filter{it.trashedAt==null&&books[it.notebookId]?.trashedAt==null&&(properties[it.id]?.state?:ManualState.INBOX)==ManualState.INBOX}
            .map{LearningEntry(StableTargetRef(LearningTargetKind.CARD,it.notebookId,it.id),it.title,books[it.notebookId]?.title.orEmpty())}
        val byCard=cards.associateBy{it.id}
        val branches=nodes.filterNot{it.removed}.mapNotNull{node->byCard[node.cardId]?.takeIf{it.trashedAt==null}?.let{LearningEntry(StableTargetRef(LearningTargetKind.BRANCH,node.notebookId,node.id),it.title,books[node.notebookId]?.title.orEmpty(),books[node.notebookId]?.trashedAt==null)}}+
            parsed.flatMap{(r,d)->when(d){
                is KnowledgeData.MapDefinition->d.structures.map{LearningEntry(StableTargetRef(LearningTargetKind.BRANCH,r.notebookId,it.id,r.id),it.title,d.title,books[r.notebookId]?.trashedAt==null)}
                is KnowledgeData.MapOccurrence->listOfNotNull(byCard[d.cardId]?.takeIf{it.trashedAt==null}?.let{LearningEntry(StableTargetRef(LearningTargetKind.BRANCH,r.notebookId,r.id,d.mapId),it.title,books[r.notebookId]?.title.orEmpty(),books[r.notebookId]?.trashedAt==null&&maps.any{it.target.id==d.mapId})})
                else->emptyList()
            }}
        LearningDirectoryState(notes,maps,collections,inbox,cards,pages,branches)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
