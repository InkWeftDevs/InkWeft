// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import kotlinx.coroutines.flow.first
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Deliberate synthetic UTC times; never changes the device clock or claims elapsed human study. */
internal suspend fun SixBatchFixture.seedRecall(){
    step("typed-recall-two-synthetic-days-and-practice-without-schedule-change"){
        val repo=app.study.recall();val book=books[0];val cardIds=manifest.getJSONArray("cardsA").strings().take(12)
        val cards=app.study.cards(book).first().associateBy{it.id}
        val questions=cardIds.indices.map{id("question-$book-$it")}
        fun success(outcome:RecallOutcome){check(outcome is RecallOutcome.Success){"Synthetic recall command did not commit: $outcome"}}
        for((index,question) in questions.withIndex()){
            val card=cards.getValue(cardIds[index]);val kind=when(index){0->RecallQuestionKind.SOURCE_MASK;1->RecallQuestionKind.TEXT_CLOZE;else->RecallQuestionKind.QUESTION}
            val refs=app.study.sources(card.id,card.revision).refs
            success(repo.configure(id("recall-spec-$index"),book,question,1,0,0,card.id,card.revision,
                "请在不看材料时说明第${index+1}个概念的条件与反例",kind,
                clozes=if(kind==RecallQuestionKind.TEXT_CLOZE)listOf("紫杉木","青铜器").map{answer->val start=card.body.indexOf(answer);check(start>=0&&card.body.lastIndexOf(answer)==start);RecallCloze(start,start+answer.length)}else emptyList(),
                regions=if(kind==RecallQuestionKind.SOURCE_MASK)listOf(RecallRegion(refs.single(),0.0,0.0,1.0,1.0))else emptyList()))
        }
        val start=Instant.parse(manifest.getJSONObject("spec").getJSONObject("requiredRecall").getString("syntheticStartUtc")).toEpochMilli()
        val sessions=mutableListOf<String>()
        repeat(2){day->
            val session=id("recall-day-$day");sessions+=session
            val at=start+day*(Sm2Schedule.DAY_MS+60_000)
            val plan=app.branchReview.prepareNotebook(book);check(plan.entries.size==12)
            success(repo.start(id("recall-start-$day"),session,plan,RecallMode.DUE,at))
            var answered=0
            while(true){
                val loaded=repo.loadSession(session).current?:break
                var row=loaded.row;val index=questions.indexOf(row.questionId);check(index>=0)
                val time=at+(answered+1)*2000L
                val ink=if(index!=0)byteArrayOf()else InkPageFile("合成回忆作答","",List(3){stroke->
                    InkStroke(id("answer-$day-$stroke"),InkPen.PEN,0xff203d60.toInt(),3f,InkTool.STYLUS,
                        List(18){j->InkSample(100f+j*12f,200f+stroke*55f+(j%3)*5f,j*10L,.6f,.2f,.3f)})
                },paper=PaperStyle.BLANK).encode()
                val op=id("recall-answer-$day-$index");val text="第${day+1}天第${index+1}题合成作答：先限定条件和样本空间，再核对交集、分母及反例。"
                success(repo.saveAnswer(op,book,row.id,row.revision,text,ink))
                success(repo.saveAnswer(op,book,row.id,row.revision,text,ink)) // Exact receipt replay, not a second answer.
                row=repo.loadSession(session).current!!.row
                if(day==0&&index<=2){
                    val hint=when(loaded.spec.kind){RecallQuestionKind.SOURCE_MASK->RecallHint.REGION;RecallQuestionKind.TEXT_CLOZE->RecallHint.TEXT;else->RecallHint.HINT}
                    success(repo.hint(id("recall-hint-$index"),book,row.id,row.revision,hint,if(hint==RecallHint.HINT)null else 0,time-800))
                    row=repo.loadSession(session).current!!.row
                    if(index==0){success(repo.consultOriginal(id("recall-original"),book,row.id,row.revision,time-600));row=repo.loadSession(session).current!!.row}
                }
                success(repo.revealAnswer(id("recall-reveal-$day-$index"),book,row.id,row.revision,time-400))
                row=repo.loadSession(session).current!!.row
                if(day==1&&index==0){success(repo.consultOriginal(id("recall-compare"),book,row.id,row.revision,time-200));row=repo.loadSession(session).current!!.row;check(row.hintMask==0)}
                success(repo.grade(id("recall-grade-$day-$index"),book,row.id,row.revision,4,time))
                val graded=repo.loadAttempt(row.id).row
                check(graded.effectiveQuality==if(day==0&&index<=2)2 else 4)
                answered++
            }
            check(answered==12&&repo.loadSession(session).row.closed)
        }
        val before=questions.map{checkNotNull(repo.schedule(it))}
        val practice=id("recall-practice");sessions+=practice
        val card=cards.getValue(cardIds[0]);val at=start+2*Sm2Schedule.DAY_MS
        success(repo.start(id("practice-start"),practice,app.branchReview.prepareCard(MapRef(book),card.id,card.revision),RecallMode.PRACTICE,at))
        var row=repo.loadSession(practice).current!!.row
        success(repo.saveAnswer(id("practice-answer"),book,row.id,row.revision,"合成临时练习：不改正式到期计划。",byteArrayOf()))
        row=repo.loadSession(practice).current!!.row
        success(repo.revealAnswer(id("practice-reveal"),book,row.id,row.revision,at+1000));row=repo.loadSession(practice).current!!.row
        success(repo.grade(id("practice-grade"),book,row.id,row.revision,5,at+2000))
        success(repo.withdraw(id("practice-withdraw"),book,row.id,checkNotNull(repo.schedule(row.questionId)).revision,"合成样例：撤销一次练习评分",at+3000))
        check(before==questions.map{checkNotNull(repo.schedule(it))})
        manifest.put("recall",JSONObject().put("clock","SYNTHETIC_FIXED_UTC_NOT_DEVICE_TIME").put("startUtc",Instant.ofEpochMilli(start).toString())
            .put("questionIds",JSONArray(questions)).put("sessions",JSONArray(sessions)).put("practiceAttempt",row.id)
            .put("formalAttempts",24).put("practiceAttempts",1).put("withdrawnPracticeScores",1)
            .put("scheduleHashes",JSONArray(before.map{ContentTransfer.hash(it.snapshot())})))
    }
}

internal suspend fun SixBatchFixture.verifyRecall():JSONObject{
    val expected=manifest.getJSONObject("recall");val repo=app.study.recall();val allHistory=repo.history(books[0])
    val baselineSessions=expected.getJSONArray("sessions").strings().toSet()
    val history=allHistory.filter{it.sessionId in baselineSessions}
    val nativeSessions=manifest.optJSONArray("nativeRecallSessions")?.strings().orEmpty()
    check(nativeSessions.size==nativeSessions.distinct().size&&nativeSessions.none{it in baselineSessions})
    check(allHistory.size==history.size+nativeSessions.size&&allHistory.all{it.sessionId in baselineSessions||it.sessionId in nativeSessions})
    if(nativeSessions.isNotEmpty())verifyNativeRecall()
    check(history.size==25&&history.count{it.mode==RecallMode.DUE.name}==24&&history.count{it.mode==RecallMode.PRACTICE.name}==1)
    val formal=history.filter{it.mode==RecallMode.DUE.name};check(formal.all{it.status==RecallAttemptStatus.GRADED.name})
    check(formal.map{checkNotNull(it.completedAt)/Sm2Schedule.DAY_MS}.distinct().size==2)
    check(formal.count{it.hintMask!=0&&it.effectiveQuality==2}==3)
    var inkAnswers=0;val kinds=mutableSetOf<RecallQuestionKind>()
    for(entry in history){val loaded=repo.loadAttempt(entry.id);check(loaded.row.answerText.isNotBlank());kinds+=loaded.spec.kind
        if(loaded.row.answerInk.isNotEmpty()){check(InkPageFile.decode(loaded.row.answerInk).strokes.size==3);inkAnswers++}}
    check(inkAnswers==2&&kinds==RecallQuestionKind.entries.toSet())
    val practice=repo.loadAttempt(expected.getString("practiceAttempt"));check(practice.correction!=null&&practice.row.scheduleAfter==null)
    val questions=expected.getJSONArray("questionIds").strings()
    check(questions.map{ContentTransfer.hash(checkNotNull(repo.schedule(it)).snapshot())}==expected.getJSONArray("scheduleHashes").strings())
    for(session in expected.getJSONArray("sessions").strings())check(repo.loadSession(session).row.closed)
    return JSONObject().put("formalAttempts",24).put("practiceAttempts",1).put("formalUtcDays",2).put("typedQuestionKinds",3)
        .put("textAnswers",25).put("inkAnswers",inkAnswers).put("assistedFormalGradesCappedAtTwo",3).put("withdrawnPracticeScores",1)
        .put("nativeUiPracticeAttempts",nativeSessions.size)
}

/** A byte-accurate canonical digest of the original recall rows, excluding only the three declared UI sessions. */
internal suspend fun SixBatchFixture.baselineRecallDigest():String{
    val sessions=manifest.getJSONObject("recall").getJSONArray("sessions").strings()
    val db=NoteDatabase.open(app)
    try{
        val marks=sessions.joinToString(","){"?"}
        val attemptSelect="SELECT id FROM recall_attempts WHERE sessionId IN ($marks)"
        val out=java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).use{data->for(table in RecallStorage.SCHEMA){
            val condition=when(table.name){
                "recall_sessions"->"id IN ($marks)"
                "recall_attempts"->"sessionId IN ($marks)"
                "recall_hint_events","recall_corrections"->"attemptId IN ($attemptSelect)"
                "recall_receipts"->"kind='CONFIGURE' OR resultId IN ($marks) OR resultId IN ($attemptSelect)"
                else->"notebookId=?"
            }
            val args=when(table.name){"recall_receipts"->(sessions+sessions).toTypedArray();"recall_questions","recall_question_revisions","recall_schedules"->arrayOf(books[0]);else->sessions.toTypedArray()}
            data.writeUTF(table.name)
            db.openHelper.readableDatabase.query("SELECT "+table.columns.joinToString(","){"`${it.name}`"}+" FROM `${table.name}` WHERE "+condition+" ORDER BY "+table.keys.joinToString(","){"`$it`"},args).use{cursor->
                data.writeInt(cursor.count)
                while(cursor.moveToNext())table.columns.forEachIndexed{i,column->
                    data.writeBoolean(cursor.isNull(i))
                    if(!cursor.isNull(i))when(column.kind){
                        'I'->data.writeLong(cursor.getLong(i));'F'->data.writeDouble(cursor.getDouble(i))
                        else->{val bytes=if(column.kind=='B')cursor.getBlob(i)else cursor.getString(i).toByteArray(Charsets.UTF_8);data.writeInt(bytes.size);data.write(bytes)}
                    }
                }
            }
        }}
        return ContentTransfer.hash(out.toByteArray())
    }finally{db.close()}
}

internal suspend fun SixBatchFixture.verifyNativeRecall(){
    val sessions=manifest.getJSONArray("nativeRecallSessions").strings();val attempts=manifest.getJSONArray("nativeRecallAttempts").strings()
    check(sessions.size==3&&attempts.size==3&&attempts.distinct().size==3)
    check(baselineRecallDigest()==manifest.getString("nativeRecallBaselineSha256")){"The original 25 recall records or schedules changed"}
    val repo=app.study.recall();val questions=manifest.getJSONObject("recall").getJSONArray("questionIds").strings()
    val expectedKinds=listOf(RecallQuestionKind.SOURCE_MASK,RecallQuestionKind.TEXT_CLOZE,RecallQuestionKind.QUESTION)
    for(index in 0..2){
        val session=repo.loadSession(sessions[index]);check(session.row.closed&&session.attempts.map{it.id}==listOf(attempts[index]))
        val current=repo.loadAttempt(attempts[index]);val row=current.row
        check(row.questionId==questions[index]&&current.spec.kind==expectedKinds[index]&&row.mode==RecallMode.PRACTICE.name)
        check(row.status==RecallAttemptStatus.GRADED.name&&row.requestedQuality==4&&row.effectiveQuality==null&&row.scheduleAfter==null)
        check(row.answerText=="同一样例 · 第${index+1}种题型的离线作答")
        check(row.hintMask==when(index){0->RecallHint.REGION.bit or RecallHint.ORIGINAL.bit;1->RecallHint.TEXT.bit;else->0})
        check(if(index==0)InkPageFile.decode(row.answerInk).strokes.isNotEmpty()else row.answerInk.isEmpty())
        check(current.correction==null)
    }
    check(repo.history(books[0]).size==28)
}
