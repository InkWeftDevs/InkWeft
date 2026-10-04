// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.*
import java.util.UUID

/** Explicit independent-content copy; source pages remain references in this same notebook. */
class MapEmbedRepository(private val db:NoteDatabase){
    suspend fun duplicate(ref:MapRef,expectedSignature:String,operationId:String):MapRef=db.withTransaction{
        UUID.fromString(operationId)
        val digest=ContentTransfer.hash("duplicate-map-v1|$ref|$expectedSignature".toByteArray())
        db.knowledge().receipt(operationId)?.let{require(it.notebookId==ref.notebookId&&it.digest==digest);return@withTransaction MapRef(ref.notebookId,it.resultId)}
        require(db.knowledge().forBook(ref.notebookId).none{row->!row.removed&&(row.data() as? KnowledgeData.MapPortal)?.let{it.sourceMapId==ref.mapId}==true}){"MAP_PORTAL_COPY_UNSUPPORTED"}
        val scene=MapGraphAccess(db).read(ref.notebookId).firstOrNull{it.ref==ref&&it.available}?:error("MAP_UNAVAILABLE")
        require(scene.signature()==expectedSignature){"MAP_CONTENT_CHANGED"}
        val originals=scene.nodes.mapNotNull{it.cardId}.distinct()
        require(db.study().cards(ref.notebookId).size+originals.size<=StudyCapacity.MAX_CARDS_PER_NOTEBOOK){"STUDY_CARD_BUDGET"}
        fun fresh(value:String)=UUID.nameUUIDFromBytes("$operationId:$value".toByteArray()).toString()
        val newRef=MapRef(ref.notebookId,fresh("map"))
        val newMapId=checkNotNull(newRef.mapId)
        val ids=scene.nodes.associate{it.id to fresh("node:${it.id}")}
        val structure=scene.nodes.filter{it.cardId==null}.map{MapStructure(ids.getValue(it.id),it.parentId?.let(ids::get),it.title,it.x,it.y)}
        val knowledge=KnowledgeRepository(db)
        val layout=ref.mapId?.let{(db.knowledge().get(it)?.data() as? KnowledgeData.MapDefinition)?.layout}?:"right"
        val changes=mutableListOf(KnowledgeRow(newMapId,ref.notebookId,1,
            KnowledgeCodec.encode(KnowledgeData.MapDefinition((scene.title.take(112)+" · 独立副本").take(120),layout,structure))))
        val presentations=db.knowledge().forBook(ref.notebookId).filterNot{it.removed}.mapNotNull{it.data() as? KnowledgeData.CardPresentation}.associateBy{it.cardId}
        val cardIds=scene.nodes.mapNotNull{it.cardId}.distinct().associateWith{fresh("card:$it")}
        cardIds.forEach{(old,id)->
            val card=checkNotNull(db.study().card(old));require(card.trashedAt==null)
            db.study().addCard(card.copy(id=id,revision=1));db.study().revision(StudyCardRevisionRow(id,1,card.title,card.body,null))
            val sources=StudySourceVersions(db);sources.freezeCurrent(card);val frozen=sources.read(old);sources.writeSet(id,1,frozen.refs,frozen.complete)
            presentations[old]?.let{presentation->
                val row=KnowledgeRow(fresh("presentation:$old"),ref.notebookId,1,KnowledgeCodec.encode(presentation.copy(cardId=id)))
                db.knowledge().insert(row);db.knowledge().revision(KnowledgeRevisionRow(row.id,1,row.notebookId,row.payload,false))
            }
        }
        scene.nodes.forEach{n->n.cardId?.let{card->changes+=KnowledgeRow(ids.getValue(n.id),ref.notebookId,1,
            KnowledgeCodec.encode(KnowledgeData.MapOccurrence(newMapId,cardIds.getValue(card),n.parentId?.let(ids::get),n.x,n.y)))}}
        knowledge.commitGraph(ref.notebookId,knowledge.graphChanges(ref.notebookId,changes,
            mapOf<String?,List<String>>(newMapId to scene.nodes.map{ids.getValue(it.id)})))
        db.knowledge().receipt(KnowledgeReceiptRow(operationId,ref.notebookId,digest,newMapId))
        db.notes().touch(ref.notebookId,System.currentTimeMillis());newRef
    }
    suspend fun validateReferences(book:String,objects:List<PageObject>){
        objects.mapNotNull{it.mapEmbed}.filter{it.policy==MapEmbedPolicy.LIVE}.forEach{embed->
            require(embed.target.notebookId==book){"MAP_EMBED_CROSS_BOOK"}
            require(db.notes().note(book)!=null){"MAP_EMBED_BOOK_MISSING"}
            embed.target.mapId?.let{id->val map=db.knowledge().get(id);require(map!=null&&map.notebookId==book&&map.data() is KnowledgeData.MapDefinition){"MAP_EMBED_CLOSURE_MISSING"}}
        }
    }
}
