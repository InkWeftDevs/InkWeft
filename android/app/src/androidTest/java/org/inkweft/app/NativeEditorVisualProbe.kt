package org.inkweft.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** One persisted synthetic notebook, reused across the old and candidate user APK. */
class NativeEditorVisualProbe {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun id()=UUID.randomUUID().toString()
    private inline fun<reified T:View> view():T {
        val queue=java.util.ArrayDeque<View>();queue.add(compose.activity.window.decorView)
        while(queue.isNotEmpty()){val v=queue.removeFirst();if(v is T&&v.isShown)return v;if(v is ViewGroup)for(i in 0 until v.childCount)queue.add(v.getChildAt(i))}
        error("Missing ${T::class.java.simpleName}")
    }
    private fun tap(tag:String){compose.revealAction(tag);val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick();compose.waitForIdle()}
    private fun shot(stage:String,name:String){
        compose.waitForIdle();SystemClock.sleep(180)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()!!
        try{File(app.getExternalFilesDir(null),"vis-$stage-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
    }
    private fun seed():JSONObject=runBlocking {
        require(InstrumentationRegistry.getArguments().getString("visualProbe")=="synthetic-tablet"||app.repository.observeNotes().first().isEmpty()){"Dedicated empty emulator required"}
        val book=app.workspaceRepository.create("V47 视觉样板 · 概率论",false,PaperStyle.DOTS).id
        val text=listOf("第二章  条件概率与独立性","01  已知信息怎样改变概率？","在事件 B 已经发生时，样本空间缩小到 B。\n先明确条件，再观察 A 与 B 的交集。","P(A | B) = P(A ∩ B) / P(B)","02  例题：连续两次不放回取球","袋中有 3 个红球、2 个白球。第一次取出红球后，\n第二次仍取红球的概率：2 / 4 = 1 / 2。","03  独立与互斥","独立：一个事件不改变另一个事件的概率。\n互斥：两个事件不能同时发生。","复习时检查：条件概率非零 → 交集 → 分母")
        app.pageObjects.save(book,0,id(),text.mapIndexed{i,s->PageObject(id(),PageObjectKind.TEXT,100f,90f+i*132,800f,125f,text=s,fontSize=if(i==0)32f else 23f,bold=i in listOf(0,1,4,6),color=if(i==3)0xff176bb5.toInt()else 0xff20242d.toInt())})
        repeat(12){i->val s=InkStroke(id(),InkPen.PEN,if(i%3==0)0xffb54d48.toInt()else 0xff286bb5.toInt(),2.5f,InkTool.STYLUS,List(25){j->InkSample(112f+j*20f,330f+i*66f+kotlin.math.sin(j*.4f)*4,j*8L,.5f)})
            app.inkRepository.save(CommitInk(id(),book,i.toLong(),InkMutation.Add(s)))}
        val root=id();val titles=listOf("条件概率","限定样本空间","判断独立性","代入例题","检查分母")
        titles.forEachIndexed{i,title->app.study.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=if(i==0)root else id(),parentId=if(i==0)null else root,title=title,body="${title}：回到原笔记核对定义与条件。",x=if(i==0)40.0 else 320.0,y=if(i==0)230.0 else 50.0+(i-1)*120.0))}
        JSONObject().put("book",book).put("root",root).put("runId",id())
    }
    private fun authorHash(book:String):String=runBlocking {
        val digest=MessageDigest.getInstance("SHA-256")
        app.pages.activePages(book).forEach{p->digest.update(p.id.toByteArray());digest.update(p.paper.toString().toByteArray());app.inkRepository.read(p.id).strokes.forEach{digest.update(InkStrokeCodec.encode(it.stroke))};digest.update(PageObjectCodec.encode(app.pageObjects.read(p.id).objects))}
        app.study.cards(book).first().sortedBy{it.id}.forEach{digest.update(it.toString().toByteArray())}
        app.study.nodes(book).first().sortedBy{it.id}.forEach{digest.update(it.toString().toByteArray())}
        digest.digest().joinToString(""){"%02x".format(it)}
    }
    @Test fun fiveStatesOnTheSameAuthorContent(){
        val args=InstrumentationRegistry.getArguments();require(args.getString("visualProbe") in listOf("dedicated-emulator","synthetic-tablet"))
        val stage=args.getString("stage")?:"candidate"
        val marker=File(app.filesDir,"native-visual-probe.json")
        val fixture=if(marker.exists())JSONObject(marker.readText())else seed().also{marker.writeText(it.toString())}
        val book=fixture.getString("book");val root=fixture.getString("root")
        if(!fixture.optBoolean("shapeAdded")){
            require(stage=="baseline"||args.getString("visualProbe")=="synthetic-tablet"){"Regenerate the paired baseline first"}
            runBlocking{val revision=app.inkRepository.read(book).revision
                val points=listOf(760f to 580f,870f to 470f,920f to 580f,760f to 580f)
                app.inkRepository.save(CommitInk(id(),book,revision,InkMutation.Add(InkStroke(id(),InkPen.PEN,0xff278c69.toInt(),3f,InkTool.STYLUS,points.mapIndexed{i,p->InkSample(p.first,p.second,i*100L,.5f)}))))}
            fixture.put("shapeAdded",true).remove("authorHash")
        }
        val before=authorHash(book);if(fixture.has("authorHash"))assertEquals(fixture.getString("authorHash"),before)
        fixture.put("authorHash",before);marker.writeText(fixture.toString())
        val note=runBlocking{app.repository.read(book)!!}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.singlePageEditor();compose.waitUntil(15000){app.navigationReady.value}
        if(args.getString("framing")=="width")compose.runOnIdle{view<InkCanvasView>().fitWidth()}else compose.frameCanvasFixture()
        if(compose.onAllNodesWithTag("study-close").fetchSemanticsNodes().isNotEmpty())tap("study-close")
        if(compose.onAllNodesWithTag("close-pen-settings").fetchSemanticsNodes().isNotEmpty())compose.closePenSettings()
        shot(stage,"01-writing")
        compose.openCurrentPen();shot(stage,"02-pen");tap("pen-advanced");shot(stage,"02-pen-advanced");compose.closePenSettings()
        tap("top-eraser");tap("top-eraser");compose.onNodeWithTag("eraser-dialog").assertIsDisplayed();shot(stage,"02-eraser")
        compose.onNodeWithContentDescription("关闭橡皮").performClick();tap("top-draw")
        tap("quick-study");compose.onNodeWithTag("study-map").assertIsDisplayed()
        tap("study-fit-overview")
        var point=Offset.Zero
        compose.runOnIdle{val b=view<MindMapView>().nodeBounds(root)!!;point=Offset(b.centerX(),b.centerY())}
        compose.onNodeWithTag("study-map").performTouchInput{click(point)};compose.waitForIdle();shot(stage,"04-map-node")
        tap("node-rename");compose.onNodeWithTag("node-title-input").performTextReplacement("限定条件后再判断概率")
        compose.onNodeWithTag("node-title-input").performTouchInput{click()}
        compose.runOnIdle{val target=compose.activity.currentFocus!!;val imm=compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager;imm.showSoftInput(target,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
        compose.waitUntil(8000){val insets=compose.activity.window.decorView.rootWindowInsets;insets?.isVisible(WindowInsets.Type.ime())==true&&insets.getInsets(WindowInsets.Type.ime()).bottom>150}
        SystemClock.sleep(900)
        val keyboardHeight=compose.activity.window.decorView.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        assertTrue("Soft keyboard needs an occupied screen region",keyboardHeight>150)
        File(app.getExternalFilesDir(null),"vis-$stage-ime.json").writeText(JSONObject().put("bottomPixels",keyboardHeight).toString())
        compose.onNodeWithTag("node-title-save").assertIsDisplayed();compose.onNodeWithTag("node-title-cancel").assertIsDisplayed();shot(stage,"05-title-ime");tap("node-title-cancel")
        compose.runOnIdle{compose.activity.window.decorView.clearFocus();compose.activity.window.insetsController?.hide(WindowInsets.Type.ime())}
        if(compose.activity.resources.configuration.screenWidthDp<600){
            // Keep the source reachable before starting a real drag on a narrow window.
            compose.onNodeWithTag("study-window-drag").performTouchInput{swipe(center,center+Offset(0f,800f),400)}
            compose.waitForIdle()
            compose.runOnIdle{view<InkCanvasView>().fitWidth()}
        }
        tap("top-excerpt")
        compose.runOnIdle{view<SelectionOverlayView>().onRegion(InkRegion(listOf(EraserPoint(100f,260f),EraserPoint(700f,400f))))}
        compose.waitForIdle()
        var from=Offset.Zero;var to=Offset.Zero
        compose.runOnIdle{
            val source=view<SelectionOverlayView>();val pos=IntArray(2);source.getLocationOnScreen(pos)
            val p=source.canvasView!!.snapshotViewport().worldToScreen(500.0,330.0,source.width.toDouble(),source.height.toDouble(),source.resources.displayMetrics.density.toDouble());from=Offset(pos[0]+p.x.toFloat(),pos[1]+p.y.toFloat())
            val map=view<MindMapView>();map.getLocationOnScreen(pos);val b=map.nodeBounds(root)!!;to=Offset(pos[0]+b.centerX(),pos[1]+b.centerY())
        }
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,p:Offset){val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,p.x,p.y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;try{check(instrumentation.uiAutomation.injectInputEvent(e,true))}finally{e.recycle()}}
        send(MotionEvent.ACTION_DOWN,from)
        for(i in 1..24){SystemClock.sleep(20);send(MotionEvent.ACTION_MOVE,from+(to-from)*(i/24f))}
        SystemClock.sleep(180);send(MotionEvent.ACTION_MOVE,to);shot(stage,"03-drop-preview")
        compose.runOnIdle{view<SelectionOverlayView>().cancelDragAndDrop()};send(MotionEvent.ACTION_UP,to)
        tap("top-draw");tap("study-close")
        assertEquals("Visual states must not rewrite author data",before,authorHash(book))
        File(app.getExternalFilesDir(null),"vis-$stage-result.json").writeText(JSONObject().put("runId",fixture.getString("runId")).put("book",book).put("authorHash",before).put("unchanged",true).put("stage",stage).toString())
    }
}
