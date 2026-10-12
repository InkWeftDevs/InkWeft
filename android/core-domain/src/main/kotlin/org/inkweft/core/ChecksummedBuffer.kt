// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest

/** Fixed chunks avoid a second page-sized allocation on geometric buffer growth. */
internal class ChecksummedBuffer:OutputStream() {
    private val chunks=ArrayList<ByteArray>()
    private var count=0
    private var finished=false
    fun size()=count
    override fun write(value:Int){
        check(!finished)
        if(count%CHUNK==0)chunks.add(ByteArray(CHUNK))
        chunks.last()[count++%CHUNK]=value.toByte()
    }
    override fun write(bytes:ByteArray,offset:Int,length:Int){
        require(offset>=0&&length>=0&&offset<=bytes.size-length);check(!finished)
        var copied=0
        while(copied<length){
            if(count%CHUNK==0)chunks.add(ByteArray(CHUNK))
            val n=minOf(length-copied,CHUNK-count%CHUNK)
            bytes.copyInto(chunks.last(),count%CHUNK,offset+copied,offset+copied+n)
            count+=n;copied+=n
        }
    }
    fun finish(maxBytes:Int):ByteArray {
        check(!finished);finished=true
        require(count.toLong()+32<=maxBytes)
        val output=ByteArray(count+32);val hash=MessageDigest.getInstance("SHA-256")
        var offset=0
        for(chunk in chunks){val n=minOf(CHUNK,count-offset);hash.update(chunk,0,n);chunk.copyInto(output,offset,0,n);offset+=n}
        chunks.clear();hash.digest().copyInto(output,count);return output
    }

    companion object {
        private const val CHUNK=64*1024
        /** A bounded view prevents parsing the footer as data, without a body-sized copy. */
        fun checkedInput(bytes:ByteArray,offset:Int=0,length:Int=bytes.size):DataInputStream {
            require(length>=32&&offset>=0&&offset<=bytes.size-length)
            val size=length-32
            val hash=MessageDigest.getInstance("SHA-256").apply{update(bytes,offset,size)}.digest()
            require(MessageDigest.isEqual(hash,bytes.copyOfRange(offset+size,offset+length)))
            return DataInputStream(ByteArrayInputStream(bytes,offset,size))
        }
    }
}
