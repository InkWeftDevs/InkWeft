// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Owned, unmodified import bytes. The JPEG in PageObject is only a quick preview. */
class ImageSource(bytes:ByteArray) {
    private val content=bytes.copyOf()
    val sha256=ContentTransfer.hash(content)
    val size get()=content.size
    init { require(size in 1..MAX_BYTES){"IMAGE_ORIGINAL_SIZE"} }
    fun bytes()=content.copyOf()
    companion object {
        const val MAX_BYTES=20*1024*1024
        const val LIBRARY_BYTES=32_000_000L
        fun validHash(value:String)=value.matches(Regex("[0-9a-f]{64}"))
    }
}
