// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Attach the existing SHA-256 footer without first cloning the complete body. */
internal class ChecksummedBuffer:ByteArrayOutputStream() {
    fun finish(maxBytes:Int):ByteArray {
        require(count.toLong()+32<=maxBytes)
        val hash=MessageDigest.getInstance("SHA-256").apply{update(buf,0,count)}.digest()
        return buf.copyOf(count+32).also{hash.copyInto(it,count)}
    }
}
