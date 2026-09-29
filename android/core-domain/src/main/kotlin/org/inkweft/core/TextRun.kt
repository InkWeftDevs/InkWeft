// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.util.UUID

/** Platform-shaped fragment. Source membership is fragment-level, never a claimed character box. */
enum class TextLayoutPolicy { IN_PLACE, PARAGRAPH }
data class TextRun(
    val id:String, val lineId:String, val start:Int, val end:Int,
    val x:Float, val baseline:Float, val size:Float,
    val sourceIds:List<String>, val sourceRevision:Long=-1,
    val policy:TextLayoutPolicy=TextLayoutPolicy.IN_PLACE,
    val model:String="ppocrv5-mobile-da72dc72ca4d-ctc-peak", val score:Float=-1f
) {
    init {
        UUID.fromString(id);UUID.fromString(lineId)
        require(start>=0&&end>start&&sourceRevision>=-1)
        require(listOf(x,baseline,size,score).all{it.isFinite()}&&x>=0&&baseline>=0&&size in 1f..960f)
        require(sourceIds.size<=256&&sourceIds.distinct().size==sourceIds.size);sourceIds.forEach{UUID.fromString(it)}
        require(model.length<=80&&score in -1f..1f)
    }
    fun transformed(dx:Float=0f,dy:Float=0f,scale:Float=1f,offset:Int=0)=copy(
        start=start+offset,end=end+offset,x=x*scale+dx,baseline=baseline*scale+dy,size=size*scale)
}
