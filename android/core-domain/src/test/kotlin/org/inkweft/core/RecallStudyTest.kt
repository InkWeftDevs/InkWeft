// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecallStudyTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun vectorsDerivedFromOfficialCeilFormulaAreDeterministic(){
        var state=Sm2State();val intervals=mutableListOf<Int>()
        repeat(5){state=Sm2Schedule.grade(state,4,it*1000L).state;intervals+=state.intervalDays;assertEquals(250,state.easeHundredths)}
        assertEquals(listOf(1,6,15,38,95),intervals)
        state=Sm2State();val perfect=mutableListOf<Int>()
        repeat(4){state=Sm2Schedule.grade(state,5,0).state;perfect+=state.intervalDays}
        assertEquals(listOf(1,6,17,48),perfect);assertEquals(290,state.easeHundredths)
    }
    @Test fun failureFloorAssistanceAndUtcDueAreExplicit(){
        val at=1_792_300_123_456L;val first=Sm2Schedule.grade(Sm2State(),0,at)
        assertEquals(0,first.state.repetitions);assertEquals(170,first.state.easeHundredths);assertEquals(at+86_400_000,first.state.dueAt)
        assertEquals(130,Sm2Schedule.grade(first.state,0,at).state.easeHundredths)
        val hinted=Sm2Schedule.grade(Sm2State(),5,at,true)
        assertEquals(5,hinted.requestedQuality);assertEquals(2,hinted.effectiveQuality);assertEquals(218,hinted.state.easeHundredths)
        assertThrows(IllegalArgumentException::class.java){Sm2Schedule.grade(Sm2State(),6,at)}
        assertThrows(ArithmeticException::class.java){Sm2Schedule.grade(Sm2State(),5,Long.MAX_VALUE)}
    }
    @Test fun frozenAlgorithmHasDocumentedUpperIntervalSafetyBoundary(){
        val state=Sm2State(4,36_500,300,0)
        val result=Sm2Schedule.grade(state,4,0)
        assertEquals(36_500,result.state.intervalDays);assertEquals(Sm2Schedule.VERSION,result.state.algorithm)
        assertThrows(IllegalArgumentException::class.java){Sm2State(algorithm="unknown")}
    }
    @Test fun localDateFilterUsesZoneBoundariesWithoutChangingUtcSchedule(){
        val spring=RecallHistoryFilter.localDays("2026-03-08","2026-03-08","America/New_York")
        assertEquals(23*60*60*1000L,spring.second!!-spring.first!!)
        val autumn=RecallHistoryFilter.localDays("2026-11-01","2026-11-01","America/New_York")
        assertEquals(25*60*60*1000L,autumn.second!!-autumn.first!!)
        val filter=RecallHistoryFilter(RecallDueFilter.DUE,RecallQuestionKind.TEXT_CLOZE,true,spring.first,spring.second)
        assertTrue(filter.matches(RecallQuestionKind.TEXT_CLOZE,RecallHint.TEXT.bit,spring.first!!,0,1))
        assertFalse(filter.matches(RecallQuestionKind.TEXT_CLOZE,0,spring.first!!,0,1))
        assertFalse(filter.matches(RecallQuestionKind.TEXT_CLOZE,4,spring.second!!,0,1))
    }
    @Test fun clozeProjectionContainsOnlyAllowedTextAndQuestionCannotUseIt(){
        val body="前文 SECRET 后文";val spec=RecallQuestionSpec(id(),1,id(),1,"填空",RecallQuestionKind.TEXT_CLOZE,clozes=listOf(RecallCloze(3,9)))
        assertFalse(spec.maskedText(body).contains("SECRET"));assertEquals(body,spec.maskedText(body,setOf(0)))
        assertEquals(spec,RecallCodec.spec(RecallCodec.spec(spec)))
        assertThrows(IllegalArgumentException::class.java){RecallQuestionSpec(id(),1,id(),1,"问答").maskedText(body)}
        assertThrows(IllegalArgumentException::class.java){spec.copy(clozes=listOf(RecallCloze(0,1))).validateBody("😀乙")}
    }
    @Test fun sourceMasksRequireFrozenCompleteReferencesAndBoundedRegions(){
        val ref=StudySourceVersionRef(id(),3)
        val region=RecallRegion(ref,.1,.2,.7,.8)
        val spec=RecallQuestionSpec(id(),2,id(),4,"原迹回答",RecallQuestionKind.SOURCE_MASK,listOf(ref),true,regions=listOf(region))
        assertEquals(spec,RecallCodec.spec(RecallCodec.spec(spec)))
        assertThrows(IllegalArgumentException::class.java){spec.copy(sourcesComplete=false)}
        assertThrows(IllegalArgumentException::class.java){spec.copy(sourceRefs=emptyList())}
        assertThrows(IllegalArgumentException::class.java){RecallRegion(ref,0.0,0.0,2.0,1.0)}
    }
    @Test fun scheduleSnapshotsAndAnswerBudgetsRoundTripWithoutExternalContent(){
        val state=Sm2Schedule.grade(Sm2State(),4,100).state;val attempt=id()
        val encoded=RecallCodec.schedule(state,2,attempt,1);val decoded=RecallCodec.schedule(encoded)
        assertEquals(state,decoded.state);assertEquals(2L,decoded.revision);assertEquals(attempt,decoded.lastAttemptId)
        RecallCodec.validateAnswer("文字作答",InkPageFile("作答","",emptyList(),false,PaperStyle.BLANK).encode())
        assertThrows(IllegalArgumentException::class.java){RecallCodec.validateAnswer("a".repeat(8001),byteArrayOf())}
        assertThrows(IllegalArgumentException::class.java){RecallCodec.validateAnswer("",ByteArray(512001))}
        assertEquals(setOf(0,2),RecallCodec.revealed("0,2"));assertThrows(IllegalArgumentException::class.java){RecallCodec.revealed("2,0")}
    }
    @Test fun branchQueueUsesAuthorCardOrderAndInputQuestionOrder(){
        val a=id();val b=id();val qa1=BranchReviewEntryRef(id(),1,a,1);val qa2=BranchReviewEntryRef(id(),1,a,1);val qb=BranchReviewEntryRef(id(),1,b,1)
        val plan=BranchReview.notebook(id(),linkedMapOf(b to 1L,a to 1L),listOf(qa2,qb,qa1))
        assertEquals(listOf(qb,qa2,qa1),plan.entries)
    }
    @Test fun presentationVersionRoundTripsAndLegacyAnswerNeverReadsCurrentAnnotation(){
        val question=id();val card=id();val presentation=RecallPresentationRef(id(),7)
        val fixed=RecallQuestionSpec(question,2,card,3,"固定问法",presentation=presentation)
        assertEquals(fixed,RecallCodec.spec(RecallCodec.spec(fixed)))
        val old=java.io.ByteArrayOutputStream().also{out->java.io.DataOutputStream(out).use{d->
            d.writeInt(0x49575231);d.writeUTF(question);d.writeLong(2);d.writeUTF(card);d.writeLong(3)
            d.writeUTF("固定问法");d.writeUTF(RecallQuestionKind.QUESTION.name);d.writeUTF(StudySourceRefs.encode(emptyList()));d.writeBoolean(true)
            d.writeInt(0);d.writeInt(0)
        }}.toByteArray()
        val legacy=RecallCodec.spec(old)
        assertFalse(legacy.presentationKnown);assertNull(legacy.presentation)
        assertEquals(legacy,RecallCodec.spec(RecallCodec.spec(legacy)))
        assertThrows(IllegalArgumentException::class.java){fixed.copy(presentationKnown=false)}
    }

}
