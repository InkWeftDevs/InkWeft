// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Successful repository read phases; freeze includes transaction scheduling/queries,
 * decode includes validation. Neither measures a displayed frame or identifies a page. */
enum class ReadKind { INK, AUTHORING }
data class ReadTiming(val kind:ReadKind,val freezeNanos:Long,val decodeNanos:Long,val entries:Int,
    val payloadBytes:Long=0,val decodedPoints:Long=0,val candidateRows:Long=0){
    init{require(freezeNanos>=0&&decodeNanos>=0&&entries>=0&&payloadBytes>=0&&decodedPoints>=0&&candidateRows>=0)}
    val freezeMicros get()=freezeNanos/1000
    val decodeMicros get()=decodeNanos/1000
}
