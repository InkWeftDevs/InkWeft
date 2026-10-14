// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import android.os.Debug
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in causal evidence, never an acceptance benchmark. All content is synthetic.
 * The public paired probe remains unchanged. Callback clocks add overhead; the reference
 * excludes loading, FTS, SQLite, sorting and result construction. It cannot time a full read. */
class LibrarySearchCausalTest {
    private fun id()=UUID.randomUUID().toString()
    private fun emit(marker:String,value:JSONObject){
        val output=value.toString()
        check(output.toByteArray(Charsets.UTF_8).size<=32_768)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply{
            putString("stream","$marker $output\n")
        })
    }
    private fun fixture(block:suspend(NoteDatabase)->Unit)=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="library-causal-${id()}.db";val db=NoteDatabase.open(context,name)
        try{withContext(Dispatchers.IO){block(db)}}finally{try{db.close()}finally{context.deleteDatabase(name)}}
    }

    @Test fun warmUdfCostsAndQueryPlanAgainstInMemoryReference(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("libraryCausalDiagnostics")=="true")
        fixture{db->
            // Same logical final warm fixture as KvmLibraryPairProbe: 500 large ASCII
            // bodies, 20,000 indexed page sources, and the author after 61 real saves.
            val body="Synthetic note body "+"b".repeat(20_000)
            val source="合成页面索引 "+"a".repeat(1024)
            var targetBook="";var targetPage=""
            db.withTransaction{
                val sql=db.openHelper.writableDatabase
                repeat(500){bookIndex->
                    val book=id();val title="合成笔记 ${bookIndex.toString().padStart(3,'0')}"
                    db.notes().insertNote(NoteRow(book,1,title,body,bookIndex.toLong()))
                    db.notes().insertRevision(NoteRevisionRow(book,1,title,body,bookIndex.toLong()))
                    db.workspace().insert(WorkspaceRow(book))
                    repeat(40){position->
                        val page=if(position==0)book else id()
                        db.pages().insert(NotebookPageRow(page,book,position,false,0))
                        val target=bookIndex==499&&position==39
                        if(target){targetBook=book;targetPage=page}
                        sql.execSQL("INSERT INTO page_search_text VALUES(?,0,?,'MANUAL')",arrayOf(page,if(target)"target-only" else source))
                    }
                }
            }
            val notes=NoteRepository(db)
            var writer=WorkspaceRepository(db).create("Concurrent author",false,PaperStyle.BLANK)
            suspend fun save(text:String){
                val result=notes.save(SaveNote(id(),writer.id,writer.revision,writer.title,text))
                assertTrue(result is SaveResult.Committed);writer=(result as SaveResult.Committed).note
            }
            repeat(30){save("Synthetic concurrent edit $it")}
            save("Paused reader edit")
            repeat(30){save("Synthetic indexed edit $it")}
            assertEquals(62L,writer.revision);assertEquals(writer,notes.read(writer.id))
            var indexed=0
            LibraryTextIndex.build(db){indexed+=it}
            assertEquals(20_000,indexed)
            val headers=notes.observeNoteHeaders().first()
            assertEquals(501,headers.size);assertEquals(0,headers.sumOf{it.text.length})
            val repo=LibrarySearchRepository(db)
            val request=LibrarySearchRequest(1,headers,emptyMap(),"target-only")
            fun checkResult(result:LibrarySearchResult){
                assertFalse(result.failed);assertEquals(0,result.pendingIndex)
                assertEquals(targetBook,result.items.single().note.id)
                assertEquals(targetPage,result.items.single().pageId)
                assertEquals(body.take(600),result.items.single().note.text)
            }
            suspend fun plannedRead(profile:Boolean):Pair<LibrarySearchResult,JSONObject>{
                var query="";var plan=emptyList<String>()
                repo.querySqlObserver={query=it};repo.queryPlanObserver={plan=it}
                repo.udfObserver=if(profile){{_:LibrarySearchUdfRead->}}else null
                val start=System.nanoTime()
                val result=try{repo.read(request)}finally{
                    repo.querySqlObserver=null;repo.queryPlanObserver=null;repo.udfObserver=null
                }
                val wall=System.nanoTime()-start
                checkResult(result);assertTrue(query.isNotEmpty());assertTrue(plan.isNotEmpty())
                return result to JSONObject().put("wallNs",wall).put("udfEnabled",profile)
                    .put("includesExplain",true).put("pageQuery",query).put("queryPlan",JSONArray(plan))
            }
            // UDF observer off before and after the instrumented reads. These actual
            // statements also EXPLAIN their own SQL; the clocks include EXPLAIN overhead.
            val (before,beforeRecord)=plannedRead(false)
            val (instrumented,instrumentedPlan)=plannedRead(true)
            val profiled=JSONArray()
            repeat(3){sample->
                var captured:LibrarySearchUdfRead?=null
                repo.udfObserver={captured=it}
                val start=System.nanoTime()
                val result=try{repo.read(request)}finally{repo.udfObserver=null}
                val wall=System.nanoTime()-start
                checkResult(result);assertEquals(before,result)
                val totals=checkNotNull(captured);assertTrue(totals.completed)
                // Multiplicity is diagnostic evidence, not an assumed query-plan contract.
                assertTrue(totals.body.calls>0);assertTrue(totals.source.calls>0)
                profiled.put(JSONObject().put("sample",sample).put("wallNs",wall)
                    .put("pageWallNs",totals.pageWallNs).put("pageCpuNs",totals.pageCpuNs).put("pageTimingValid",totals.pageTimingValid)
                    .put("completed",totals.completed).put("fields",JSONObject()
                        .put("title",totals.title.json()).put("body",totals.body.json())
                        .put("tags",totals.tags.json()).put("source",totals.source.json())))
            }
            val (after,afterRecord)=plannedRead(false)
            assertEquals(before,instrumented);assertEquals(before,after)

            // Loading is deliberately after all SQLite reads and timed separately. The
            // reference uses actual saved heads and workspace tags, in their saved order.
            val preloadStart=System.nanoTime()
            val full=notes.observeNotes().first();val workspaces=db.workspace().all().associateBy{it.noteId}
            val preloadWall=System.nanoTime()-preloadStart
            assertEquals(501,full.size)
            assertTrue(workspaces.values.all{!it.pinned&&it.trashedAt==null})
            val knownPageHits=setOf(targetBook);val knownCardHits=emptySet<String>()
            val reference=JSONArray()
            repeat(3){sample->
                currentCoroutineContext().ensureActive()
                // No suspension between these thread CPU clocks.
                val start=System.nanoTime();val cpu=Debug.threadCpuTimeNanos()
                val matched=full.filter{note->note.title.contains(request.query,true)||
                    note.text.contains(request.query,true)||workspaces.getValue(note.id).tags.contains(request.query,true)||
                    note.id in knownPageHits||note.id in knownCardHits}.map{it.id}
                val cpuEnd=Debug.threadCpuTimeNanos();val wallEnd=System.nanoTime()
                val timingValid=cpu>=0&&cpuEnd>=cpu&&wallEnd>=start
                assertEquals(before.items.map{it.note.id},matched)
                reference.put(JSONObject().put("sample",sample).put("wallNs",if(timingValid)wallEnd-start else JSONObject.NULL)
                    .put("cpuNs",if(timingValid)cpuEnd-cpu else JSONObject.NULL).put("timingValid",timingValid).put("matchCount",matched.size))
            }
            val productionSql=beforeRecord.getString("pageQuery")
            val normalizedInstrumentedSql=instrumentedPlan.getString("pageQuery")
                .replace("library_contains_title","library_contains")
                .replace("library_contains_body","library_contains")
                .replace("library_contains_tags","library_contains")
            val sameSql=productionSql==afterRecord.getString("pageQuery")&&productionSql==normalizedInstrumentedSql
            val samePlan=beforeRecord.getJSONArray("queryPlan").toString()==instrumentedPlan.getJSONArray("queryPlan").toString()&&
                beforeRecord.getJSONArray("queryPlan").toString()==afterRecord.getJSONArray("queryPlan").toString()
            emit("LIBRARY_CAUSAL_WARM",JSONObject().put("schemaVersion",1).put("scope","CAUSAL_ONLY")
                .put("query","target-only").put("fixture",JSONObject().put("books",500).put("authorBooks",1)
                    .put("pages",20_000).put("noteBodyUtf16",body.length).put("writerRevision",writer.revision)
                    .put("indexedRows",indexed).put("pending",before.pendingIndex))
                .put("productionBefore",beforeRecord).put("instrumentedPlan",instrumentedPlan).put("profiledReads",profiled)
                .put("productionAfter",afterRecord).put("reference",JSONObject().put("preloadWallNs",preloadWall)
                    .put("notes",full.size).put("bodyUtf16",full.sumOf{it.text.length.toLong()})
                    .put("knownPageHitBooks",1).put("knownCardHitBooks",0).put("reads",reference))
                .put("matchedBookIndex",499).put("matchedPagePosition",39).put("resultEquality",true)
                .put("sqlDifferenceOnlyDiagnosticFunctionNames",sameSql).put("planEquality",samePlan))
            assertTrue("Instrumented SQL may only rename contains functions",sameSql)
            assertTrue("Diagnostic function names must retain the actual query plan",samePlan)
        }
    }

    @Test fun profiledContainsPreservesUnicodeAndSourceResults()=fixture{db->
        val workspace=WorkspaceRepository(db);val notes=NoteRepository(db);val pages=NotebookPages(db)
        val value="İı Σσς Ångström 🚀 中文 %_ 𐐀"
        val saved=mutableListOf<Note>();val tags=HashMap<String,String>();val sources=HashMap<String,String>()
        repeat(5){field->
            var note=workspace.create(if(field==0)value else "Synthetic $field",false,PaperStyle.BLANK)
            if(field==1){
                val result=notes.save(SaveNote(id(),note.id,note.revision,note.title,value))
                assertTrue(result is SaveResult.Committed);note=(result as SaveResult.Committed).note
            }
            if(field==2){val tagValue=value.replace(' ','\n');assertTrue(workspace.organize(note.id,0,"",tagValue,false,false));tags[note.id]=tagValue}
            if(field==3){assertTrue(pages.saveSearchText(note.id,0,value));sources[note.id]=value}
            if(field==4){
                val card=StudyCardRow(id(),note.id,1,"Synthetic card",value,null)
                db.withTransaction{db.study().addCard(card);db.study().revision(StudyCardRevisionRow(card.id,1,card.title,card.body,null))}
                sources[note.id]=value
            }
            saved+=note
        }
        val repo=LibrarySearchRepository(db)
        val queries=listOf("i","σ","ångSTRÖm","🚀","中文","%_","𐐨","missing")
        var comparisons=0
        for(indexed in listOf(false,true)){
            if(indexed)LibraryTextIndex.build(db)
            for(query in queries){
                val request=LibrarySearchRequest(1,saved,emptyMap(),query,byTitle=true)
                val plain=repo.read(request)
                var captured:LibrarySearchUdfRead?=null;repo.udfObserver={captured=it}
                val profiled=try{repo.read(request)}finally{repo.udfObserver=null}
                assertEquals(plain,profiled);assertTrue(checkNotNull(captured).completed)
                val expected=saved.filter{it.title.contains(query,true)||it.text.contains(query,true)||
                    tags[it.id].orEmpty().contains(query,true)||sources[it.id].orEmpty().contains(query,true)}.map{it.id}.toSet()
                assertEquals(expected,profiled.items.map{it.note.id}.toSet())
                for(item in profiled.items){
                    assertEquals(item.note.id==saved[3].id,item.pageId!=null)
                    assertEquals(item.note.id==saved[4].id,item.cardMatch)
                }
                comparisons++
            }
        }
        emit("LIBRARY_CAUSAL_UNICODE",JSONObject().put("schemaVersion",1).put("scope","CORRECTNESS_ONLY")
            .put("queries",queries.size).put("comparisons",comparisons).put("pendingAndIndexed",true).put("resultEquality",true))
    }

    private fun LibrarySearchUdfTotals.json()=JSONObject().put("calls",calls).put("utf16Chars",utf16Chars).put("matches",matches)
        .put("timingValid",timingValid)
        .put("conversionWallNs",conversionWallNs).put("conversionCpuNs",conversionCpuNs)
        .put("containsWallNs",containsWallNs).put("containsCpuNs",containsCpuNs)
}
