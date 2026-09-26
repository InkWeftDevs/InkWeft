package org.inkweft.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class CustomCoverUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun ready(){compose.waitUntil(15000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}}
    private fun id()=UUID.randomUUID().toString()
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(compose.activity.getExternalFilesDir(null),"cover-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    private fun open(n:Note){compose.onNodeWithTag("note-menu-${n.id}").performClick();compose.onNodeWithTag("change-cover-${n.id}").performClick();compose.waitUntil(10000){runCatching{compose.onNodeWithTag("confirm-cover").assertIsEnabled()}.isSuccess}}
    @Test fun cancelIsDraftAndSavedDesignSurvivesRecreation(){
        ready();val n=runBlocking{app.workspaceRepository.create("自定义封面 ${id().take(5)}",false,PaperStyle.CORNELL)}
        compose.waitUntil(10000){compose.onAllNodesWithTag("note-menu-${n.id}").fetchSemanticsNodes().isNotEmpty()}
        open(n);compose.onNodeWithTag("cover-custom").performScrollTo().performClick();compose.onNodeWithTag("cover-title").performScrollTo().performTextReplacement("数学手册")
        compose.activityRule.scenario.recreate();compose.waitUntil(10000){compose.onAllNodesWithTag("cover-title").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("cover-title").performScrollTo().assertTextContains("数学手册")
        compose.onNodeWithTag("cancel-cover").performClick();assertEquals("auto",runBlocking{app.workspaceRepository.get(n.id).coverKey});assertNull(runBlocking{app.workspaceRepository.customCover(n.id)})
        open(n);compose.onNodeWithTag("cover-custom").performScrollTo().performClick();compose.onNodeWithTag("cover-title").performScrollTo().performTextReplacement("数学手册")
        compose.onNodeWithTag("cover-subtitle").performScrollTo().performTextReplacement("2026 秋 · 微积分")
        compose.onNodeWithTag("cover-color").performScrollTo().performTextReplacement("XYZ");compose.onNodeWithTag("confirm-cover").assertIsNotEnabled()
        compose.onNodeWithTag("cover-color").performTextReplacement("234E42");shot("editor")
        compose.onNodeWithTag("confirm-cover").performClick();compose.waitUntil(10000){runBlocking{app.workspaceRepository.get(n.id).coverKey}=="custom"}
        compose.activityRule.scenario.recreate();ready();open(n)
        compose.onNodeWithTag("cover-title").performScrollTo().assertTextContains("数学手册")
        compose.onNodeWithTag("cover-subtitle").performScrollTo().assertTextContains("2026 秋 · 微积分")
        compose.onNodeWithTag("cancel-cover").performClick();assertEquals(1,runBlocking{app.pages.activePages(n.id).size});shot("shelf")
    }
    @Test fun creationUsesConfirmedCustomDesignWithoutAnExtraPage(){
        ready();compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick()
        compose.onNodeWithTag("new-custom-cover").performScrollTo().performClick();compose.onNodeWithTag("cover-custom").performScrollTo().performClick()
        compose.onNodeWithTag("cover-title").performScrollTo().performTextReplacement("新建时的封面")
        compose.onNodeWithTag("confirm-cover").performClick();compose.onNodeWithTag("create-note").performClick()
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("ink-status").assertTextContains("已提交",substring=true)}.isSuccess}
        val n=androidx.lifecycle.ViewModelProvider(compose.activity)[NotebookViewModel::class.java].ui.value.current!!.base
        val c=runBlocking{app.workspaceRepository.customCover(n.id)}!!;assertEquals("新建时的封面",CustomCoverCodec.decode(c).title)
        assertEquals(1,runBlocking{app.pages.activePages(n.id).size})
    }
    @Test fun imageIsResizedCopiedAndCropPersistsWithoutSourceFile(){
        ready();val b=Bitmap.createBitmap(2400,1600,Bitmap.Config.ARGB_8888);val out=ByteArrayOutputStream()
        try{android.graphics.Canvas(b).apply{drawColor(0xffeecb8c.toInt());val paint=android.graphics.Paint().apply{color=0xff234e42.toInt()};drawRect(0f,0f,900f,1600f,paint)};b.compress(Bitmap.CompressFormat.PNG,100,out)}finally{b.recycle()}
        File(compose.activity.getExternalFilesDir(null),"cover-source-fixture.png").writeBytes(out.toByteArray())
        val f=File(compose.activity.cacheDir,"test-cover-${id()}.png");f.writeBytes(out.toByteArray())
        val image=try{CoverImages.read(compose.activity,Uri.fromFile(f))}finally{f.delete()}
        assertTrue(image.size<=CustomCoverCodec.MAX_IMAGE);val decoded=BitmapFactory.decodeByteArray(image,0,image.size)
        try{assertTrue(maxOf(decoded.width,decoded.height)<=1024)}finally{decoded.recycle()}
        val payload=CustomCoverCodec.encode(CustomCover(title="图片笔记",subtitle="本地图片副本",image=image,layout=CoverLayout.BAND))
        val n=runBlocking{app.workspaceRepository.create("图片取景 ${id().take(5)}",false,PaperStyle.RULED,NotebookCover.CUSTOM,customCover=payload)}
        compose.waitUntil(10000){compose.onAllNodesWithTag("note-menu-${n.id}").fetchSemanticsNodes().isNotEmpty()};open(n)
        compose.onNodeWithTag("cover-zoom").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2f)}
        compose.onNodeWithTag("confirm-cover").performClick();compose.waitUntil(10000){runBlocking{app.workspaceRepository.get(n.id).revision}>0}
        assertEquals(2f,CustomCoverCodec.decode(runBlocking{app.workspaceRepository.customCover(n.id)}!!).zoom)
        open(n);shot("image-crop");compose.onNodeWithTag("cover-remove-image").performScrollTo().performClick();compose.onNodeWithTag("cancel-cover").performClick()
        assertArrayEquals(image,CustomCoverCodec.decode(runBlocking{app.workspaceRepository.customCover(n.id)}!!).image)
    }
    @Test fun unsupportedImageDoesNotProduceAStoredCover(){
        assertThrows(Exception::class.java){CoverImages.normalize("not an image".toByteArray())}
        assertThrows(IllegalArgumentException::class.java){CoverImages.normalize(ByteArray(20*1024*1024+1))}
    }
}
