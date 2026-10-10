// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest

/** Attach the existing SHA-256 footer without first cloning the complete body. */
internal class ChecksummedBuffer:ByteArrayOutputStream() {
    fun finish(maxBytes:Int):ByteArray {
        require(count.toLong()+32<=maxBytes)
        val hash=MessageDigest.getInstance("SHA-256").apply{update(buf,0,count)}.digest()
        return buf.copyOf(count+32).also{hash.copyInto(it,count)}
    }

    companion object {
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
