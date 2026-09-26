// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.UUID

enum class TargetKind { NOTE, PAGE, CARD, ANCHOR }
data class TargetRef(val kind:TargetKind,val id:String){init{UUID.fromString(id)}}
enum class RelationKind(val label:String){REFERENCE("内容引用"),PREREQUISITE("前置知识"),CONTRAST("对照"),DERIVATION("推导"),APPLICATION("应用")}
enum class ManualState(val label:String){INBOX("待整理"),REVIEW("待复习"),UNDERSTOOD("已理解")}

sealed interface KnowledgeData {
    data class Anchor(val pageId:String,val inkRevision:Long,val bounds:CanvasBounds,val strokeIds:List<String>):KnowledgeData
    data class Link(val source:TargetRef,val target:TargetRef,val relation:RelationKind=RelationKind.REFERENCE,val pinnedRevision:Long?=null):KnowledgeData
    data class Properties(val cardId:String,val state:ManualState=ManualState.INBOX,val tags:List<String> = emptyList()):KnowledgeData
    data class Collection(val title:String,val tag:String="",val state:ManualState?=null,val matchAny:Boolean=false):KnowledgeData
    data class Question(val cardId:String,val prompt:String,val state:ManualState=ManualState.REVIEW):KnowledgeData
    data class Placement(val cardId:String,val x:Double,val y:Double):KnowledgeData
    data class Alias(val cardId:String,val name:String):KnowledgeData
    data class MapDefinition(val title:String):KnowledgeData
    data class MapOccurrence(val mapId:String,val cardId:String,val parentId:String?,val x:Double,val y:Double):KnowledgeData
    data class Decoration(val from:String,val to:String,val label:String="装饰线"):KnowledgeData
}

/** Closed declarative format: no executable payload or arbitrary property names. */
object KnowledgeCodec {
    const val MAX_BYTES=32768
    fun validate(v:KnowledgeData){
        fun id(s:String){UUID.fromString(s)}
        when(v){
            is KnowledgeData.Anchor->{id(v.pageId);require(v.inkRevision>=0);require(v.strokeIds.size in 1..256&&v.strokeIds.distinct().size==v.strokeIds.size);v.strokeIds.forEach(::id)
                require(v.bounds.left>=-BoardLimits.WORLD&&v.bounds.right<=BoardLimits.WORLD&&v.bounds.top>=-BoardLimits.WORLD&&v.bounds.bottom<=BoardLimits.WORLD)}
            is KnowledgeData.Link->{require(v.pinnedRevision==null||v.target.kind==TargetKind.CARD&&v.pinnedRevision>0);require(v.source!=v.target)}
            is KnowledgeData.Properties->{id(v.cardId);require(v.tags.size<=12&&v.tags.distinct().size==v.tags.size&&v.tags.all{it.isNotBlank()&&it.length<=24&&!it.contains('\n')})}
            is KnowledgeData.Collection->{require(v.title.isNotBlank()&&v.title.length<=120&&v.tag.length<=24)}
            is KnowledgeData.Question->{id(v.cardId);require(v.prompt.isNotBlank()&&v.prompt.length<=2000)}
            is KnowledgeData.Placement->{id(v.cardId);require(v.x.isFinite()&&v.y.isFinite()&&v.x in -40000.0..40000.0&&v.y in -40000.0..40000.0)}
            is KnowledgeData.Alias->{id(v.cardId);require(v.name.isNotBlank()&&v.name.length<=120)}
            is KnowledgeData.MapDefinition->require(v.title.isNotBlank()&&v.title.length<=120)
            is KnowledgeData.MapOccurrence->{id(v.mapId);id(v.cardId);v.parentId?.let(::id);require(v.x.isFinite()&&v.y.isFinite()&&v.x in -40000.0..40000.0&&v.y in -40000.0..40000.0)}
            is KnowledgeData.Decoration->{id(v.from);id(v.to);require(v.from!=v.to&&v.label.length<=120)}
        }
    }
    fun encode(v:KnowledgeData):ByteArray {
        validate(v);val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use{d->
            d.writeInt(0x49574b31)
            fun ref(r:TargetRef){d.writeUTF(r.kind.name);d.writeUTF(r.id)}
            when(v){
                is KnowledgeData.Anchor->{d.writeUTF("ANCHOR");d.writeUTF(v.pageId);d.writeLong(v.inkRevision);listOf(v.bounds.left,v.bounds.top,v.bounds.right,v.bounds.bottom).forEach(d::writeDouble);d.writeInt(v.strokeIds.size);v.strokeIds.forEach(d::writeUTF)}
                is KnowledgeData.Link->{d.writeUTF("LINK");ref(v.source);ref(v.target);d.writeUTF(v.relation.name);d.writeLong(v.pinnedRevision?:0)}
                is KnowledgeData.Properties->{d.writeUTF("PROPERTIES");d.writeUTF(v.cardId);d.writeUTF(v.state.name);d.writeInt(v.tags.size);v.tags.forEach(d::writeUTF)}
                is KnowledgeData.Collection->{d.writeUTF("COLLECTION");d.writeUTF(v.title);d.writeUTF(v.tag);d.writeUTF(v.state?.name.orEmpty());d.writeBoolean(v.matchAny)}
                is KnowledgeData.Question->{d.writeUTF("QUESTION");d.writeUTF(v.cardId);d.writeUTF(v.prompt);d.writeUTF(v.state.name)}
                is KnowledgeData.Placement->{d.writeUTF("PLACEMENT");d.writeUTF(v.cardId);d.writeDouble(v.x);d.writeDouble(v.y)}
                is KnowledgeData.Alias->{d.writeUTF("ALIAS");d.writeUTF(v.cardId);d.writeUTF(v.name)}
                is KnowledgeData.MapDefinition->{d.writeUTF("MAP");d.writeUTF(v.title)}
                is KnowledgeData.MapOccurrence->{d.writeUTF("MAP_NODE");d.writeUTF(v.mapId);d.writeUTF(v.cardId);d.writeUTF(v.parentId.orEmpty());d.writeDouble(v.x);d.writeDouble(v.y)}
                is KnowledgeData.Decoration->{d.writeUTF("DECORATION");d.writeUTF(v.from);d.writeUTF(v.to);d.writeUTF(v.label)}
            }
        };return bytes.toByteArray().also{require(it.size<=MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):KnowledgeData {
        require(bytes.size<=MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            require(d.readInt()==0x49574b31)
            fun ref()=TargetRef(TargetKind.valueOf(d.readUTF()),d.readUTF())
            fun list(max:Int):List<String>{val n=d.readInt();require(n in 0..max);return List(n){d.readUTF()}}
            val value=when(d.readUTF()){
                "ANCHOR"->KnowledgeData.Anchor(d.readUTF(),d.readLong(),CanvasBounds(d.readDouble(),d.readDouble(),d.readDouble(),d.readDouble()),list(256))
                "LINK"->KnowledgeData.Link(ref(),ref(),RelationKind.valueOf(d.readUTF()),d.readLong().let{require(it>=0);if(it==0L)null else it})
                "PROPERTIES"->KnowledgeData.Properties(d.readUTF(),ManualState.valueOf(d.readUTF()),list(12))
                "COLLECTION"->KnowledgeData.Collection(d.readUTF(),d.readUTF(),d.readUTF().ifEmpty{null}?.let(ManualState::valueOf),d.readBoolean())
                "QUESTION"->KnowledgeData.Question(d.readUTF(),d.readUTF(),ManualState.valueOf(d.readUTF()))
                "PLACEMENT"->KnowledgeData.Placement(d.readUTF(),d.readDouble(),d.readDouble())
                "ALIAS"->KnowledgeData.Alias(d.readUTF(),d.readUTF())
                "MAP"->KnowledgeData.MapDefinition(d.readUTF())
                "MAP_NODE"->KnowledgeData.MapOccurrence(d.readUTF(),d.readUTF(),d.readUTF().ifEmpty{null},d.readDouble(),d.readDouble())
                "DECORATION"->KnowledgeData.Decoration(d.readUTF(),d.readUTF(),d.readUTF())
                else->error("UNKNOWN_KNOWLEDGE_KIND")
            };require(d.read()==-1);validate(value);value
        }
    }
}

class KnowledgeCommand(val operationId:String,val notebookId:String,val id:String,val expectedRevision:Long,value:KnowledgeData,val removed:Boolean=false){
    private val frozen=KnowledgeCodec.encode(value)
    val data get()=KnowledgeCodec.decode(frozen)
    val payload get()=frozen.copyOf()
    init{listOf(operationId,notebookId,id).forEach{UUID.fromString(it)};require(expectedRevision in 0 until Long.MAX_VALUE)}
    fun digest():String {val out=ByteArrayOutputStream();DataOutputStream(out).use{d->listOf("knowledge.v1",operationId,notebookId,id).forEach(d::writeUTF);d.writeLong(expectedRevision);d.writeBoolean(removed);d.writeInt(frozen.size);d.write(frozen)};return ContentTransfer.hash(out.toByteArray())}
}

object KnowledgeQueries {
    fun matches(c:KnowledgeData.Collection,p:KnowledgeData.Properties):Boolean{
        val checks=buildList{if(c.tag.isNotEmpty())add(c.tag in p.tags);c.state?.let{add(p.state==it)}}
        return checks.isEmpty()||if(c.matchAny)checks.any{it}else checks.all{it}
    }
    data class LocalGraph(val nodes:Set<TargetRef>,val edges:List<KnowledgeData.Link>,val truncated:Boolean,val hops:Int)
    fun graph(focus:TargetRef,links:List<KnowledgeData.Link>,hops:Int=1):LocalGraph{
        require(hops in 1..2);val nodes=linkedSetOf(focus)
        repeat(hops){val previous=nodes.toSet();links.forEach{if(it.source in previous||it.target in previous){nodes+=it.source;nodes+=it.target}}}
        val kept=nodes.take(100).toSet();val edges=links.filter{it.source in kept&&it.target in kept}
        return LocalGraph(kept,edges.take(200),nodes.size>100||edges.size>200,hops)
    }
    /** Used by renderers to stop recursive embedding; cycles never create new author edges. */
    fun canExpand(target:TargetRef,path:List<TargetRef>,rendered:Int)=target !in path&&path.size<4&&rendered<100
}
