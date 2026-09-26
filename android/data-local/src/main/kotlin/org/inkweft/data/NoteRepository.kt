// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.inkweft.core.*

enum class NoteFaultPoint { BEFORE_RENAME_RECEIPT, AFTER_RENAME_TRANSACTION }

/** Single local-user author-write entry; title operations never accept body text. */
class NoteRepository(private val db:NoteDatabase,private val fault:(NoteFaultPoint)->Unit={}) {
    private val dao=db.notes()
    fun observeNotes():Flow<List<Note>> = dao.observeNotes().map{rows->rows.map{it.domain()}}
    suspend fun read(id:String):Note?=dao.note(id)?.domain()

    suspend fun save(command:SaveNote):SaveResult=db.withTransaction {
        val digest=command.digest()
        val previous=dao.receipt(command.commandId)
        if(previous!=null)return@withTransaction replay(previous,command.noteId,digest)
        val current=dao.note(command.noteId)
        if(current?.revision!=command.expectedRevision && !(current==null&&command.expectedRevision==0L))return@withTransaction SaveResult.Conflict(current?.domain())
        val revision=command.expectedRevision+1;val at=System.currentTimeMillis()
        val row=NoteRow(command.noteId,revision,command.title,command.text,at)
        if(current==null)dao.insertNote(row)
        else if(dao.compareAndSet(row.id,command.expectedRevision,revision,row.title,row.text,at)!=1)return@withTransaction SaveResult.Conflict(dao.note(row.id)?.domain())
        dao.insertRevision(NoteRevisionRow(row.id,revision,row.title,row.text,at))
        dao.insertReceipt(ReceiptRow(command.commandId,row.id,digest,revision))
        SaveResult.Committed(row.domain())
    }
    suspend fun rename(command:RenameNote):SaveResult {
        val result=db.withTransaction {
            val digest=command.digest();val previous=dao.receipt(command.commandId)
            if(previous!=null)return@withTransaction replay(previous,command.noteId,digest)
            val current=dao.note(command.noteId)
            if(current==null||current.revision!=command.expectedRevision)return@withTransaction SaveResult.Conflict(current?.domain())
            if(db.workspace().get(current.id)?.trashedAt!=null)return@withTransaction SaveResult.Conflict(current.domain())
            val next=current.revision+1;val at=System.currentTimeMillis()
            if(dao.compareAndSet(current.id,current.revision,next,command.title,current.text,at)!=1)return@withTransaction SaveResult.Conflict(dao.note(current.id)?.domain())
            dao.insertRevision(NoteRevisionRow(current.id,next,command.title,current.text,at))
            fault(NoteFaultPoint.BEFORE_RENAME_RECEIPT)
            dao.insertReceipt(ReceiptRow(command.commandId,current.id,digest,next))
            SaveResult.Committed(Note(current.id,next,command.title,current.text))
        }
        fault(NoteFaultPoint.AFTER_RENAME_TRANSACTION)
        return result
    }
    private suspend fun replay(row:ReceiptRow,noteId:String,digest:String):SaveResult {
        if(row.noteId!=noteId||row.digest!=digest)return SaveResult.ReusedCommandId
        val history=checkNotNull(dao.revision(row.noteId,row.committedRevision)){"Receipt revision unavailable; do not reset storage"}
        return SaveResult.Committed(Note(history.noteId,history.revision,history.title,history.text))
    }
    private fun NoteRow.domain()=Note(id,revision,title,text)
}
