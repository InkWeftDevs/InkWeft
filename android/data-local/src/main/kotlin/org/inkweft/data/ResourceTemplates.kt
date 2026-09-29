// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.room.withTransaction
import org.inkweft.core.*
import java.util.UUID

/** Template bytes become independent author data in the same transaction as provenance. */
class ResourceTemplates(private val db:NoteDatabase){
    suspend fun instantiate(hash:String,title:String,paper:PaperStyle,document:PdfPageSource?,map:KnowledgeData.MapTemplate?,operation:String=UUID.randomUUID().toString()):Note=db.withTransaction {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        db.libraryContent().receipt(operation)?.let{r->require(r.kind=="RESOURCE"&&r.digest==hash);return@withTransaction checkNotNull(NoteRepository(db).read(r.noteId))}
        val note=WorkspaceRepository(db).create(title,false,paper)
        DocumentRepository(db).attach(note.id,document)
        if(map!=null){
            KnowledgeRepository(db).submit(KnowledgeCommand(UUID.randomUUID().toString(),note.id,UUID.randomUUID().toString(),0,MapTemplates.instantiate(map,title)))
        }
        db.libraryContent().insert(LibraryContentReceipt(operation,"RESOURCE",hash,note.id))
        note
    }
    suspend fun copies(hash:String)=db.libraryContent().resourceCopies(hash)
}
