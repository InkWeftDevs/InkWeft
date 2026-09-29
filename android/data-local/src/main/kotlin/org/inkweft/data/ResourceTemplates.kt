// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.*
import java.util.UUID

/** Template bytes become independent author data in the same transaction as provenance. */
class ResourceTemplates(private val db:NoteDatabase,private val fault:(String)->Unit={}){
    private fun provenance(operation:String)=UUID.nameUUIDFromBytes("resource:$operation".toByteArray()).toString()
    suspend fun hasReceipt(operation:String,hash:String):Boolean= db.libraryContent().receipt(provenance(operation))?.let{require(it.kind=="RESOURCE"&&it.digest==hash);true}?:false
    suspend fun created(operation:String,hash:String):Note?= db.libraryContent().receipt(operation)?.let{require(it.kind=="RESOURCE"&&it.digest==hash);checkNotNull(NoteRepository(db).read(it.noteId))}
    suspend fun inserted(hash:String,command:InsertPages):InsertPagesResult?=if(hasReceipt(command.commandId,hash))PageInsertionRepository(db).insert(command)else null
    suspend fun insert(hash:String,command:InsertPages,document:PdfPageSource?):InsertPagesResult=db.withTransaction {
        inserted(hash,command)?.let{return@withTransaction it}
        val result=PageInsertionRepository(db).insert(command)
        if(result is InsertPagesResult.Applied){
            require(!result.replayed){"RESOURCE_RECEIPT_MISSING"}
            command.pageIds.forEach{DocumentRepository(db).attach(it,document)}
            db.libraryContent().insert(LibraryContentReceipt(provenance(command.commandId),"RESOURCE",hash,command.notebookId))
        };result
    }
    suspend fun map(hash:String,command:KnowledgeCommand):String=db.withTransaction {
        require(command.data is KnowledgeData.MapDefinition&&command.expectedRevision==0L&&!command.removed)
        val result=KnowledgeRepository(db).submit(command)
        if(!hasReceipt(command.operationId,hash))db.libraryContent().insert(LibraryContentReceipt(provenance(command.operationId),"RESOURCE",hash,command.notebookId))
        result
    }
    suspend fun instantiate(hash:String,title:String,paper:PaperStyle,document:PdfPageSource?,map:KnowledgeData.MapTemplate?,operation:String=UUID.randomUUID().toString(),cover:NotebookCover=NotebookCover.AUTO,custom:ByteArray?=null):Note=db.withTransaction {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        db.libraryContent().receipt(operation)?.let{r->require(r.kind=="RESOURCE"&&r.digest==hash);return@withTransaction checkNotNull(NoteRepository(db).read(r.noteId))}
        val note=WorkspaceRepository(db).create(title,false,paper,cover,customCover=custom)
        fault("after-author");DocumentRepository(db).attach(note.id,document);fault("after-document")
        if(map!=null){
            KnowledgeRepository(db).submit(KnowledgeCommand(UUID.randomUUID().toString(),note.id,UUID.randomUUID().toString(),0,MapTemplates.instantiate(map,title)))
        }
        fault("before-reference");db.libraryContent().insert(LibraryContentReceipt(operation,"RESOURCE",hash,note.id))
        note
    }.also{fault("after-commit")}
    suspend fun copies(hash:String)=db.libraryContent().resourceCopies(hash)
}
