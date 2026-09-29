// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.ContextWrapper
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.ContentTransfer
import org.inkweft.core.PaperStyle
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** The runner must create a private bounded tmpfs. This probe never fills the parent filesystem. */
class AndroidTemporarySpaceProbe {
    @Test fun realEnospcDropsPartialFilesAndOldCipherRemainsRestorable()=runBlocking{
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as InkWeftApplication
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("spaceProbe")=="dedicated-emulator"&&args.getString("deviceGuard")=="verified-disposable-emulator")
        require(app.packageName in setOf("org.inkweft.app.a0.insertion","org.inkweft.app.a0.workspace"))
        val limited=File(checkNotNull(args.getString("spaceRoot"))).canonicalFile
        require(limited.parentFile==app.cacheDir.canonicalFile&&limited.name.matches(Regex("v47-space-[0-9a-f-]{36}"))){"Unapproved fault directory"}
        val mount=File("/proc/mounts").readLines().map{it.split(' ')}.singleOrNull{it.size>=4&&it[1]==limited.path}
        require(mount!=null&&mount[2]=="tmpfs"&&"rw" in mount[3].split(',')){"A dedicated writable tmpfs is required"}
        val stats=StatFs(limited.path);val capacity=stats.blockCountLong*stats.blockSizeLong
        require(capacity in 1..8L*1024*1024&&limited.listFiles()?.isEmpty()==true){"Fault scope is not empty or exceeds 8 MiB"}
        val root=File(app.cacheDir,"space-evidence-${UUID.randomUUID()}").apply{check(mkdirs())}
        val source=NoteDatabase.open(app,File(root,"source.db").absolutePath)
        val restored=NoteDatabase.open(app,File(root,"restored.db").absolutePath)
        val key=EncryptedBackupFile.random(32);val library=UUID.randomUUID().toString()
        val report=JSONObject().put("format","inkweft.android-space.v1").put("capacityBytes",capacity).put("filesystem","tmpfs").put("status","FAIL")
        var snapshot:LibraryBackupRepository.Snapshot?=null
        val filler=File(limited,"bounded-fill.bin")
        val output=File(limited,"cipher.iwbk");val clear=File(limited,"restored.iwbackup")
        fun errno(t:Throwable)=generateSequence(t){it.cause}.filterIsInstance<ErrnoException>().firstOrNull()?.errno
        fun full(label:String,action:()->Unit){
            var failure:Throwable?=null
            try{action()}catch(t:Throwable){failure=t}
            check(failure!=null&&errno(failure)==OsConstants.ENOSPC){"$label did not fail with ENOSPC"}
            report.put(label,"ENOSPC")
        }
        try{
            val note=WorkspaceRepository(source).create("独立限容合成笔记",false,PaperStyle.DOTS)
            snapshot=LibraryBackupRepository(app,source).snapshot()
            val old=File(root,"old.iwbk")
            EncryptedBackupFile.encrypt(snapshot!!.file,old,library,key)
            val digest=ContentTransfer.hash(old.readBytes());val plain=ContentTransfer.hash(snapshot!!.file.readBytes())
            val limitedContext=object:ContextWrapper(app){override fun getCacheDir()=limited}
            val limitedBackup=LibraryBackupRepository(limitedContext,source)
            val spaceError=runCatching{limitedBackup.snapshot()}.exceptionOrNull()
            check(spaceError?.message=="BACKUP_LOW_SPACE"){"Backup reserve gate not reached"}
            report.put("backupReserve","BACKUP_LOW_SPACE")
            var written=0L
            full("filesystemWrite"){
                FileOutputStream(filler).use{stream->
                    val bytes=ByteArray(65536)
                    while(written<=capacity){val count=Os.write(stream.fd,bytes,0,bytes.size);check(count>0);written+=count}
                    error("tmpfs capacity was not enforced")
                }
            }
            check(written<=capacity){"Fault exceeded bounded mount"}
            full("encryptedTemporaryWrite"){EncryptedBackupFile.encrypt(snapshot!!.file,output,library,key)}
            check(!output.exists()){ "Failed ciphertext was published" }
            full("decryptedTemporaryWrite"){EncryptedBackupFile.decrypt(old,clear,library,key)}
            check(!clear.exists()&&limited.listFiles().orEmpty().none{it.name.endsWith(".partial")}){"Failed plaintext escaped staging"}
            check(digest==ContentTransfer.hash(old.readBytes())){"Old ciphertext changed"}
            check(filler.delete())
            EncryptedBackupFile.decrypt(old,clear,library,key)
            check(plain==ContentTransfer.hash(clear.readBytes())){"Recovered archive digest changed"}
            val restore=LibraryBackupRepository(app,restored)
            clear.inputStream().use{restore.inspect(it)}.use{preview->check(restore.restore(preview)==LibraryBackupRepository.RestoreResult.RESTORED)}
            check(NoteRepository(restored).read(note.id)?.title==note.title){"Old author content not restored"}
            check(NoteRepository(source).read(note.id)?.title==note.title){"Source author content changed"}
            report.put("status","PASS").put("filledBytes",written).put("partialCipherAbsent",true).put("partialPlaintextAbsent",true)
                .put("oldCipherUnchanged",true).put("isolatedRestore","RESTORED").put("parentFilesystemFilled",false)
        }finally{
            key.fill(0);filler.delete();output.delete();clear.delete()
            val staging=File(limited,"library-backup");check(staging.listFiles()?.isEmpty()!=false);staging.delete()
            snapshot?.close();source.close();restored.close()
            check(root.canonicalFile.parentFile==app.cacheDir.canonicalFile);root.deleteRecursively()
            File(app.getExternalFilesDir(null),"v47-android-space.json").writeText(report.toString(2))
        }
    }
}
