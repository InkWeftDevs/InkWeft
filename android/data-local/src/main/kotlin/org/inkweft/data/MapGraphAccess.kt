// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.combine
import org.inkweft.core.*

/** A single notebook projection shared by delivery, search and embedded rendering. */
class MapGraphAccess(private val db:NoteDatabase){
    fun observe(book:String)=combine(db.study().observeCards(book),db.study().observeNodes(book),
        db.knowledge().observeBook(book),db.study().observeSourceIds(book)){cards,main,records,sources->project(book,cards,main,records,sources)}
    suspend fun read(book:String)=db.withTransaction{project(book,db.study().cards(book),db.study().nodes(book),db.knowledge().forBook(book),db.study().sourceIds(book))}
    private fun project(book:String,cards:List<StudyCardRow>,main:List<StudyNodeRow>,records:List<KnowledgeRow>,sources:List<String>):List<MapScene>{
        val byCard=cards.filter{it.trashedAt==null}.associateBy{it.id};val sourced=sources.toSet()
        val decoded=records.associate{it.id to it.data()}
        fun content(n:StudyNodeRow):MapSceneNode?=byCard[n.cardId]?.takeIf{!n.removed}?.let{c->MapSceneNode(n.id,n.parentId,c.id,c.title,c.body,n.x,n.y,n.revision,c.revision,if(c.id in sourced)"已保存原迹"else"无来源")}
        val mainScene=MapScene(MapRef(book),"主图",main.mapNotNull(::content),StudyGraph.orderHash(main.map{it.model()}))
        return listOf(mainScene)+records.filter{decoded[it.id] is KnowledgeData.MapDefinition}.map{r->
            val definition=decoded.getValue(r.id) as KnowledgeData.MapDefinition
            val structures=definition.structures.map{MapSceneNode(it.id,it.parentId,null,it.title,"",it.x,it.y,r.revision,r.revision)}
            val occurrences=records.mapNotNull{record->(decoded[record.id] as? KnowledgeData.MapOccurrence)?.takeIf{it.mapId==r.id}?.let{StudyNodeRow(record.id,book,it.cardId,it.parentId,it.x,it.y,record.revision,record.removed)}}
            val graph=structures.map{StudyNode(it.id,it.id,it.parentId,it.x,it.y,it.revision)}+occurrences.map{it.model()}
            MapScene(MapRef(book,r.id),definition.title,if(r.removed)emptyList()else structures+occurrences.mapNotNull(::content),StudyGraph.orderHash(graph),!r.removed)
        }
    }
}
