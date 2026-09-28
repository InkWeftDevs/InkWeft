package org.inkweft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File
import android.graphics.Bitmap

class BrushB1UiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun pencilLiveParametersAndFavoritesStayIndependentOfOtherPens(){
        val app=compose.activity.application as InkWeftApplication
        compose.waitUntil(15000){runCatching{compose.onNodeWithTag("new-note").assertIsEnabled()}.isSuccess}
        val title="B1 独立铅笔-"+UUID.randomUUID().toString().take(6)
        compose.onNodeWithTag("new-note").performClick();compose.onNodeWithTag("create-page").performClick()
        compose.onNodeWithTag("new-title").performTextInput(title);compose.onNodeWithTag("create-note").performClick();compose.singlePageEditor()
        val id=runBlocking{app.repository.observeNotes().first()}.single{it.title==title}.id
        compose.selectPen("pencil");compose.openCurrentPen()
        compose.onNodeWithTag("pencil-hardness-2").performScrollTo().performClick()
        compose.onNodeWithTag("pencil-advanced").performScrollTo().performClick()
        compose.onNodeWithTag("pencil-tilt").performScrollTo().performClick()
        val preset=PenWidthStore(compose.activity,"inkweft-pen-widths-book-$id")
        assertEquals(2,preset.readRecipes()[0].hardness);assertFalse(preset.readRecipes()[0].tiltShading)
        compose.onNodeWithTag("brush-scratch-open").assertDoesNotExist()
        if(FavoritePenStore(compose.activity).read().none{it.kind==InkPen.PENCIL&&it.recipe==preset.readRecipes()[0]})
            compose.onNodeWithTag("pen-favorite").performScrollTo().performClick()
        assertTrue(FavoritePenStore(compose.activity).read().any{it.kind==InkPen.PENCIL&&it.recipe==preset.readRecipes()[0]})
        compose.closePenSettings();assertTrue(runBlocking{app.inkRepository.read(id).strokes}.isEmpty())
        compose.selectPen("ballpoint");compose.openCurrentPen()
        compose.onNodeWithTag("pencil-tilt").assertDoesNotExist();compose.onNodeWithTag("pen-sensitivity").assertDoesNotExist()
        compose.closePenSettings()
    }
}
