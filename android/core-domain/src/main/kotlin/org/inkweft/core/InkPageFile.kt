// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.security.MessageDigest
import java.util.Collections

/** Portable, bounded page CONTENT copy. Not a full vault/history backup or encryption. */
class InkPageFile(val title: String, val text: String, strokes: List<InkStroke>) {
    val strokes: List<InkStroke> = Collections.unmodifiableList(ArrayList(strokes))
    init {
        require(title.isNotBlank() && title.length <= 120 && text.length <= 100_000)
        require(strokes.size <= InkLimits.MAX_STROKES)
        require(strokes.map { it.id }.distinct().size == strokes.size)
        require(strokes.sumOf { it.samples.size } <= InkLimits.MAX_PAGE_POINTS)
    }
    fun encode(): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.writeInt(0x49575031) // IWP1: fixed 1000 x 1414 canonical page
            fun field(text: String) { val data=text.toByteArray(Charsets.UTF_8); out.writeInt(data.size); out.write(data) }
            field(title); field(text); out.writeInt(strokes.size)
            strokes.forEach { val data=InkStrokeCodec.encode(it);out.writeInt(data.size);out.write(data) }
        }
        val bytes=body.toByteArray()
        require(bytes.size+32<=MAX_BYTES)
        return bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    companion object {
        const val MAX_BYTES = 4_000_000
        fun decode(bytes: ByteArray): InkPageFile {
            require(bytes.size in 48..MAX_BYTES)
            val body=bytes.copyOfRange(0,bytes.size-32)
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body),bytes.copyOfRange(bytes.size-32,bytes.size)))
            return DataInputStream(ByteArrayInputStream(body)).use { input ->
                require(input.readInt()==0x49575031) { "Unknown page copy version" }
                fun field(limit: Int): String {
                    val length=input.readInt();require(length in 0..limit && length<=input.available())
                    val data=ByteArray(length);input.readFully(data)
                    val text=data.toString(Charsets.UTF_8)
                    require(text.toByteArray(Charsets.UTF_8).contentEquals(data))
                    return text
                }
                val title=field(480);val text=field(400_000);val count=input.readInt()
                require(count in 0..InkLimits.MAX_STROKES)
                var points=0
                val strokes=List(count) {
                    val length=input.readInt();require(length in 1..InkLimits.MAX_STROKE_BYTES && length<=input.available())
                    val data=ByteArray(length);input.readFully(data)
                    InkStrokeCodec.decode(data).also { points+=it.samples.size;require(points<=InkLimits.MAX_PAGE_POINTS) }
                }
                require(input.available()==0)
                InkPageFile(title,text,strokes)
            }
        }
    }
}
