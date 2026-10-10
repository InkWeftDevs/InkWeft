// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.File
import java.io.OutputStream

/** A stable fixed-page source. Books share the same immutable document instead of duplicating it per page. */
class PdfDocumentSource private constructor(private val content:OriginalBytes,val pages:Int,verifyHeader:Boolean=true) {
    constructor(bytes:ByteArray,pages:Int):this(OriginalBytes.memory(bytes.also{require(it.size<=ARRAY_MAX_BYTES){"CONTENT_SIZE_LIMIT_USE_FULL_BACKUP"}}),pages)
    val size get()=content.size
    val sha256 get()=content.sha256
    init{require(size in 8..MAX_BYTES);require(pages in 1..500)
        if(verifyHeader)content.openStream().use{val header=ByteArray(5);java.io.DataInputStream(it).readFully(header);require(header.contentEquals("%PDF-".toByteArray()))}}
    fun openStream()=content.openStream()
    fun copyTo(output:OutputStream,checkActive:()->Unit={})=content.copyTo(output,checkActive)
    /** Legacy in-memory content formats stay bounded even when a stored PDF is much larger. */
    fun bytes()=content.bytes(ARRAY_MAX_BYTES)
    companion object {
        const val MAX_BYTES=512_000_000
        const val ARRAY_MAX_BYTES=32_000_000
        fun fromFile(file:File,pages:Int,checkActive:()->Unit={})=PdfDocumentSource(OriginalBytes.file(file,MAX_BYTES,checkActive),pages)
        /** Identity comes from streaming import or immutable stored chunks. Avoid fetching chunk zero on every page lookup. */
        fun fromStream(content:OriginalBytes,pages:Int)=PdfDocumentSource(content,pages,false)
    }
}
data class PdfPageSource(val document:PdfDocumentSource,val page:Int){init{require(page in 0 until document.pages)}}
