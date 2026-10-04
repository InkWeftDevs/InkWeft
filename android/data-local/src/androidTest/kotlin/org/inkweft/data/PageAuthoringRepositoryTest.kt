// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class PageAuthoringRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun ink(world:Boolean=false)=InkStroke(id(),InkPen.PEN,0xff223344.toInt(),3f,InkTool.STYLUS,listOf(InkSample(40f,80f,0,world=world),InkSample(100f,110f,20,world=world)),world)
    private fun fixture(block:suspend(NoteDatabase,NoteDatabase)->Unit)=runBlocking{
        val a="layers-${id()}.db";val b="layers-restore-${id()}.db";val source=NoteDatabase.open(context,a);val target=NoteDatabase.open(context,b)
        try{block(source,target)}finally{source.close();target.close();context.deleteDatabase(a);context.deleteDatabase(b)}
    }
    private suspend fun book(db:NoteDatabase)=WorkspaceRepository(db).create("合成三层两留白",false,PaperStyle.BLANK).id
    private suspend fun configure(db:NoteDatabase,page:String):AuthoringSnapshot {
        val repo=PageAuthoringRepository(db);val before=repo.readPage(page)
        val hidden=UserLayer(id(),"隐藏答案");val locked=UserLayer(id(),"锁定原迹")
        val next=before.state.withLayers(before.state.layers.add(hidden).add(locked).update(locked.copy(locked=true)).update(hidden.copy(visible=false)).select(UserLayers.DEFAULT_ID))
            .withBlanks(listOf(DocumentWhitespace(id(),300.0),DocumentWhitespace(id(),800.0,450.0)))
        repo.save(AuthoringScope.page(page,page),before,id(),next);return repo.readPage(page)
    }
    @Test fun capturedScopeRejectsStaleOrMissingLayerButConsecutivePensDoNotConflict()=fixture{db,_->
        val page=book(db);val configured=configure(db,page);val repo=InkRepository(db)
        val scope=configured.state.layers.writeScope(configured.revision)
        val first=CommitInk(id(),page,0,InkMutation.Add(ink()),scope)
        val second=CommitInk(id(),page,1,InkMutation.Add(ink()),scope)
        assertTrue(repo.save(first) is InkCommitResult.Committed);assertTrue(repo.save(second) is InkCommitResult.Committed)
        assertEquals(configured.revision,PageAuthoringRepository(db).readPage(page).revision)
        assertEquals(InkCommitResult.Rejected,repo.save(CommitInk(id(),page,2,InkMutation.Add(ink()))))
        assertEquals(InkCommitResult.Conflict,repo.save(CommitInk(id(),page,2,InkMutation.Add(ink()),scope.copy(configurationRevision=scope.configurationRevision-1))))
        assertEquals(2,repo.read(page).strokes.size)
    }
    @Test fun oldReceiptReplaysAfterLayerLockAndStaleMetadataCannotEraseNewMembership()=fixture{db,_->
        val page=book(db);val state=configure(db,page);val meta=PageAuthoringRepository(db);val repo=InkRepository(db)
        val first=CommitInk(id(),page,0,InkMutation.Add(ink()),state.state.layers.writeScope(state.revision));repo.save(first)
        try{meta.save(AuthoringScope.page(page,page),state,id(),state.state);fail()}catch(_:IllegalArgumentException){}
        val fresh=meta.readPage(page);val other=UserLayer(id(),"保留可写")
        val next=fresh.state.withLayers(fresh.state.layers.add(other).update(fresh.state.layers.layers.first{it.id==UserLayers.DEFAULT_ID}.copy(locked=true)))
        meta.save(AuthoringScope.page(page,page),fresh,id(),next)
        assertEquals(InkCommitResult.Committed(1),repo.save(first))
        assertNotNull(meta.readPage(page).state.layers.layer(LayerContent(LayerContentKind.INK,(first.mutation as InkMutation.Add).stroke.id)))
    }
    @Test fun hiddenLockedMembershipPersistsAndWholeMixedEditIsRejected()=fixture{db,_->
        val page=book(db);val repo=InkRepository(db);val a=ink();repo.save(CommitInk(id(),page,0,InkMutation.Add(a)))
        val meta=PageAuthoringRepository(db);val base=meta.readPage(page);val layer=UserLayer(id(),"第二层")
        meta.save(AuthoringScope.page(page,page),base,id(),base.state.withLayers(base.state.layers.add(layer)))
        val secondState=meta.readPage(page);val b=ink();repo.save(CommitInk(id(),page,1,InkMutation.Add(b),secondState.state.layers.writeScope(secondState.revision)))
        val current=meta.readPage(page);meta.save(AuthoringScope.page(page,page),current,id(),current.state.withLayers(current.state.layers.update(layer.copy(locked=true)).select(UserLayers.DEFAULT_ID)))
        val locked=meta.readPage(page)
        assertEquals(InkCommitResult.Rejected,repo.save(CommitInk(id(),page,2,InkMutation.Visibility(listOf(a.id,b.id),false),locked.state.layers.writeScope(locked.revision))))
        assertEquals(2,InkSession(repo.read(page)).visibleDraft().size)
    }
    @Test fun rollbackAndUnknownReceiptKeepLayerAndInkAtomic()=fixture{db,_->
        val page=book(db);val initial=configure(db,page);val meta=PageAuthoringRepository(db);val state=initial.state
        val command=id();val after=state.withLayers(state.layers.add(UserLayer(id(),"故障层")))
        try{PageAuthoringRepository(db){if(it==AuthoringFault.BEFORE_RECEIPT)error("fault")}.save(AuthoringScope.page(page,page),initial,command,after);fail()}catch(_:IllegalStateException){}
        assertEquals(PageAuthoringCodec.fingerprint(state),PageAuthoringCodec.fingerprint(meta.readPage(page).state))
        try{PageAuthoringRepository(db){if(it==AuthoringFault.AFTER_TRANSACTION)error("lost reply")}.save(AuthoringScope.page(page,page),initial,command,after);fail()}catch(_:IllegalStateException){}
        assertEquals(initial.revision+1,meta.save(AuthoringScope.page(page,page),initial,command,after))
        val current=meta.readPage(page);val stroke=ink();val write=CommitInk(id(),page,0,InkMutation.Add(stroke),current.state.layers.writeScope(current.revision))
        try{InkRepository(db){if(it==InkFaultPoint.BEFORE_RECEIPT)error("fault")}.save(write);fail()}catch(_:IllegalStateException){}
        assertTrue(InkRepository(db).read(page).strokes.isEmpty());assertNull(meta.readPage(page).state.layers.layer(LayerContent(LayerContentKind.INK,stroke.id)))
    }
    @Test fun nonemptyLayerDeletionUndoBackupAndEditableCopyKeepHiddenPayloads()=fixture{db,target->
        val page=book(db);val meta=PageAuthoringRepository(db);var snapshot=configure(db,page)
        val hidden=snapshot.state.layers.layers.first{it.name=="隐藏答案"}
        var state=snapshot.state.withLayers(snapshot.state.layers.update(hidden.copy(visible=true)).select(hidden.id))
        val annotation=BoundAnnotation(ink(true),AnnotationTarget(AnnotationTargetKind.WHITESPACE,state.blanks.first().id))
        state=state.add(annotation)
        state=state.withLayers(state.layers.update(hidden.copy(visible=false)))
        meta.save(AuthoringScope.page(page,page),snapshot,id(),state);snapshot=meta.readPage(page)
        assertEquals(3,snapshot.state.layers.layers.size);assertEquals(2,snapshot.state.blanks.size);assertTrue(snapshot.state.visibleAnnotations().isEmpty())
        val copy=LibraryContentRepository(db).duplicate(CopyNotebook(id(),page,id()))
        val copied=meta.readPage(copy.id).state;assertEquals(3,copied.layers.layers.size);assertEquals(2,copied.blanks.size);assertEquals(1,copied.annotations.size);assertTrue(copied.visibleAnnotations().isEmpty())
        assertNotEquals(annotation.stroke.id,copied.annotations.single().stroke.id)
        LibraryBackupRepository(context,db).snapshot().use{backup->val r=LibraryBackupRepository(context,target);backup.file.inputStream().use{r.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,r.restore(it))}}
        assertEquals(PageAuthoringCodec.fingerprint(snapshot.state),PageAuthoringCodec.fingerprint(PageAuthoringRepository(target).readPage(page).state))
        val deleted=snapshot.state.withLayers(snapshot.state.layers.remove(hidden.id,LayerDelete.DeleteContents))
        meta.save(AuthoringScope.page(page,page),snapshot,id(),deleted)
        assertEquals(1,meta.readPage(page).state.annotations.size)
        val removed=meta.readPage(page);meta.save(AuthoringScope.page(page,page),removed,id(),snapshot.state)
        assertEquals(PageAuthoringCodec.fingerprint(snapshot.state),PageAuthoringCodec.fingerprint(meta.readPage(page).state))
    }
    @Test fun removedStructuralOccurrenceKeepsAnnotationOwnerThroughCompleteBackup()=fixture{db,target->
        val book=book(db);val map=id();val node=id();val knowledge=KnowledgeRepository(db)
        knowledge.submit(KnowledgeCommand(id(),book,map,0,KnowledgeData.MapDefinition("结构图",structures=listOf(MapStructure(node,null,"主题",0.0,0.0)))))
        val scope=AuthoringScope(book,AuthoringScopeKind.MAP,map);val meta=PageAuthoringRepository(db);val before=meta.read(scope)
        val at=AnnotationTarget(AnnotationTargetKind.MAP_OCCURRENCE,node)
        val state=before.state.add(BoundAnnotation(ink(true),at,280.0)).withRegion(AnnotationRegion(at))
        meta.save(scope,before,id(),state)
        val row=checkNotNull(db.knowledge().get(map))
        knowledge.submit(KnowledgeCommand(id(),book,map,row.revision,KnowledgeData.MapDefinition("结构图")))
        assertTrue(StudyRepository(db).readGraph(book,map).nodes.none{it.id==node})
        LibraryBackupRepository(context,db).snapshot().use{backup->val restore=LibraryBackupRepository(context,target);backup.file.inputStream().use{restore.inspect(it)}.use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(it))}}
        assertEquals(PageAuthoringCodec.fingerprint(state),PageAuthoringCodec.fingerprint(PageAuthoringRepository(target).read(scope).state))
    }
    @Test fun sourceCaptureRejectsChangedOrHiddenAuthoringButReplaysConfirmedReceipt()=fixture{db,_->
        val page=book(db);val stroke=ink();InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke)))
        val meta=PageAuthoringRepository(db);val before=meta.readPage(page);val study=StudyRepository(db)
        fun request(scope:Long?)=StudyCommand(id(),page,StudyAction.CREATE_EXCERPT,cardId=id(),title="受图层约束的摘录",source=StudySourceDraft(page,1,stroke.bounds(),listOf(stroke.id),authoringRevision=scope))
        val confirmed=request(0);study.submit(confirmed)
        val next=before.state.withLayers(before.state.layers.add(UserLayer(id(),"备用层")).update(before.state.layers.layers.single().copy(visible=false)))
        meta.save(AuthoringScope.page(page,page),before,id(),next)
        val stale=request(0);assertEquals(StudyOutcome.Rejected("SOURCE_AUTHORING_CHANGED"),study.outcome(stale));assertNull(study.lookup(stale))
        assertEquals(StudyOutcome.Rejected("SOURCE_AUTHORING_CHANGED"),study.outcome(request(null)))
        assertEquals(StudyOutcome.Rejected("SOURCE_LAYER_HIDDEN"),study.outcome(request(1)))
        assertEquals(confirmed.cardId,study.submit(confirmed));assertEquals(1,db.study().cards(page).size)
    }
    @Test fun lateSearchIndexCannotRepublishWordsFromAHiddenLayer()=fixture{db,_->
        val page=book(db);val stroke=ink();InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke)))
        val pages=NotebookPages(db);val meta=PageAuthoringRepository(db);val before=meta.readPage(page)
        assertTrue(pages.saveSearchText(page,1,"旧可见答案",0,"OCR",0))
        val after=before.state.withLayers(before.state.layers.add(UserLayer(id(),"可写备用层")).update(before.state.layers.layers.single().copy(visible=false)))
        meta.save(AuthoringScope.page(page,page),before,id(),after)
        assertNull(pages.searchText(page))
        assertFalse(pages.saveSearchText(page,1,"迟到的隐藏答案",0,"OCR",0))
        assertFalse(pages.saveSearchText(page,1,"无作者scope的旧任务",0,"MANUAL"))
        assertNull(pages.searchText(page))
        assertTrue(pages.saveSearchText(page,1,"当前可见范围人工文字",0,"MANUAL",1))
        assertEquals("当前可见范围人工文字",pages.searchText(page)!!.text)
    }
    @Test fun source14ArchiveIsNotPromotedTwiceAfterAddingLayerTables()=fixture{db,target->
        val page=book(db);val stroke=ink();InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke)))
        val schema=LibraryBackupRepository.SCHEMA_V14;val sql=db.openHelper.writableDatabase
        val rows=object:LibraryArchive.Rows {
            override fun count(table:Int)=sql.query("SELECT COUNT(*) FROM `${schema[table].name}`").use{it.moveToFirst();it.getLong(0)}
            override fun visit(table:Int,accept:(List<Any?>)->Unit){val t=schema[table];sql.query("SELECT * FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`$it`"}).use{c->while(c.moveToNext())accept(t.columns.mapIndexed{i,col->if(c.isNull(i))null else when(col.kind){'S'->c.getString(i);'B'->c.getBlob(i);'F'->c.getDouble(i);else->c.getLong(i)}})}}
        }
        val bytes=ByteArrayOutputStream().also{LibraryArchive.write(it,schema,rows,0)}.toByteArray()
        val restore=LibraryBackupRepository(context,target);restore.inspect(bytes.inputStream()).use{assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(it))}
        assertArrayEquals(InkStrokeCodec.encode(stroke),InkStrokeCodec.encode(InkRepository(target).read(page).strokes.single().stroke))
        assertTrue(PageAuthoringRepository(target).readPage(page).state.legacy)
    }
    @Test fun scopedCheckpointRoundTripPreservesLayerAcrossReopen()=fixture{db,_->
        val page=book(db);val configured=configure(db,page);val stroke=ink();val scope=configured.state.layers.writeScope(configured.revision)
        val repo=InkRepository(db);repo.checkpoint(page,stroke,scope)
        val recovered=repo.recoverScopedCheckpoints(page).single();assertEquals(scope,recovered.layerScope);assertArrayEquals(InkStrokeCodec.encode(stroke),InkStrokeCodec.encode(recovered.stroke))
        val command=CommitInk(id(),page,0,InkMutation.Add(recovered.stroke),recovered.layerScope);assertTrue(repo.save(command) is InkCommitResult.Committed);assertTrue(repo.recoverScopedCheckpoints(page).isEmpty())
    }
}
