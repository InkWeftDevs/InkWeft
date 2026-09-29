package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.LibraryBackupRepository
import org.junit.Test
import org.junit.Assert.*
import org.json.*
import java.io.ByteArrayOutputStream
import java.util.zip.*

class ResourcePackTest {
    private fun zip(vararg files:Pair<String,ByteArray>):ByteArray=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{z->files.forEach{(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}}.toByteArray()
    private fun manifest(version:Int=1)=JSONObject().put("format","inkweft.resource-pack.v1").put("id","example.original-study").put("title","原创学习模板").put("author","InkWeft 合成测试").put("version",version)
        .put("resources",JSONArray().put(JSONObject().put("id","cornell").put("title","课堂笔记").put("type","paper").put("paper","CORNELL"))
            .put(JSONObject().put("id","review").put("title","章节复盘").put("type","map").put("layout","right").put("nodes",JSONArray().put(JSONObject().put("title","中心主题").put("parent",JSONObject.NULL).put("x",40).put("y",200)))))
    private fun bytes(version:Int=1)=zip("manifest.json" to manifest(version).toString().toByteArray())

    @Test fun lifecycleRollbackAndBackupPreserveIndependentPaperAndMapCopies()=runBlocking{
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val store=app.resourcePacks;val first=ResourcePackCodec.inspect(bytes());store.install(first)
        val paper=store.instantiate(first.hash,"cornell");val map=store.instantiate(first.hash,"review")
        assertEquals(PaperStyle.CORNELL.ordinal,app.workspaceRepository.get(paper.id).paper)
        assertTrue(app.mapGraphs.read(map.id).flatMap{it.nodes}.all{it.cardId==null})
        assertEquals(2,app.resourceTemplates.copies(first.hash).size)
        val second=ResourcePackCodec.inspect(bytes(2))
        try{ResourcePacks(app){if(it=="before-registry")error("injected disk failure")}.install(second);fail("Fault ignored")}catch(_:IllegalStateException){}
        assertTrue(store.installed().single{it.pack.hash==first.hash}.enabled)
        store.install(second);assertFalse(store.installed().single{it.pack.hash==first.hash}.enabled)
        store.uninstall(first.hash);assertTrue(store.installed().any{it.pack.hash==first.hash})
        store.enable(second.hash,false)
        try{store.instantiate(second.hash,"cornell");fail("Disabled pack used")}catch(_:IllegalArgumentException){}
        store.uninstall(second.hash);assertFalse(store.installed().any{it.pack.hash==second.hash})
        app.libraryBackup.snapshot().use{snapshot->snapshot.file.inputStream().use{app.libraryBackup.inspect(it)}.use{p->assertEquals(2,p.notes);assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,app.libraryBackup.restore(p))}}
        assertEquals(PaperStyle.CORNELL.ordinal,app.workspaceRepository.get(paper.id).paper)
    }
    @Test fun pngPaperIsCopiedIntoDocumentAndRestoresAfterPackRemoval()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val bitmap=android.graphics.Bitmap.createBitmap(128,180,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE);val canvas=android.graphics.Canvas(bitmap)
        canvas.drawLine(10f,50f,118f,50f,android.graphics.Paint().apply{color=android.graphics.Color.BLUE;strokeWidth=2f})
        val image=ByteArrayOutputStream().also{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val j=manifest().put("id","example.png-paper").put("resources",JSONArray().put(JSONObject().put("id","paper").put("title","原创横线纸").put("type","paper").put("image","paper.png")))
            .put("files",JSONObject().put("paper.png",JSONObject().put("bytes",image.size).put("sha256",ContentTransfer.hash(image))))
        val pack=ResourcePackCodec.inspect(zip("manifest.json" to j.toString().toByteArray(),"paper.png" to image))
        app.resourcePacks.install(pack);val note=app.resourcePacks.instantiate(pack.hash,"paper")
        val before=app.documents.read(note.id)!!.document.sha256
        app.resourcePacks.uninstall(pack.hash)
        val root=java.io.File(app.cacheDir,"restore-resource-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val isolated=org.inkweft.data.NoteDatabase.open(app,java.io.File(root,"restored.db").absolutePath)
        try{val restore=LibraryBackupRepository(app,isolated)
            app.libraryBackup.snapshot().use{snapshot->snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}}
            assertEquals(before,org.inkweft.data.DocumentRepository(isolated).read(note.id)!!.document.sha256)
            assertEquals(listOf(note.id),org.inkweft.data.ResourceTemplates(isolated).copies(pack.hash))
        }finally{isolated.close();check(root.canonicalPath.startsWith(app.cacheDir.canonicalPath+java.io.File.separator));root.deleteRecursively()}
    }
    @Test fun lowSpaceAndInstanceFaultCutsKeepOldVersionAndReceipt()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val first=ResourcePackCodec.inspect(bytes());app.resourcePacks.install(first)
        try{ResourcePacks(app,freeBytes={0}).install(ResourcePackCodec.inspect(bytes(2)));fail("No space accepted")}catch(_:IllegalArgumentException){}
        assertTrue(app.resourcePacks.installed().single().enabled)
        val directory=java.io.File(app.cacheDir,"resource-fault-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val db=org.inkweft.data.NoteDatabase.open(app,java.io.File(directory,"library.db").absolutePath)
        try{
            for(cut in listOf("after-author","after-document","before-reference","after-commit")){
                val op=java.util.UUID.randomUUID().toString();val faulty=org.inkweft.data.ResourceTemplates(db){if(it==cut)error("injected")}
                try{faulty.instantiate(first.hash,"实例验收",PaperStyle.CORNELL,null,first.resources.last().map,op);fail("Fault ignored")}catch(_:IllegalStateException){}
                val healthy=org.inkweft.data.ResourceTemplates(db);assertEquals(cut=="after-commit",healthy.created(op,first.hash)!=null)
                val recovered=healthy.instantiate(first.hash,"实例验收",PaperStyle.CORNELL,null,first.resources.last().map,op)
                assertEquals(recovered.id,healthy.instantiate(first.hash,"实例验收",PaperStyle.CORNELL,null,first.resources.last().map,op).id)
            }
            assertEquals(4,org.inkweft.data.ResourceTemplates(db).copies(first.hash).size)
        }finally{db.close();require(directory.canonicalFile.parentFile==app.cacheDir.canonicalFile);directory.deleteRecursively()}
    }
    @Test fun catalogReusesVerifiedMetadataButRejectsChangedArchive()=runBlocking{
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val pack=ResourcePackCodec.inspect(bytes());val store=ResourcePacks(app);store.install(pack)
        repeat(3){assertEquals(2,store.catalog().size)};assertEquals(0,store.validations)
        val restarted=ResourcePacks(app);restarted.catalog();val validations=restarted.validations
        repeat(3){restarted.catalog()};assertEquals(validations,restarted.validations)
        val file=java.io.File(app.filesDir,"resource-packs/${pack.hash}.iwpack");val original=file.readBytes()
        try{file.writeBytes(original.copyOf().also{it[it.lastIndex]=(it.last().toInt() xor 1).toByte()})
            assertTrue(restarted.catalog().isEmpty());assertTrue(restarted.installed().single().damaged)
            restarted.install(pack);assertFalse(restarted.installed().single().damaged)
        }finally{file.writeBytes(original)}
    }
    @Test fun templatesInsertIntoCurrentBookAndMapWithoutChangingOldContent()=runBlocking{
        fun id()=java.util.UUID.randomUUID().toString()
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val pack=ResourcePackCodec.inspect(bytes());val store=app.resourcePacks;store.install(pack)
        val note=app.workspaceRepository.create("原笔记",false,PaperStyle.RULED)
        val command=InsertPages(id(),note.id,InsertPages.orderHash(listOf(note.id)),PageInsertLocation.AFTER,note.id,PaperStyle.CORNELL,listOf(id(),id()),false,note.id)
        assertTrue(store.insert(TemplateRef(pack.hash,"cornell"),command) is InsertPagesResult.Applied)
        val rows=app.pages.activePages(note.id);assertEquals(3,rows.size);assertEquals(PaperStyle.RULED.ordinal,rows.first().paper)
        assertEquals(listOf(PaperStyle.CORNELL.ordinal,PaperStyle.CORNELL.ordinal),rows.drop(1).map{it.paper})
        val map=KnowledgeCommand(id(),note.id,id(),0,MapTemplates.instantiate(pack.resources.last().map!!,"当前本导图"))
        store.map(TemplateRef(pack.hash,"review"),map)
        store.uninstall(pack.hash)
        assertTrue((store.insert(TemplateRef(pack.hash,"cornell"),command) as InsertPagesResult.Applied).replayed)
        assertEquals(map.id,store.map(TemplateRef(pack.hash,"review"),map))
        assertEquals(3,app.pages.activePages(note.id).size)
        assertEquals(listOf(note.id),app.resourceTemplates.copies(pack.hash).distinct())
        assertTrue(app.mapGraphs.read(note.id).any{it.nodes.isNotEmpty()})
        app.libraryBackup.snapshot().use{snapshot->snapshot.file.inputStream().use{app.libraryBackup.inspect(it)}.use{assertEquals(1,it.notes)}}
    }
    @Test fun hostileArchivePathsBudgetsAndNamespacesFailBeforeInstall(){
        val valid=manifest().toString().toByteArray()
        for(path in listOf("../x.json","/x.json","C:/x.json","a/../x.json","a//x.json","nested.zip","script.js")){
            assertThrows(Exception::class.java){ResourcePackCodec.inspect(zip("manifest.json" to valid,path to byteArrayOf(1)))}
        }
        assertThrows(Exception::class.java){ResourcePackCodec.inspect(zip("manifest.json" to manifest().put("id","org.inkweft.replace").toString().toByteArray()))}
        assertThrows(Exception::class.java){ResourcePackCodec.inspect(zip("manifest.json" to ("[".repeat(17)+"]".repeat(17)).toByteArray()))}
        assertThrows(Exception::class.java){ResourcePackCodec.inspect(zip("manifest.json" to valid,"bomb.png" to ByteArray(1024*1024)))}
        val symlink=bytes();val b=java.nio.ByteBuffer.wrap(symlink).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val central=(0..symlink.size-46).first{b.getInt(it)==0x02014b50};b.putInt(central+38,0xa1ff shl 16)
        assertThrows(Exception::class.java){ResourcePackCodec.inspect(symlink)}
        assertEquals(2,ResourcePackCodec.inspect(bytes()).resources.size)
    }
}
