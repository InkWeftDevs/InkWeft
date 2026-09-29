package org.inkweft.app

import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.util.UUID

/** Explicit disposable-emulator runner: native gestures, process death, native reopen and undo. */
class ContinuousCrashProbe {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun nativeCrossPageRecovery(){
        val args=InstrumentationRegistry.getArguments();require(args.getString("groupProbe")=="dedicated-emulator")
        val app=compose.activity.application as InkWeftApplication
        val marker=File(app.filesDir,"continuous-crash-probe.json");val cut=args.getString("cut")!!
        fun persist(m:JSONObject){FileOutputStream(marker).use{it.write(m.toString().toByteArray());it.fd.sync()}}
        fun open(note:Note){
            compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
            compose.waitUntil(15_000){app.navigationReady.value&&compose.onAllNodesWithTag("continuous-pages").fetchSemanticsNodes().isNotEmpty()}
        }
        if(args.getString("phase")=="prepare"){
            require(!marker.exists())
            runBlocking{app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}}}
            val note=runBlocking{app.workspaceRepository.create("整笔终止 · 合成三页",false,PaperStyle.DOTS).also{n->var previous=n.id;repeat(2){previous=app.pages.addAfter(n.id,previous,UUID.randomUUID().toString()).id};app.pages.select(n.id,n.id)}}
            open(note);persist(JSONObject().put("book",note.id).put("cut",cut))
            app.inkRepository.groupFaultForTest={point->if(point==cut){
                persist(JSONObject(marker.readText()).put("reached",point))
                android.os.Process.killProcess(android.os.Process.myPid())
            }}
            compose.runOnIdle{
                val view=checkNotNull(compose.activity.window.decorView.findViewWithTag<InkCanvasView>("ink-page-${note.id}"))
                assertTrue(view.inputReady);view.pen=InkPen.PENCIL
                val checkpoint=view.onCheckpoint;view.onCheckpoint={stroke->persist(JSONObject(marker.readText()).put("stroke",stroke.id));checkpoint(stroke)}
                val now=SystemClock.uptimeMillis();val density=view.resources.displayMetrics.density.toDouble()
                repeat(360){i->
                    val p=view.snapshotViewport().worldToScreen(300.0+i*.2,1300.0+i*5,view.width.toDouble(),view.height.toDouble(),density)
                    val prop=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                    val coord=MotionEvent.PointerCoords().apply{x=p.x.toFloat();y=p.y.toFloat();pressure=.2f+i*.001f;size=.1f}
                    val action=if(i==0)MotionEvent.ACTION_DOWN else if(i==359&&cut !in listOf("before-prefix","after-prefix","after-cancel"))MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    MotionEvent.obtain(now,now+i*10L,action,1,arrayOf(prop),arrayOf(coord),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0).also{try{assertTrue(view.dispatchTouchEvent(it))}finally{it.recycle()}}
                }
                if(cut=="after-cancel")view.cancelGesture()
            }
            compose.waitUntil(20_000){false};fail("Cut not reached")
        }else{
            val m=JSONObject(marker.readText());assertEquals(cut,m.getString("reached"))
            val note=runBlocking{checkNotNull(app.repository.read(m.getString("book")))}
            val prefix=runBlocking{app.inkRepository.pendingGroups(note.id).singleOrNull()}
            val variation=args.getString("variation")?:"normal"
            runBlocking{
                val original=app.pages.activePages(note.id)
                if(variation in listOf("reorder","recycle")){
                    assertTrue(app.pages.edit(EditPage(UUID.randomUUID().toString(),note.id,original[1].id,
                        if(variation=="reorder")PageEditKind.MOVE else PageEditKind.TRASH,InsertPages.orderHash(original.map{it.id}),0,stayOnPageId=note.id)) is EditPageResult.Applied)
                    if(variation=="reorder")app.pages.addAfter(note.id,note.id,UUID.randomUUID().toString())
                }
                if(variation=="revision")app.inkRepository.save(CommitInk(UUID.randomUUID().toString(),note.id,0,InkMutation.Add(InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff000000.toInt(),3f,InkTool.STYLUS,listOf(InkSample(20f,20f,0))))))
            }
            if(variation in listOf("recycle","revision")){
                compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
                compose.waitUntil(15000){compose.onAllNodesWithTag("group-recovery-retry").fetchSemanticsNodes().isNotEmpty()}
                assertEquals(prefix!!.stroke.id,runBlocking{app.inkRepository.pendingGroups(note.id).single().stroke.id})
                if(variation=="recycle")assertFalse(runBlocking{app.pages.activePages(note.id)}.any{it.id==prefix.pages[1]})
                assertEquals(if(variation=="revision")1 else 0,runBlocking{prefix.pages.sumOf{app.inkRepository.read(it).strokes.size}})
                persist(m.put("verified",true).put("variation",variation).put("draftPreserved",true));return
            }
            open(note)
            val pages=runBlocking{app.pages.activePages(note.id)}
            val empty=cut in listOf("before-prefix","after-cancel")
            compose.waitUntil(15_000){app.navigationReady.value&&runBlocking{app.inkRepository.pendingGroups(note.id).isEmpty()}}
            val strokes=runBlocking{pages.flatMap{app.inkRepository.read(it.id).strokes}}
            if(empty)assertTrue(strokes.isEmpty()) else{
                assertTrue(strokes.size>=2);assertEquals(strokes.size,strokes.map{it.stroke.id}.distinct().size)
                assertTrue(strokes.all{it.stroke.pen==InkPen.PENCIL})
                prefix?.commands()?.forEach{command->val expected=(command.mutation as InkMutation.Replace).added
                    val actual=runBlocking{app.inkRepository.read(command.noteId).strokes.map{it.stroke}}
                    assertEquals(expected.map{it.id},actual.map{it.id});expected.zip(actual).forEach{(a,b)->assertArrayEquals(InkStrokeCodec.encode(a),InkStrokeCodec.encode(b))}}
                compose.onNodeWithTag("ink-undo").assertIsEnabled().performClick()
                compose.waitUntil(15_000){runBlocking{pages.all{InkSession(app.inkRepository.read(it.id)).visibleDraft().isEmpty()}}}
                compose.onNodeWithTag("ink-redo").performClick()
                compose.waitUntil(15_000){runBlocking{pages.sumOf{InkSession(app.inkRepository.read(it.id)).visibleDraft().size}==strokes.size}}
            }
            persist(m.put("verified",true).put("fragments",strokes.size).put("oneUndo",!empty))
        }
    }
}
