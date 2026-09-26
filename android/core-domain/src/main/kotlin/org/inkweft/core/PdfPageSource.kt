// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.security.MessageDigest

/** A stable fixed-page source. Books share the same immutable document instead of duplicating it per page. */
class PdfDocumentSource(bytes:ByteArray,val pages:Int) {
    private val content=bytes.copyOf()
    val size get()=content.size
    val sha256:String=MessageDigest.getInstance("SHA-256").digest(content).joinToString(""){"%02x".format(it.toInt()and 255)}
    init{require(content.size in 8..MAX_BYTES&&content.take(5).toByteArray().contentEquals("%PDF-".toByteArray()));require(pages in 1..500)}
    fun bytes()=content.copyOf()
    companion object {const val MAX_BYTES=32_000_000}
}
data class PdfPageSource(val document:PdfDocumentSource,val page:Int){init{require(page in 0 until document.pages)}}
