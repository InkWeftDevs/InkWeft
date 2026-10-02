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
        require(db.study().cards(ref.notebookId).size+originals.size<=200){"STUDY_CARD_BUDGET"}
        val used=db.openHelper.writableDatabase.query("SELECT COALESCE(SUM(length(snapshot)),0) FROM study_sources").use{it.moveToFirst();it.getLong(0)}
        var copiedBytes=0L
        originals.forEach{copiedBytes+=db.study().source(it)?.snapshot?.size?:0}
        require(used+copiedBytes<=32_000_000){"STUDY_SNAPSHOT_BUDGET"}
        fun fresh(value:String)=UUID.nameUUIDFromBytes("$operationId:$value".toByteArray()).toString()
        val newRef=MapRef(ref.notebookId,fresh("map"))
        val newMapId=checkNotNull(newRef.mapId)
        val ids=scene.nodes.associate{it.id to fresh("node:${it.id}")}
        val structure=scene.nodes.filter{it.cardId==null}.map{MapStructure(ids.getValue(it.id),it.parentId?.let(ids::get),it.title,it.x,it.y)}
        val knowledge=KnowledgeRepository(db)
        knowledge.submit(KnowledgeCommand(fresh("create-map"),ref.notebookId,newRef.mapId!!,0,KnowledgeData.MapDefinition((scene.title.take(112)+" · 独立副本").take(120),structures=structure)))
        val cardIds=scene.nodes.mapNotNull{it.cardId}.distinct().associateWith{fresh("card:$it")}
        cardIds.forEach{(old,id)->
            val card=checkNotNull(db.study().card(old));require(card.trashedAt==null)
            db.study().addCard(card.copy(id=id,revision=1));db.study().revision(StudyCardRevisionRow(id,1,card.title,card.body,null))
            db.study().source(old)?.let{db.study().source(it.copy(cardId=id))}
        }
        // Parents are already present before their children are inserted.
        val rows=StudyOutline.project(scene.nodes.map{StudyNode(it.id,it.cardId?:it.id,it.parentId,it.x,it.y,it.revision)},emptySet(),null).rows
        rows.forEach{row->val n=scene.nodes.first{it.id==row.node.id};n.cardId?.let{card->
            knowledge.submit(KnowledgeCommand(fresh("place:${n.id}"),ref.notebookId,ids.getValue(n.id),0,KnowledgeData.MapOccurrence(newMapId,cardIds.getValue(card),n.parentId?.let(ids::get),n.x,n.y)))
        }}
        db.knowledge().receipt(KnowledgeReceiptRow(operationId,ref.notebookId,digest,newMapId));newRef
    }
    suspend fun validateReferences(book:String,objects:List<PageObject>){
        objects.mapNotNull{it.mapEmbed}.filter{it.policy==MapEmbedPolicy.LIVE}.forEach{embed->
            require(embed.target.notebookId==book){"MAP_EMBED_CROSS_BOOK"}
            require(db.notes().note(book)!=null){"MAP_EMBED_BOOK_MISSING"}
            embed.target.mapId?.let{id->val map=db.knowledge().get(id);require(map!=null&&map.notebookId==book&&map.data() is KnowledgeData.MapDefinition){"MAP_EMBED_CLOSURE_MISSING"}}
        }
    }
}
