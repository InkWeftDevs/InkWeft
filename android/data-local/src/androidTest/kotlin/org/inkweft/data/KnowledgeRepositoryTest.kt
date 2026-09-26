package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class KnowledgeRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{val name="knowledge-${id()}.db";val db=NoteDatabase.open(context,name);try{block(db,WorkspaceRepository(db).create("关联测试",false,PaperStyle.CORNELL).id)}finally{db.close();context.deleteDatabase(name)}}
    private suspend fun card(db:NoteDatabase,book:String):String{val c=StudyCommand(id(),book,StudyAction.CREATE,id(),id(),title="知识甲",body="原答案");StudyRepository(db).submit(c);return c.cardId!!}
    private fun command(book:String,data:KnowledgeData,id:String=id(),revision:Long=0)=KnowledgeCommand(id(),book,id,revision,data)
    @Test fun creationReplaysSameIdentityWithoutHalfNotebook()=fixture{db,_->val op=id();val repo=WorkspaceRepository(db)
        val n=repo.create("新建一次",false,PaperStyle.CORNELL,operationId=op);val again=repo.create("新建一次",false,PaperStyle.CORNELL,operationId=op)
        assertEquals(n.id,again.id);assertEquals(1,db.pages().allPages(n.id).size)
        try{repo.create("另一个请求",false,PaperStyle.CORNELL,operationId=op);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun highlighterEraseKeepsValidIndexButDoesNotReviveStaleText()=fixture{db,book->
        val ink=InkRepository(db);fun stroke(pen:InkPen)=InkStroke(id(),pen,if(pen==InkPen.PEN)0xff000000.toInt()else 0x66ffee00,3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(200f,100f,10)))
        ink.save(CommitInk(id(),book,0,InkMutation.Add(stroke(InkPen.PEN))));val h=stroke(InkPen.HIGHLIGHTER);ink.save(CommitInk(id(),book,1,InkMutation.Add(h)))
        NotebookPages(db).saveSearchText(book,2,"保留的手写词")
        ink.save(CommitInk(id(),book,2,InkMutation.Visibility(listOf(h.id),false)));assertEquals(3L,db.pages().search(book)!!.inkRevision)
        ink.save(CommitInk(id(),book,3,InkMutation.Add(stroke(InkPen.PEN))));ink.save(CommitInk(id(),book,4,InkMutation.Add(stroke(InkPen.HIGHLIGHTER))))
        assertEquals(3L,db.pages().search(book)!!.inkRevision);assertEquals(5L,db.ink().page(book)!!.revision)
    }
    @Test fun regionLinkNeedsNoCardAndKeepsOriginalInk()=fixture{db,book->
        val stroke=InkStroke(id(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(100f,100f,0),InkSample(200f,100f,10)))
        InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke)));val before=db.ink().stroke(stroke.id)!!.payload
        val repo=KnowledgeRepository(db);val a=command(book,KnowledgeData.Anchor(book,1,CanvasBounds(95.0,95.0,205.0,105.0),listOf(stroke.id)));repo.submit(a)
        assertTrue(db.study().cards(book).isEmpty());assertEquals(book,repo.resolve(TargetRef(TargetKind.ANCHOR,a.id)).second!!.pageId);assertArrayEquals(before,db.ink().stroke(stroke.id)!!.payload)
    }
    @Test fun replayDoesNotDuplicateAndDigestMismatchIsRejected()=fixture{db,book->val repo=KnowledgeRepository(db);val c=command(book,KnowledgeData.Collection("集合"));assertEquals(c.id,repo.submit(c));assertEquals(c.id,repo.submit(c));assertEquals(1,db.knowledge().all().size)
        try{repo.submit(KnowledgeCommand(c.operationId,book,c.id,0,KnowledgeData.Collection("另一个")));fail()}catch(_:IllegalArgumentException){}}
    @Test fun sameCardAcrossTwoMapsKeepsOneBodyAndIndependentOccurrences()=fixture{db,book->
        val c=card(db,book);val repo=KnowledgeRepository(db);val a=command(book,KnowledgeData.MapDefinition("甲图"));val b=command(book,KnowledgeData.MapDefinition("乙图"));repo.submit(a);repo.submit(b)
        val n1=command(book,KnowledgeData.MapOccurrence(a.id,c,null,40.0,80.0));val n2=command(book,KnowledgeData.MapOccurrence(b.id,c,null,200.0,100.0));repo.submit(n1);repo.submit(n2)
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=c,expectedRevision=1,title="共享标题",body="共享新正文"))
        repo.submit(command(book,(n1.data as KnowledgeData.MapOccurrence).copy(x=400.0),n1.id,1))
        val changed=db.knowledge().get(n1.id)!!;repo.submit(KnowledgeCommand(id(),book,n1.id,2,changed.data(),true))
        assertEquals(1,db.study().cards(book).size);assertEquals("共享新正文",db.study().card(c)!!.body);assertFalse(db.knowledge().get(n2.id)!!.removed);assertEquals(200.0,(db.knowledge().get(n2.id)!!.data() as KnowledgeData.MapOccurrence).x,0.0)
        repo.validateArchive()
    }
    @Test fun mapCycleAndRemovingParentAreRejectedWithoutPartialWrite()=fixture{db,book->
        val c=card(db,book);val repo=KnowledgeRepository(db);val map=command(book,KnowledgeData.MapDefinition("层级"));repo.submit(map)
        val root=command(book,KnowledgeData.MapOccurrence(map.id,c,null,40.0,80.0));repo.submit(root)
        val child=command(book,KnowledgeData.MapOccurrence(map.id,c,root.id,300.0,80.0));repo.submit(child)
        try{repo.submit(command(book,(root.data as KnowledgeData.MapOccurrence).copy(parentId=child.id),root.id,1));fail()}catch(_:IllegalArgumentException){}
        try{repo.submit(KnowledgeCommand(id(),book,root.id,1,root.data,true));fail()}catch(_:IllegalArgumentException){}
        assertEquals(1L,db.knowledge().get(root.id)!!.revision);assertFalse(db.knowledge().get(root.id)!!.removed)
    }
    @Test fun decorationNeverBecomesKnowledgeLinkAndSurvivesBackup()=fixture{db,book->
        val c=card(db,book);val repo=KnowledgeRepository(db);val a=command(book,KnowledgeData.Placement(c,40.0,80.0));val b=command(book,KnowledgeData.Placement(c,350.0,100.0));repo.submit(a);repo.submit(b)
        val line=command(book,KnowledgeData.Decoration(a.id,b.id));repo.submit(line)
        assertTrue(db.knowledge().all().none{it.data() is KnowledgeData.Link});assertEquals(1,db.study().nodes(book).size)
        LibraryBackupRepository(context,db).snapshot().use{snapshot->snapshot.file.inputStream().use{LibraryBackupRepository(context,db).inspect(it)}.use{preview->
            assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,LibraryBackupRepository(context,db).restore(preview))}}
    }
    @Test fun lostResponseQueriesCommittedReceipt()=fixture{db,book->val c=command(book,KnowledgeData.Collection("待复习"));try{KnowledgeRepository(db){if(it==KnowledgeFault.AFTER_COMMIT)error("synthetic")}.submit(c);fail()}catch(_:IllegalStateException){}
        assertEquals(c.id,KnowledgeRepository(db).submit(c));assertEquals(1,db.knowledge().all().size)}
    @Test fun failedTransactionDoesNotLeaveHalfRecord()=fixture{db,book->val c=command(book,KnowledgeData.Collection("集合"));try{KnowledgeRepository(db){if(it==KnowledgeFault.BEFORE_RECEIPT)error("synthetic")}.submit(c);fail()}catch(_:IllegalStateException){}
        assertNull(db.knowledge().get(c.id));assertNull(db.knowledge().receipt(c.operationId));assertNull(db.knowledge().revision(c.id,1))}
    @Test fun staleRevisionDoesNotOverwriteProperties()=fixture{db,book->val c=card(db,book);val repo=KnowledgeRepository(db);val create=command(book,KnowledgeData.Properties(c));repo.submit(create)
        repo.submit(command(book,KnowledgeData.Properties(c,ManualState.REVIEW),create.id,1));try{repo.submit(command(book,KnowledgeData.Properties(c,ManualState.UNDERSTOOD),create.id,1));fail()}catch(_:IllegalArgumentException){}
        assertEquals(ManualState.REVIEW,(db.knowledge().get(create.id)!!.data() as KnowledgeData.Properties).state)}
    @Test fun backlinkIsSingleRecordAndRenameKeepsTargetIdentity()=fixture{db,book->val c=card(db,book);val repo=KnowledgeRepository(db);val l=KnowledgeData.Link(TargetRef(TargetKind.PAGE,book),TargetRef(TargetKind.CARD,c));repo.submit(command(book,l))
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=c,expectedRevision=1,title="知识改名",body="新答案"));assertEquals(1,db.knowledge().all().size);assertEquals(c,(db.knowledge().all().single().data() as KnowledgeData.Link).target.id)}
    @Test fun pinnedVersionAndLiveContentRemainDifferent()=fixture{db,book->val c=card(db,book);val repo=KnowledgeRepository(db);repo.submit(command(book,KnowledgeData.Link(TargetRef(TargetKind.PAGE,book),TargetRef(TargetKind.CARD,c),pinnedRevision=1)))
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=c,expectedRevision=1,title="新版",body="新答案"));assertEquals("原答案",repo.cardVersion(c,1)!!.body);assertEquals("新答案",db.study().card(c)!!.body)}
    @Test fun propertyAndBoardDoNotCreateMoreCardsOrMapNodes()=fixture{db,book->val c=card(db,book);val repo=KnowledgeRepository(db);repo.submit(command(book,KnowledgeData.Placement(c,50.0,80.0)));repo.submit(command(book,KnowledgeData.Properties(c,ManualState.REVIEW,listOf("数学"))));repo.submit(command(book,KnowledgeData.Question(c,"解释这个知识")))
        assertEquals(1,db.study().cards(book).size);assertEquals(1,db.study().nodes(book).size);assertEquals("原答案",db.study().card(c)!!.body)}
    @Test fun missingOrCrossBookSourceCannotCreateLink()=fixture{db,book->val other=WorkspaceRepository(db).create("其他",false,PaperStyle.BLANK);val c=card(db,book)
        try{KnowledgeRepository(db).submit(command(book,KnowledgeData.Link(TargetRef(TargetKind.PAGE,other.id),TargetRef(TargetKind.CARD,c))));fail()}catch(_:IllegalArgumentException){}
        assertTrue(db.knowledge().all().isEmpty())}
    @Test fun recycledTargetUnavailableButAuthorLinkPreserved()=fixture{db,book->val other=WorkspaceRepository(db).create("其他",false,PaperStyle.BLANK);val repo=KnowledgeRepository(db);repo.submit(command(book,KnowledgeData.Link(TargetRef(TargetKind.PAGE,book),TargetRef(TargetKind.NOTE,other.id))))
        val w=db.workspace().get(other.id)!!;WorkspaceRepository(db).organize(other.id,w.revision,w.folder,w.tags,w.favorite,true)
        assertFalse(repo.available(TargetRef(TargetKind.NOTE,other.id)));assertEquals(1,db.knowledge().all().size);repo.validateArchive()}
    @Test fun backupRestoresEveryKnowledgeKindAndRejectsConflict()=fixture{db,book->val c=card(db,book);val repo=KnowledgeRepository(db)
        val values=listOf(KnowledgeData.Link(TargetRef(TargetKind.PAGE,book),TargetRef(TargetKind.CARD,c),pinnedRevision=1),KnowledgeData.Properties(c,ManualState.REVIEW,listOf("数学")),KnowledgeData.Collection("数学复习","数学",ManualState.REVIEW),KnowledgeData.Question(c,"说明理由"),KnowledgeData.Placement(c,80.0,200.0),KnowledgeData.Alias(c,"同义名"))
        values.forEach{repo.submit(command(book,it))};val name="restore-${id()}.db";val target=NoteDatabase.open(context,name)
        try{val backup=LibraryBackupRepository(context,db);backup.snapshot().use{snap->val restore=LibraryBackupRepository(context,target)
            snap.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}
            assertEquals(values.toSet(),target.knowledge().all().map{it.data()}.toSet());assertEquals(PaperStyle.CORNELL.ordinal,target.pages().get(book)!!.paper)
            snap.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,restore.restore(preview))}
            val prop=target.knowledge().all().first{it.data() is KnowledgeData.Properties};KnowledgeRepository(target).submit(command(book,KnowledgeData.Properties(c,ManualState.UNDERSTOOD),prop.id,1))
            snap.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.IDENTITY_CONFLICT,restore.restore(preview))}
        }}finally{target.close();context.deleteDatabase(name)}}
}
