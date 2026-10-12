// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageCopyRepositoryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun stroke(x:Float=20f,world:Boolean=false)=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,
        listOf(InkSample(x,40f,0,.5f,world=world),InkSample(x+1,41f,1,.5f,world=world)),world)
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="page-copy-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("合成归属复制",false,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun pageCommand(db:NoteDatabase,page:String)=EditPage(id(),page,page,PageEditKind.COPY,
        InsertPages.orderHash(db.pages().list(page).map{it.id}),checkNotNull(db.ink().page(page)).revision,
        PageInsertLocation.END,null,id(),null,page)

    @Test fun bothCopyEntrypointsPreserveSharedCutIdentityLayersAnnotationsAndReceipts()=fixture{db,page->
        val a=stroke(20f);val b=stroke(40f);val hiddenLayerInk=stroke(60f);val deletedInk=stroke(80f);val history=stroke(100f)
        val shared=InkCut(id(),3f,listOf(EraserPoint(21f,40f),EraserPoint(41f,40f)))
        db.withTransaction{
            db.ink().insertPage(InkPageRow(page,7))
            listOf(a,b,hiddenLayerInk,deletedInk,history).forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,it!==history,1))}
            db.ink().insertCut(InkCutRow(shared.id,page,InkCutCodec.encode(shared),"${a.id},${b.id}",true,7))
        }
        val text=PageObject(id(),PageObjectKind.TEXT,text="有界来源",sourceStrokeIds=listOf(a.id,b.id))
        val retired=PageObject(id(),PageObjectKind.TEXT,text="历史转换",sourceStrokeIds=listOf(history.id),hidden=true)
        PageObjectRepository(db).save(page,0,id(),listOf(text,retired))
        val hidden=UserLayer(id(),"隐藏锁定层",visible=false,locked=true);val blank=DocumentWhitespace(id(),300.0)
        val annotations=listOf(BoundAnnotation(stroke(world=true),AnnotationTarget(AnnotationTargetKind.PAGE,page)),
            BoundAnnotation(stroke(world=true),AnnotationTarget(AnnotationTargetKind.WHITESPACE,blank.id)),
            BoundAnnotation(stroke(world=true),AnnotationTarget(AnnotationTargetKind.PAGE_OBJECT,text.id)))
        val memberships=listOf(a,b,history).map{LayerMembership(LayerContent(LayerContentKind.INK,it.id),UserLayers.DEFAULT_ID)}+
            LayerMembership(LayerContent(LayerContentKind.INK,hiddenLayerInk.id),hidden.id)+
            listOf(text,retired).map{LayerMembership(LayerContent(LayerContentKind.OBJECT,it.id),UserLayers.DEFAULT_ID)}+
            annotations.mapIndexed{i,it->LayerMembership(LayerContent(LayerContentKind.ANNOTATION,it.stroke.id),if(i==1)hidden.id else UserLayers.DEFAULT_ID)}
        val state=PageAuthoring(UserLayers(listOf(UserLayer(UserLayers.DEFAULT_ID,"基础层"),hidden),UserLayers.DEFAULT_ID,memberships,
            listOf(LayerContent(LayerContentKind.INK,deletedInk.id))),listOf(blank),annotations,listOf(AnnotationRegion(annotations.last().target)))
        val meta=PageAuthoringRepository(db);meta.save(AuthoringScope.page(page,page),meta.readPage(page),id(),state)
        assertTrue(NotebookPages(db).saveSearchText(page,7,"当前人工索引",authoringRevision=meta.readPage(page).revision))
        val before=PageAuthoringCodec.fingerprint(meta.readPage(page).state);val beforeBytes=checkNotNull(db.ink().stroke(a.id)).payload
        val notebookCommand=CopyNotebook(id(),page,id());val notebook=LibraryContentRepository(db).duplicate(notebookCommand)
        val command=pageCommand(db,page);val edited=PageEditingRepository(db).apply(command) as EditPageResult.Applied
        val allCopiedIds=mutableSetOf<String>();var firstMask:String?=null
        for(target in listOf(notebook.id,edited.resultPageId)){
            val identities=InkRepository.CopiedInk(page,target,7,4)
            val ink=InkRepository(db).readVisible(target);assertEquals(4,ink.size);assertEquals(4L,db.ink().page(target)?.revision)
            assertTrue(ink.none{it.id in listOf(a.id,b.id,hiddenLayerInk.id,deletedInk.id,history.id)})
            assertTrue(ink.all{allCopiedIds.add(it.id)})
            val one=ink.single{it.id==identities.strokeId(a.id)};val two=ink.single{it.id==identities.strokeId(b.id)}
            assertEquals(a.samples,one.samples);assertEquals(b.samples,two.samples)
            assertEquals(one.cuts.single().id,two.cuts.single().id);assertNotEquals(shared.id,one.cuts.single().id)
            assertEquals(shared.points,one.cuts.single().points);firstMask?.let{assertNotEquals(it,one.cuts.single().id)};firstMask=one.cuts.single().id
            val objectRow=PageObjectRepository(db).read(target).objects.single();assertNotEquals(text.id,objectRow.id)
            assertEquals(listOf(one.id,two.id),objectRow.sourceStrokeIds)
            val copy=meta.readPage(target);assertEquals(0L,copy.revision)
            val layer=copy.state.layers.layers.single{it.name==hidden.name};assertFalse(layer.visible);assertTrue(layer.locked);assertNotEquals(hidden.id,layer.id)
            assertEquals(layer.id,copy.state.layers.layer(LayerContent(LayerContentKind.INK,identities.strokeId(hiddenLayerInk.id)))?.id)
            assertTrue(copy.state.layers.isDeleted(LayerContent(LayerContentKind.INK,identities.strokeId(deletedInk.id))))
            assertEquals(5L,db.authoring().membershipCount(target))
            assertEquals(3,copy.state.annotations.size);assertTrue(copy.state.annotations.none{v->annotations.any{it.stroke.id==v.stroke.id}})
            assertEquals(target,copy.state.annotations.single{it.target.kind==AnnotationTargetKind.PAGE}.target.id)
            val copiedBlank=copy.state.blanks.single();assertNotEquals(blank.id,copiedBlank.id)
            assertEquals(copiedBlank.id,copy.state.annotations.single{it.target.kind==AnnotationTargetKind.WHITESPACE}.target.id)
            assertEquals(objectRow.id,copy.state.annotations.single{it.target.kind==AnnotationTargetKind.PAGE_OBJECT}.target.id)
            assertEquals(objectRow.id,copy.state.regions.single().target.id)
            assertEquals("当前人工索引",db.pages().search(target)?.text);assertEquals(4L,db.pages().search(target)?.inkRevision)
        }
        assertEquals(before,PageAuthoringCodec.fingerprint(meta.readPage(page).state));assertArrayEquals(beforeBytes,checkNotNull(db.ink().stroke(a.id)).payload)
        db.openHelper.writableDatabase.execSQL("UPDATE ink_strokes SET payload=zeroblob(3000000) WHERE id=?",arrayOf(a.id))
        assertEquals(notebook.id,LibraryContentRepository(db).duplicate(notebookCommand).id)
        assertTrue((PageEditingRepository(db).apply(command) as EditPageResult.Applied).replayed)
    }

    @Test fun missingOwnershipOnHiddenHistoryRejectsBothCopiesAndLeavesRetryableReceipts()=fixture{db,page->
        val live=stroke();val hidden=stroke(50f)
        db.withTransaction{
            db.ink().insertPage(InkPageRow(page,1))
            listOf(live,hidden).forEach{db.ink().insertStroke(InkStrokeRow(it.id,page,InkStrokeCodec.encodeStored(it),2,it===live,1))}
            PageLayerStore(db).ensureHeader(page,page)
        }
        val sql=db.openHelper.writableDatabase;sql.execSQL("DELETE FROM page_layer_memberships WHERE pageId=? AND contentId=?",arrayOf(page,hidden.id))
        val book=CopyNotebook(id(),page,id());val command=pageCommand(db,page)
        assertEquals("LAYER_CONTENT_UNASSIGNED",runCatching{LibraryContentRepository(db).duplicate(book)}.exceptionOrNull()?.message)
        assertNull(db.notes().note(book.destinationId));assertNull(db.libraryContent().receipt(book.commandId))
        assertEquals("LAYER_CONTENT_UNASSIGNED",runCatching{PageEditingRepository(db).apply(command)}.exceptionOrNull()?.message)
        assertNull(db.pages().get(checkNotNull(command.newPageId)));assertNull(db.pageEdits().receipt(command.commandId))
        assertEquals(2,InkTotals.read(sql,page).strokeCount)
        db.authoring().assign(PageLayerMembershipRow(page,"INK",hidden.id,UserLayers.DEFAULT_ID))
        assertEquals(book.destinationId,LibraryContentRepository(db).duplicate(book).id)
        assertTrue(PageEditingRepository(db).apply(command) is EditPageResult.Applied)
    }

    @Test fun legacyDefaultOwnershipCopiesWithoutMaterializingOrChangingSourceHeader()=fixture{db,page->
        val original=stroke()
        db.withTransaction{db.ink().insertPage(InkPageRow(page,1));db.ink().insertStroke(InkStrokeRow(original.id,page,InkStrokeCodec.encodeStored(original),2,true,1))}
        assertNull(db.authoring().get("PAGE",page));assertEquals(0L,db.authoring().membershipCount(page))
        val copied=LibraryContentRepository(db).duplicate(CopyNotebook(id(),page,id()))
        assertNull(db.authoring().get("PAGE",page));assertEquals(0L,db.authoring().membershipCount(page))
        val ink=InkRepository(db).readVisible(copied.id).single();assertEquals(original.samples,ink.samples)
        val state=PageAuthoringRepository(db).readPage(copied.id).state
        assertEquals(UserLayers.DEFAULT_ID,state.layers.layer(LayerContent(LayerContentKind.INK,ink.id))?.id)
    }
}
