// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.*
import org.inkweft.core.OriginalBytes
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class OriginalFileCacheTest {
    @Test fun cacheCanBeClearedAfterRestartBeforeAnyOriginalIsOpened()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("original-cache-test").toFile()
        try{val content=OriginalBytes.memory(ByteArray(120))
            OriginalFileCache(root).acquire(content.size,content.sha256){out,active->content.copyTo(out,active)}.close()
            val restarted=OriginalFileCache(root);assertEquals(120L,restarted.diskBytes());restarted.trim();assertEquals(0L,restarted.diskBytes())
        }finally{root.deleteRecursively()}
    }
    @Test fun restartReusesVerifiedFilesAndRebuildsTamperedBytes()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("original-cache-test").toFile()
        try {
            val content=OriginalBytes.memory(ByteArray(120){it.toByte()});var copies=0
            suspend fun acquire(cache:OriginalFileCache)=cache.acquire(content.size,content.sha256){out,active->copies++;content.copyTo(out,active)}
            acquire(OriginalFileCache(root)).close()
            acquire(OriginalFileCache(root)).close();assertEquals(1,copies)
            root.listFiles()!!.single().writeBytes(ByteArray(120){3})
            acquire(OriginalFileCache(root)).use{assertArrayEquals(content.bytes(120),it.file.readBytes())}
            assertEquals(2,copies)
        }finally{root.deleteRecursively()}
    }
    @Test fun leasedFilesSurviveTrimAndLruEvictsOnlyReleasedFiles()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("original-cache-test").toFile()
        try{
            val cache=OriginalFileCache(root,200);val a=OriginalBytes.memory(ByteArray(120){1});val b=OriginalBytes.memory(ByteArray(120){2})
            val first=cache.acquire(a.size,a.sha256){out,active->a.copyTo(out,active)}
            cache.trim();assertTrue(first.file.exists())
            try{cache.acquire(b.size,b.sha256){out,active->b.copyTo(out,active)};fail()}catch(e:IllegalArgumentException){assertEquals("ORIGINAL_CACHE_BUSY",e.message)}
            first.close();first.close()
            cache.acquire(b.size,b.sha256){out,active->b.copyTo(out,active)}.use{assertFalse(first.file.exists());assertTrue(it.file.exists())}
            cache.trim();assertEquals(0L,cache.snapshot().first)
        }finally{root.deleteRecursively()}
    }
    @Test fun incompleteOrCancelledCopiesAreNeverPublished()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("original-cache-test").toFile()
        try{
            val cache=OriginalFileCache(root)
            try{cache.acquire(100,"a".repeat(64)){out,_->out.write(byteArrayOf(1));throw CancellationException()};fail()}catch(_:CancellationException){}
            assertEquals(0,root.listFiles()!!.size);assertEquals(0,cache.snapshot().second)
        }finally{root.deleteRecursively()}
    }
    @Test fun sourceChunksRejectMissingAndWrongFinalBlock(){
        val bytes=ByteArray(11){it.toByte()}
        val stream=OriginalChunkInput(11,4){index->bytes.copyOfRange(index*4,minOf(11,(index+1)*4))}
        assertArrayEquals(bytes,stream.readBytes())
        try{OriginalChunkInput(11,4){if(it==1)null else ByteArray(4)}.readBytes();fail()}catch(e:IllegalArgumentException){assertEquals("ORIGINAL_CHUNK_MISSING",e.message)}
        try{OriginalChunkInput(11,4){ByteArray(4)}.readBytes();fail()}catch(e:IllegalArgumentException){assertEquals("ORIGINAL_CHUNK_LENGTH",e.message)}
    }
}
