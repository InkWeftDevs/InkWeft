// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class AuthoringProjectionRegressionTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke()=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(20f,30f,0,.4f),InkSample(40f,50f,20,.6f)))
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="author-projection-${id()}.db"
        val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("合成图层投影",false,PaperStyle.BLANK).id)}
        finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun seed(db:NoteDatabase,page:String,strokes:List<InkStroke>,visible:Int,state:PageAuthoring){
        db.withTransaction {
            if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))else assertEquals(1,db.ink().compareAndSet(page,0,1))
            strokes.forEachIndexed{i,s->db.ink().insertStroke(InkStrokeRow(s.id,page,InkStrokeCodec.encode(s),s.samples.size,i<visible,1))}
            db.authoring().put(PageAuthoringRow("PAGE",page,page,0,PageAuthoringCodec.encode(state)))
        }
    }

    @Test fun firstStrokePast22000RetainedRowsCommitsAndKeepsHiddenAuthorBytes(){fixture{db,page->
        val originals=List(UserLayers.LEGACY_MAX_CONTENT){stroke()}
        val state=PageAuthoring(UserLayers.legacy(originals.map{LayerContent(LayerContentKind.INK,it.id)}))
        seed(db,page,originals,1000,state)
        val meta=PageAuthoringRepository(db);val before=meta.readPage(page);val added=stroke()
        assertEquals(22000,db.ink().strokeIds(page).size)
        assertEquals(InkCommitResult.Committed(2),InkRepository(db).save(CommitInk(id(),page,1,InkMutation.Add(added),before.state.layers.writeScope(before.revision))))
        val saved=meta.readPage(page)
        assertEquals(22001,saved.state.layers.memberships.size)
        assertEquals(0x49574134,ByteBuffer.wrap(checkNotNull(db.authoring().get("PAGE",page)).payload).int)
        assertArrayEquals(InkStrokeCodec.encode(originals.last()),checkNotNull(db.ink().stroke(originals.last().id)).payload)
        val ink=InkRepository(db).read(page);assertEquals(22001,ink.strokes.size)
        assertEquals(1001,InkSession(ink).visibleDraft().size)
    }}

    @Test fun projectedIdsIncludeHistoryButFullInkAndObjectReadsStillValidatePayloads(){fixture{db,page->
        val a=stroke();val b=stroke();val refs=listOf(a,b).map{LayerContent(LayerContentKind.INK,it.id)}
        seed(db,page,listOf(a,b),1,PageAuthoring(UserLayers.legacy(refs)))
        assertEquals(listOf(a,b).sortedBy{it.id}.map{it.id},db.ink().strokeIds(page))
        // Only this disposable fixture is poisoned, outside application writes.
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=? WHERE id=?",arrayOf(byteArrayOf(0),b.id))
        assertTrue(PageAuthoringRepository(db).readPage(page).state.layers.owns(refs[1]))
        assertTrue(runCatching{InkRepository(db).read(page)}.isFailure)
        db.authoring().put(PageAuthoringRow("PAGE",page,page,1,PageAuthoringCodec.encode(PageAuthoring(UserLayers.legacy(refs.take(1))))))
        assertEquals("LAYER_CONTENT_UNASSIGNED",runCatching{PageAuthoringRepository(db).readPage(page)}.exceptionOrNull()?.message)
        db.authoring().put(PageAuthoringRow("PAGE",page,page,2,PageAuthoringCodec.encode(PageAuthoring(UserLayers(memberships=listOf(LayerMembership(refs[0],UserLayers.DEFAULT_ID)),deleted=listOf(refs[1]))))))
        assertTrue(PageAuthoringRepository(db).readPage(page).state.layers.isDeleted(refs[1]))
        db.objects().put(PageObjectRow(page,1,byteArrayOf(0)))
        assertTrue(runCatching{PageAuthoringRepository(db).readPage(page)}.isFailure)
    }}

    @Test fun bulkReplacementInheritsEachSourceLayerAndLockedBatchRemainsAtomic(){fixture{db,page->
        val originals=List(InkSelectionEdit.MAX_SELECTED){stroke()};val second=UserLayer(id(),"第二层")
        val refs=originals.map{LayerContent(LayerContentKind.INK,it.id)}
        val state=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),second),UserLayers.DEFAULT_ID,
            refs.mapIndexed{i,c->LayerMembership(c,if(i%2==0)UserLayers.DEFAULT_ID else second.id)}))
        seed(db,page,originals,originals.size,state)
        val repo=InkRepository(db);val meta=PageAuthoringRepository(db);val added=List(originals.size){stroke()}
        val change=InkMutation.Replace(originals.map{it.id},added,originals.map{it.id})
        assertEquals(InkCommitResult.Committed(2),repo.save(CommitInk(id(),page,1,change,state.layers.writeScope(0))))
        val current=meta.readPage(page)
        added.forEachIndexed{i,s->assertEquals(if(i%2==0)UserLayers.DEFAULT_ID else second.id,current.state.layers.layer(LayerContent(LayerContentKind.INK,s.id))?.id)}
        originals.forEach{s->assertArrayEquals(InkStrokeCodec.encode(s),checkNotNull(db.ink().stroke(s.id)).payload)}
        meta.save(AuthoringScope.page(page,page),current,id(),current.state.withLayers(current.state.layers.update(second.copy(locked=true))))
        val locked=meta.readPage(page)
        assertEquals(InkCommitResult.Rejected,repo.save(CommitInk(id(),page,2,InkMutation.Visibility(added.map{it.id},false),locked.state.layers.writeScope(locked.revision))))
        assertEquals(2L,repo.read(page).revision);assertEquals(added.map{it.id}.toSet(),InkSession(repo.read(page)).visibleDraft().map{it.id}.toSet())
    }}
}
