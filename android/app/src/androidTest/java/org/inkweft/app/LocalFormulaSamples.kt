package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Explicitly staged and consented local samples; excluded from the CI *Test.kt inventory. */
class LocalFormulaSamples {
    @Test fun collectConsentedSegments()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        require(app.packageName=="org.inkweft.app.a0.feedback")
        val root=File(app.filesDir,"beauty-samples").canonicalFile;val manifest=File(root,"samples.jsonl")
        require(manifest.isFile&&manifest.length()<=512000)
        val rows=manifest.readLines().filter{it.isNotBlank()}.map(::JSONObject);require(rows.size in 1..20)
        val output=File(root,"formula-recognized.jsonl");require(!output.exists())
        val staged=File(root,"formula-recognized.partial");require(!staged.exists())
        try{staged.bufferedWriter().use{writer->rows.forEach{j->
            require(j.getBoolean("consent")&&j.getString("writer").isNotBlank());require(j.getString("split")=="development")
            val filename=j.getString("source");require(filename.matches(Regex("[A-Za-z0-9_.-]+\\.inkweft")))
            val file=File(root,filename).canonicalFile;require(file.parentFile==root&&file.length() in 1..10_000_000)
            val bytes=file.readBytes();val page=InkPageFile.decode(bytes);require(page.strokes.size in 1..256)
            val capture=BeautyDiagnostics().apply{start(true)}
            val trace=capture.begin("local-consented-sample",InkUi(strokes=page.strokes,loading=false),emptySet(),emptyList(),page.strokes,BeautyOptions(formula=true),false,null,emptySet())
            val budgetBefore=RenderResources.snapshot()["category.formula-input"]?:0L
            val start=System.nanoTime();val result=app.formulas.recognize(page.strokes,trace)
            check((RenderResources.snapshot()["category.formula-input"]?:0L)==budgetBefore)
            File(root,"formula-diagnostics.zip").apply{check(!exists());writeBytes(capture.bundle())}
            val regions=JSONArray();result.regions.forEach{r->regions.put(JSONObject().put("latex",r.text).put("source_count",r.strokeIds.size).put("uncalibrated_score",r.score.toDouble()))}
            writer.appendLine(JSONObject().put("id",j.getString("id")).put("source_sha256",EncryptedBackupFile.hex(bytes)).put("reference",j.getString("reference"))
                .put("hypothesis",result.text).put("regions",regions).put("automatic",false).put("recognition_ms",(System.nanoTime()-start)/1e6).toString())
        }};check(staged.renameTo(output))}finally{staged.delete()}
    }
}
