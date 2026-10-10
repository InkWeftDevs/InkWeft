// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/** One bounded SQLite blob at a time; no CursorWindow or joined original crosses the API. */
internal class OriginalChunkInput(private val size:Int,private val chunkSize:Int,
    private val readChunk:(Int)->ByteArray?):InputStream() {
    private var offset=0;private var position=0;private var block=ByteArray(0);private var at=0;private var closed=false
    override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
    override fun read(bytes:ByteArray,off:Int,len:Int):Int {
        check(!closed);require(off>=0&&len>=0&&off<=bytes.size-len)
        if(len==0)return 0
        if(offset==size)return -1
        if(at==block.size){block=requireNotNull(readChunk(position++)){"ORIGINAL_CHUNK_MISSING"};at=0
            require(block.size==minOf(chunkSize,size-offset)){"ORIGINAL_CHUNK_LENGTH"}}
        val n=minOf(len,block.size-at);block.copyInto(bytes,off,at,at+n);at+=n;offset+=n;return n
    }
    override fun close(){closed=true;block=ByteArray(0)}
}

/** Destination inserts participate in the caller's transaction; reject changed/trailing input before commit. */
internal suspend fun storeOriginal(size:Int,sha256:String,chunkSize:Int,input:()->InputStream,
    insert:suspend(Int,ByteArray)->Unit) {
    val context=currentCoroutineContext();val hash=MessageDigest.getInstance("SHA-256");var seen=0;var index=0
    input().use{stream->
        while(seen<size){context.ensureActive();val block=ByteArray(minOf(chunkSize,size-seen));var at=0
            while(at<block.size){context.ensureActive();val n=stream.read(block,at,block.size-at)
                require(n>=0){"ORIGINAL_TRUNCATED"}
                if(n==0){val one=stream.read();require(one>=0){"ORIGINAL_TRUNCATED"};block[at++]=one.toByte()}else at+=n}
            hash.update(block);insert(index++,block);seen+=block.size
        }
        context.ensureActive();require(stream.read()==-1){"ORIGINAL_LENGTH_CHANGED"}
    }
    require(hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==sha256){"ORIGINAL_CHECKSUM_CHANGED"}
}
