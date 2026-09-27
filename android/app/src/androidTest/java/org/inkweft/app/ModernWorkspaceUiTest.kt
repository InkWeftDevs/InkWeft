package org.inkweft.app

import android.graphics.Bitmap
import android.content.SharedPreferences
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class ModernWorkspaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private lateinit var oldEditor:Map<String,*>
    private lateinit var oldFavorites:List<FavoritePen>
    private lateinit var oldBeauty:BeautyOptions
    @Before fun isolate(){val p=app.getSharedPreferences("inkweft-editor",0);oldEditor=p.all;oldFavorites=FavoritePenStore(app).read();oldBeauty=BeautyStore(app).read();BeautyStore(app).save(BeautyOptions());p.edit().clear().commit();runBlocking{FavoritePenStore(app).save(emptyList())}}
    @After fun restore(){val e=app.getSharedPreferences("inkweft-editor",0).edit().clear();oldEditor.forEach{(k,v)->when(v){is Float->e.putFloat(k,v);is Boolean->e.putBoolean(k,v);is String->e.putString(k,v);is Int->e.putInt(k,v);is Long->e.putLong(k,v)}};e.commit();BeautyStore(app).save(oldBeauty);runBlocking{FavoritePenStore(app).save(oldFavorites)}}
    private fun open():Note{
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val n=runBlocking{app.workspaceRepository.create("笔盒与收藏验收",false,PaperStyle.GRID)}
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(n)}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("ink-tool-0").fetchSemanticsNodes().isNotEmpty()&&app.navigationReady.value}
        return n
    }
    private fun shot(name:String){compose.waitForIdle();val b=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot());try{File(app.getExternalFilesDir(null),name).outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{b.recycle()}}
    @Test fun favoriteEditsApplyImmediatelyAndKeepTheSameIdentityAfterReopen(){
        val n=open();val presets=PenWidthStore(app,"inkweft-pen-widths-book-"+n.id);val initial=presets.readKinds()
        compose.openCurrentPen();compose.onNodeWithTag("pen-kind-pen").performScrollTo().performClick()
        compose.onNodeWithTag("width-preset-2").performScrollTo().performClick();compose.onNodeWithTag("pen-color-2").performScrollTo().performClick()
        shot("v21-pen-card.png")
        compose.onNodeWithTag("pen-favorite").performScrollTo().performClick()
        compose.waitUntil(10_000){FavoritePenStore(app).read().size==1}
        val favorite=FavoritePenStore(app).read().single()
        compose.closePenSettings()
        assertEquals(InkPen.PEN,presets.readKinds()[0])
        compose.onNodeWithTag("favorite-pen-${favorite.id}").performScrollTo().performTouchInput{longClick()}
        compose.onNodeWithTag("width-preset-0").performScrollTo().performClick()
        assertEquals(favorite.id,FavoritePenStore(app).read().single().id);assertEquals(1.5f,presets.read()[0])
        compose.onNodeWithTag("width-preset-2").performScrollTo().performClick();compose.closePenSettings()
        compose.waitUntil(10_000){presets.readKinds()[0]==InkPen.PEN&&presets.read()[0]==6f&&presets.readColors()[0]==PenWidthStore.colors(0)[2]}
        shot("v21-favorites.png");compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000){compose.onAllNodesWithTag("favorite-pen-${favorite.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.openCurrentPen();compose.onNodeWithTag("pen-favorite").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000){FavoritePenStore(app).read().isEmpty()}
        compose.closePenSettings()
        assertTrue(runBlocking{app.inkRepository.read(n.id).strokes.isEmpty()})
    }
    @Test fun paletteCollapsesRestoresAndPointsIntoPaperOnBothSides(){
        open();val full=compose.onNodeWithTag("floating-pen-case").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("case-collapse").performClick();compose.onNodeWithTag("ink-tool-0").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("floating-pen-case").fetchSemanticsNode().boundsInRoot.height<full)
        shot("v21-collapsed.png");compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000){compose.onAllNodesWithTag("case-collapse").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("floating-pen-case").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"已收起"))
        compose.onNodeWithTag("case-collapse").performClick();compose.onNodeWithTag("pen-case-handle").performClick();compose.onNodeWithText("放到右侧").performClick()
        compose.onNodeWithTag("floating-pen-case").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"笔尖朝左"));shot("v21-right-palette.png")
        compose.onNodeWithTag("pen-case-handle").performClick();compose.onNodeWithText("放到左侧").performClick()
        compose.onNodeWithTag("floating-pen-case").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"笔尖朝右"))
    }
    @Test fun favoriteStoreRejectsInvalidValuesAndKeepsHighlighterAlpha(){
        val key="favorite-test-${UUID.randomUUID()}";val store=FavoritePenStore(app,key)
        try{val p=FavoritePen(UUID.randomUUID().toString(),InkPen.HIGHLIGHTER,22f,PenWidthStore.colors(2)[2]);assertTrue(runBlocking{store.save(listOf(p))});assertEquals(listOf(p),store.read())
            try{runBlocking{store.save(listOf(p.copy(color=0xff000000.toInt()))) };fail("opaque highlighter stored")}catch(_:IllegalArgumentException){}
            assertEquals(listOf(p),store.read())
        }finally{app.deleteSharedPreferences(key)}
    }
    @Test fun settingsCanChangeWritingPreferencesAndResetOnlyPalettePlacement(){
        val note=open()
        compose.onNodeWithTag("pen-case-handle").performClick();compose.onNodeWithText("放到右侧").performClick();compose.onNodeWithTag("case-collapse").performClick()
        compose.onNodeWithTag("back-library").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithTag("new-note").fetchSemanticsNodes().isNotEmpty()}
        val prefs=app.getSharedPreferences("inkweft-editor",0)
        if(compose.onAllNodesWithTag("open-library-drawer").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("open-library-drawer").performClick()
        compose.onNodeWithText("设置与数据").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings-favorite-pens").performScrollTo().performClick();compose.onNodeWithTag("settings-auto-beauty").performScrollTo().performClick()
        assertTrue(prefs.getBoolean("favorites-open",false));assertTrue(BeautyStore(app).read().enabled)
        compose.onNodeWithTag("settings-reset-case").performScrollTo().performClick()
        assertFalse(prefs.contains("case-x"));assertFalse(prefs.contains("case-collapsed"));assertTrue(prefs.getBoolean("favorites-open",false))
        shot("v21-settings.png")
        compose.onNodeWithContentDescription("关闭设置").performScrollTo().performClick()
        compose.runOnIdle{ViewModelProvider(compose.activity)[NotebookViewModel::class.java].select(note)}
        compose.waitUntil(15_000){compose.onAllNodesWithTag("floating-pen-case").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("floating-pen-case").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"笔尖朝右"))
        compose.onNodeWithTag("favorite-pen-case").assertExists();compose.openBeautySettings();compose.onNodeWithTag("beauty-enabled").assertIsOn()
    }
}
