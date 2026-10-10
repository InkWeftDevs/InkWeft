// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** Null mapId denotes this notebook's main map, never an implicit current tab. */
data class MapRef(val notebookId:String,val mapId:String?=null){
    init{UUID.fromString(notebookId);mapId?.let(UUID::fromString)}
    val key:String get()=mapId?:"main"
}
data class CaptureDraft(val notebookId:String,val source:StudySourceDraft,val text:String=""){
    val book:String get()=notebookId
    init{UUID.fromString(notebookId);require(text.length<=20_000)}
    fun command(target:MapRef,parentId:String?,expectedGraph:String,x:Double=40.0,y:Double=80.0,
        operationId:String=UUID.randomUUID().toString(),cardId:String=UUID.randomUUID().toString(),nodeId:String=UUID.randomUUID().toString()):StudyCommand{
        require(target.notebookId==notebookId)
        require(expectedGraph.matches(Regex("[0-9a-f]{64}")))
        return StudyCommand(operationId,notebookId,StudyAction.CREATE,cardId=cardId,nodeId=nodeId,parentId=parentId,
            title=text.lineSequence().firstOrNull{it.isNotBlank()}?.take(48)?:"摘录",body=text,x=x,y=y,source=source,expectedGraph=expectedGraph,mapId=target.mapId)
    }
}

/** Shared immutable display projection. No Room row, view or editor is stored in a scene. */
data class MapSceneNode(val id:String,val parentId:String?,val cardId:String?,val title:String,val body:String,
    val x:Double,val y:Double,val revision:Long,val contentRevision:Long,val sourceState:String="无来源")
data class MapScene(val ref:MapRef,val title:String,val nodes:List<MapSceneNode>,val graphHash:String,val available:Boolean=true,val layout:String="right",val summaryGroups:List<KnowledgeData.MapSummaryGroup> = emptyList()){
    fun branch(id:String?,depth:Int=32):MapScene{
        require(depth in 0..32)
        if(id==null)return this
        val byId=nodes.associateBy{it.id};val selected=nodes.filter{n->
            var current:MapSceneNode?=n;var steps=0;val seen=mutableSetOf<String>()
            while(current!=null&&steps<=depth&&seen.add(current.id)){if(current.id==id)return@filter true;current=byId[current.parentId];steps++};false
        }
        val ids=selected.map{it.id}.toSet()
        return copy(nodes=selected,available=available&&selected.isNotEmpty(),summaryGroups=summaryGroups.filter{g->g.memberIds.all{it in ids}&&byId[g.memberIds.first()]?.parentId in ids})
    }
    fun signature():String=contentSignature
    private val contentSignature:String by lazy { ContentTransfer.hash(buildString{
        append("scene-v1|").append(ref).append('|').append(title).append('|').append(available).append('|').append(graphHash)
        nodes.sortedBy{it.id}.forEach{append('|').append(it)}
        if(layout!="right"||summaryGroups.isNotEmpty()){append("|layout:").append(layout);summaryGroups.forEach{append("|summary:").append(it)}}
    }.toByteArray()) }
}
data class MapSearchHit(val ref:MapRef,val nodeId:String,val cardId:String?,val mapTitle:String,val title:String,
    val branchPath:List<String>,val matchedField:String,val snippet:String,val sourceState:String,val rank:Int)
object MapSearch {
    fun find(scenes:List<MapScene>,query:String,current:MapRef,allMaps:Boolean,annotations:Map<String,String> = emptyMap()):List<MapSearchHit>{
        val q=query.trim();if(q.isEmpty())return emptyList()
        return scenes.filter{it.available&&(allMaps||it.ref==current)}.flatMap{s->
            val byId=s.nodes.associateBy{it.id}
            s.nodes.mapNotNull{n->
                val inTitle=n.title.contains(q,true);val inBody=n.body.contains(q,true)
                val annotation=annotations[n.cardId].orEmpty()
                val matchedText=if(inBody)n.body else annotation
                val index=matchedText.indexOf(q,ignoreCase=true)
                if(!inTitle&&index<0)return@mapNotNull null
                val path=mutableListOf<String>();val seen=mutableSetOf(n.id);var parent=byId[n.parentId]
                while(parent!=null&&seen.add(parent.id)){path.add(0,parent.title);parent=byId[parent.parentId]}
                MapSearchHit(s.ref,n.id,n.cardId,s.title,n.title,path,if(inTitle)"标题"else if(inBody)"正文"else"个人注释",
                    if(inTitle)n.title else matchedText.substring((index-24).coerceAtLeast(0),(index+q.length+64).coerceAtMost(matchedText.length)),n.sourceState,
                    (if(s.ref==current)0 else 10)+(if(n.title.equals(q,true))0 else if(inTitle)1 else 2))
            }
        }.sortedWith(compareBy<MapSearchHit>{it.rank}.thenBy{it.mapTitle}.thenBy{it.title}.thenBy{it.nodeId})
    }
}
