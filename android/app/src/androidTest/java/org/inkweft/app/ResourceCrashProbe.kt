package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.PaperStyle
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.UUID
import java.util.zip.*

/** Actual process termination; dedicated-emulator harness only, never a user's populated library. */
class ResourceCrashProbe {
    private fun pack(version:Int):ResourcePack {
        val json="""{"format":"inkweft.resource-pack.v1","id":"example.crash-resource","title":"合成恢复模板","author":"InkWeft","version":$version,"resources":[{"id":"paper","title":"课堂提纲","type":"paper","paper":"CORNELL"}]}"""
        val out=ByteArrayOutputStream();ZipOutputStream(out).use{it.putNextEntry(ZipEntry("manifest.json").apply{time=0L});it.write(json.toByteArray());it.closeEntry()}
        return ResourcePackCodec.inspect(out.toByteArray())
    }
    @Test fun resourceCutKeepsPriorAuthorsAndOneReceipt()=runBlocking<Unit>{
        val args=InstrumentationRegistry.getArguments();require(args.getString("resourceProbe")=="dedicated-emulator")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val marker=File(app.filesDir,"resource-crash-probe.json");val cut=args.getString("cut")!!
        fun terminate(point:String){if(point==cut.substringAfter(':')){
            val reached=JSONObject(marker.readText()).put("reached",point).toString().toByteArray();FileOutputStream(marker).use{it.write(reached);it.fd.sync()}
            android.os.Process.killProcess(android.os.Process.myPid())
        }}
        if(args.getString("phase")=="verify")assertEquals(cut.substringAfter(':'),JSONObject(marker.readText()).getString("reached"))
        if(cut.startsWith("install:")){
            if(args.getString("phase")=="prepare"){
                require(!marker.exists());app.libraryBackup.snapshot().use{s->s.file.inputStream().use{app.libraryBackup.inspect(it)}.use{require(it.notes==0)}}
                app.resourcePacks.install(pack(1));val original=app.resourcePacks.instantiate(pack(1).hash,"paper")
                marker.writeText(JSONObject().put("old",original.id).toString())
                ResourcePacks(app,fault=::terminate).install(pack(2));fail("Expected process termination")
            }else{
                val id=JSONObject(marker.readText()).getString("old");assertEquals(PaperStyle.CORNELL.ordinal,app.workspaceRepository.get(id).paper)
                val enabled=app.resourcePacks.installed().single{it.enabled};assertEquals(if(cut.endsWith("after-registry"))2 else 1,enabled.pack.version)
                app.resourcePacks.install(pack(2));assertEquals(PaperStyle.CORNELL.ordinal,app.workspaceRepository.get(id).paper)
                assertEquals(listOf(id),app.resourceTemplates.copies(pack(1).hash))
            }
        }else{
            val db=NoteDatabase.open(app,File(app.filesDir,"resource-instance-probe.db").absolutePath)
            try{
                if(args.getString("phase")=="prepare"){
                    require(!marker.exists());val old=WorkspaceRepository(db).create("原合成资料",false,PaperStyle.DOTS);val op=UUID.randomUUID().toString()
                    marker.writeText(JSONObject().put("old",old.id).put("operation",op).toString())
                    ResourceTemplates(db,::terminate).instantiate(pack(1).hash,"新模板实例",PaperStyle.CORNELL,null,null,op);fail("Expected process termination")
                }else{
                    val m=JSONObject(marker.readText());val templates=ResourceTemplates(db);val op=m.getString("operation")
                    assertEquals(cut.endsWith("after-commit"),templates.created(op,pack(1).hash)!=null)
                    assertEquals(PaperStyle.DOTS.ordinal,WorkspaceRepository(db).get(m.getString("old")).paper)
                    val note=templates.instantiate(pack(1).hash,"新模板实例",PaperStyle.CORNELL,null,null,op)
                    assertEquals(note.id,templates.instantiate(pack(1).hash,"新模板实例",PaperStyle.CORNELL,null,null,op).id)
                    assertEquals(listOf(note.id),templates.copies(pack(1).hash))
                }
            }finally{db.close()}
        }
    }
}
