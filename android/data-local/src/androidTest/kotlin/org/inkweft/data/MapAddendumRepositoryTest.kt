package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MapAddendumRepositoryTest {
 private val context get()=ApplicationProvider.getApplicationContext<Context>()
 private fun id()=UUID.randomUUID().toString()
 private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{val name="map-addendum-${id()}.db";val db=NoteDatabase.open(context,name);try{block(db,WorkspaceRepository(db).create("概率论验收",false,PaperStyle.DOTS).id)}finally{db.close();context.deleteDatabase(name)}}
 private suspend fun map(db:NoteDatabase,book:String):String {val map=id();KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,map,0,KnowledgeData.MapDefinition("学习路径")));return map}
 private suspend fun capture(db:NoteDatabase,book:String):CaptureDraft{val ink=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(200f,110f,30)));InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(ink)));return CaptureDraft(book,StudySourceDraft(book,1,ink.bounds(),listOf(ink.id)),"独立性与条件概率\n已确认内容")}
 @Test fun frozenDestinationHasAtomicReplayAndRejectsChangedBranch()=fixture{db,book->
  val a=map(db,book);val b=map(db,book);val draft=capture(db,book);val access=MapGraphAccess(db);val ref=MapRef(book,a);val graph=access.read(book).first{it.ref==ref}
  val command=draft.command(ref,null,graph.graphHash)
  assertEquals(StudyOutcome.Unknown,StudyRepository(db){if(it==StudyFault.BEFORE_RECEIPT)error("rollback")}.outcome(command));assertTrue(db.study().cards(book).isEmpty())
  assertTrue(StudyRepository(db){if(it==StudyFault.AFTER_COMMIT)error("lost")}.outcome(command) is StudyOutcome.Success)
  StudyRepository(db).submit(command);assertEquals(1,db.study().cards(book).size);assertEquals(draft.text,db.study().cards(book).single().body);assertNotNull(db.study().source(command.cardId!!));assertTrue(access.read(book).first{it.ref.mapId==b}.nodes.isEmpty())
  assertTrue(StudyRepository(db).outcome(draft.command(ref,null,graph.graphHash)) is StudyOutcome.Rejected);assertEquals(1,db.study().cards(book).size)
  val reuse=StudyCommand(id(),book,StudyAction.REUSE,cardId=command.cardId,nodeId=id(),mapId=b);StudyRepository(db).submit(reuse)
  StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.UNDO_CAPTURE,cardId=command.cardId,nodeId=command.nodeId,expectedRevision=1,mapId=a))
  assertNull(db.study().card(command.cardId!!)!!.trashedAt);assertFalse(db.knowledge().get(reuse.nodeId!!)!!.removed)
 }
 @Test fun liveAndPinnedFollowContentAndIndependentCopyReplaysOnce()=fixture{db,book->
  val target=map(db,book);val draft=capture(db,book);val ref=MapRef(book,target);val access=MapGraphAccess(db);val c=draft.command(ref,null,access.read(book).first{it.ref==ref}.graphHash);StudyRepository(db).submit(c)
  val old=access.read(book).first{it.ref==ref};val pinned=MapEmbed(ref,policy=MapEmbedPolicy.PINNED,snapshot=old);val operation=id();val repo=MapEmbedRepository(db)
  val copy=repo.duplicate(ref,old.signature(),operation);assertEquals(copy,repo.duplicate(ref,old.signature(),operation));val copied=access.read(book).first{it.ref==copy};assertNotEquals(old.nodes.single().cardId,copied.nodes.single().cardId);assertNotNull(db.study().source(copied.nodes.single().cardId!!))
  StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=c.cardId,expectedRevision=1,title="新标题",body="新正文"))
  val latest=access.read(book);assertEquals("新正文",MapEmbed(ref).resolve(latest)!!.nodes.single().body);assertEquals(draft.text,pinned.resolve(latest)!!.nodes.single().body);assertEquals(draft.text,latest.first{it.ref==copy}.nodes.single().body)
  assertNotEquals(old.signature(),latest.first{it.ref==ref}.signature());assertEquals(old.graphHash,latest.first{it.ref==ref}.graphHash)
 }
 @Test fun pageOccurrenceCopyDeleteAndBackupKeepClosure()=fixture{db,book->
  val target=map(db,book);val draft=capture(db,book);val ref=MapRef(book,target);val access=MapGraphAccess(db);val c=draft.command(ref,null,access.read(book).first{it.ref==ref}.graphHash);StudyRepository(db).submit(c)
  val fixed=MapEmbed(ref,policy=MapEmbedPolicy.PINNED,snapshot=access.read(book).first{it.ref==ref});val objects=listOf(MapEmbed(ref),fixed).mapIndexed{i,e->PageObject(id(),PageObjectKind.MAP,40f,60f+i*420,600f,380f,mapEmbed=e)}
  val repo=PageObjectRepository(db);repo.save(book,0,id(),objects)
  val copyId=id();val edit=EditPage(id(),book,book,PageEditKind.COPY,InsertPages.orderHash(db.pages().list(book).map{it.id}),1,PageInsertLocation.END,null,copyId,null,book)
  assertTrue(PageEditingRepository(db).apply(edit) is EditPageResult.Applied)
  val copied=repo.read(copyId).objects;assertEquals(objects.map{it.mapEmbed},copied.map{it.mapEmbed});assertNotEquals(objects[0].id,copied[0].id)
  repo.save(book,1,id(),emptyList());assertNotNull(db.study().card(c.cardId!!));assertFalse(db.knowledge().get(target)!!.removed)
  val name="embed-restore-${id()}.db";val restored=NoteDatabase.open(context,name)
  try{LibraryBackupRepository(context,db).snapshot().use{snapshot->val backup=LibraryBackupRepository(context,restored);snapshot.file.inputStream().use{backup.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,backup.restore(preview))}}
   assertEquals(copied,PageObjectRepository(restored).read(copyId).objects);assertEquals("已确认内容",MapGraphAccess(restored).read(book).first{it.ref==ref}.nodes.single().body.lineSequence().last())
  }finally{restored.close();context.deleteDatabase(name)}
 }
 @Test fun recycledMapAndForeignBookAreNeverSilentlyRetargeted()=fixture{db,book->
  val target=map(db,book);val ref=MapRef(book,target);val embed=PageObject(id(),PageObjectKind.MAP,20f,20f,600f,380f,mapEmbed=MapEmbed(ref));PageObjectRepository(db).save(book,0,id(),listOf(embed))
  val row=db.knowledge().get(target)!!;KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,target,row.revision,row.data(),true));assertFalse(MapGraphAccess(db).read(book).first{it.ref==ref}.available)
  val other=WorkspaceRepository(db).create("另一本",false,PaperStyle.BLANK).id
  try{PageObjectRepository(db).save(other,0,id(),listOf(embed));fail()}catch(e:IllegalArgumentException){assertEquals("MAP_EMBED_CROSS_BOOK",e.message)}
  assertTrue(PageObjectRepository(db).read(other).objects.isEmpty())
 }
}
