// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import java.util.Locale
import org.inkweft.core.*

// notes is the UI directory/change token; saved content is always read from the database snapshot.
data class LibrarySearchRequest(val generation:Long,val notes:List<Note>,val drafts:Map<String,NoteDraft>,
    val query:String="",val filter:String="all",val type:String="all",val byTitle:Boolean=false,val offset:Int=0,val pageSize:Int=60)
/** note.text is a bounded cover preview, never an editable author head. */
data class LibraryShelfItem(val note:Note,val workspace:WorkspaceRow,val pageId:String?=null,val cardMatch:Boolean=false)
data class LibrarySearchResult(val generation:Long=0,val items:List<LibraryShelfItem> = emptyList(),
    val active:List<Note> = emptyList(),val folders:Map<String,Int> = emptyMap(),val tags:List<String> = emptyList(),
    val favorites:Int=0,val unfiled:Int=0,val trashed:Int=0,val failed:Boolean=false,
    val query:String="",val filter:String="all",val type:String="all",val byTitle:Boolean=false,val offset:Int=0,val nextOffset:Int?=null,val pendingIndex:Int=0,val sourceVersion:Long=0)

/** Native FTS candidates, checked against current author text, in bounded shelf pages. */
class LibrarySearchRepository(private val db:NoteDatabase) {
    internal var scanObserver:((String,Int)->Unit)?=null
    internal var indexObserver:((String,Int)->Unit)?=null
    internal var indexFirstBatchStepObserver:((String)->Unit)?=null
    internal var indexBatchStepObserver:((String)->Unit)?=null
    // Counts rows/UTF-16 characters crossing the cursor boundary, excluding draft inputs.
    internal var readObserver:((String,Int,Int)->Unit)?=null
    internal var queryPlanObserver:((List<String>)->Unit)?=null
    internal var querySqlObserver:((String)->Unit)?=null
    // Synthetic diagnostic runs only. Null keeps the original UDFs and reads no clocks.
    internal var udfObserver:((LibrarySearchUdfRead)->Unit)?=null
    @Volatile private var diagnostics:LibrarySearchDiagnostics?=null
    // Install before collecting/reading. The default null observer creates no trace or clock reads.
    internal var stageObserver:((String,Long,Long,Long,Long,Int)->Unit)?
        get()=diagnostics?.observer
        set(value){diagnostics=value?.let(::LibrarySearchDiagnostics)}

    fun observe(requests:Flow<LibrarySearchRequest>):Flow<LibrarySearchResult> = channelFlow {
        val tracing=diagnostics
        val observation=LibrarySearchObservation(tracing){request,trace->read(request,trace)}
        val completed=MutableStateFlow(0);var indexed=0
        launch(Dispatchers.IO){db.invalidationTracker.createFlow("page_search_text","study_cards").collectLatest{
            LibraryTextIndex.build(db,onFirstBatchStep=indexFirstBatchStepObserver,onBatchStep=indexBatchStepObserver){indexed+=it;indexObserver?.invoke(observation.query,indexed)}
            tracing?.event("index-build-completed",count=completed.value+1)
            completed.value++
        }}
        observation.results(requests,db.invalidationTracker.createFlow(
            "notes","notebook_workspace","notebook_pages","page_search_text","ink_pages","study_cards"
        ),completed).collect{send(it)}
    }

    suspend fun read(request:LibrarySearchRequest):LibrarySearchResult=read(request,diagnostics?.read(request.generation,-1))

    private suspend fun read(request:LibrarySearchRequest,trace:LibrarySearchReadTrace?):LibrarySearchResult {
        trace?.event("read-requested")
        return withContext(Dispatchers.IO){
        require(request.offset>=0&&request.pageSize in 1..500)
        trace?.event("read-start")
        val reportUdf=udfObserver;val udf=reportUdf?.let{LibrarySearchUdfRead()}
        var primaryFailure:Throwable?=null
        // Requery's deferred transaction pins one WAL read snapshot without joining Room's
        // author-write queue. Keep all native calls on this thread until endTransaction.
        val active=currentCoroutineContext();val cancellation=android.os.CancellationSignal()
        try{val result=coroutineScope {
            val watcher=launch(Dispatchers.IO,start=CoroutineStart.UNDISPATCHED){try{awaitCancellation()}finally{cancellation.cancel()}}
            try{SQLiteDatabase.openDatabase(db.openHelper.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY){
                throw android.database.sqlite.SQLiteDatabaseCorruptException("Library search corruption; author data retained")
            }.use{reader->
                trace?.event("connection-open")
                active.ensureActive()
                var scanned=0
                if(udf==null){
                    reader.addFunction("library_contains",1,{args,result->
                        active.ensureActive();result.set(if(args.getString(0).orEmpty().contains(request.query,true))1 else 0)
                    })
                    reader.addFunction("library_source_contains",1,{args,result->
                        active.ensureActive();scanObserver?.invoke(request.query,++scanned)
                        result.set(if(args.getString(0).orEmpty().contains(request.query,true))1 else 0)
                    })
                }else{
                    // Same arity, flags, Boolean operation and cancellation checks. Separate
                    // names identify fields without an extra SQL argument or text logging.
                    for((field,totals) in listOf("title" to udf.title,"body" to udf.body,"tags" to udf.tags))
                        reader.addFunction("library_contains_$field",1,{args,result->
                            active.ensureActive();result.set(totals.contains(args,request.query))
                        })
                    reader.addFunction("library_source_contains",1,{args,result->
                        active.ensureActive();scanObserver?.invoke(request.query,++scanned)
                        result.set(udf.source.contains(args,request.query))
                    })
                }
                // SQLite NOCASE/lower() are ASCII-only. A big-endian UTF-16 blob compares
                // exactly like Kotlin's Locale.ROOT title sort, including supplementary chars.
                reader.addFunction("library_title",1,{args,result->
                    active.ensureActive();val title=args.getString(0).lowercase(Locale.ROOT)
                    result.set(ByteArray(title.length*2){i->(title[i/2].code ushr if(i%2==0)8 else 0).toByte()})
                })
                reader.addFunction("library_blank",1,{args,result->active.ensureActive();result.set(if(args.getString(0).orEmpty().isBlank())1 else 0)})
                val sql=object:SupportSQLiteDatabase by reader{
                    override fun query(query:String)=reader.query(SimpleSQLiteQuery(query),cancellation)
                    override fun query(query:String,bindArgs:Array<out Any?>)=reader.query(SimpleSQLiteQuery(query,bindArgs),cancellation)
                }
                active.ensureActive();reader.beginTransactionDeferred()
                try{trace?.event("snapshot-open");readSnapshot(request,sql,trace,udf){active.ensureActive()}}
                finally{reader.endTransaction();trace?.event("snapshot-closed")}
            }.also{trace?.event("connection-closed")}}catch(failure:Exception){active.ensureActive();throw failure}finally{watcher.cancel()}
        };trace?.event("read-end",result.items.size);if(udf!=null)udf.completed=true;result
        }catch(c:CancellationException){primaryFailure=c;trace?.event("read-cancel");throw c}
        catch(failure:Exception){primaryFailure=failure;trace?.event("read-error");throw failure}
        catch(failure:Throwable){primaryFailure=failure;throw failure}
        finally{if(udf!=null)try{reportUdf?.invoke(udf)}catch(reportFailure:Throwable){
            val original=primaryFailure
            if(original==null)throw reportFailure
            if(original!==reportFailure)original.addSuppressed(reportFailure)
        }}
        }
    }

    private fun readSnapshot(request:LibrarySearchRequest,sql:SupportSQLiteDatabase,trace:LibrarySearchReadTrace?,udf:LibrarySearchUdfRead?,check:()->Unit):LibrarySearchResult {
        // Only open drafts enter this temporary overlay. Saved titles, revisions, ordering,
        // classification and search sources are all read from the same author snapshot.
        // execSQL has no CancellationSignal overload. Stop between temporary-table steps,
        // including when there are no drafts or matching source rows to run a row check.
        check()
        sql.execSQL("CREATE TEMP TABLE library_drafts(id TEXT PRIMARY KEY,revision INTEGER,title TEXT,text TEXT,position INTEGER)")
        request.drafts.values.forEachIndexed{index,draft->check();sql.execSQL("INSERT INTO library_drafts VALUES(?,?,?,?,?)",
            arrayOf<Any>(draft.base.id,draft.base.revision,draft.title,draft.text,index))}
        trace?.event("drafts-ready",request.drafts.size)
        check()
        val shelf="""
            WITH shelf AS (
                SELECT n.id,COALESCE(d.revision,n.revision) revision,COALESCE(d.title,n.title) title,
                    n.updatedAt,0 draftPosition,
                    COALESCE(w.world,0) world,COALESCE(w.paper,1) paper,COALESCE(w.folder,'') folder,COALESCE(w.tags,'') tags,
                    COALESCE(w.favorite,0) favorite,w.trashedAt,COALESCE(w.pinned,0) pinned,
                    COALESCE(w.revision,0) workspaceRevision,COALESCE(w.coverKey,'auto') coverKey,
                    COALESCE(w.selectedPageId,'') selectedPageId,COALESCE(w.centerX,500.0) centerX,COALESCE(w.centerY,707.0) centerY,COALESCE(w.zoom,0.0) zoom
                FROM notes n LEFT JOIN library_drafts d ON d.id=n.id LEFT JOIN notebook_workspace w ON w.noteId=n.id
                UNION ALL
                SELECT d.id,d.revision,d.title,-9223372036854775808,d.position,
                    0,1,'','',0,NULL,0,0,'auto','',500.0,707.0,0.0
                FROM library_drafts d WHERE NOT EXISTS(SELECT 1 FROM notes n WHERE n.id=d.id)
            )
        """.trimIndent()
        val args=ArrayList<Any>()
        val filter=buildList {
            add(if(request.filter=="trash")"trashedAt IS NOT NULL" else "trashedAt IS NULL")
            when{
                request.filter=="favorite"->add("favorite=1")
                request.filter=="unfiled"->add("library_blank(folder)=1")
                request.filter.startsWith("folder:")->{add("folder=?");args+=request.filter.removePrefix("folder:")}
                request.filter.startsWith("tag:")->{add("instr(char(10)||tags||char(10),char(10)||?||char(10))>0");args+=request.filter.removePrefix("tag:")}
            }
            if(request.type!="all")add("world="+if(request.type=="board")"1"else"0")
        }.joinToString(" AND ")
        trace?.event("scope-start")
        check()
        sql.execSQL("CREATE TEMP TABLE library_scope(id TEXT PRIMARY KEY)")
        check()
        sql.execSQL("$shelf INSERT INTO library_scope SELECT id FROM shelf WHERE $filter",args.toTypedArray())
        trace?.event("scope-end")
        check()
        sql.execSQL("CREATE TEMP TABLE library_page_hits(notebookId TEXT,id TEXT,position INTEGER)")
        check()
        sql.execSQL("CREATE INDEX library_page_hit_order ON library_page_hits(notebookId,position,id)")
        check()
        sql.execSQL("CREATE TEMP TABLE library_card_hits(notebookId TEXT PRIMARY KEY)")
        val terms=request.query.takeIf{it.isNotBlank()}?.let(LibrarySearchTerms::query)
        trace?.event("hits-ready")
        check()
        if(terms!=null){
            // Filter the authoritative source inside SQLite. Unmatched bodies never enter a
            // CursorWindow or a temp sort; only compact hit identities cross the boundary.
            val pageQuery="""
                SELECT p.notebookId,p.id,p.position FROM (
                    SELECT target FROM library_search_pending WHERE kind=0
                    UNION SELECT d.target FROM library_search_fts f CROSS JOIN library_search_docs d ON d.key=f.docid WHERE f.terms MATCH ? AND d.kind=0
                ) hit CROSS JOIN page_search_text s ON s.pageId=hit.target
                CROSS JOIN notebook_pages p ON p.id=s.pageId LEFT JOIN ink_pages h ON h.noteId=p.id
                WHERE p.trashedAt IS NULL AND s.inkRevision=COALESCE(h.revision,0)
                    AND p.notebookId IN (SELECT id FROM library_scope) AND library_source_contains(s.text)=1
            """.trimIndent()
            trace?.event("page-hits-start")
            sql.query(pageQuery,arrayOf(terms)).use{rows->while(rows.moveToNext()){
                check();sql.execSQL("INSERT INTO library_page_hits VALUES(?,?,?)",arrayOf<Any>(rows.getString(0),rows.getString(1),rows.getInt(2)))
            }}
            trace?.event("page-hits-end")
            val cardQuery="""
                SELECT c.notebookId FROM (
                    SELECT target FROM library_search_pending WHERE kind=1
                    UNION SELECT d.target FROM library_search_fts f CROSS JOIN library_search_docs d ON d.key=f.docid WHERE f.terms MATCH ? AND d.kind=1
                ) hit CROSS JOIN study_cards c ON c.id=hit.target
                WHERE c.trashedAt IS NULL AND c.notebookId IN (SELECT id FROM library_scope)
                    AND (library_source_contains(c.title)=1 OR library_source_contains(c.body)=1)
            """.trimIndent()
            trace?.event("card-hits-start")
            sql.query(cardQuery,arrayOf(terms)).use{rows->while(rows.moveToNext()){
                check();sql.execSQL("INSERT OR IGNORE INTO library_card_hits VALUES(?)",arrayOf(rows.getString(0)))
            }}
            trace?.event("card-hits-end")
        }
        val titleContains=if(udf==null)"library_contains" else "library_contains_title"
        val bodyContains=if(udf==null)"library_contains" else "library_contains_body"
        val tagsContains=if(udf==null)"library_contains" else "library_contains_tags"
        val match=if(terms==null)"1" else """($titleContains(title)=1 OR $bodyContains(COALESCE((SELECT d.text FROM library_drafts d WHERE d.id=shelf.id),(SELECT n.text FROM notes n WHERE n.id=shelf.id)))=1 OR $tagsContains(tags)=1
            OR EXISTS(SELECT 1 FROM library_page_hits h WHERE h.notebookId=shelf.id)
            OR EXISTS(SELECT 1 FROM library_card_hits h WHERE h.notebookId=shelf.id))"""
        val order="pinned DESC,"+(if(request.byTitle)"library_title(title),"else "")+"updatedAt DESC,draftPosition,id"
        val matched=ArrayList<LibraryShelfItem>();var previewChars=0
        // One lookahead preview makes the next offset explicit. Cursor text stays bounded
        // to (pageSize + 1) short previews, even when there are thousands of matches.
        val pageQuery="""$shelf, page AS MATERIALIZED (
            SELECT * FROM shelf WHERE id IN (SELECT id FROM library_scope) AND $match
            ORDER BY $order LIMIT ? OFFSET ?
            ) SELECT page.id,page.revision,page.title,substr(COALESCE(d.text,n.text,''),1,600),world,paper,folder,tags,favorite,trashedAt,pinned,
            workspaceRevision,coverKey,selectedPageId,
            (SELECT id FROM library_page_hits h WHERE h.notebookId=page.id ORDER BY position,id LIMIT 1),
            EXISTS(SELECT 1 FROM library_card_hits h WHERE h.notebookId=page.id),page.centerX,page.centerY,page.zoom
            FROM page LEFT JOIN notes n ON n.id=page.id LEFT JOIN library_drafts d ON d.id=page.id
            ORDER BY page.pinned DESC,"""+(if(request.byTitle)"library_title(page.title),"else "")+
            "page.updatedAt DESC,page.draftPosition,page.id"
        val pageArgs=arrayOf<Any>(request.pageSize+1,request.offset)
        querySqlObserver?.invoke(pageQuery)
        queryPlanObserver?.invoke(sql.query("EXPLAIN QUERY PLAN $pageQuery",pageArgs).use{rows->buildList{while(rows.moveToNext())add(rows.getString(3))}})
        trace?.event("page-start")
        val pageWall=if(udf==null)0L else System.nanoTime()
        val pageCpu=if(udf==null)0L else android.os.Debug.threadCpuTimeNanos()
        sql.query(pageQuery,pageArgs).use{rows->while(rows.moveToNext()){
            check();val id=rows.getString(0);val preview=rows.getString(3);previewChars+=preview.length
            val note=Note(id,rows.getLong(1),rows.getString(2),preview)
            val workspace=WorkspaceRow(id,world=rows.getInt(4)!=0,paper=rows.getInt(5),folder=rows.getString(6),tags=rows.getString(7),
                favorite=rows.getInt(8)!=0,trashedAt=if(rows.isNull(9))null else rows.getLong(9),pinned=rows.getInt(10)!=0,
                revision=rows.getLong(11),coverKey=rows.getString(12),selectedPageId=rows.getString(13),centerX=rows.getDouble(16),centerY=rows.getDouble(17),zoom=rows.getDouble(18))
            matched+=LibraryShelfItem(note,workspace,if(rows.isNull(14))null else rows.getString(14),rows.getInt(15)!=0)
        }}
        if(udf!=null){
            val cpuEnd=android.os.Debug.threadCpuTimeNanos();val wallEnd=System.nanoTime()
            udf.pageTimingValid=pageCpu>=0&&cpuEnd>=pageCpu&&wallEnd>=pageWall
            if(udf.pageTimingValid){udf.pageCpuNs=cpuEnd-pageCpu;udf.pageWallNs=wallEnd-pageWall}
        }
        trace?.event("page-end",matched.size)
        readObserver?.invoke("previews",matched.size,previewChars)
        val nextOffset=if(matched.size>request.pageSize)request.offset+request.pageSize else null
        check()
        val active=ArrayList<Note>();val folders=sortedMapOf<String,Int>();val tags=sortedSetOf<String>()
        var favorites=0;var unfiled=0;var trashed=0;var metadataRows=0
        // Assemble the directory only after hits and the bounded page have completed.
        // Superseded reads can then cancel without constructing discarded note headers
        // and classifications. This still reads the same author snapshot, without bodies.
        trace?.event("metadata-start")
        sql.query("$shelf SELECT id,revision,title,folder,tags,favorite,trashedAt FROM shelf ORDER BY updatedAt DESC,draftPosition,id").use{rows->while(rows.moveToNext()){
            check();metadataRows++
            if(!rows.isNull(6)){trashed++;continue}
            active+=Note(rows.getString(0),rows.getLong(1),rows.getString(2),"")
            if(rows.getInt(5)!=0)favorites++
            val folder=rows.getString(3);if(folder.isBlank())unfiled++ else folders[folder]=(folders[folder]?:0)+1
            tags+=rows.getString(4).split('\n').filter{it.isNotBlank()}
        }}
        trace?.event("metadata-end",metadataRows)
        readObserver?.invoke("metadata",metadataRows,0)
        trace?.event("pending-start")
        val pending=sql.query("SELECT COUNT(*) FROM library_search_pending").use{it.moveToFirst();it.getInt(0)}
        trace?.event("pending-end",pending)
        return LibrarySearchResult(request.generation,matched.take(request.pageSize),active,folders,tags.toList(),favorites,unfiled,trashed,
            query=request.query,filter=request.filter,type=request.type,byTitle=request.byTitle,offset=request.offset,nextOffset=nextOffset,
            pendingIndex=pending)
    }
}

/** Per-read synthetic diagnostics. No query, author text, IDs or per-row event objects. */
internal class LibrarySearchUdfRead {
    val title=LibrarySearchUdfTotals();val body=LibrarySearchUdfTotals()
    val tags=LibrarySearchUdfTotals();val source=LibrarySearchUdfTotals()
    var pageWallNs=0L;var pageCpuNs=0L;var pageTimingValid=false;var completed=false
}

internal class LibrarySearchUdfTotals {
    var calls=0L;var utf16Chars=0L;var matches=0L
    var conversionWallNs=0L;var conversionCpuNs=0L
    var containsWallNs=0L;var containsCpuNs=0L
    var timingValid=true
    fun contains(args:SQLiteDatabase.Function.Args,query:String):Int {
        calls++
        // Each interval is synchronous on this native callback's thread. Clock calls
        // themselves add overhead; these totals are causal diagnostics, not benchmarks.
        val conversionWall=System.nanoTime();val conversionCpu=android.os.Debug.threadCpuTimeNanos()
        val text=args.getString(0).orEmpty()
        val conversionCpuEnd=android.os.Debug.threadCpuTimeNanos();val conversionWallEnd=System.nanoTime()
        if(conversionCpu>=0&&conversionCpuEnd>=conversionCpu&&conversionWallEnd>=conversionWall){
            conversionCpuNs+=conversionCpuEnd-conversionCpu;conversionWallNs+=conversionWallEnd-conversionWall
        }else timingValid=false
        utf16Chars+=text.length
        val containsWall=System.nanoTime();val containsCpu=android.os.Debug.threadCpuTimeNanos()
        val found=text.contains(query,true)
        val containsCpuEnd=android.os.Debug.threadCpuTimeNanos();val containsWallEnd=System.nanoTime()
        if(containsCpu>=0&&containsCpuEnd>=containsCpu&&containsWallEnd>=containsWall){
            containsCpuNs+=containsCpuEnd-containsCpu;containsWallNs+=containsWallEnd-containsWall
        }else timingValid=false
        if(found)matches++
        return if(found)1 else 0
    }
}
