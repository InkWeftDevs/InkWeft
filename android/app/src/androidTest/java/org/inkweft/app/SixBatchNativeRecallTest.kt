// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real production controls on the existing full 96-card fixture. No replacement UI or reseeding. */
class SixBatchNativeRecallTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun waitFor(tag:String){compose.waitUntil(60_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()}
    private fun tap(tag:String){compose.revealAction(tag);waitFor(tag);val node=compose.onNodeWithTag(tag)
        runCatching{node.performScrollTo()};compose.waitUntil(60_000){runCatching{node.assertIsEnabled()}.isSuccess}
        node.assertIsDisplayed().performTouchInput{click()};compose.waitForIdle()}
    private fun current(book:String)=runBlocking{val repo=app.study.recall();repo.resume(book)?.let{repo.loadSession(it.id).current}}
    private fun shot(f:SixBatchFixture,stage:String,tag:String){
        waitFor(tag);compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed();compose.waitForIdle()
        val name="native-recall-$stage.png";val bitmap=checkNotNull(automation.takeScreenshot())
        try{File(f.root,name).outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
        val c=compose.activity.resources.configuration
        f.manifest.getJSONArray("nativeRecallCaptures").put(JSONObject().put("file",name).put("sha256",SixBatchFixture.sha(File(f.root,name)))
            .put("screenWidthDp",c.screenWidthDp).put("fontScale",c.fontScale.toDouble()).put("review","PENDING_VISUAL_REVIEW"));f.save()
    }
    private fun sourceReady(){compose.waitUntil(60_000){compose.runOnIdle{
        val views=java.util.ArrayDeque<View>();WindowInspector.getGlobalWindowViews().forEach(views::add)
        var ready=false
        while(views.isNotEmpty()){
            val view=views.removeFirst()
            if(view is RecallMaskedSourceView&&view.isShown)ready=!(view.getChildAt(0) as InkCanvasView).rasterPending
            if(view is ViewGroup)repeat(view.childCount){views.add(view.getChildAt(it))}
        }
        ready
    }}}
    @Suppress("DEPRECATION") private fun assertAuthorTitleShielded(title:String){
        val texts=mutableListOf<String>()
        fun read(node:AccessibilityNodeInfo){
            if(node.packageName?.toString()==app.packageName&&node.isVisibleToUser){
                node.text?.let{ texts+=it.toString() };node.contentDescription?.let{ texts+=it.toString() }
            }
            repeat(node.childCount){node.getChild(it)?.let{child->try{read(child)}finally{child.recycle()}}}
        }
        automation.windows.forEach{window->window.root?.let{root->try{read(root)}finally{root.recycle()}}}
        assertTrue("Actual visible recall controls must be accessible",texts.any{it.contains("本轮持久回忆")})
        assertFalse("Author card title leaked through an underlying window",texts.any{it.contains(title)})
    }
    private fun hideKeyboard(){compose.runOnIdle{
        val input=app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        WindowInspector.getGlobalWindowViews().forEach{root->root.findFocus()?.let{input.hideSoftInputFromWindow(it.windowToken,0);it.clearFocus()}}
    };compose.waitForIdle()}

    @Test fun captureSameFixtureTypedRecallAndPersistentAnswers(){
        val f=SixBatchFixture.load(app);runBlocking{f.verify()}
        check(!f.manifest.has("nativeRecallSessions")){"Continue the recorded phase; do not create duplicate UI attempts"}
        f.manifest.put("nativeRecallBaselineSha256",runBlocking{f.baselineRecallDigest()})
            .put("nativeRecallSessions",JSONArray()).put("nativeRecallAttempts",JSONArray()).put("nativeRecallCaptures",JSONArray());f.save()
        val oldFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val reading=app.getSharedPreferences("inkweft-reading",0);val key="continuous-v20-${f.books[0]}"
        val hadReading=reading.contains(key);val oldReading=reading.getBoolean(key,true)
        try{
            val note=runBlocking{checkNotNull(app.repository.read(f.books[0]))}
            compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
            compose.singlePageEditor();compose.waitUntil(60_000){app.navigationReady.value};tap("quick-study");tap("study-tab-0")
            for(index in 0..2)runBlocking{f.step("native-recall-type-$index-real-controls"){
                val card=f.manifest.getJSONArray("cardsA").getString(index)
                tap("study-card-$card");tap("card-review");tap("branch-review-durable");tap("recall-start-practice")
                compose.waitUntil(60_000){current(f.books[0])!=null};waitFor("recall-fixed-prompt")
                var loaded=checkNotNull(current(f.books[0]));assertEquals(card,loaded.spec.cardId)
                f.manifest.getJSONArray("nativeRecallSessions").put(loaded.row.sessionId)
                f.manifest.getJSONArray("nativeRecallAttempts").put(loaded.row.id);f.save()
                assertAuthorTitleShielded(loaded.card.title)
                if(index==0){waitFor("recall-source-mask-projection");sourceReady();shot(f,"01-source-masked","recall-source-mask-projection")}
                if(index==1){compose.onNodeWithTag("recall-inline-cloze").assertTextEquals(loaded.spec.maskedText(loaded.card.body));shot(f,"04-cloze-masked","recall-inline-cloze")}
                if(index==2){compose.onNodeWithTag("recall-question-neutral").assertExists();compose.onNodeWithTag("recall-fixed-answer").assertDoesNotExist()}
                compose.onNodeWithTag("recall-answer-text").performScrollTo().performTextInput("同一样例 · 第${index+1}种题型的离线作答")
                hideKeyboard()
                if(index==0){
                    val original=app.inkRepository.read(f.manifest.getString("formulaPage"))
                    tap("recall-answer-finger")
                    compose.onNodeWithTag("recall-answer-ink-canvas").performScrollTo().assertIsDisplayed().performTouchInput{
                        down(Offset(width*.15f,height*.35f));moveTo(Offset(width*.45f,height*.65f),120);moveTo(Offset(width*.75f,height*.3f),120);up()
                    }
                    tap("recall-save-answer")
                    compose.waitUntil(60_000){current(f.books[0])?.row?.answerInk?.isNotEmpty()==true}
                    val after=app.inkRepository.read(f.manifest.getString("formulaPage"))
                    assertEquals(original.revision,after.revision);assertEquals(InkSession(original).visibleDraft(),InkSession(after).visibleDraft())
                    shot(f,"02-source-answer","recall-answer-ink-canvas")
                    val attempt=checkNotNull(current(f.books[0])).row.id
                    tap("recall-pause");compose.waitUntil(60_000){compose.onAllNodesWithTag("durable-recall").fetchSemanticsNodes().isEmpty()}
                    assertEquals(RecallHint.ORIGINAL.bit,checkNotNull(current(f.books[0])).row.hintMask)
                    tap("card-review");waitFor("recall-fixed-prompt")
                    assertEquals(attempt,checkNotNull(current(f.books[0])).row.id)
                    compose.onNodeWithTag("recall-start-practice").assertDoesNotExist()
                    assertEquals("同一样例 · 第1种题型的离线作答",checkNotNull(current(f.books[0])).row.answerText)
                    tap("recall-reveal-region-0");compose.waitUntil(60_000){current(f.books[0])?.row?.hintMask==(RecallHint.REGION.bit or RecallHint.ORIGINAL.bit)}
                    sourceReady();shot(f,"03-source-revealed","recall-source-mask-projection")
                }
                if(index==1){
                    tap("recall-reveal-cloze-0");compose.waitUntil(60_000){current(f.books[0])?.row?.hintMask==RecallHint.TEXT.bit}
                    val attempt=checkNotNull(current(f.books[0])).row.id
                    compose.activityRule.scenario.recreate();waitFor("recall-inline-cloze")
                    assertEquals(attempt,checkNotNull(current(f.books[0])).row.id)
                    loaded=checkNotNull(current(f.books[0]));compose.onNodeWithTag("recall-inline-cloze").assertTextEquals(loaded.spec.maskedText(loaded.card.body,setOf(0)))
                    compose.onNodeWithTag("recall-reveal-cloze-1").assertExists();shot(f,"05-cloze-revealed","recall-inline-cloze")
                }
                tap("recall-seal-compare");compose.waitUntil(60_000){current(f.books[0])?.row?.answerRevealed==true}
                tap("recall-grade-4");waitFor("recall-round-result")
                if(index==2)shot(f,"06-question-result","recall-round-result")
                tap("recall-pause");tap("card-back")
            }}
            runBlocking{f.verifyNativeRecall();f.verify()}
            f.manifest.put("nativeRecallCapture","CAPTURED_PENDING_VISUAL_REVIEW");f.save()
        }finally{
            automation.serviceInfo=automation.serviceInfo.apply{flags=oldFlags}
            val edit=reading.edit();if(hadReading)edit.putBoolean(key,oldReading)else edit.remove(key);check(edit.commit())
        }
    }
    @Test fun reopenSameFixtureNativeRecallRecords(){
        val f=SixBatchFixture.load(app)
        check(f.manifest.getString("nativeRecallCapture")=="CAPTURED_PENDING_VISUAL_REVIEW")
        runBlocking{f.step("cold-reopen-original-25-and-exact-three-native-attempts"){
            f.verifyNativeRecall();f.verify()
            val captures=f.manifest.getJSONArray("nativeRecallCaptures");check(captures.length()==6)
            for(index in 0 until captures.length()){val image=captures.getJSONObject(index);check(SixBatchFixture.sha(File(f.root,image.getString("file")))==image.getString("sha256"))}
        }}
        f.manifest.put("nativeRecallReopen","PASS");f.save()
    }
}
