// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.*
import java.util.UUID

data class MapPortalPreview(
    val source:MapRef,
    val target:MapRef,
    val nodeId:String,
    val sourceTitle:String,
    val targetTitle:String,
    val bookTitle:String,
    val canOpen:Boolean,
    val sourceMapTitle:String,
    val targetBranchId:String?=null,
    val targetBranchTitle:String?=null,
)

/** Portals retain exact author identities. Reads never redirect to another occurrence or write receipts. */
class MapPortalRepository(private val db:NoteDatabase){
    suspend fun preview(book:String,id:String,capturedRevision:Long):MapPortalPreview=db.withTransaction{
        UUID.fromString(book);UUID.fromString(id);require(capturedRevision>0)
        val row=db.knowledge().get(id)?:throw KnowledgeRejected(KnowledgeRejection.UNAVAILABLE)
        if(row.notebookId!=book)throw KnowledgeRejected(KnowledgeRejection.INVALID)
        if(row.revision!=capturedRevision)throw KnowledgeRejected(KnowledgeRejection.CONFLICT)
        if(row.removed)throw KnowledgeRejected(KnowledgeRejection.UNAVAILABLE)
        val portal=row.data() as? KnowledgeData.MapPortal?:throw KnowledgeRejected(KnowledgeRejection.INVALID)
        val note=db.notes().note(book)
        val source=MapRef(book,portal.sourceMapId);val target=MapRef(book,portal.targetMapId)
        val scenes=MapGraphAccess(db).read(book)
        val sourceScene=scenes.find{it.ref==source};val targetScene=scenes.find{it.ref==target}
        val node=sourceScene?.nodes?.find{it.id==portal.sourceNodeId}
        val branch=targetScene?.nodes?.find{it.id==portal.targetBranchId}
        val activeBook=note!=null&&db.workspace().get(book)?.trashedAt==null
        MapPortalPreview(source,target,portal.sourceNodeId,node?.title?:"源节点已移除或不可用",
            targetScene?.title?:"目标图已回收或不可用",note?.title?:"笔记已回收或不可用",
            activeBook&&sourceScene?.available==true&&node!=null&&targetScene?.available==true&&(portal.targetBranchId==null||branch!=null),
            sourceScene?.title?:"源图已回收或不可用",portal.targetBranchId,
            portal.targetBranchId?.let{branch?.title?:"目标分支已移除或不可用"})
    }

    /** Active writes require live endpoints. Archives and deletion require provable historical ownership. */
    internal suspend fun validateData(book:String,portal:KnowledgeData.MapPortal,active:Boolean){
        require(db.notes().note(book)!=null)
        suspend fun map(id:String?):KnowledgeRow? {
            if(id==null)return null
            val row=requireNotNull(db.knowledge().get(id))
            require(row.notebookId==book&&row.data() is KnowledgeData.MapDefinition)
            return row
        }
        val sourceMap=map(portal.sourceMapId);val targetMap=map(portal.targetMapId)
        if(active){
            require(sourceMap?.removed!=true){"MAP_PORTAL_SOURCE_UNAVAILABLE"}
            require(targetMap?.removed!=true){"MAP_PORTAL_TARGET_UNAVAILABLE"}
            val scenes=MapGraphAccess(db).read(book)
            val source=scenes.find{it.ref==MapRef(book,portal.sourceMapId)}
            require(source?.available==true&&source.nodes.any{it.id==portal.sourceNodeId}){"MAP_PORTAL_SOURCE_UNAVAILABLE"}
            val target=scenes.find{it.ref==MapRef(book,portal.targetMapId)}
            require(target?.available==true&&(portal.targetBranchId==null||target.nodes.any{it.id==portal.targetBranchId})){"MAP_PORTAL_TARGET_UNAVAILABLE"}
            return
        }
        suspend fun ownsNode(mapRow:KnowledgeRow?,nodeId:String){
            if(mapRow==null){
                val node=requireNotNull(db.study().node(nodeId))
                require(node.notebookId==book&&db.study().card(node.cardId)?.notebookId==book)
                return
            }
            fun containsStructure(payload:ByteArray)=
                (KnowledgeCodec.decode(payload) as? KnowledgeData.MapDefinition)?.structures?.any{it.id==nodeId}==true
            if(containsStructure(mapRow.payload)||db.knowledge().revisions(mapRow.id).any{it.notebookId==book&&containsStructure(it.payload)})return
            val node=requireNotNull(db.knowledge().get(nodeId))
            require(node.notebookId==book&&node.data() is KnowledgeData.MapOccurrence)
            suspend fun ownsOccurrence(payload:ByteArray):Boolean {
                val occurrence=KnowledgeCodec.decode(payload) as? KnowledgeData.MapOccurrence?:return false
                return occurrence.mapId==mapRow.id&&db.study().card(occurrence.cardId)?.notebookId==book
            }
            require(ownsOccurrence(node.payload)||db.knowledge().revisions(node.id).any{it.notebookId==book&&ownsOccurrence(it.payload)})
        }
        ownsNode(sourceMap,portal.sourceNodeId)
        portal.targetBranchId?.let{ownsNode(targetMap,it)}
    }
}
