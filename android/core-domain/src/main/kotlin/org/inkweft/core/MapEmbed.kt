// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.UUID

enum class MapEmbedPolicy { LIVE, PINNED }
/** A page occurrence references a map; deleting it never means deleting the map. */
data class MapEmbed(val target:MapRef,val branchId:String?=null,val depth:Int=32,
    val policy:MapEmbedPolicy=MapEmbedPolicy.LIVE,val snapshot:MapScene?=null){
    init{branchId?.let(UUID::fromString);require(depth in 0..32);require((policy==MapEmbedPolicy.PINNED)==(snapshot!=null));require(snapshot==null||snapshot.ref==target)}
    fun resolve(scenes:List<MapScene>):MapScene?=(snapshot?:scenes.find{it.ref==target})?.branch(branchId,depth)
    fun cacheKey(scene:MapScene?,width:Int,height:Int)=ContentTransfer.hash("embed-renderer-1|${target}|$branchId|$depth|$policy|${scene?.signature()}|$width|$height".toByteArray())
}
object MapEmbedCodec {
    const val MAX_BYTES=640_000
    fun encode(value:MapEmbed):ByteArray{
        val b=ByteArrayOutputStream();DataOutputStream(b).use{d->
            d.writeInt(0x49574d31);d.writeUTF(value.target.notebookId);d.writeUTF(value.target.mapId.orEmpty());d.writeUTF(value.branchId.orEmpty());d.writeInt(value.depth);d.writeByte(value.policy.ordinal)
            value.snapshot?.let{s->
                require(s.nodes.size<=StudyGraph.MAX_NODES&&s.title.length<=120&&s.available)
                require(s.graphHash.matches(Regex("[0-9a-f]{64}")))
                StudyGraph.validate(s.nodes.map{StudyNode(it.id,it.cardId?:it.id,it.parentId,it.x,it.y,it.revision)})
                d.writeUTF(s.title);d.writeUTF(s.graphHash);d.writeInt(s.nodes.size)
                s.nodes.forEach{n->
                    listOfNotNull(n.id,n.parentId,n.cardId).forEach(UUID::fromString)
                    require(n.title.length<=120&&n.body.length<=20_000&&n.sourceState.length<=80)
                    require(n.x.isFinite()&&n.y.isFinite()&&kotlin.math.abs(n.x)<=40000&&kotlin.math.abs(n.y)<=40000&&n.revision>0&&n.contentRevision>0)
                    d.writeUTF(n.id);d.writeUTF(n.parentId.orEmpty());d.writeUTF(n.cardId.orEmpty());d.writeUTF(n.title)
                    val text=n.body.toByteArray(Charsets.UTF_8);d.writeInt(text.size);d.write(text);d.writeDouble(n.x);d.writeDouble(n.y);d.writeLong(n.revision);d.writeLong(n.contentRevision);d.writeUTF(n.sourceState)
                    require(b.size()<=MAX_BYTES)
                }
                require(s.layout in MapLayouts.supported&&s.summaryGroups.size<=MapSummaries.MAX_GROUPS)
                if(s.summaryGroups.isNotEmpty()){
                    val models=s.nodes.map{StudyNode(it.id,it.cardId?:it.id,it.parentId,it.x,it.y,it.revision)}
                    MapSummaries.validate(models,StudyOrganization.canonicalOrder(models,models.map{it.id}),s.summaryGroups)
                }
                if(s.layout!="right"||s.summaryGroups.isNotEmpty()){
                    d.writeUTF("MAP_SCENE_V2");d.writeUTF(s.layout);d.writeInt(s.summaryGroups.size)
                    s.summaryGroups.forEach{group->require(group.mapId==s.ref.mapId&&group.memberIds.all{id->s.nodes.any{it.id==id}});val payload=KnowledgeCodec.encode(group);d.writeInt(payload.size);d.write(payload)}
                }
            }
        };return b.toByteArray().also{require(it.size<=MAX_BYTES)}
    }
    fun decode(bytes:ByteArray):MapEmbed{
        require(bytes.size in 8..MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            require(d.readInt()==0x49574d31)
            val ref=MapRef(d.readUTF(),d.readUTF().ifEmpty{null});val branch=d.readUTF().ifEmpty{null};val depth=d.readInt();val policy=MapEmbedPolicy.entries.getOrNull(d.readUnsignedByte())?:error("Embed policy")
            val scene=if(policy==MapEmbedPolicy.PINNED){
                val title=d.readUTF();val graph=d.readUTF();val count=d.readInt();require(count in 0..StudyGraph.MAX_NODES)
                val nodes=List(count){
                    val id=d.readUTF();val parent=d.readUTF().ifEmpty{null};val card=d.readUTF().ifEmpty{null};val name=d.readUTF()
                    val size=d.readInt();require(size in 0..80_000&&size<=d.available());val text=ByteArray(size);d.readFully(text);val body=text.toString(Charsets.UTF_8);require(body.toByteArray(Charsets.UTF_8).contentEquals(text))
                    MapSceneNode(id,parent,card,name,body,d.readDouble(),d.readDouble(),d.readLong(),d.readLong(),d.readUTF())
                };require(nodes.map{it.id}.distinct().size==nodes.size)
                val layout:String;val groups:List<KnowledgeData.MapSummaryGroup>
                if(d.available()>0){require(d.readUTF()=="MAP_SCENE_V2");layout=d.readUTF();val groupsCount=d.readInt();require(groupsCount in 0..MapSummaries.MAX_GROUPS)
                    groups=List(groupsCount){val size=d.readInt();require(size in 1..KnowledgeCodec.MAX_BYTES&&size<=d.available());val payload=ByteArray(size);d.readFully(payload);KnowledgeCodec.decode(payload) as KnowledgeData.MapSummaryGroup}
                }else{layout="right";groups=emptyList()}
                MapScene(ref,title,nodes,graph,layout=layout,summaryGroups=groups)
            }else null
            require(d.available()==0);MapEmbed(ref,branch,depth,policy,scene).also{encode(it)}
        }
    }
}
