package org.inkweft.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import org.inkweft.app.ui.designsystem.InkTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import org.junit.*
import java.io.*
import java.util.zip.*

class BackupResourceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as InkWeftApplication
    private fun shot(name:String){compose.waitForIdle();val b=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!;File(app.getExternalFilesDir(null),"v44-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    @Test fun backupMainKeepsAdvancedIdentifiersBehindDetailsAndKeyStepsReachable(){
        compose.activity.setContent{InkTheme.Content{BackupSettings{}}}
        compose.onNodeWithText("立即备份").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("资料库 ID").assertDoesNotExist();shot("backup-main")
        compose.onNodeWithText("设置恢复密钥").performClick()
        compose.onNodeWithText("生成新密钥").performClick()
        compose.onNodeWithText("保存密钥文件").assertIsEnabled();compose.onNodeWithText("读取密钥文件").assertIsDisplayed();shot("backup-key")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("连接服务器").performClick()
        compose.onNodeWithTag("backup-server").performTextInput("https://backup.example.com")
        compose.onNodeWithText("连接",substring=false).assertIsNotEnabled();shot("backup-connect")
    }
    @Test fun installedMapResourceCanCreateIndependentNoteWithoutReviewCards(){
        val json="""{"format":"inkweft.resource-pack.v1","id":"example.ui-pack","title":"学习整理模板","author":"InkWeft 原创示例","version":1,"resources":[{"id":"map","title":"章节要点","type":"map","layout":"right","nodes":[{"title":"章节","parent":null,"x":40,"y":200},{"title":"定义与条件","parent":0,"x":300,"y":200}]}]}"""
        val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(json.toByteArray());z.closeEntry()}}.toByteArray()
        runBlocking{app.resourcePacks.install(ResourcePackCodec.inspect(bytes))}
        compose.activity.setContent{InkTheme.Content{ResourcePackSettings{}}}
        compose.waitUntil(10000){compose.onAllNodesWithText("从导图新建 · 章节要点").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("从导图新建 · 章节要点").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("已新建「章节要点」，可回资料库打开。").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("使用此版本：章节要点").assertIsDisplayed();shot("resource-installed")
        compose.onNodeWithText("禁用").performClick();compose.waitForIdle()
        compose.onNodeWithText("从导图新建 · 章节要点").assertIsNotEnabled()
    }
}
