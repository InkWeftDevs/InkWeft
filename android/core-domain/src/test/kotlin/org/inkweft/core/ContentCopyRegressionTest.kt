// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID

class ContentCopyRegressionTest {
    private fun page()=InkPageFile("图层副本","原文",emptyList(),authoring=PageAuthoring(UserLayers().add(UserLayer(UUID.randomUUID().toString(),"自定义层"))))

    @Test fun latestAuthoringPageCanBeImportedThroughTheRealTransferEntry(){
        val original=page();val bytes=original.encode()
        assertEquals(ContentTransfer.Kind.PAGE,ContentTransfer.kind(bytes))
        for(parsed in listOf(ContentTransfer.decode(bytes),ContentTransfer.read(ByteArrayInputStream(bytes)))){
            val copy=(parsed.content as ContentTransfer.Content.Page).value
            assertEquals(original.title,copy.title);assertEquals(original.text,copy.text)
            assertEquals(PageAuthoringCodec.fingerprint(original.authoring!!),PageAuthoringCodec.fingerprint(copy.authoring!!))
            assertEquals(ContentTransfer.hash(bytes),parsed.sha256);assertEquals(bytes.size,parsed.byteCount)
        }
    }

    @Test fun nestedPageParsingUsesOnlyItsBoundedSlice(){
        val original=page();val bytes=original.encode();val wrapped=ByteArray(7)+bytes+ByteArray(19){42}
        assertEquals(original.text,InkPageFile.decodeRange(wrapped,7,bytes.size).text)
        assertThrows(IllegalArgumentException::class.java){InkPageFile.decodeRange(wrapped,6,bytes.size)}
        assertThrows(IllegalArgumentException::class.java){InkPageFile.decodeRange(wrapped,7,bytes.size+1)}
        assertThrows(IllegalArgumentException::class.java){InkPageFile.decodeRange(wrapped,7,Int.MAX_VALUE)}
        val book=NotebookFile("整本","",listOf(original,InkPageFile("第二页","第二页原文",emptyList())))
        val copy=NotebookFile.decode(book.encode())
        assertEquals(book.pages.map{it.text},copy.pages.map{it.text})
        assertEquals(PageAuthoringCodec.fingerprint(original.authoring!!),PageAuthoringCodec.fingerprint(copy.pages[0].authoring!!))
    }

    @Test fun validOuterChecksumDoesNotBypassNestedPageChecksum(){
        val p=page();val original=p.encode();val bytes=NotebookFile("整本","",listOf(p)).encode()
        val header=byteArrayOf(0x49,0x57,0x50,0x38)
        val offset=(0 until bytes.size-4).first{n->header.indices.all{bytes[n+it]==header[it]}}
        bytes[offset+original.size-1]=(bytes[offset+original.size-1].toInt() xor 1).toByte()
        val hash=MessageDigest.getInstance("SHA-256").apply{update(bytes,0,bytes.size-32)}.digest();hash.copyInto(bytes,bytes.size-32)
        assertThrows(IllegalArgumentException::class.java){NotebookFile.decode(bytes)}
    }
}
