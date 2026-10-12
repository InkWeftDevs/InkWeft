// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.inkweft.core.*

data class LibrarySearchRequest(val generation:Long,val notes:List<Note>,val drafts:Map<String,NoteDraft>,
    val query:String="",val filter:String="all",val type:String="all",val byTitle:Boolean=false)
data class LibraryShelfItem(val note:Note,val workspace:WorkspaceRow,val pageId:String?=null,val cardMatch:Boolean=false)
data class LibrarySearchResult(val generation:Long=0,val items:List<LibraryShelfItem> = emptyList(),
    val active:List<Note> = emptyList(),val folders:Map<String,Int> = emptyMap(),val tags:List<String> = emptyList(),
    val favorites:Int=0,val unfiled:Int=0,val trashed:Int=0,val failed:Boolean=false,
    val query:String="",val filter:String="all",val type:String="all",val byTitle:Boolean=false)

/** One cancellable pass over current search text; no library-sized source payloads enter UI state. */
class LibrarySearchRepository(private val db:NoteDatabase) {
    internal var scanObserver:((String,Int)->Unit)?=null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(requests:Flow<LibrarySearchRequest>):Flow<LibrarySearchResult> {
        var previous:LibrarySearchRequest?=null
        return combine(requests,db.invalidationTracker.createFlow(
            "notebook_workspace","notebook_pages","page_search_text","ink_pages","study_cards"
        )){request,_->request}.mapLatest{request->
            val before=previous;previous=request
            // Text edits debounce; deleting the query and changing a category take effect immediately.
            if(request.query.isNotBlank()&&before?.query!=request.query&&
                (before==null||before.filter==request.filter&&before.type==request.type&&before.byTitle==request.byTitle))delay(150)
            try{read(request)}catch(c:CancellationException){throw c}
            catch(_:Exception){LibrarySearchResult(generation=request.generation,failed=true,query=request.query,
                filter=request.filter,type=request.type,byTitle=request.byTitle)}
        }
    }

    suspend fun read(request:LibrarySearchRequest):LibrarySearchResult=withContext(Dispatchers.IO){
        val activeContext=currentCoroutineContext()
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
        val pages=mutableMapOf<String,Pair<Int,String>>();val cards=mutableSetOf<String>()
        if(request.query.isNotBlank()&&candidates.isNotEmpty()){
            val ids=candidates.map{it.note.id};val marks=ids.joinToString(","){"?"}
            // Both branches belong to one SQLite statement snapshot. Stale OCR/manual rows and
            // recycled pages/cards never contribute a hit; page hits retain navigation priority.
            val query="""
                SELECT p.notebookId AS notebookId,0 AS kind,p.id AS target,p.position AS position,'' AS title,s.text AS body
                FROM page_search_text s JOIN notebook_pages p ON p.id=s.pageId LEFT JOIN ink_pages h ON h.noteId=p.id
                WHERE p.trashedAt IS NULL AND s.inkRevision=COALESCE(h.revision,0) AND p.notebookId IN ($marks)
                UNION ALL
                SELECT notebookId,1,id,0,title,body FROM study_cards WHERE trashedAt IS NULL AND notebookId IN ($marks)
            """.trimIndent()
            var scanned=0
            db.openHelper.readableDatabase.query(query,(ids+ids).toTypedArray()).use{rows->while(rows.moveToNext()){
                activeContext.ensureActive();scanObserver?.invoke(request.query,++scanned)
                val book=rows.getString(0);val page=rows.getInt(1)==0
                val position=rows.getInt(3);val target=rows.getString(2);val first=pages[book]
                if(if(page)first!=null&&(position>first.first||position==first.first&&target>=first.second) else book in cards)continue
                if(rows.getString(4).contains(request.query,true)||rows.getString(5).contains(request.query,true)){
                    if(page)pages[book]=position to target else cards+=book
                }
            }}
        }
        val matched=candidates.mapNotNull{item->
            activeContext.ensureActive();val note=item.note
            if(request.query.isBlank()||note.title.contains(request.query,true)||note.text.contains(request.query,true)||
                item.workspace.tags.contains(request.query,true)||note.id in pages||note.id in cards)
                item.copy(pageId=pages[note.id]?.second,cardMatch=note.id in cards)else null
        }
        val ordered=ShelfOrder.arrange(matched,{it.workspace.pinned},{it.note.title},request.byTitle)
        activeContext.ensureActive()
        LibrarySearchResult(request.generation,ordered,active,folders,tags.toList(),favorites,unfiled,trashed,
            query=request.query,filter=request.filter,type=request.type,byTitle=request.byTitle)
    }
}
