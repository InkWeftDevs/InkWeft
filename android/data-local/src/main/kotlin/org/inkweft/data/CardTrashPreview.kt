// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

data class CardTrashAffected(val id:String,val notebookId:String,val revision:Long,val label:String)
data class CardTrashPreview(val card:StudyCardRow,val fingerprint:String,val references:List<CardTrashAffected>,
    val questions:List<CardTrashAffected>,val occurrences:List<CardTrashAffected>) {
    fun command(id:String=UUID.randomUUID().toString())=StudyCommand(id,card.notebookId,StudyAction.TRASH_CARD,
        cardId=card.id,expectedRevision=card.revision,expectedTrashImpact=fingerprint)
}

/** One read transaction, including references owned by other notebooks. Opening/cancelling writes nothing. */
internal suspend fun readCardTrashPreview(db:NoteDatabase,book:String,cardId:String):CardTrashPreview=db.withTransaction {
    val card=db.study().card(cardId)?:throw StudyRejected("CARD_VERSION_CHANGED")
    if(card.notebookId!=book||card.trashedAt!=null)throw StudyRejected("CARD_VERSION_CHANGED")
    if(db.workspace().get(book)?.let{it.trashedAt==null}!=true||db.notes().note(book)==null)throw StudyRejected("STUDY_BOOK_UNAVAILABLE")
    val target=TargetRef(TargetKind.CARD,card.id)
    val related=db.knowledge().all().filter{r->when(val d=r.data()){
        is KnowledgeData.Link->d.source==target||d.target==target
        is KnowledgeData.Question->d.cardId==card.id
        is KnowledgeData.Placement->d.cardId==card.id
        is KnowledgeData.MapOccurrence->d.cardId==card.id
        else->false
    }}.sortedBy{it.id}
    val main=db.study().nodes(book).filter{it.cardId==card.id}.sortedBy{it.id}
    val bytes=ByteArrayOutputStream()
    DataOutputStream(bytes).use{out->
        out.writeUTF("card-trash-impact-v1");out.writeUTF(book);out.writeUTF(card.id);out.writeLong(card.revision)
        // Include removed identities too, so editing/removing an item after preview also requires a fresh list.
        out.writeInt(main.size);main.forEach{out.writeUTF(it.id);out.writeLong(it.revision);out.writeBoolean(it.removed)}
        out.writeInt(related.size);related.forEach{r->out.writeUTF(r.id);out.writeUTF(r.notebookId);out.writeLong(r.revision)
            out.writeBoolean(r.removed);out.writeInt(r.payload.size);out.write(r.payload)}
    }
    val bookLabels=mutableMapOf<String,String>()
    suspend fun notebook(id:String):String {
        bookLabels[id]?.let{return it}
        return "${db.notes().note(id)?.title?:"笔记不可用"} · $id".also{bookLabels[id]=it}
    }
    suspend fun endpoint(ref:TargetRef):String=when(ref.kind){
        TargetKind.NOTE->db.notes().note(ref.id)?.title?:"笔记不可用"
        TargetKind.PAGE->db.pages().get(ref.id)?.let{"第 ${it.position+1} 页"}?:"页面不可用"
        TargetKind.CARD->db.study().card(ref.id)?.title?:"卡片不可用"
        TargetKind.ANCHOR->"来源区域"
    }+" · ${ref.id}"
    val references=mutableListOf<CardTrashAffected>();val questions=mutableListOf<CardTrashAffected>()
    val occurrences=mutableListOf<CardTrashAffected>()
    for(row in related.filterNot{it.removed}){
        val label=notebook(row.notebookId)
        when(val d=row.data()){
            is KnowledgeData.Link->references+=CardTrashAffected(row.id,row.notebookId,row.revision,
                "$label\n${d.relation.label}：${endpoint(d.source)} → ${endpoint(d.target)}"+
                    (d.pinnedRevision?.let{" · 固定版本 $it（历史快照保留）"}?:""))
            is KnowledgeData.Question->questions+=CardTrashAffected(row.id,row.notebookId,row.revision,"$label\n${d.prompt} · ${d.state.label}")
            is KnowledgeData.Placement->occurrences+=CardTrashAffected(row.id,row.notebookId,row.revision,"$label · 知识板")
            is KnowledgeData.MapOccurrence->occurrences+=CardTrashAffected(row.id,row.notebookId,row.revision,"$label · 导图 ${d.mapId}")
            else->Unit
        }
    }
    main.filterNot{it.removed}.forEach{occurrences+=CardTrashAffected(it.id,book,it.revision,"${notebook(book)} · 主图")}
    CardTrashPreview(card,ContentTransfer.hash(bytes.toByteArray()),references,questions,occurrences)
}
