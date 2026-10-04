// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import org.inkweft.core.*

/** A single notebook projection shared by delivery, search and embedded rendering. */
class MapGraphAccess(private val db:NoteDatabase){
    fun observe(book:String)=db.invalidationTracker.createFlow("study_cards","study_nodes","knowledge_records","study_sources","study_card_source_sets","study_source_revisions").map{read(book)}
    suspend fun read(book:String)=db.withTransaction{project(book,db.study().cards(book),db.study().nodes(book),db.knowledge().forBook(book),db.study().sourceIds(book))}
    private fun project(book:String,cards:List<StudyCardRow>,main:List<StudyNodeRow>,records:List<KnowledgeRow>,sources:List<String>):List<MapScene>{
        val byCard=cards.filter{it.trashedAt==null}.associateBy{it.id};val sourced=sources.toSet()
        val maps=listOf<String?>(null)+records.filter{it.data() is KnowledgeData.MapDefinition}.map{it.id}
        return maps.map{mapId->
            val snapshot=graphSnapshot(MapRef(book,mapId),cards,main,records)
            val definition=snapshot.definition?.data() as? KnowledgeData.MapDefinition
            val structures=definition?.structures.orEmpty().associateBy{it.id}
            val available=snapshot.definition?.removed!=true
            val nodes=if(!available)emptyList()else snapshot.nodes.filterNot{it.removed}.mapNotNull{n->
                val structure=structures[n.id]
                if(structure!=null)MapSceneNode(n.id,n.parentId,null,structure.title,"",n.x,n.y,n.revision,n.revision)
                else byCard[n.cardId]?.let{c->MapSceneNode(n.id,n.parentId,c.id,c.title,c.body,n.x,n.y,n.revision,c.revision,if(c.id in sourced)"已保存原迹"else"无来源")}
            }
            MapScene(snapshot.ref,definition?.title?:"主图",nodes,snapshot.graphFingerprint,available)
        }
    }
}
