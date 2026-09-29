package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Explicit opt-in collector, excluded from the CI *Test.kt inventory. All input/output stays local. */
class LocalBeautySamples {
    @Test fun collectConsentedSegments()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val root=File(app.getExternalFilesDir(null),"beauty-samples").canonicalFile
        val manifest=File(root,"samples.jsonl")
        require(manifest.isFile&&manifest.length()<=512000)
        val rows=manifest.readLines().filter{it.isNotBlank()}.map(::JSONObject);require(rows.size in 1..200)
        val output=File(root,"recognized.jsonl");require(!output.exists()){"Archive the previous run before collecting again"}
        val staged=File(root,"recognized.partial");require(!staged.exists())
        try{staged.bufferedWriter().use{writer->rows.forEach{j->
            require(j.getBoolean("consent"));require(j.getString("writer").isNotBlank());require(j.getString("split") in setOf("development","calibration","evaluation"))
            val filename=j.getString("source");require(filename.matches(Regex("[A-Za-z0-9_.-]+\\.inkweft")))
            val file=File(root,filename).canonicalFile;require(file.parentFile==root&&file.length() in 1..10_000_000)
            val bytes=file.readBytes();val page=InkPageFile.decode(bytes);val strokes=page.strokes
            require(strokes.size in 1..256&&j.getString("reference").isNotBlank())
            val start=System.nanoTime();val first=app.handwriting.recognize(strokes);val second=app.handwriting.recognize(strokes,padded=true)
            val decision=BeautyQuality.decide(strokes,first,second,true)
            val result=JSONObject().put("id",j.getString("id")).put("writer",j.getString("writer")).put("split",j.getString("split")).put("consent",true)
                .put("source_sha256",EncryptedBackupFile.hex(bytes)).put("reference",j.getString("reference")).put("hypothesis",first.text)
                .put("automatic",decision.automatic).put("reason",decision.reason).put("recognition_ms",(System.nanoTime()-start)/1e6)
            writer.appendLine(result.toString())
        }};check(staged.renameTo(output))}finally{staged.delete()}
    }
}
