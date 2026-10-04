// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AuthoringPendingStoreTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun journalFaultPrecedesDatabaseAndDurableReopenKeepsOneOperation()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="author-journal-${id()}.db";val db=NoteDatabase.open(context,name)
        val dir=File(context.cacheDir,"author-journal-${id()}")
        try{
            val page=WorkspaceRepository(db).create("合成恢复",false,PaperStyle.BLANK).id;val scope=AuthoringScope.page(page,page);val repo=PageAuthoringRepository(db);val before=repo.read(scope)
            val after=before.state.withLayers(before.state.layers.add(UserLayer(id(),"恢复层")))
            val pending=AuthoringPending(scope,id(),before,after)
            try{AuthoringPendingStore(dir){if(it=="before-commit")error("fault")}.save(pending);fail()}catch(_:IllegalStateException){}
            assertNull(repo.confirmed(pending));assertEquals(0L,repo.read(scope).revision)
            val store=AuthoringPendingStore(dir);store.save(pending)
            val recovered=AuthoringPendingStore(dir).read(scope)!!;assertEquals(pending.commandId,recovered.commandId)
            assertEquals(PageAuthoringCodec.fingerprint(after),PageAuthoringCodec.fingerprint(recovered.after))
            assertEquals(1L,repo.save(scope,recovered.before,recovered.commandId,recovered.after));assertEquals(1L,repo.confirmed(recovered))
            assertEquals(1L,repo.save(scope,recovered.before,recovered.commandId,recovered.after))
            store.remove(scope,pending.commandId);assertNull(store.read(scope))
        }finally{db.close();context.deleteDatabase(name);dir.deleteRecursively()}
    }
    @Test fun lostCommitResponseAndCorruptJournalNeverCreateNewIdentityOrOverwrite()=runBlocking{
        val context=ApplicationProvider.getApplicationContext<Context>();val name="author-lost-${id()}.db";val db=NoteDatabase.open(context,name)
        val dir=File(context.cacheDir,"author-lost-${id()}")
        try{
            val page=WorkspaceRepository(db).create("合成丢响应",false,PaperStyle.BLANK).id;val scope=AuthoringScope.page(page,page);val repo=PageAuthoringRepository(db);val before=repo.read(scope)
            val p=AuthoringPending(scope,id(),before,before.state.withBlanks(listOf(DocumentWhitespace(id(),300.0))))
            val journal=AuthoringPendingStore(dir);journal.save(p)
            try{PageAuthoringRepository(db){if(it==AuthoringFault.AFTER_TRANSACTION)error("lost reply")}.save(scope,p.before,p.commandId,p.after);fail()}catch(_:IllegalStateException){}
            val recovered=AuthoringPendingStore(dir).read(scope)!!;assertEquals(1L,repo.confirmed(recovered));assertEquals(1L,repo.save(scope,recovered.before,recovered.commandId,recovered.after))
            val path=File(File(dir,"authoring"),"PAGE-$page.iwpending");val bytes=path.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();path.writeBytes(bytes)
            try{journal.read(scope);fail()}catch(_:IllegalArgumentException){}
            try{journal.save(AuthoringPending(scope,id(),p.before,p.after));fail()}catch(_:IllegalArgumentException){}
            assertArrayEquals(bytes,path.readBytes());assertEquals(1L,repo.read(scope).revision)
        }finally{db.close();context.deleteDatabase(name);dir.deleteRecursively()}
    }
}
