// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class StudyCapacityRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val name="study-capacity-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("容量事务夹具",false,PaperStyle.BLANK).id)}
        finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun card(db:NoteDatabase,book:String):String {
        val row=StudyCardRow(id(),book,1,"合成卡片","")
        db.study().addCard(row);db.study().revision(StudyCardRevisionRow(row.id,1,row.title,row.body,null));return row.id
    }
    private fun knowledgeStamp(rows:List<KnowledgeRow>)=rows.map{listOf(it.id,it.revision,it.removed,ContentTransfer.hash(it.payload))}
    private suspend fun rejectStudy(db:NoteDatabase,c:StudyCommand,reason:String) {
        val repo=StudyRepository(db);val graph=repo.readGraph(c.notebookId,c.mapId).graphFingerprint
        val cards=db.study().cards(c.notebookId);val rows=knowledgeStamp(db.knowledge().forBook(c.notebookId))
        val sources=db.study().sourceIds(c.notebookId);val bytes=repo.snapshotBytes();val note=db.notes().note(c.notebookId)
        assertEquals(StudyOutcome.Rejected(reason),repo.outcome(c))
        assertEquals(graph,repo.readGraph(c.notebookId,c.mapId).graphFingerprint)
        assertEquals(cards,db.study().cards(c.notebookId));assertEquals(rows,knowledgeStamp(db.knowledge().forBook(c.notebookId)))
        assertEquals(sources,db.study().sourceIds(c.notebookId));assertEquals(bytes,repo.snapshotBytes());assertEquals(note,db.notes().note(c.notebookId))
        assertNull(db.study().receipt(c.id))
        if(c.action!=StudyAction.REUSE)c.cardId?.let{assertNull(db.study().cardVersion(it,c.expectedRevision+1))}
    }
    /** DAO seeding isolates the 127/255 boundaries; all tested mutations use real repositories. */
    private suspend fun graph(db:NoteDatabase,book:String,card:String,total:Int,active:Int,named:Boolean):String?=db.withTransaction {
        val map=if(named)id()else null
        if(map!=null){val row=KnowledgeRow(map,book,1,KnowledgeCodec.encode(KnowledgeData.MapDefinition("命名图")))
            db.knowledge().insert(row);db.knowledge().revision(KnowledgeRevisionRow(row.id,1,book,row.payload,false))}
        repeat(total){i->val node=id()
            if(map==null)db.study().addNode(StudyNodeRow(node,book,card,null,40.0,i.toDouble(),removed=i>=active))
            else {val row=KnowledgeRow(node,book,1,KnowledgeCodec.encode(KnowledgeData.MapOccurrence(map,card,null,40.0,i.toDouble())),i>=active)
                db.knowledge().insert(row);db.knowledge().revision(KnowledgeRevisionRow(row.id,1,book,row.payload,row.removed))}
        };map
    }
    private fun jpeg(size:Int):ByteArray {
        val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        try{for(y in 0 until size)for(x in 0 until size)bitmap.setPixel(x,y,0xff000000.toInt() or (((x*73+y*29) and 255) shl 16) or (((x*19+y*83) and 255) shl 8) or ((x*47+y*31) and 255))
            return ByteArrayOutputStream().also{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,it))}.toByteArray()
        }finally{bitmap.recycle()}
    }
    private fun excerpt(book:String,picture:ByteArray)=StudyCommand(id(),book,StudyAction.CREATE_EXCERPT,cardId=id(),title="容量摘录",
        source=StudySourceDraft(book,0,CanvasBounds(0.0,0.0,100.0,100.0),emptyList(),picture,0))
    /** Encoded snapshots fill an isolated quota fixture. Full archive validity is tested separately. */
    private suspend fun fillSnapshotBytes(db:NoteDatabase,amount:Long) {
        var remaining=amount;val before=db.study().snapshotBytes()
        while(remaining>0){
            val book=WorkspaceRepository(db).create("合成来源配额",false,PaperStyle.BLANK).id
            val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
            assertTrue(InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
            val size=minOf(remaining,16_000_000L).toInt();val count=(size+99_999)/100_000
            val base=InkPageFile("容量夹具","",listOf(stroke),false,PaperStyle.BLANK).encode().size
            val cache=mutableMapOf<Int,ByteArray>()
            db.withTransaction {repeat(count){i->
                val bytes=size/count+if(i<size%count)1 else 0
                val snapshot=cache.getOrPut(bytes){InkPageFile("容量夹具","x".repeat(bytes-base),listOf(stroke),false,PaperStyle.BLANK).encode().also{
                    assertEquals(bytes,it.size);assertEquals(stroke.id,InkPageFile.decode(it).strokes.single().id)}}
                val c=card(db,book);db.study().source(StudySourceRow(c,book,1,10.0,10.0,30.0,30.0,stroke.id,snapshot))
            }};remaining-=size
        };assertEquals(before+amount,db.study().snapshotBytes())
    }

    @Test fun cardCapacityCountsRecycledCardsAndAllowsRestoreReuseAndEdit()=fixture{db,book->
        val repo=StudyRepository(db);val originals=db.withTransaction{List(199){card(db,book)}}
        assertEquals(199,repo.cards(book).first().size)
        val last=StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="第200张")
        repo.submit(last);assertEquals(200,repo.cards(book).first().size)
        val recycled=originals.first();repo.submit(StudyCommand(id(),book,StudyAction.TRASH_CARD,cardId=recycled,expectedRevision=1))
        assertNotNull(db.study().card(recycled)!!.trashedAt);assertEquals(200,repo.cards(book).first().size)
        rejectStudy(db,StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="第201张"),"STUDY_CARD_BUDGET")
        repo.submit(StudyCommand(id(),book,StudyAction.RESTORE_CARD,cardId=recycled,expectedRevision=2))
        repo.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=recycled,nodeId=id()))
        repo.submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=recycled,expectedRevision=3,title="满额仍可编辑",body="内容保留"))
        assertEquals(200,repo.cards(book).first().size);assertNull(db.study().card(recycled)!!.trashedAt)
        assertEquals("满额仍可编辑",db.study().card(recycled)!!.title);assertEquals(2,repo.readGraph(book).nodes.size)
        assertEquals(last.cardId,repo.submit(last));assertEquals(200,repo.cards(book).first().size)
    }

    @Test fun activeNodeCapacityRejectsAtomicallyAndRemovalAllowsReuse()=fixture{db,first->
        for(named in listOf(false,true)){
            val book=if(named)WorkspaceRepository(db).create("命名图活动容量",false,PaperStyle.BLANK).id else first
            val c=card(db,book);val map=graph(db,book,c,127,127,named);val repo=StudyRepository(db)
            assertEquals(127,repo.readGraph(book,map).nodes.count{!it.removed})
            val last=StudyCommand(id(),book,StudyAction.REUSE,cardId=c,nodeId=id(),mapId=map)
            repo.submit(last);assertEquals(128,repo.readGraph(book,map).nodes.count{!it.removed})
            rejectStudy(db,StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="第129个",mapId=map),"STUDY_NODE_BUDGET")
            if(map!=null){val op=KnowledgeCommand(id(),book,id(),0,KnowledgeData.MapOccurrence(map,c,null,0.0,0.0))
                val before=knowledgeStamp(db.knowledge().forBook(book));val note=db.notes().note(book)
                assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.STUDY_NODE_BUDGET),KnowledgeRepository(db).outcome(op))
                assertEquals(before,knowledgeStamp(db.knowledge().forBook(book)));assertEquals(note,db.notes().note(book));assertNull(db.knowledge().receipt(op.operationId))}
            repo.submit(StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=last.nodeId,expectedRevision=1,mapId=map))
            assertEquals(128,repo.readGraph(book,map).nodes.size);assertEquals(127,repo.readGraph(book,map).nodes.count{!it.removed})
            repo.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=c,nodeId=id(),mapId=map))
            assertEquals(129,repo.readGraph(book,map).nodes.size);assertEquals(128,repo.readGraph(book,map).nodes.count{!it.removed})
            assertEquals(1,db.study().cards(book).size)
        }
    }

    @Test fun retainedNodeCapacityRejectsStudyAndKnowledgeWithoutPartialWrites()=fixture{db,first->
        for(named in listOf(false,true)){
            val book=if(named)WorkspaceRepository(db).create("命名图历史容量",false,PaperStyle.BLANK).id else first
            val c=card(db,book);val map=graph(db,book,c,255,1,named);val repo=StudyRepository(db)
            assertEquals(255,repo.readGraph(book,map).nodes.size)
            val last=StudyCommand(id(),book,StudyAction.REUSE,cardId=c,nodeId=id(),mapId=map)
            repo.submit(last);assertEquals(256,repo.readGraph(book,map).nodes.size)
            rejectStudy(db,StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="第257条",mapId=map),"STUDY_NODE_RECORD_BUDGET")
            if(map!=null){val op=KnowledgeCommand(id(),book,id(),0,KnowledgeData.MapOccurrence(map,c,null,0.0,0.0))
                val before=knowledgeStamp(db.knowledge().forBook(book));val note=db.notes().note(book)
                assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.STUDY_NODE_RECORD_BUDGET),KnowledgeRepository(db).outcome(op))
                assertEquals(before,knowledgeStamp(db.knowledge().forBook(book)));assertEquals(note,db.notes().note(book));assertNull(db.knowledge().receipt(op.operationId))}
            repo.submit(StudyCommand(id(),book,StudyAction.MOVE,nodeId=last.nodeId,expectedRevision=1,x=100.0,y=200.0,mapId=map))
            repo.submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=c,expectedRevision=1,title="保留历史仍可编辑"))
            repo.submit(StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=last.nodeId,expectedRevision=2,mapId=map))
            assertEquals(256,repo.readGraph(book,map).nodes.size);assertEquals(1,repo.readGraph(book,map).nodes.count{!it.removed})
            rejectStudy(db,StudyCommand(id(),book,StudyAction.REUSE,cardId=c,nodeId=id(),mapId=map),"STUDY_NODE_RECORD_BUDGET")
            assertEquals(last.nodeId,repo.submit(last));assertEquals(256,repo.readGraph(book,map).nodes.size)
        }
    }

    @Test fun knowledgeCapacityPreservesReasonsInBothTransactionChecks()=fixture{db,book->
        val payload=KnowledgeCodec.encode(KnowledgeData.Collection("合成集合"))
        db.withTransaction{repeat(1999){val row=KnowledgeRow(id(),book,1,payload)
            db.knowledge().insert(row);db.knowledge().revision(KnowledgeRevisionRow(row.id,1,book,payload,false))}}
        val repo=KnowledgeRepository(db);val map=KnowledgeCommand(id(),book,id(),0,KnowledgeData.MapDefinition("需要图与顺序两条记录"))
        val before=knowledgeStamp(db.knowledge().forBook(book));val note=db.notes().note(book)
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.KNOWLEDGE_BUDGET),repo.outcome(map))
        assertEquals(before,knowledgeStamp(db.knowledge().forBook(book)));assertEquals(note,db.notes().note(book));assertNull(db.knowledge().receipt(map.operationId))
        val last=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Collection("第2000条"));repo.submit(last)
        val extra=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Collection("第2001条"))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.KNOWLEDGE_BUDGET),repo.outcome(extra));assertNull(db.knowledge().receipt(extra.operationId))
        repo.submit(KnowledgeCommand(id(),book,last.id,1,KnowledgeData.Collection("满额编辑")))
        assertEquals(2000,db.knowledge().forBook(book).size);assertEquals(last.id,repo.submit(last))
    }

    @Test fun snapshotCapacityUsesStoredBytesAndRecropChargesOnlyItsDelta()=fixture{db,book->
        val repo=StudyRepository(db);val picture=jpeg(64);val smaller=jpeg(2);val larger=jpeg(128)
        assertTrue(smaller.size<picture.size&&picture.size<larger.size)
        val c=excerpt(book,picture);repo.submit(c);val old=repo.source(c.cardId!!)!!;val bytes=old.snapshot.size
        fillSnapshotBytes(db,StudyCapacity.MAX_SNAPSHOT_BYTES-bytes)
        assertEquals(32_000_000L,repo.snapshotBytes());assertEquals(32_000_000L,repo.observeSnapshotBytes().first())
        assertEquals(c.cardId,repo.submit(c));rejectStudy(db,excerpt(book,picture),"STUDY_SNAPSHOT_BUDGET")
        val reused=id();repo.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=c.cardId,nodeId=reused))
        fun recrop(image:ByteArray,revision:Long)=StudyCommand(id(),book,StudyAction.RECROP_EXCERPT,cardId=c.cardId,expectedRevision=revision,
            source=StudySourceDraft(book,0,old.let{CanvasBounds(it.left,it.top,it.right,it.bottom)},emptyList(),image,0))
        val same=recrop(picture,1);repo.submit(same);assertEquals(32_000_000L,repo.snapshotBytes())
        val unchanged=repo.source(c.cardId!!)!!.snapshot
        rejectStudy(db,recrop(larger,2),"STUDY_SNAPSHOT_BUDGET");assertArrayEquals(unchanged,repo.source(c.cardId!!)!!.snapshot)
        val shrink=recrop(smaller,2);repo.submit(shrink);val reduced=repo.source(c.cardId!!)!!.snapshot.size
        assertTrue(reduced<bytes);assertEquals(32_000_000L-bytes+reduced,repo.observeSnapshotBytes().first())
        repo.submit(StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=reused,expectedRevision=1))
        repo.submit(StudyCommand(id(),book,StudyAction.TRASH_CARD,cardId=c.cardId,expectedRevision=3))
        assertNotNull(db.study().card(c.cardId!!)!!.trashedAt);assertEquals(32_000_000L-bytes+reduced,repo.snapshotBytes())
        repo.submit(StudyCommand(id(),book,StudyAction.RESTORE_CARD,cardId=c.cardId,expectedRevision=4))
        repo.submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=c.cardId,expectedRevision=5,title="恢复后编辑"))
        assertEquals(32_000_000L-bytes+reduced,repo.observeSnapshotBytes().first())
    }

    @Test fun independentCopyChargesDistinctCardsOnceAndReplayAddsNothing()=fixture{db,book->
        val repo=StudyRepository(db);val c=excerpt(book,jpeg(16));repo.submit(c)
        repeat(2){repo.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=c.cardId,nodeId=id()))}
        db.withTransaction{repeat(197){card(db,book)}};val sourceBytes=repo.source(c.cardId!!)!!.snapshot.size
        fillSnapshotBytes(db,StudyCapacity.MAX_SNAPSHOT_BYTES-2L*sourceBytes)
        val scene=MapGraphAccess(db).read(book).single{it.ref.mapId==null};val op=id();val copies=MapEmbedRepository(db)
        val duplicate=copies.duplicate(scene.ref,scene.signature(),op)
        val copy=MapGraphAccess(db).read(book).single{it.ref==duplicate}
        assertEquals(2,copy.nodes.size);assertEquals(1,copy.nodes.mapNotNull{it.cardId}.distinct().size)
        assertEquals(199,db.study().cards(book).size);assertEquals(32_000_000L,repo.snapshotBytes())
        suspend fun reject(reason:String){val command=id();val cards=db.study().cards(book);val rows=knowledgeStamp(db.knowledge().forBook(book));val note=db.notes().note(book)
            val current=MapGraphAccess(db).read(book).single{it.ref.mapId==null}
            try{copies.duplicate(current.ref,current.signature(),command);fail()}catch(e:IllegalArgumentException){assertEquals(reason,e.message)}
            assertEquals(cards,db.study().cards(book));assertEquals(rows,knowledgeStamp(db.knowledge().forBook(book)))
            assertEquals(note,db.notes().note(book));assertEquals(32_000_000L,repo.snapshotBytes());assertNull(db.knowledge().receipt(command))}
        reject("STUDY_SNAPSHOT_BUDGET")
        repo.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="第200张"));reject("STUDY_CARD_BUDGET")
        assertEquals(duplicate,copies.duplicate(scene.ref,scene.signature(),op))
        assertEquals(200,db.study().cards(book).size);assertEquals(32_000_000L,repo.snapshotBytes())
    }

    @Test fun oversizedSingleSnapshotIsAConfirmedAtomicRejection()=fixture{db,book->
        val strokes=List(8){InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
            List(8192){i->InkSample((20+i%900).toFloat(),20f,i.toLong())})}
        assertTrue(InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Replace(emptyList(),strokes))) is InkCommitResult.Committed)
        assertTrue(InkPageFile("摘录原迹","",strokes,false,PaperStyle.BLANK).encode().size>1_800_000)
        rejectStudy(db,StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="过大来源",
            source=StudySourceDraft(book,1,CanvasBounds(0.0,0.0,1000.0,1414.0),strokes.map{it.id})),"STUDY_SNAPSHOT_TOO_LARGE")
        assertTrue(db.study().cards(book).isEmpty());assertEquals(0L,StudyRepository(db).snapshotBytes())
    }
}
