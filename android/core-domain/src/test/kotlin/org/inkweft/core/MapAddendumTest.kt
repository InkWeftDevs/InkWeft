package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MapAddendumTest {
 private fun id()=UUID.randomUUID().toString()
 private fun scene():MapScene {val root=id();return MapScene(MapRef(id()),"复习图",listOf(MapSceneNode(root,null,null,"条件概率","",40.0,80.0,1,1),MapSceneNode(id(),root,id(),"例题","不放回取球的条件概率",300.0,80.0,1,1)),"a".repeat(64))}
 @Test fun explicitTargetAndTextAreIdenticalForThreeDeliveryPaths(){
  val book=id();val source=StudySourceDraft(book,1,CanvasBounds(0.0,0.0,100.0,100.0),listOf(id()));val draft=CaptureDraft(book,source,"已确认摘录\nP(A|B)")
  val target=MapRef(book,id());val operation=id();val card=id();val node=id();val branch=id()
  val commands=List(3){draft.command(target,branch,"b".repeat(64),operationId=operation,cardId=card,nodeId=node)}
  assertEquals(1,commands.map{it.digest()}.distinct().size);assertEquals(target.mapId,commands[0].mapId);assertEquals(draft.text,commands[0].body);assertSame(source,commands[0].source)
  try{draft.command(MapRef(id()),null,"b".repeat(64));fail()}catch(_:IllegalArgumentException){}
 }
 @Test fun searchRetainsEachOccurrenceAndStructureAndSeparatesScope(){
  val a=scene();val shared=a.nodes.last();val duplicate=shared.copy(id=id(),parentId=null)
  val b=a.copy(ref=MapRef(a.ref.notebookId,id()),title="另一图",nodes=listOf(duplicate))
  assertEquals(2,MapSearch.find(listOf(a,b),"条件概率",a.ref,false).size)
  val all=MapSearch.find(listOf(a,b),"条件概率",a.ref,true)
  assertEquals(3,all.size);assertTrue(all.any{it.cardId==null});assertEquals(2,all.count{it.cardId==shared.cardId});assertEquals(listOf("条件概率"),all.first{it.nodeId==shared.id}.branchPath)
  assertEquals(2,MapSearch.find(listOf(a,b.copy(available=false)),"条件概率",a.ref,true).size)
  assertTrue(MapSearch.find(listOf(a),"复习图",a.ref,false).isEmpty())
 }
 @Test fun contentOnlyRevisionInvalidatesLiveCacheButNotPinned(){
  val s=scene();val live=MapEmbed(s.ref);val fixed=MapEmbed(s.ref,policy=MapEmbedPolicy.PINNED,snapshot=s)
  val edited=s.copy(nodes=s.nodes.map{if(it.cardId==null)it else it.copy(body="新结论",contentRevision=2)})
  assertEquals(s.graphHash,edited.graphHash);assertNotEquals(live.cacheKey(s,600,380),live.cacheKey(edited,600,380));assertNotEquals(live.cacheKey(s,600,380),live.cacheKey(s,900,380))
  assertEquals("不放回取球的条件概率",fixed.resolve(listOf(edited))!!.nodes.last().body);assertEquals("新结论",live.resolve(listOf(edited))!!.nodes.last().body)
 }
 @Test fun mapObjectWireRoundTripsLiveAndCompleteSnapshot(){
  val s=scene();val objects=listOf(MapEmbed(s.ref),MapEmbed(s.ref,s.nodes.last().id,policy=MapEmbedPolicy.PINNED,snapshot=s)).map{PageObject(id(),PageObjectKind.MAP,30f,50f,600f,380f,mapEmbed=it)}
  assertEquals(objects,PageObjectCodec.decode(PageObjectCodec.encode(objects)))
  assertEquals(1,objects.last().mapEmbed!!.resolve(emptyList())!!.nodes.size)
  try{InkPageFile("实时","",emptyList(),false,PaperStyle.BLANK,objects).encode();fail()}catch(e:IllegalArgumentException){assertEquals("LIVE_MAP_REQUIRES_FULL_BACKUP_OR_SNAPSHOT",e.message)}
  val pinned=InkPageFile("快照","",emptyList(),false,PaperStyle.BLANK,listOf(objects.last()))
  assertEquals(objects.last(),InkPageFile.decode(pinned.encode()).objects.single())
 }
 @Test fun invalidSnapshotGraphAndMissingBranchDoNotPretendToBeLive(){
  val s=scene();assertFalse(MapEmbed(s.ref,id()).resolve(listOf(s))!!.available)
  val cycle=s.copy(nodes=s.nodes.map{it.copy(parentId=it.id)})
  try{MapEmbedCodec.encode(MapEmbed(s.ref,policy=MapEmbedPolicy.PINNED,snapshot=cycle));fail()}catch(_:IllegalArgumentException){}
  val wire=MapEmbedCodec.encode(MapEmbed(s.ref));try{MapEmbedCodec.decode(wire+byteArrayOf(0));fail()}catch(_:IllegalArgumentException){}
 }
}
