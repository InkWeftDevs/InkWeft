// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real ink rows at three scales; the layer editor never loads their geometry or complete ID set. */
class PageLayerEditorTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun ref(index:Int)=LayerContent(LayerContentKind.INK,UUID(319,index.toLong()+1).toString())
    private fun header(state:PageAuthoring)=PageAuthoringCodec.decodeHeader(PageAuthoringCodec.encodeHeader(state))
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val name="layer-editor-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("合成分批图层",false,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun seed(db:NoteDatabase,page:String,from:Int,to:Int,layer:String)=db.withTransaction {
        if(db.ink().page(page)==null)db.ink().insertPage(InkPageRow(page,1))
        val sql=db.openHelper.writableDatabase
        sql.compileStatement("INSERT INTO ink_strokes VALUES(?,?,?,2,?,1)").use{stroke->
            sql.compileStatement("INSERT INTO page_layer_memberships VALUES(?,'INK',?,?)").use{membership->
                for(index in from until to){
                    val identity=ref(index).id
                    val ink=InkStroke(identity,InkPen.PEN,0xff102030.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0),InkSample(40f,50f,10)))
                    stroke.clearBindings();stroke.bindString(1,identity);stroke.bindString(2,page);stroke.bindBlob(3,InkStrokeCodec.encodeStored(ink));stroke.bindLong(4,if(index%3==0)0 else 1);stroke.executeInsert()
                    membership.clearBindings();membership.bindString(1,page);membership.bindString(2,identity);membership.bindString(3,layer);membership.executeInsert()
                }
            }
        }
    }
    private fun count(db:NoteDatabase,page:String,layer:String?):Long = db.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM page_layer_memberships WHERE pageId=? AND layerId IS ?",arrayOf(page,layer)
    ).use{it.moveToFirst();it.getLong(0)}

    @Test fun projectionJournalAndBulkUndoKeepAConstantLoadedIdentityWindowAtThreeScales()=fixture{db,page->
        val source=UserLayer(id(),"保留原迹");val target=UserLayer(id(),"转入层");val scope=AuthoringScope.page(page,page)
        val repository=PageAuthoringRepository(db)
        repository.storePage(page,page,0,PageAuthoring(UserLayers().add(source).add(target).select(UserLayers.DEFAULT_ID)))
        var previous=0;var encodedSize:Int?=null
        for(total in listOf(1_000,20_000,100_000)){
            seed(db,page,previous,total,source.id);previous=total
            val started=System.nanoTime();val before=repository.readEditor(scope,List(32,::ref));val elapsed=System.nanoTime()-started
            assertEquals(32,before.state.layers.memberships.size);assertEquals(total.toLong(),before.layerCounts[source.id]);assertEquals(total.toLong(),before.inkLayerCounts[source.id])
            val size=PageAuthoringCodec.encode(before.state).size
            encodedSize?.let{assertEquals(it,size)};encodedSize=size
            val after=before.state.withLayers(before.state.layers.update(target.copy(name="待保存改名")))
            val pending=AuthoringPending(scope,id(),before,after)
            repository.stagePending(pending)
            try{
                val file=java.io.File(db.checkpointRoot,"authoring/PAGE-$page.iwpending")
                assertTrue(file.length()<16_000)
                val recovered=checkNotNull(repository.pending(scope));assertTrue(recovered.before.editorProjection)
                assertEquals(32,recovered.before.state.layers.memberships.size)
                val evidence=org.json.JSONObject().put("storedInk",total).put("loadedMemberships",32).put("authorBytes",size)
                    .put("pendingBytes",file.length()).put("readMs",elapsed/1_000_000.0)
                    .put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{putString("stream","AUTHORING_PROJECTION_EVIDENCE $evidence\n")})
            }finally{repository.clearPending(scope,pending.commandId)}
        }
        val before=repository.readEditor(scope,listOf(ref(0),ref(99_999)))
        val move=id();val moved=before.state.withLayers(before.state.layers.transfer(listOf(ref(0),ref(99_999)),target.id))
        repository.save(scope,before,move,moved,ownershipEdit=LayerOwnershipEdit.Layer(source.id,target.id))
        assertEquals(0L,count(db,page,source.id));assertEquals(100_000L,count(db,page,target.id))
        // Undo with a different viewport restores every offscreen and invisible historical row too.
        val current=repository.readEditor(scope,listOf(ref(10),ref(20)))
        repository.save(scope,current,id(),header(before.state),ownershipEdit=LayerOwnershipEdit.Replay(move,true))
        assertEquals(100_000L,count(db,page,source.id));assertEquals(0L,count(db,page,target.id))
        val restored=repository.readEditor(scope,emptyList());assertTrue(restored.state.layers.memberships.isEmpty())
        repository.save(scope,restored,id(),header(moved),ownershipEdit=LayerOwnershipEdit.Replay(move,false))
        assertEquals(100_000L,count(db,page,target.id))
        val deleteBefore=repository.readEditor(scope,listOf(ref(30)));val deleted=deleteBefore.state.withLayers(deleteBefore.state.layers.remove(target.id,LayerDelete.DeleteContents));val deletion=id()
        repository.save(scope,deleteBefore,deletion,deleted,ownershipEdit=LayerOwnershipEdit.Layer(target.id,null),retainedHistory=listOf(move))
        assertEquals(100_000L,count(db,page,null))
        repository.save(scope,repository.readEditor(scope),id(),header(deleteBefore.state),ownershipEdit=LayerOwnershipEdit.Replay(deletion,true))
        assertEquals(100_000L,count(db,page,target.id));assertEquals(0L,count(db,page,null))
        repository.validateStored(checkNotNull(db.authoring().get("PAGE",page)))
    }

    @Test fun projectedReadsArePureAndSelectedBeautyOwnershipStillMovesTogether()=fixture{db,page->
        val repository=PageAuthoringRepository(db);val scope=AuthoringScope.page(page,page)
        assertNull(db.authoring().get("PAGE",page));repository.readEditor(scope);assertNull(db.authoring().get("PAGE",page))
        val stroke=InkStroke(ref(0).id,InkPen.PEN,0xff102030.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0)))
        InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke)))
        val beauty=PageObject(id(),PageObjectKind.TEXT,text="合成",sourceStrokeIds=listOf(stroke.id))
        PageObjectRepository(db).save(page,0,id(),listOf(beauty))
        val initial=repository.readEditor(scope);val layer=UserLayer(id(),"目标")
        repository.save(scope,initial,id(),initial.state.withLayers(initial.state.layers.add(layer)))
        val current=repository.readEditor(scope)
        assertTrue(current.state.layers.owns(ref(0)))
        val moved=current.state.withLayers(current.state.layers.transfer(listOf(ref(0)),layer.id))
        val command=id()
        try{repository.save(scope,current,command,moved);fail("A retained beauty source cannot move alone")}catch(e:IllegalArgumentException){assertEquals("LAYER_BEAUTY_MOVE_TOGETHER",e.message)}
        assertNull(db.authoring().receipt(command));assertEquals(UserLayers.DEFAULT_ID,repository.readEditor(scope).state.layers.layer(ref(0))?.id)
        val objectRef=LayerContent(LayerContentKind.OBJECT,beauty.id)
        repository.save(scope,current,id(),current.state.withLayers(current.state.layers.transfer(listOf(ref(0),objectRef),layer.id)))
        assertEquals(layer.id,repository.readEditor(scope).state.layers.layer(objectRef)?.id)
    }

    @Test fun pendingReplaySurvivesLostReplyAndArchiveKeepsAuthorRowsWithoutSessionDeltas()=fixture{db,page->
        val repository=PageAuthoringRepository(db);val scope=AuthoringScope.page(page,page);val source=UserLayer(id(),"可恢复层")
        repository.storePage(page,page,0,PageAuthoring(UserLayers().add(source).select(UserLayers.DEFAULT_ID)));seed(db,page,0,12,source.id)
        val before=repository.readEditor(scope,listOf(ref(0)));val deleted=before.state.withLayers(before.state.layers.remove(source.id,LayerDelete.DeleteContents));val command=id()
        repository.save(scope,before,command,deleted,ownershipEdit=LayerOwnershipEdit.Layer(source.id,null))
        val saved=repository.readEditor(scope);val pending=AuthoringPending(scope,id(),saved,header(before.state),-1,ownershipEdit=LayerOwnershipEdit.Replay(command,true),retainedHistory=listOf(command))
        repository.stagePending(pending)
        val databaseName=checkNotNull(db.openHelper.databaseName);db.close()
        val durable=NoteDatabase.open(context,databaseName)
        try{
            val reopened=PageAuthoringRepository(durable);val recovered=checkNotNull(reopened.pending(scope))
            assertEquals(pending.ownershipEdit,recovered.ownershipEdit)
            try{PageAuthoringRepository(durable){if(it==AuthoringFault.AFTER_TRANSACTION)error("lost reply")}.save(scope,recovered.before,recovered.commandId,recovered.after,recovered.afterPayload(),recovered.ownershipEdit,recovered.retainedHistory);fail()}catch(e:IllegalStateException){assertEquals("lost reply",e.message)}
            assertEquals(2L,reopened.confirmed(recovered))
            assertEquals(2L,reopened.save(scope,recovered.before,recovered.commandId,recovered.after,recovered.afterPayload(),recovered.ownershipEdit,recovered.retainedHistory))
            assertEquals(12L,count(durable,page,source.id))
            val targetName="layer-editor-restore-${id()}.db";val target=NoteDatabase.open(context,targetName)
            try{
                LibraryBackupRepository(context,durable).snapshot().use{snapshot->
                    val restore=LibraryBackupRepository(context,target)
                    snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}
                }
                assertEquals(12L,count(target,page,source.id))
                assertEquals(0L,target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM page_layer_changes").use{it.moveToFirst();it.getLong(0)})
                assertEquals(2L,PageAuthoringRepository(target).readEditor(scope).revision)
            }finally{target.close();context.deleteDatabase(targetName)}
        }finally{repository.clearPending(scope,pending.commandId);durable.close()}
    }
}
