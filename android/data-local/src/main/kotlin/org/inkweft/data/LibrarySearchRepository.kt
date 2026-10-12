// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.room.withTransaction
import org.inkweft.core.*

data class LibrarySearchRequest(val generation:Long,val notes:List<Note>,val drafts:Map<String,NoteDraft>,
    val query:String="",val filter:String="all",val type:String="all",val byTitle:Boolean=false,val offset:Int=0,val pageSize:Int=60)
data class LibraryShelfItem(val note:Note,val workspace:WorkspaceRow,val pageId:String?=null,val cardMatch:Boolean=false)
data class LibrarySearchResult(val generation:Long=0,val items:List<LibraryShelfItem> = emptyList(),
    val active:List<Note> = emptyList(),val folders:Map<String,Int> = emptyMap(),val tags:List<String> = emptyList(),
    val favorites:Int=0,val unfiled:Int=0,val trashed:Int=0,val failed:Boolean=false,
    val query:String="",val filter:String="all",val type:String="all",val byTitle:Boolean=false,val offset:Int=0,val nextOffset:Int?=null,val pendingIndex:Int=0)

/** Native FTS candidates, checked against current author text, in bounded shelf pages. */
class LibrarySearchRepository(private val db:NoteDatabase) {
    internal var scanObserver:((String,Int)->Unit)?=null
    internal var indexObserver:((String,Int)->Unit)?=null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(requests:Flow<LibrarySearchRequest>):Flow<LibrarySearchResult> = channelFlow {
        var previous:LibrarySearchRequest?=null
        val completed=MutableStateFlow(0);var indexed=0
        launch(Dispatchers.IO){db.invalidationTracker.createFlow("page_search_text","study_cards").collectLatest{
            LibraryTextIndex.build(db){indexed+=it;indexObserver?.invoke(previous?.query.orEmpty(),indexed)}
            completed.value++
        }}
        combine(requests,db.invalidationTracker.createFlow(
            "notebook_workspace","notebook_pages","page_search_text","ink_pages","study_cards"
        ),completed){request,_,_->request}.mapLatest{request->
            val before=previous;previous=request
            // Text edits debounce; deleting the query and changing a category take effect immediately.
            if(request.query.isNotBlank()&&before?.query!=request.query&&
                (before==null||before.filter==request.filter&&before.type==request.type&&before.byTitle==request.byTitle))delay(150)
            try{read(request)}catch(c:CancellationException){throw c}
            catch(_:Exception){LibrarySearchResult(generation=request.generation,failed=true,query=request.query,
                filter=request.filter,type=request.type,byTitle=request.byTitle,offset=request.offset)}
        }.collect{send(it)}
    }

    suspend fun read(request:LibrarySearchRequest):LibrarySearchResult=withContext(Dispatchers.IO){
        require(request.offset>=0&&request.pageSize in 1..500)
        val activeContext=currentCoroutineContext()
        // Classification and source hits share the final read transaction, including after
        // a long initial backfill. A direct read must not retain pre-backfill trash/pin state.
        val readSnapshot:suspend(androidx.sqlite.db.SupportSQLiteDatabase)->LibrarySearchResult={sql->
        val metadata=db.workspace().all().associateBy{it.noteId}
        val display=LinkedHashMap<String,Note>()
        request.notes.forEach{activeContext.ensureActive();display[it.id]=it}
        request.drafts.values.forEach{activeContext.ensureActive();display[it.base.id]=it.base.copy(title=it.title,text=it.text)}
        val active=ArrayList<Note>();val folders=sortedMapOf<String,Int>();val tags=sortedSetOf<String>()
        val candidates=ArrayList<LibraryShelfItem>();var favorites=0;var unfiled=0;var trashed=0
        for(note in display.values){
            activeContext.ensureActive()
            val row=metadata[note.id]?:WorkspaceRow(note.id)
            val noteTags=row.tags.split('\n')
            if(row.trashedAt==null){
                active+=note;if(row.favorite)favorites++
                if(row.folder.isBlank())unfiled++ else folders[row.folder]=(folders[row.folder]?:0)+1
                tags+=noteTags.filter{it.isNotBlank()}
            }else trashed++
            if((request.filter=="trash")!=(row.trashedAt!=null))continue
            if(!when{
                request.filter=="favorite"->row.favorite
                request.filter=="unfiled"->row.folder.isBlank()
                request.filter.startsWith("folder:")->row.folder==request.filter.removePrefix("folder:")
                request.filter.startsWith("tag:")->request.filter.removePrefix("tag:") in noteTags
                else->true
            })continue
            if(request.type!="all"&&(request.type=="board")!=row.world)continue
            candidates+=LibraryShelfItem(note,row)
        }
        val ordered=ShelfOrder.arrange(candidates,{it.workspace.pinned},{it.note.title},request.byTitle)
            var scanned=0;var offset=request.offset.coerceAtMost(ordered.size)
            val matched=ArrayList<LibraryShelfItem>();var nextOffset:Int?=null
            val terms=request.query.takeIf{it.isNotBlank()}?.let(LibrarySearchTerms::query)
            val pageHits=HashMap<String,String>();val cardHits=HashSet<String>()
            if(terms!=null){
                val pageQuery="""
                    SELECT p.notebookId,p.id,'',s.text FROM (
                        SELECT target FROM library_search_pending WHERE kind=0
                        UNION SELECT d.target FROM library_search_fts f CROSS JOIN library_search_docs d ON d.key=f.docid WHERE f.terms MATCH ? AND d.kind=0
                    ) hit CROSS JOIN page_search_text s ON s.pageId=hit.target
                    CROSS JOIN notebook_pages p ON p.id=s.pageId LEFT JOIN ink_pages h ON h.noteId=p.id
                    WHERE p.trashedAt IS NULL AND s.inkRevision=COALESCE(h.revision,0)
                    ORDER BY p.notebookId,p.position,p.id
                """.trimIndent()
                val cardQuery="""
                    SELECT c.notebookId,c.id,c.title,c.body FROM (
                        SELECT target FROM library_search_pending WHERE kind=1
                        UNION SELECT d.target FROM library_search_fts f CROSS JOIN library_search_docs d ON d.key=f.docid WHERE f.terms MATCH ? AND d.kind=1
                    ) hit CROSS JOIN study_cards c ON c.id=hit.target
                    WHERE c.trashedAt IS NULL
                    ORDER BY c.notebookId,c.id
                """.trimIndent()
                for((isPage,query) in listOf(true to pageQuery,false to cardQuery))sql.query(query,arrayOf(terms)).use{rows->while(rows.moveToNext()){
                    activeContext.ensureActive();scanObserver?.invoke(request.query,++scanned)
                    val book=rows.getString(0)
                    if(book in display&&(rows.getString(2).contains(request.query,true)||rows.getString(3).contains(request.query,true))){
                        if(isPage)pageHits.putIfAbsent(book,rows.getString(1))else cardHits.add(book)
                    }
                }}
            }
            while(offset<ordered.size&&matched.size<=request.pageSize){
                activeContext.ensureActive();val item=ordered[offset++];val note=item.note
                val page=pageHits[note.id];val card=note.id in cardHits
                if(terms==null||note.title.contains(request.query,true)||note.text.contains(request.query,true)||
                    item.workspace.tags.contains(request.query,true)||page!=null||card){
                    if(matched.size==request.pageSize){nextOffset=offset-1;break}
                    matched+=item.copy(pageId=page,cardMatch=card)
                }
            }
            activeContext.ensureActive()
            LibrarySearchResult(request.generation,matched,active,folders,tags.toList(),favorites,unfiled,trashed,
                query=request.query,filter=request.filter,type=request.type,byTitle=request.byTitle,
                offset=request.offset,nextOffset=nextOffset,pendingIndex=sql.query("SELECT COUNT(*) FROM library_search_pending").use{it.moveToFirst();it.getInt(0)})
        }
        // Indexed and pending sources share this snapshot. This result is complete even on
        // the first query; no partial cache result is ever labelled as the complete library.
        db.withTransaction{readSnapshot(db.openHelper.writableDatabase)}
    }
}
