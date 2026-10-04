// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

/** Policy wrapper around the MIT SuperMemo2 v3.0.1 Kotlin kernel port. Algorithm: © Piotr Wozniak / SuperMemo World.
 * https://www.super-memory.com/english/ol/sm2.htm
 * Attribution/use policy: https://www.supermemo.com/en/blog/licensing-and-copyrighting-of-supermemo-algorithms
 * This version follows the written ceil rule, not the Delphi example's round rule. */
object Sm2Schedule {
    const val VERSION="SM2-IW1"
    const val DAY_MS=86_400_000L
    const val MAX_INTERVAL_DAYS=36_500
    /** q=0..5; an assisted formal answer is explicitly capped at 2. */
    fun grade(previous:Sm2State,quality:Int,completedAt:Long,assisted:Boolean=false):Sm2Result {
        require(quality in 0..5&&completedAt>=0)
        require(previous.algorithm==VERSION)
        val effective=if(assisted)minOf(quality,2)else quality
        val kernel=SuperMemo2Kernel.review(effective,previous.easeHundredths,previous.intervalDays,previous.repetitions)
        val interval=kernel.intervalDays.coerceAtMost(MAX_INTERVAL_DAYS.toLong()).toInt()
        val next=Sm2State(kernel.repetitions,interval,kernel.easeHundredths,
            Math.addExact(completedAt,Math.multiplyExact(interval.toLong(),DAY_MS)))
        return Sm2Result(quality,effective,next)
    }
}
data class Sm2State(val repetitions:Int=0,val intervalDays:Int=0,val easeHundredths:Int=250,val dueAt:Long=0,val algorithm:String=Sm2Schedule.VERSION) {
    init{require(repetitions>=0&&intervalDays in 0..Sm2Schedule.MAX_INTERVAL_DAYS&&easeHundredths>=130&&dueAt>=0);require(algorithm==Sm2Schedule.VERSION)}
}
data class Sm2Result(val requestedQuality:Int,val effectiveQuality:Int,val state:Sm2State)
