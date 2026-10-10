// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.File
import java.io.OutputStream

/** Owned, unmodified import bytes. The JPEG in PageObject is only a quick preview. */
class ImageSource private constructor(private val content:OriginalBytes) {
    constructor(bytes:ByteArray):this(OriginalBytes.memory(bytes.also{require(it.size in 1..MAX_BYTES){"IMAGE_ORIGINAL_SIZE"}}))
    val sha256 get()=content.sha256
    val size get()=content.size
    init { require(size in 1..MAX_BYTES){"IMAGE_ORIGINAL_SIZE"} }
    fun bytes()=content.bytes(MAX_BYTES)
    fun openStream()=content.openStream()
    fun copyTo(output:OutputStream,checkActive:()->Unit={})=content.copyTo(output,checkActive)
    companion object {
        const val MAX_BYTES=20*1024*1024
        fun validHash(value:String)=value.matches(Regex("[0-9a-f]{64}"))
        fun fromFile(file:File,checkActive:()->Unit={})=ImageSource(OriginalBytes.file(file,MAX_BYTES,checkActive))
        fun fromStream(content:OriginalBytes)=ImageSource(content)
    }
}
