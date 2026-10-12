// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ContinuousPreparationAtomicityTest {
    private fun id()=UUID.randomUUID().toString()
    private fun main(action:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync{action()}

    @Test fun secondObjectScopeFailureLeavesAllModelsAndAuthorRowsUnchanged()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="prepare-atomicity-${id()}.db"
        val db=NoteDatabase.open(context,name);val store=ViewModelStore()
        val ink=InkRepository(db);val objects=PageObjectRepository(db)
        try{
            val first=WorkspaceRepository(db).create("合成跨页擦除准备失败",false,PaperStyle.BLANK).id
            val second=NotebookPages(db).addAfter(first,first,id()).id
            val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),3f,InkTool.STYLUS,
                listOf(InkSample(110f,130f,0),InkSample(250f,130f,30)))
            assertTrue(ink.save(CommitInk(id(),first,0,InkMutation.Add(stroke))) is InkCommitResult.Committed)
            val formulaA=PageObject(id(),PageObjectKind.FORMULA,x=100f,y=100f,width=160f,height=80f,text="x")
            val formulaB=formulaA.copy(id=id())
            objects.save(first,0,id(),listOf(formulaA));objects.save(second,0,id(),listOf(formulaB))
            val beforeInk=ink.read(first);val beforeObjects=listOf(objects.read(first),objects.read(second))
            fun receipts()=db.openHelper.readableDatabase.query("SELECT (SELECT COUNT(*) FROM ink_receipts)+(SELECT COUNT(*) FROM object_receipts)").use{it.moveToFirst();it.getLong(0)}
            val beforeReceipts=receipts()
            lateinit var inkA:InkViewModel;lateinit var inkB:InkViewModel
            lateinit var objectA:PageObjectViewModel;lateinit var objectB:PageObjectViewModel
            lateinit var writer:ContinuousInkWriter
            val secondScopeCalls=AtomicInteger();val rejected=CompletableDeferred<String>()
            main{
                inkA=ViewModelProvider(store,InkViewModel.Factory(first,ink))["ink-a",InkViewModel::class.java]
                inkB=ViewModelProvider(store,InkViewModel.Factory(second,ink))["ink-b",InkViewModel::class.java]
                objectA=ViewModelProvider(store,PageObjectViewModel.Factory(first,objects))["objects-a",PageObjectViewModel::class.java]
                objectB=ViewModelProvider(store,PageObjectViewModel.Factory(second,objects))["objects-b",PageObjectViewModel::class.java]
                writer=ViewModelProvider(store,ViewModelProvider.NewInstanceFactory())[ContinuousInkWriter::class.java]
                inkA.layerScopeProvider={LayerWriteScope(UserLayers.DEFAULT_ID,0)}
                inkB.layerScopeProvider={LayerWriteScope(UserLayers.DEFAULT_ID,0)}
                objectA.layerScopeProvider={LayerWriteScope(UserLayers.DEFAULT_ID,0)}
                // Models may be ready while an adjacent page no longer has a selected writable layer.
                objectB.layerScopeProvider={secondScopeCalls.incrementAndGet();error("LAYER_CHOOSE_WRITABLE")}
                writer.onNotice={rejected.complete(it)}
            }
            withTimeout(10_000){
                inkA.ui.first{!it.loading&&it.canStart};inkB.ui.first{!it.loading&&it.canStart}
                objectA.ui.first{!it.loading&&!it.busy&&!it.pending};objectB.ui.first{!it.loading&&!it.busy&&!it.pending}
            }
            val path=listOf(InkSample(90f,130f,0),InkSample(280f,130f,40))
            main{writer.erase(listOf(PageEraseTarget(inkA,objectA,path),PageEraseTarget(inkB,objectB,path)),12f,true,false,ink)}
            withTimeout(10_000){rejected.await()}
            assertTrue("The second object's scope must be checked",secondScopeCalls.get()>0)
            main{
                for(model in listOf(inkA,inkB)){
                    assertEquals("Failed preparation must not enqueue an ink prefix",0,model.ui.value.queued)
                    assertFalse(model.ui.value.processing);assertNull(model.ui.value.blocked);assertTrue(model.ui.value.canStart)
                }
                for(model in listOf(objectA,objectB)){
                    assertFalse("Failed preparation must not retain an object draft",model.ui.value.pending)
                    assertFalse("Failed preparation must not strand the object model busy",model.ui.value.busy)
                }
                assertEquals(listOf(formulaA),objectA.ui.value.objects);assertEquals(listOf(formulaB),objectB.ui.value.objects)
                assertFalse(writer.blocked.value);assertFalse(writer.needsRetry.value)
            }
            val afterInk=ink.read(first)
            assertEquals(beforeInk.revision,afterInk.revision)
            assertEquals(beforeInk.strokes.map{Triple(it.stroke.id,it.visible,it.createdRevision)},afterInk.strokes.map{Triple(it.stroke.id,it.visible,it.createdRevision)})
            beforeInk.strokes.zip(afterInk.strokes).forEach{(before,after)->assertArrayEquals(InkStrokeCodec.encode(before.stroke),InkStrokeCodec.encode(after.stroke))}
            assertEquals(beforeInk.cuts.size,afterInk.cuts.size)
            assertEquals(beforeObjects,listOf(objects.read(first),objects.read(second)))
            assertEquals(beforeReceipts,receipts())
        }finally{main{store.clear()};db.close();context.deleteDatabase(name)}
    }
}
