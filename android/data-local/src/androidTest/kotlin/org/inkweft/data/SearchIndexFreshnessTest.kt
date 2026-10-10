// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SearchIndexFreshnessTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking {
        val name="search-freshness-${id()}.db";val db=NoteDatabase.open(context,name)
        try{val page=WorkspaceRepository(db).create("合成搜索索引",false,PaperStyle.BLANK).id;block(db,page)}
        finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun freshnessFollowsInkObjectLayerAndPageChanges(){fixture{db,page->
        val pages=NotebookPages(db);assertFalse(pages.hasCurrentSearchText(page))
        assertTrue(pages.saveSearchText(page,0,"空页索引",0));assertTrue(pages.hasCurrentSearchText(page))
        val stroke=InkStroke(id(),InkPen.PEN,0xff222222.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,30f,0),InkSample(40f,50f,20)))
        assertTrue(InkRepository(db).save(CommitInk(id(),page,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
        assertFalse(pages.hasCurrentSearchText(page))
        assertTrue(pages.saveSearchText(page,1,"当前笔迹",0));assertTrue(pages.hasCurrentSearchText(page))
        PageObjectRepository(db).save(page,0,id(),emptyList(),expectedInk=1)
        assertFalse(pages.hasCurrentSearchText(page))
        assertTrue(pages.saveSearchText(page,1,"对象更新后",1));assertTrue(pages.hasCurrentSearchText(page))
        val meta=PageAuthoringRepository(db);val before=meta.readPage(page)
        meta.save(AuthoringScope.page(page,page),before,id(),before.state.withLayers(before.state.layers.add(UserLayer(id(),"第二层"))))
        assertFalse(pages.hasCurrentSearchText(page))
        assertTrue(pages.saveSearchText(page,1,"作者配置更新后",1,authoringRevision=before.revision+1))
        assertTrue(pages.hasCurrentSearchText(page))
        assertEquals(1,db.pages().arrange(page,0,1L));assertFalse(pages.hasCurrentSearchText(page))
        assertEquals(1,db.pages().arrange(page,0,null));assertTrue(pages.hasCurrentSearchText(page))
        assertFalse(pages.hasCurrentSearchText(id()))
    }}

    @Test fun checkingIndexNeverDecodesPayloadWhileFullAuthoringReadStillValidates(){fixture{db,page->
        val pages=NotebookPages(db)
        assertTrue(pages.saveSearchText(page,0,"已校对的独立索引",0))
        // Deliberately poison only a disposable test DB, bypassing application
        // writes. Freshness uses headers; opening/exporting still validates data.
        db.objects().put(PageObjectRow(page,1,byteArrayOf(0)))
        assertTrue(pages.hasCurrentSearchText(page))
        assertTrue(runCatching{PageAuthoringRepository(db).readPage(page)}.isFailure)
        db.openHelper.writableDatabase.execSQL("INSERT OR REPLACE INTO ink_pages(noteId,revision) VALUES (?,1)",arrayOf(page))
        assertFalse(pages.hasCurrentSearchText(page))
        db.pages().invalidateSearch(page);assertFalse(pages.hasCurrentSearchText(page))
    }}
}
