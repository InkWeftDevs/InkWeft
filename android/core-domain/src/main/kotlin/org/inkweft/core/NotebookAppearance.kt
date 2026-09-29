// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Stable presentation keys, not page templates or identity. No image assets. */
enum class NotebookCover(val key:String) {
    AUTO("auto"), CONTENT("content"), FOREST("forest"), INK("ink"), SAND("sand"),
    ROSE("rose"), LILAC("lilac"), GRID("grid"), WAVE("wave"), CUSTOM("custom");
    fun resolved(noteId:String):NotebookCover = if(this!=AUTO)this else
        listOf(FOREST,INK,SAND,ROSE,LILAC)[Math.floorMod(noteId.hashCode(),5)]
    companion object {
        fun fromKey(key:String)=entries.firstOrNull{it.key==key}?:AUTO
        fun validKey(key:String)=entries.any{it.key==key}
    }
}

/** Title-only command. The repository uses stored body text, never caller text. */
data class RenameNote(val commandId:String,val noteId:String,val expectedRevision:Long,val title:String) {
    init {
        UUID.fromString(commandId);UUID.fromString(noteId)
        require(expectedRevision in 1 until Long.MAX_VALUE-1)
        require(validTitle(title) && title==title.trim())
    }
    fun digest():String {
        val data=ByteArrayOutputStream()
        DataOutputStream(data).use { out ->
            listOf("inkweft.rename-note.v1",commandId,noteId,expectedRevision.toString(),title).forEach { field ->
                val bytes=field.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    companion object {
        fun validTitle(value:String)=value.isNotBlank() && value.length<=120 && value.none{it<' ' || it=='\u007f'}
    }
}

/** Rebase only title metadata, retaining unsaved body and any newer title edit. */
fun NoteDraft.acceptRenamedHead(requestedBase:Note,titleAtRequest:String,committed:Note):NoteDraft {
    require(committed.id==requestedBase.id && committed.revision==requestedBase.revision+1)
    require(committed.text==requestedBase.text){"Title-only result changed stored body"}
    if(base!=requestedBase || pending!=null || phase!=SavePhase.EDITING)return this
    return copy(base=committed,title=if(title==titleAtRequest)committed.title else title)
}
