// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class StudyAndSelectionRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{val name="study-${id()}.db";val db=NoteDatabase.open(context,name);try{block(db,WorkspaceRepository(db).create("学习资料",false,PaperStyle.GRID).id)}finally{db.close();context.deleteDatabase(name)}}
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff24342f.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,110f,0,.5f),InkSample(150f,110.5f,30,.7f),InkSample(200f,110f,60,.7f)))
    private suspend fun seed(db:NoteDatabase,page:String):InkStroke{val s=stroke();assertTrue(InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(s))) is InkCommitResult.Committed);return s}
    private fun create(book:String,title:String="我的理解",source:StudySourceDraft?=null,parent:String?=null)=StudyCommand(id(),book,StudyAction.CREATE,id(),id(),title=title,body="人工摘要",source=source,parentId=parent)
    @Test fun replacingInkPreservesOriginalBytesAndCanAtomicallyUndo()=fixture{db,page->
        val s=seed(db,page);val before=db.ink().stroke(s.id)!!.payload;val ink=InkRepository(db)
        val moved=InkSelectionEdit.copy(listOf(s),30f,40f).single();val c=CommitInk(id(),page,1,InkMutation.Replace(listOf(s.id),listOf(moved)))
        assertEquals(InkCommitResult.Committed(2),ink.save(c));assertArrayEquals(before,db.ink().stroke(s.id)!!.payload)
        assertFalse(db.ink().stroke(s.id)!!.visible);assertEquals(moved.samples,ink.read(page).strokes.single{it.visible}.stroke.samples)
        assertEquals(InkCommitResult.Committed(3),ink.save(CommitInk(id(),page,2,InkMutation.Swap(listOf(moved.id),listOf(s.id)))))
        assertEquals(s.id,InkSession(ink.read(page)).visibleDraft().single().id)
    }
    @Test fun replaceWithLostAcknowledgementReplaysWithoutDuplicates()=fixture{db,page->
        val s=seed(db,page);val command=CommitInk(id(),page,1,InkMutation.Replace(listOf(s.id),InkSelectionEdit.beautify(listOf(s),.5f)))
        try{InkRepository(db){if(it==InkFaultPoint.AFTER_TRANSACTION)error("synthetic")}.save(command);fail()}catch(_:IllegalStateException){}
        assertEquals(InkCommitResult.Committed(2),InkRepository(db).save(command));assertEquals(2,db.ink().strokes(page).size)
    }
    @Test fun replaceBeforeReceiptFailureRollsBackEverything()=fixture{db,page->
        val s=seed(db,page);val new=InkSelectionEdit.copy(listOf(s),5f,5f)
        try{InkRepository(db){if(it==InkFaultPoint.BEFORE_RECEIPT)error("synthetic")}.save(CommitInk(id(),page,1,InkMutation.Replace(listOf(s.id),new)));fail()}catch(_:IllegalStateException){}
        assertEquals(1L,db.ink().page(page)!!.revision);assertTrue(db.ink().stroke(s.id)!!.visible);assertNull(db.ink().stroke(new.single().id))
    }
    @Test fun exactEraseAndRecolorInvalidateManualIndex()=fixture{db,page->
        val s=seed(db,page);val ink=InkRepository(db);NotebookPages(db).saveSearchText(page,1,"旧关键词")
        val mask=InkRegion(listOf(EraserPoint(140f,100f),EraserPoint(160f,125f))).mask()
        ink.save(CommitInk(id(),page,1,InkMutation.Cut(EraseSelection(mask,listOf(s.id)))))
        assertTrue(db.pages().observeSearch().first().none{it.pageId==page});val current=InkSession(ink.read(page)).visibleDraft().single()
        assertEquals(InkCutShape.RECTANGLE,current.cuts.single().shape)
        val changed=InkSelectionEdit.recolor(listOf(current),0xffb83239.toInt());ink.save(CommitInk(id(),page,2,InkMutation.Replace(listOf(s.id),changed)))
        assertEquals(mask.points,InkSession(ink.read(page)).visibleDraft().single().cuts.single().points)
    }
    @Test fun staleAndCrossPageEditsCannotOverwriteInk()=fixture{db,page->
        val s=seed(db,page);val other=WorkspaceRepository(db).create("其他",false,PaperStyle.BLANK)
        val c=CommitInk(id(),other.id,0,InkMutation.Replace(listOf(s.id),InkSelectionEdit.copy(listOf(s),0f,0f)))
        assertEquals(InkCommitResult.Conflict,InkRepository(db).save(c));assertEquals(1,db.ink().strokes(page).size)
        assertEquals(InkCommitResult.Conflict,InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Visibility(listOf(s.id),false))))
    }
    @Test fun excerptSnapshotIsImmutableAfterSourceEraseAndPageMove()=fixture{db,page->
        val s=seed(db,page);val c=create(page,source=StudySourceDraft(page,1,CanvasBounds(90.0,90.0,210.0,130.0),listOf(s.id)))
        val repo=StudyRepository(db);repo.submit(c);val old=repo.source(c.cardId!!)!!.snapshot.clone()
        InkRepository(db).save(CommitInk(id(),page,1,InkMutation.Visibility(listOf(s.id),false)))
        assertArrayEquals(old,repo.source(c.cardId!!)!!.snapshot);assertEquals(s.samples,InkPageFile.decode(old).strokes.single().samples)
        assertEquals(page,repo.source(c.cardId!!)!!.pageId);assertEquals(1L,repo.source(c.cardId!!)!!.inkRevision)
    }
    @Test fun staleSourceRejectsWholeCardTransaction()=fixture{db,page->
        val s=seed(db,page);val repo=StudyRepository(db);val c=create(page,source=StudySourceDraft(page,0,CanvasBounds(90.0,90.0,210.0,130.0),listOf(s.id)))
        try{repo.submit(c);fail()}catch(_:IllegalArgumentException){}
        assertTrue(db.study().cards(page).isEmpty());assertTrue(db.study().nodes(page).isEmpty());assertNull(db.study().receipt(c.id))
    }
    @Test fun oneCardContentHasTwoIndependentMapPositions()=fixture{db,page->
        val repo=StudyRepository(db);val c=create(page);repo.submit(c)
        val other=id();repo.submit(StudyCommand(id(),page,StudyAction.REUSE,cardId=c.cardId,nodeId=other,x=600.0,y=300.0))
        repo.submit(StudyCommand(id(),page,StudyAction.EDIT,cardId=c.cardId,expectedRevision=1,title="统一改名",body="两个位置共享"))
        assertEquals(1,db.study().cards(page).size);assertEquals(2,db.study().nodes(page).size)
        assertTrue(db.study().nodes(page).all{it.cardId==c.cardId});assertEquals("统一改名",db.study().card(c.cardId!!)!!.title)
        assertEquals(600.0,db.study().node(other)!!.x,0.0)
    }
    @Test fun movingNodeNeverModifiesSharedCard()=fixture{db,page->
        val repo=StudyRepository(db);val c=create(page);repo.submit(c);val before=db.study().card(c.cardId!!)!!
        repo.submit(StudyCommand(id(),page,StudyAction.MOVE,nodeId=c.nodeId,expectedRevision=1,x=450.0,y=650.0))
        assertEquals(before,db.study().card(c.cardId!!));assertEquals(450.0,db.study().node(c.nodeId!!)!!.x,0.0)
    }
    @Test fun cycleAndRemovingParentWithChildrenAreRejected()=fixture{db,page->
        val repo=StudyRepository(db);val a=create(page);repo.submit(a);val b=create(page,parent=a.nodeId);repo.submit(b)
        try{repo.submit(StudyCommand(id(),page,StudyAction.REPARENT,nodeId=a.nodeId,expectedRevision=1,parentId=b.nodeId));fail()}catch(_:IllegalArgumentException){}
        try{repo.submit(StudyCommand(id(),page,StudyAction.REMOVE_NODE,nodeId=a.nodeId,expectedRevision=1));fail()}catch(_:IllegalArgumentException){}
        assertNull(db.study().node(a.nodeId!!)!!.parentId);assertFalse(db.study().node(a.nodeId!!)!!.removed)
    }
    @Test fun removingOccurrenceKeepsCardAndAllowsReuse()=fixture{db,page->
        val repo=StudyRepository(db);val c=create(page);repo.submit(c)
        repo.submit(StudyCommand(id(),page,StudyAction.REMOVE_NODE,nodeId=c.nodeId,expectedRevision=1));assertFalse(db.study().card(c.cardId!!)!!.trashedAt!=null)
        repo.submit(StudyCommand(id(),page,StudyAction.REUSE,cardId=c.cardId,nodeId=id()))
        assertEquals(1,db.study().nodes(page).count{!it.removed});assertEquals(1,db.study().cards(page).size)
    }
    @Test fun cardTrashAndRestoreKeepRevisionHistory()=fixture{db,page->
        val repo=StudyRepository(db);val c=create(page);repo.submit(c)
        try{repo.submit(StudyCommand(id(),page,StudyAction.TRASH_CARD,cardId=c.cardId,expectedRevision=1));fail()}catch(_:IllegalArgumentException){}
        repo.submit(StudyCommand(id(),page,StudyAction.REMOVE_NODE,nodeId=c.nodeId,expectedRevision=1))
        repo.submit(StudyCommand(id(),page,StudyAction.TRASH_CARD,cardId=c.cardId,expectedRevision=1));assertNotNull(db.study().card(c.cardId!!)!!.trashedAt)
        repo.submit(StudyCommand(id(),page,StudyAction.RESTORE_CARD,cardId=c.cardId,expectedRevision=2));assertNull(db.study().card(c.cardId!!)!!.trashedAt)
        assertEquals(3L,db.study().card(c.cardId!!)!!.revision)
    }
    @Test fun cardUnknownOutcomeReplaysSameSnapshotAndIds()=fixture{db,page->
        val c=create(page);try{StudyRepository(db){if(it==StudyFault.AFTER_COMMIT)error("synthetic")}.submit(c);fail()}catch(_:IllegalStateException){}
        val repo=StudyRepository(db);assertEquals(c.cardId,repo.submit(c));assertEquals(c.cardId,repo.lookup(c));assertEquals(1,db.study().cards(page).size);assertEquals(1,db.study().nodes(page).size)
    }
    @Test fun cardFailureBeforeReceiptHasNoPartialRows()=fixture{db,page->
        val c=create(page);try{StudyRepository(db){if(it==StudyFault.BEFORE_RECEIPT)error("synthetic")}.submit(c);fail()}catch(_:IllegalStateException){}
        assertTrue(db.study().cards(page).isEmpty());assertTrue(db.study().nodes(page).isEmpty());assertNull(db.study().receipt(c.id))
    }
    @Test fun sameStudyCommandIdDifferentPayloadIsRejected()=fixture{db,page->
        val c=create(page);val repo=StudyRepository(db);repo.submit(c)
        try{repo.submit(StudyCommand(c.id,page,StudyAction.CREATE,cardId=c.cardId,nodeId=c.nodeId,title="不同正文"));fail()}catch(_:IllegalArgumentException){}
        assertEquals("我的理解",db.study().card(c.cardId!!)!!.title)
    }
    @Test fun arrangeUsesGraphRevisionAndRetainsCardContent()=fixture{db,page->
        val repo=StudyRepository(db);val a=create(page);repo.submit(a);val b=create(page,parent=a.nodeId);repo.submit(b)
        val hash=StudyGraph.orderHash(db.study().nodes(page).map{it.model()});repo.submit(StudyCommand(id(),page,StudyAction.ARRANGE,expectedGraph=hash))
        assertTrue(db.study().node(b.nodeId!!)!!.x>db.study().node(a.nodeId!!)!!.x)
        try{repo.submit(StudyCommand(id(),page,StudyAction.ARRANGE,expectedGraph=hash));fail()}catch(_:IllegalArgumentException){}
        assertEquals("人工摘要",db.study().card(a.cardId!!)!!.body)
    }
    @Test fun backupRestoresCardsSourcesHierarchyAndTypedMasks()=fixture{db,page->
        val s=seed(db,page);val ink=InkRepository(db);val cut=InkRegion(listOf(EraserPoint(140f,100f),EraserPoint(160f,130f))).mask()
        ink.save(CommitInk(id(),page,1,InkMutation.Cut(EraseSelection(cut,listOf(s.id)))))
        val repo=StudyRepository(db);val a=create(page,source=StudySourceDraft(page,2,CanvasBounds(90.0,90.0,210.0,140.0),listOf(s.id)));repo.submit(a);val b=create(page,parent=a.nodeId);repo.submit(b)
        val name="restore-study-${id()}.db";val target=NoteDatabase.open(context,name)
        try{val backup=LibraryBackupRepository(context,db);val restore=LibraryBackupRepository(context,target)
            backup.snapshot().use{snap->snap.file.inputStream().use{restore.inspect(it)}.use{p->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(p))}}
            assertEquals(db.study().cards(page),target.study().cards(page));assertEquals(db.study().nodes(page),target.study().nodes(page));assertArrayEquals(db.study().source(a.cardId!!)!!.snapshot,target.study().source(a.cardId!!)!!.snapshot)
            assertEquals(InkCutShape.RECTANGLE,InkSession(InkRepository(target).read(page)).visibleDraft().single().cuts.single().shape)
            assertEquals(a.cardId,StudyRepository(target).submit(a))
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun migrationSevenToEightRetainsOriginalInkAndPageIdentity()=runBlocking{
        val name="study-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("org.inkweft.data.NoteDatabase/7.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val sql=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null);val book=id();val s=stroke();val bytes=InkStrokeCodec.encode(s)
        try{val es=schema.getJSONArray("entities");for(i in 0 until es.length()){val e=es.getJSONObject(i);val t=e.getString("tableName");sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",t));val ix=e.optJSONArray("indices");for(j in 0 until (ix?.length()?:0))sql.execSQL(ix!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",t))};val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())sql.execSQL(setup.getString(i))
            sql.execSQL("INSERT INTO notes VALUES(?,1,'旧笔记','原文',1)",arrayOf(book));sql.execSQL("INSERT INTO note_revisions VALUES(?,1,'旧笔记','原文',1)",arrayOf(book))
            sql.execSQL("INSERT INTO notebook_workspace VALUES(?,0,1,'数学','复习',1,NULL,500,707,0,0,'auto',?,1)",arrayOf(book,book))
            sql.execSQL("INSERT INTO notebook_pages VALUES(?,?,0,0,1,500,707,0,NULL,NULL)",arrayOf(book,book));sql.execSQL("INSERT INTO ink_pages VALUES(?,1)",arrayOf(book));sql.execSQL("INSERT INTO ink_strokes VALUES(?,?,?,?,1,1)",arrayOf(s.id,book,bytes,3));sql.version=7
        }finally{sql.close()}
        val db=NoteDatabase.open(context,name);try{assertEquals("原文",db.notes().note(book)!!.text);assertArrayEquals(bytes,db.ink().stroke(s.id)!!.payload);assertEquals(book,db.pages().list(book).single().id);assertTrue(db.workspace().get(book)!!.pinned);assertEquals(10,db.openHelper.writableDatabase.version);val c=create(book);StudyRepository(db).submit(c);assertNotNull(db.study().card(c.cardId!!))}finally{db.close();context.deleteDatabase(name)}
    }
}
