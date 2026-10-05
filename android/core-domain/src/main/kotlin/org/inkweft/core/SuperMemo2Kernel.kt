// SPDX-License-Identifier: MIT
// Copyright 2020 Alan Kan
// Kotlin numeric adaptation 2026 InkWeft contributors.
package org.inkweft.core

/** Minimal port of alankan886/SuperMemo2 v3.0.1 sm_two.review numeric kernel.
 * Upstream commit 0aaf428cf362b976f49a2dece5e01211785caec2; license in third_party/supermemo2/LICENSE.
 * Kept: failure/reset branches, old-EF ceil interval, then EF update and 1.3 floor.
 * Adapted: exact hundredths instead of Python binary floats; caller owns epoch, policy and capacity.
 * Does not port upstream's implicit datetime.utcnow/string parsing or packaging. */
internal object SuperMemo2Kernel {
    data class Result(val easeHundredths:Int,val intervalDays:Long,val repetitions:Int)
    fun review(quality:Int,easiness:Int,previousInterval:Int,previousRepetitions:Int):Result {
        var interval:Long
        var repetitions=previousRepetitions
        if(quality<3){
            interval=1
            repetitions=0
        }else{
            interval=when(repetitions){
                0->1
                1->6
                else->(previousInterval.toLong()*easiness+99)/100
            }
            repetitions=Math.addExact(repetitions,1)
        }
        val gap=5-quality
        var nextEasiness=Math.addExact(easiness,10-gap*(8+gap*2))
        if(nextEasiness<130)nextEasiness=130
        return Result(nextEasiness,interval,repetitions)
    }
}
