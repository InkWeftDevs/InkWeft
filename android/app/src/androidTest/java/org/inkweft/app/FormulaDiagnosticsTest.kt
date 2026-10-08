package org.inkweft.app

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipInputStream

class FormulaDiagnosticsTest {
    @Test fun formulaCaptureNamesActualPipelineAndOnlyIncludesImagesAfterOptIn()=runBlocking {
        val stroke=InkStroke(UUID.randomUUID().toString(),InkPen.PEN,0xff000000.toInt(),2f,InkTool.STYLUS,listOf(InkSample(10f,20f,0),InkSample(40f,50f,10)))
        val bitmap=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
        try{for(privateImages in listOf(false,true)){
            val capture=BeautyDiagnostics();capture.start(privateImages)
            val trace=capture.begin("formula-diagnostic",InkUi(strokes=listOf(stroke),revision=1,loading=false),emptySet(),emptyList(),listOf(stroke),BeautyOptions(formula=true),false,null,emptySet())!!
            trace.group("formula",0,HandwritingLines.split(listOf(stroke)).single())
            trace.formulaInput(0,bitmap,bitmap)
            trace.output("formula",0,RecognizedWriting("x^2",.9f,1),if(privateImages)JSONArray().put(JSONObject().put("text","x"))else null)
            val source=trace.meta.getJSONObject("source")
            assertEquals("FORMULA",source.getString("content_mode"));assertEquals("FORMULA_SINGLE_PASS_REVIEW_REQUIRED",source.getString("variant_scope"))
            assertEquals(3,trace.meta.getJSONObject("formula-group-0").getInt("extra_world_margin"))
            assertEquals("[1,3,384,384]",trace.meta.getJSONObject("formula-input-0").getJSONArray("shape").toString())
            val output=trace.meta.getJSONObject("formula-output-0")
            assertFalse(output.has("ctc_steps_top2"));assertEquals(privateImages,output.has("formula_decoder_steps"));assertEquals(!privateImages,output.isNull("text"))
            val files=mutableSetOf<String>();ZipInputStream(capture.bundle().inputStream()).use{zip->while(true){val entry=zip.nextEntry?:break;files.add(entry.name)}}
            assertEquals(privateImages,"attempt-1/formula-input-0.png" in files);assertEquals(privateImages,"attempt-1/source.inkweft" in files)
        }}finally{bitmap.recycle()}
    }
}
