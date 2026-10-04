// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Opt-in isolated-emulator run. No clearing, real-device execution, or smaller fallback sample. */
class SixBatchAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun notebook()=ViewModelProvider(compose.activity)[NotebookViewModel::class.java]
    private fun workspace()=ViewModelProvider(compose.activity)[WorkspaceViewModel::class.java]
    private fun waitFor(tag:String){compose.waitUntil(60_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()}
    private fun tap(tag:String){compose.revealAction(tag);waitFor(tag);val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()}
        compose.waitUntil(60_000){runCatching{node.assertIsEnabled()}.isSuccess};node.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()}
    private fun settled(book:String){compose.waitUntil(60_000){compose.runOnIdle{notebook().ui.value.selectedId==book&&app.navigationReady.value}}}
    private fun shot(f:SixBatchFixture,name:String){
        compose.waitForIdle();val bitmap=checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try{File(f.root,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }

    @Test fun prepareFullSizeBaselineAndExerciseReadingStructureAndCrossBookReturn(){
        val f=SixBatchFixture.create(app)
        runBlocking{f.seed()}
        runBlocking{f.step("native-full-pressure-page-load-scroll-and-pinch"){
            val note=checkNotNull(app.repository.read(f.books[0]))
            compose.runOnIdle{notebook().select(note)};settled(note.id);waitFor("continuous-pages");tap("quick-readonly")
            for(index in listOf(0,5,11)){compose.onNodeWithTag("continuous-pages").performScrollToIndex(index);compose.waitForIdle()}
            waitFor("continuous-ink-12")
            compose.waitUntil(60_000){compose.runOnIdle{runCatching{
                ViewModelProvider(compose.activity)["ink-${f.stressPage}",InkViewModel::class.java].ui.value.let{!it.loading&&it.strokes.size==1000}
            }.getOrDefault(false)}}
            for(expand in listOf(true,false)){
                compose.onNodeWithTag("continuous-pages").performTouchInput{
                    val y=height*.5f;val x=width*.5f;val start=if(expand).12f else .22f
                    down(0,Offset(x-width*start,y));down(1,Offset(x+width*start,y))
                    repeat(8){i->val d=width*(start+(i+1)*(if(expand).01f else -.017f))
                        updatePointerTo(0,Offset(x-d,y));updatePointerTo(1,Offset(x+d,y));move(30)}
                    up(1);up(0)
                };compose.waitForIdle()
            }
            shot(f,"05-layered-1000-stroke-pressure")
            compose.onNodeWithTag("continuous-pages").performScrollToIndex(0);compose.waitForIdle();shot(f,"01-twelve-page-material")
        }}
        runBlocking{f.step("native-read-write-outline-whole-branch-undo-redo"){
            val note=checkNotNull(app.repository.read(f.books[0]))
            tap("quick-study");tap("study-direct-outline");tap("study-collapse-all");tap("exit-readonly")
            val map=f.manifest.getJSONArray("maps").getJSONObject(0)
            val order=app.study.readGraph(note.id).orderedNodeIds
            assertEquals(120,order.size)
            val list=compose.onNodeWithTag("study-list").fetchSemanticsNode().boundsInRoot
            val source=compose.onNodeWithTag("outline-drag-${order[6]}").fetchSemanticsNode().boundsInRoot.center-list.topLeft
            val target=compose.onNodeWithTag("outline-row-${order[0]}").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("study-list").performTouchInput{down(source);moveTo(source+Offset(0f,-20f));moveTo(Offset(target.center.x-list.left,target.top+8f-list.top));up()}
            val moved=order.subList(6,12)+order.subList(0,6)+order.drop(12)
            compose.waitUntil(60_000){runBlocking{app.study.readGraph(note.id).orderedNodeIds}==moved}
            tap("study-undo-organization");compose.waitUntil(60_000){runBlocking{app.study.readGraph(note.id).orderedNodeIds}==order}
            tap("study-redo-organization");compose.waitUntil(60_000){runBlocking{app.study.readGraph(note.id).orderedNodeIds}==moved}
            tap("study-undo-organization");compose.waitUntil(60_000){runBlocking{app.study.readGraph(note.id).orderedNodeIds}==order}
            map.put("graphFingerprintAfterUi",app.study.readGraph(note.id).graphFingerprint)
            shot(f,"02-outline-120-topics")
        }}
        runBlocking{f.step("real-card-link-cross-book-return-and-unavailable-origin"){
            tap("study-tab-0");tap("study-card-${f.manifest.getString("navigationSourceCard")}")
            tap("card-backlinks");tap("knowledge-links-outgoing");tap("knowledge-outgoing-${f.manifest.getString("navigationLink")}")
            tap("card-link-open-target");settled(f.books[1]);waitFor("knowledge-return-context")
            val before=compose.runOnIdle{workspace().knowledgeReturns.value.toList()};assertTrue(before.isNotEmpty())
            // Only this synthetic origin is temporarily recycled. The target and return record must remain.
            val row=app.workspaceRepository.get(f.books[0])
            assertTrue(app.workspaceRepository.organize(f.books[0],row.revision,row.folder,row.tags,row.favorite,true))
            tap("knowledge-return-context")
            compose.waitUntil(60_000){app.openKnowledgeTarget.value==null}
            assertEquals(f.books[1],compose.runOnIdle{notebook().ui.value.selectedId})
            assertEquals(before,compose.runOnIdle{workspace().knowledgeReturns.value.toList()})
            shot(f,"03-unavailable-return-keeps-location")
            val trashed=app.workspaceRepository.get(f.books[0])
            assertTrue(app.workspaceRepository.organize(f.books[0],trashed.revision,trashed.folder,trashed.tags,trashed.favorite,false))
            tap("knowledge-return-context");settled(f.books[0])
            compose.waitUntil(60_000){compose.runOnIdle{workspace().knowledgeReturns.value.size==before.size-1}}
            waitFor("card-full-title");compose.onNodeWithTag("card-full-title").assertTextEquals("条件概率的完整推导与边界")
            tap("card-back");tap("study-close");tap("back-library");waitFor("new-note")
        }}
        runBlocking{f.step("over-budget-originals-rollback-with-old-data-intact"){
            val db=NoteDatabase.open(app)
            try{
                val beforeImages=db.images().all();val page=f.documentPages[0];val before=app.pageObjects.read(page)
                val size=((ImageSource.LIBRARY_BYTES-db.images().totalBytes())/2+1).toInt()
                require(size in 1..ImageSource.MAX_BYTES)
                val png=File(f.root,"synthetic-original-1.png").readBytes()
                val originals=List(2){ImageSource(paddedPng(png,size,it))}
                val objectTemplate=before.objects.single()
                val added=originals.mapIndexed{i,source->objectTemplate.copy(id=f.id("budget-object-$i"),imageSource=source.sha256)}
                var rejected=false
                try{app.pageObjects.save(page,before.revision,f.id("budget-rejected-operation"),before.objects+added,originals=originals)}catch(_:ImageOriginalCapacity){rejected=true}
                assertTrue("Capacity rejection must occur at the production writer",rejected)
                assertEquals(before,app.pageObjects.read(page));assertEquals(beforeImages,db.images().all())
                f.manifest.put("rejectedOriginalPayloadBytesEach",size)
            }finally{db.close()}
        }}
        runBlocking{f.verify();backupRoundTrip(f)}
        f.manifest.put("controlledUiStatus","BASELINE_ONLY_PASS").put("fullSixBatchAcceptance","PENDING_B4_B5_AND_MANUAL")
        f.save()
    }

    @Test fun reopenExactlyTheSameFullSizedFixture(){
        val f=SixBatchFixture.load(app)
        require(f.manifest.getString("status")=="FULL_SYNTHETIC_DATA_READY_NATIVE_B4_B5_REQUIRED")
        runBlocking{f.step("separate-instrumentation-reopen-same-identities"){
            f.verify()
            for(i in 0 until f.manifest.getJSONArray("maps").length()){
                val record=f.manifest.getJSONArray("maps").getJSONObject(i)
                val graph=app.study.readGraph(record.getString("book"),record.getString("mapId").ifEmpty{null})
                assertEquals(record.getJSONArray("nodes").strings(),graph.orderedNodeIds)
                val expected=record.optString("graphFingerprintAfterUi",record.getString("graphFingerprint"))
                assertEquals(expected,graph.graphFingerprint)
            }
            assertEquals(f.manifest.getString("documentSha256"),SixBatchFixture.sha(File(f.root,"synthetic-document.pdf")))
            val note=checkNotNull(app.repository.read(f.books[0]))
            compose.runOnIdle{notebook().select(note)};settled(f.books[0])
            waitFor("continuous-pages");shot(f,"04-reopened-same-material")
        }}
        f.manifest.put("reopenStatus","PASS");f.save()
    }

    private suspend fun backupRoundTrip(f:SixBatchFixture){
        f.step("production-backup-restores-all-author-tables-and-replay-is-idempotent"){
            app.libraryBackup.snapshot().use{snapshot->
                val exported=File(f.root,"synthetic-library.iwbackup");snapshot.file.copyTo(exported,overwrite=true)
                val dbName="six-batch-restored-${f.runId}.db"
                val restored=NoteDatabase.open(app,dbName)
                try{
                    val repository=LibraryBackupRepository(app,restored)
                    exported.inputStream().use{repository.inspect(it)}.use{preview->
                        assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,repository.restore(preview))
                        assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,repository.restore(preview))
                    }
                    // The archive is immutable. Its normalized row hash excludes only the archive header time.
                    val rows=List(LibraryBackupRepository.SCHEMA.size){mutableListOf<List<Any?>>()}
                    exported.inputStream().use{LibraryArchive.read(it,LibraryBackupRepository.SCHEMA,{table,row->rows[table]+=row})}
                    val canonical=LibraryArchive.write(object:java.io.OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},
                        LibraryBackupRepository.SCHEMA,object:LibraryArchive.Rows{
                            override fun count(table:Int)=rows[table].size.toLong()
                            override fun visit(table:Int,consume:(List<Any?>)->Unit)=rows[table].forEach(consume)
                        },0).sha256
                    assertEquals(canonical,SixBatchFixture.canonical(restored))
                    f.manifest.put("backup",JSONObject().put("sha256",SixBatchFixture.sha(exported)).put("bytes",exported.length())
                        .put("canonicalRestoredRowsSha256",canonical).put("tableRows",org.json.JSONArray(snapshot.summary.rows)).put("scope","All tables in production backup schema"))
                }finally{restored.close()}
                // This database contains only the just-restored synthetic fixture; preserve the primary app data.
                check(app.deleteDatabase(dbName))
            }
        }
    }
    private fun paddedPng(base:ByteArray,total:Int,variant:Int):ByteArray{
        require(base.copyOfRange(base.size-8,base.size-4).toString(Charsets.US_ASCII)=="IEND")
        val payload=total-base.size-12;require(payload>0)
        return ByteArray(total).also{out->
            base.copyInto(out,0,0,base.size-12);val start=base.size-12;val buffer=ByteBuffer.wrap(out)
            buffer.position(start);buffer.putInt(payload);buffer.put("fiXt".toByteArray(Charsets.US_ASCII))
            out[start+8+payload-1]=variant.toByte();buffer.position(start+8+payload)
            val crc=CRC32().apply{update(out,start+4,payload+4)};buffer.putInt(crc.value.toInt());buffer.put(base,base.size-12,12)
        }
    }
}
