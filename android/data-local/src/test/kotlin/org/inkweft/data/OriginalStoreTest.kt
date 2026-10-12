// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import org.inkweft.core.OriginalBytes
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class OriginalStoreTest {
    private fun fixture(block:(File,OriginalStore)->Unit){
        val parent=Files.createTempDirectory("owned-original-test").toFile()
        try{val root=File(parent,"test.db.originals");block(root,OriginalStore(root){})}finally{parent.deleteRecursively()}
    }
    @Test fun duplicatesShareOneFileAndEveryLeaseProtectsCollection()=fixture{root,store->
        val original=OriginalBytes.memory(ByteArray(1000){it.toByte()});var copies=0;var validations=0
        repeat(2){store.publish(original.size,original.sha256,validate={validations++}){out->copies++;original.copyTo(out)}}
        assertEquals(1,copies);assertEquals(2,validations);assertEquals(1000L,store.diskBytes());assertEquals(1,root.listFiles()!!.size)
        val first=store.acquire(original.size,original.sha256);val second=store.acquire(original.size,original.sha256)
        assertEquals(first.file,second.file);assertArrayEquals(original.bytes(1000),first.file.readBytes())
        assertEquals(0,store.collect({false}).files)
        first.close();first.close();assertEquals(0,store.collect({false}).files)
        second.close();assertEquals(0,store.collect({it==original.sha256}).files)
        assertEquals(1000L,store.collect({false}).bytes);assertFalse(first.file.exists())
    }
    @Test fun failedDigestTruncationAndCancellationNeverPublish()=fixture{root,store->
        val original=OriginalBytes.memory(ByteArray(10){1})
        assertThrows(IllegalArgumentException::class.java){store.publish(10,original.sha256){it.write(ByteArray(10){2})}}
        assertThrows(IllegalArgumentException::class.java){store.publish(10,original.sha256){it.write(ByteArray(9){1})}}
        assertThrows(CancellationException::class.java){store.publish(10,original.sha256){it.write(byteArrayOf(1));throw CancellationException()}}
        assertEquals(0L,store.diskBytes());assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun directoryDurabilityFailureLeavesOnlyARecoverableOrphan() {
        val root=Files.createTempDirectory("original-sync-test").toFile()
        try{
            val original=OriginalBytes.memory(ByteArray(20){3})
            var failing=true;var syncs=0
            val store=OriginalStore(root){directory->if(directory==root){syncs++;if(failing)throw IOException("injected directory sync")}}
            assertThrows(IOException::class.java){store.publish(original.size,original.sha256){original.copyTo(it)}}
            assertTrue(store.contains(original.sha256))
            assertThrows(IOException::class.java){store.publish(original.size,original.sha256){error("retry must reuse complete orphan")}}
            assertEquals(2,syncs)
            failing=false;store.publish(original.size,original.sha256){error("recovered publication must reuse orphan")}
            assertEquals(3,syncs)
            val restarted=OriginalStore(root){directory->if(directory==root)syncs++}
            restarted.writer(original.size,original.sha256).use{writer->original.copyTo(writer);writer.finish()}
            assertEquals(4,syncs)
            restarted.acquire(original.size,original.sha256).use{assertArrayEquals(original.bytes(20),it.file.readBytes())}
            assertEquals(20L,restarted.collect({false}).bytes)
        }finally{root.deleteRecursively()}
    }
    @Test fun retryAfterRootCreationMustRepeatParentDirectorySync() {
        val parent=Files.createTempDirectory("original-parent-sync-test").toFile();val root=File(parent,"owned")
        try{
            var attempts=0;val store=OriginalStore(root){if(it==parent){attempts++;throw IOException("injected parent sync")}}
            repeat(2){assertThrows(IOException::class.java){store.writer(20,"a".repeat(64))}}
            assertEquals(2,attempts);assertTrue(root.isDirectory);assertTrue(root.listFiles()!!.isEmpty())
        }finally{parent.deleteRecursively()}
    }
    @Test fun restartDetectsTamperingWithoutDeletingReferencedBytes()=fixture{root,store->
        val original=OriginalBytes.memory(ByteArray(30){5})
        store.publish(original.size,original.sha256){original.copyTo(it)}
        val f=root.listFiles()!!.single();f.writeBytes(ByteArray(30){6})
        val restarted=OriginalStore(root){}
        assertThrows(IllegalArgumentException::class.java){restarted.acquire(original.size,original.sha256)}
        assertEquals(0,restarted.collect({true}).files);assertArrayEquals(ByteArray(30){6},f.readBytes())
    }
    @Test fun openStreamRetainsItsFileUntilClosed()=fixture{_,store->
        val original=OriginalBytes.memory(ByteArray(80){7})
        store.publish(original.size,original.sha256){original.copyTo(it)}
        store.content(original.size,original.sha256).openStream().use{stream->
            assertEquals(0,store.collect({false}).files);assertArrayEquals(original.bytes(80),stream.readBytes())
        }
        assertEquals(1,store.collect({false}).files)
    }
    @Test fun largeOriginalUsesBoundedTransfersAndOnePhysicalCopy()=fixture{_,store->
        val size=40*1024*1024;val block=ByteArray(64*1024){(it%251).toByte()}
        val digest=MessageDigest.getInstance("SHA-256");repeat(size/block.size){digest.update(block)}
        val hash=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        var largest=0
        val source=OriginalBytes.stream(size,hash){object:InputStream(){
            var read=0
            override fun read():Int=if(read==size)-1 else block[(read++)%block.size].toInt() and 255
            override fun read(bytes:ByteArray,off:Int,len:Int):Int {
                largest=maxOf(largest,len);check(len<=64*1024)
                if(read==size)return -1
                val n=minOf(len,size-read,block.size-read%block.size)
                block.copyInto(bytes,off,read%block.size,read%block.size+n);read+=n;return n
            }
        }}
        store.publish(size,hash){source.copyTo(it)}
        store.publish(size,hash){error("second copy must reuse the existing immutable file")}
        var copied=0L;store.content(size,hash).copyTo(object:OutputStream(){
            override fun write(value:Int){copied++}
            override fun write(bytes:ByteArray,offset:Int,length:Int){assertTrue(length<=64*1024);copied+=length}
        })
        assertEquals(size.toLong(),copied);assertEquals(64*1024,largest);assertEquals(size.toLong(),store.diskBytes())
        println("ORIGINAL_STORE_EVIDENCE bytes=$size physicalBytes=${store.diskBytes()} largestTransferBytes=$largest copies=1")
    }
    @Test fun stalePartsAreCollectedButUnknownFilesArePreserved()=fixture{root,store->
        check(root.mkdir());File(root,"pending-abandoned.part").writeBytes(ByteArray(19))
        File(root,"user-evidence.txt").writeText("preserve")
        store.writer(10,"a".repeat(64)).use{writer->
            writer.write(byteArrayOf(1,2));assertEquals(19L,store.collect({false}).bytes)
            assertEquals(2,root.listFiles()!!.size)
        }
        assertEquals(listOf("user-evidence.txt"),root.listFiles()!!.map{it.name})
    }
    @Test fun lowSpaceRejectsNewBytesBeforeStagingButKeepsExistingOriginalsUsable()=fixture{root,store->
        val first=OriginalBytes.memory(ByteArray(20){1});val second=OriginalBytes.memory(ByteArray(20){2})
        store.publish(first.size,first.sha256){first.copyTo(it)}
        val full=OriginalStore(root,availableBytes={0L}){}
        full.publish(first.size,first.sha256){error("existing original must not be copied")}
        val error=assertThrows(IllegalArgumentException::class.java){full.publish(second.size,second.sha256){second.copyTo(it)}}
        assertEquals("ORIGINAL_LOW_SPACE",error.message);assertEquals(1,root.listFiles()!!.size)
        full.acquire(first.size,first.sha256).use{assertArrayEquals(first.bytes(20),it.file.readBytes())}
    }
}
