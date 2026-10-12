// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NotebookHeaderLoadingTest {
    private fun id()=UUID.randomUUID().toString()
    private suspend fun note(app:InkWeftApplication,title:String,body:String):Note {
        val created=app.workspaceRepository.create(title,false,PaperStyle.BLANK)
        return (app.repository.save(SaveNote(id(),created.id,created.revision,title,body)) as SaveResult.Committed).note
    }
    private suspend fun ready(vm:NotebookViewModel,id:String)=withTimeout(10_000){vm.ui.first{it.current?.base?.id==id}}
    private suspend fun model(app:InkWeftApplication,store:ViewModelStore,saved:SavedStateHandle=SavedStateHandle())=
        withContext(Dispatchers.Main){NotebookViewModel(app,saved).also{store.put(id(),it)}}

    @Test fun delayedOpenCannotReplaceNewSelectionOrEditsAndAlwaysLoadsFullBody()=runBlocking{
        val app=ApplicationProvider.getApplicationContext<InkWeftApplication>();val store=ViewModelStore()
        val first=note(app,"Header loading A", "a".repeat(90_000));val second=note(app,"Header loading B","b".repeat(20_000))
        val vm=model(app,store);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try{
            withContext(Dispatchers.Main){vm.readSelection={key->if(key==first.id){entered.complete(Unit);withContext(NonCancellable){release.await()};first}else app.repository.read(key)}
                vm.select(first.copy(text="preview"))}
            withTimeout(5_000){entered.await()}
            withContext(Dispatchers.Main){vm.select(second.copy(text="preview"))}
            assertEquals(second.text,ready(vm,second.id).current!!.text)
            withContext(Dispatchers.Main){vm.edit("Draft B","local unsaved B")}
            release.complete(Unit);yield()
            withContext(Dispatchers.Main){assertEquals(second.id,vm.ui.value.selectedId);assertEquals("local unsaved B",vm.ui.value.current!!.text)}
            val pending=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
            withContext(Dispatchers.Main){vm.readSelection={key->if(key==first.id){pending.complete(Unit);finish.await();first}else app.repository.read(key)};vm.select(first.copy(text=""))}
            withTimeout(5_000){pending.await()}
            withContext(Dispatchers.Main){vm.edit("Draft B later","keep this edit")}
            finish.complete(Unit)
            assertEquals(first.text,ready(vm,first.id).current!!.text)
            withContext(Dispatchers.Main){assertEquals("keep this edit",vm.ui.value.drafts.getValue(second.id).text)
                vm.edit("Title only edit",vm.ui.value.current!!.text);vm.save()}
            withTimeout(10_000){vm.ui.first{it.current?.phase==SavePhase.EDITING&&it.current?.dirty==false}}
            assertEquals(first.text,app.repository.read(first.id)!!.text)
        }finally{release.complete(Unit);withContext(Dispatchers.Main){store.clear()}}
    }
    @Test fun restoreAndRenameLoadAuthorBodyAndBackCancelsPendingOpen()=runBlocking{
        val app=ApplicationProvider.getApplicationContext<InkWeftApplication>();val store=ViewModelStore()
        val note=note(app,"Restored header", "body".repeat(20_000))
        val vm=model(app,store,SavedStateHandle(mapOf("notebook.selected" to note.id)))
        val cancelledRelease=CompletableDeferred<Unit>()
        try{
            assertEquals(note.text,ready(vm,note.id).current!!.text)
            val fresh=model(app,store);withTimeout(10_000){fresh.ui.first{!it.loading}}
            val header=fresh.ui.value.notes.single{it.id==note.id};assertEquals("",header.text)
            withContext(Dispatchers.Main){fresh.openRename(header)}
            withTimeout(10_000){fresh.renaming.first{it!=null}}
            assertEquals(note.text,fresh.renaming.value!!.base.text)
            withContext(Dispatchers.Main){fresh.select(header)};ready(fresh,note.id)
            withContext(Dispatchers.Main){fresh.edit("Restored header","unsaved while renaming");fresh.editRename("Renamed safely");fresh.saveRename()}
            withTimeout(10_000){fresh.renaming.first{it==null}}
            assertEquals(note.text,app.repository.read(note.id)!!.text)
            assertEquals("unsaved while renaming",fresh.ui.value.current!!.text)
            val other=note(app,"Cancelled header","full author text");val entered=CompletableDeferred<Unit>();val release=cancelledRelease
            withContext(Dispatchers.Main){fresh.readSelection={entered.complete(Unit);withContext(NonCancellable){release.await()};other};fresh.select(other.copy(text=""))}
            withTimeout(5_000){entered.await()}
            withContext(Dispatchers.Main){fresh.back()};release.complete(Unit);yield()
            withContext(Dispatchers.Main){assertNull(fresh.ui.value.selectedId)}
        }finally{cancelledRelease.complete(Unit);withContext(Dispatchers.Main){store.clear()}}
    }
    @Test fun failedAwaitedNavigationKeepsSourceAndRetryLoadsTheFailedTarget()=runBlocking{
        val app=ApplicationProvider.getApplicationContext<InkWeftApplication>();val store=ViewModelStore()
        val destination=note(app,"Awaited destination","complete destination body")
        val vm=model(app,store)
        val workspace=withContext(Dispatchers.Main){WorkspaceViewModel(app,SavedStateHandle()).also{store.put(id(),it)}}
        try{
            withTimeout(10_000){vm.ui.first{!it.loading}}
            withContext(Dispatchers.Main){vm.readSelection={throw java.io.IOException("synthetic body read failure")}}
            var sourceDismissed=false
            val opened=withContext(Dispatchers.Main){workspace.openPageAwait(destination.id,destination.id){
                vm.selectAwait(destination.copy(text="preview")).also{if(it)sourceDismissed=true}
            }}
            assertFalse(opened);assertFalse(sourceDismissed);assertNull(vm.ui.value.selectedId)
            assertFalse(workspace.pendingPageNavigation.value.containsKey(destination.id))
            withContext(Dispatchers.Main){vm.readSelection=app.repository::read;vm.retryRead()}
            assertEquals(destination.text,ready(vm,destination.id).current!!.text)
        }finally{withContext(Dispatchers.Main){store.clear()}}
    }

    @Test fun cancelledNavigationCallerCannotPublishAfterNonCancellableRead()=runBlocking{
        val app=ApplicationProvider.getApplicationContext<InkWeftApplication>();val store=ViewModelStore()
        val destination=note(app,"Cancelled source scope","complete body")
        val vm=model(app,store);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try{
            withTimeout(10_000){vm.ui.first{!it.loading}}
            withContext(Dispatchers.Main){vm.readSelection={entered.complete(Unit);withContext(NonCancellable){release.await()};destination}}
            val opening=async(Dispatchers.Main){vm.selectAwait(destination.copy(text="preview"))}
            withTimeout(5_000){entered.await()}
            opening.cancel();release.complete(Unit);opening.join()
            assertNull(vm.ui.value.selectedId);assertFalse(vm.ui.value.drafts.containsKey(destination.id))
            withContext(Dispatchers.Main){vm.retryRead()}
            withTimeout(10_000){vm.ui.first{!it.loading}}
            assertNull(vm.ui.value.selectedId)
        }finally{release.complete(Unit);withContext(Dispatchers.Main){store.clear()}}
    }

}
