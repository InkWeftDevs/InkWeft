// SPDX-License-Identifier: MIT
// Copyright 2020 Alan Kan. Upstream test vectors adapted to exact hundredths by InkWeft contributors.
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test

/** From tests/test_sm_two.py at SuperMemo2 v3.0.1 / 0aaf428cf362b976f49a2dece5e01211785caec2.
 * These are upstream-library vectors, not tests published by the SM-2 algorithm author. */
class SuperMemo2PortTest {
    @Test fun upstreamFirstReviewSixGrades(){
        val eases=listOf(170,196,218,236,250,260)
        for(grade in 0..5){val result=Sm2Schedule.grade(Sm2State(),grade,1_704_067_200_000)
            assertEquals(eases[grade],result.state.easeHundredths);assertEquals(1,result.state.intervalDays)
            assertEquals(if(grade<3)0 else 1,result.state.repetitions);assertEquals(1_704_153_600_000,result.state.dueAt)
        }
    }
    @Test fun upstreamRepeatSixGradesAndCeil(){
        val eases=listOf(150,176,198,216,230,240)
        for(grade in 0..5){val result=Sm2Schedule.grade(Sm2State(3,12,230),grade,0)
            assertEquals(eases[grade],result.state.easeHundredths);assertEquals(if(grade<3)1 else 28,result.state.intervalDays)
            assertEquals(if(grade<3)0 else 4,result.state.repetitions)
        }
    }
    @Test fun upstreamFloorAndSecondReview(){
        assertEquals(Sm2State(0,1,130,86_400_000),Sm2Schedule.grade(Sm2State(3,12,130),0,0).state)
        assertEquals(Sm2State(2,6,250,518_400_000),Sm2Schedule.grade(Sm2State(1,1,250),4,0).state)
    }
    @Test fun exactHundredthsDeliberatelyDoNotCompoundBinaryFloatCeilDrift(){
        // Decimal 10*1.4 is exactly14. Repeated Python EF additions can become 1.4000000000000001.
        assertEquals(14,Sm2Schedule.grade(Sm2State(3,10,140),4,0).state.intervalDays)
    }
}
