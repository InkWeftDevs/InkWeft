// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest

/** Immutable identity, repeatable input. Large originals never require a joined byte array.
 * File/stream owners keep their backing storage alive; every complete copy verifies its identity. */
class OriginalBytes private constructor(val size:Int,val sha256:String,private val input:()->InputStream) {
    fun openStream():InputStream=input()
    fun copyTo(output:OutputStream,checkActive:()->Unit={}) {
        val hash=MessageDigest.getInstance("SHA-256");var seen=0L
        openStream().use{source->
            val buffer=ByteArray(64*1024)
            while(true){checkActive();var n=source.read(buffer)
                if(n<0)break
                if(n==0){val one=source.read();if(one<0)break;buffer[0]=one.toByte();n=1}
                require(n.toLong()<=size.toLong()-seen){"ORIGINAL_LENGTH_CHANGED"}
                output.write(buffer,0,n);hash.update(buffer,0,n);seen+=n
            }
        }
        checkActive();require(seen==size.toLong()){ "ORIGINAL_TRUNCATED" }
        require(hex(hash.digest())==sha256){"ORIGINAL_CHECKSUM_CHANGED"}
    }
    fun bytes(limit:Int):ByteArray {
        require(size<=limit){"CONTENT_SIZE_LIMIT_USE_FULL_BACKUP"}
        return ByteArrayOutputStream(size).also{copyTo(it)}.toByteArray()
    }
    companion object {
        private fun hex(b:ByteArray)=b.joinToString(""){"%02x".format(it.toInt() and 255)}
        fun memory(bytes:ByteArray):OriginalBytes {
            val owned=bytes.copyOf()
            return OriginalBytes(owned.size,hex(MessageDigest.getInstance("SHA-256").digest(owned))){owned.inputStream()}
        }
        /** The application owns this stable staging file until import finishes or is dismissed. */
        fun file(file:File,maxBytes:Int,checkActive:()->Unit={}):OriginalBytes {
            val size=file.length();require(size in 1..maxBytes.toLong()){"ORIGINAL_SIZE_LIMIT"}
            val hash=MessageDigest.getInstance("SHA-256");var seen=0L
            file.inputStream().use{source->val buffer=ByteArray(64*1024)
                while(true){checkActive();val n=source.read(buffer);if(n<0)break
                    seen+=n;require(seen<=size);hash.update(buffer,0,n)}
            }
            checkActive();require(seen==size&&file.length()==size){"ORIGINAL_LENGTH_CHANGED"}
            return OriginalBytes(size.toInt(),hex(hash.digest())){require(file.length()==size){"ORIGINAL_LENGTH_CHANGED"};file.inputStream()}
        }
        /** For immutable, transactionally stored chunks. A full copy checks the supplied digest. */
        fun stream(size:Int,sha256:String,input:()->InputStream):OriginalBytes {
            require(size>0&&sha256.matches(Regex("[0-9a-f]{64}")))
            return OriginalBytes(size,sha256,input)
        }
    }
}
