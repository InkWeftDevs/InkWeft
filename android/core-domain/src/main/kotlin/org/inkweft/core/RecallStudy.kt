// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class RecallQuestionKind(val label:String){QUESTION("问答"),TEXT_CLOZE("文字挖空"),SOURCE_MASK("原迹区域遮挡")}
enum class RecallMode(val label:String){DUE("正式到期复习"),PRACTICE("临时练习")}
enum class RecallDueFilter(val label:String){ALL("全部"),DUE("已到期 / 首次"),NOT_DUE("未到期")}
enum class RecallHint(val bit:Int,val label:String){HINT(1,"查看提示"),ORIGINAL(2,"查看原文"),TEXT(4,"揭开文字空位"),REGION(8,"揭开原迹区域")}
enum class RecallAttemptStatus{QUEUED,OPEN,GRADED,SKIPPED,ABANDONED}
object RecallLimits {
    const val MAX_SPEC_BYTES=32_768
    const val MAX_ANSWER_CHARS=8_000
    const val MAX_ANSWER_INK_BYTES=512_000
    const val MAX_ANSWER_STROKES=256
    const val MAX_ANSWER_POINTS=16_000
    const val MAX_INK_LIBRARY_BYTES=64_000_000L
    const val MAX_ATTEMPTS_PER_BOOK=20_000
    const val MAX_SESSIONS_PER_BOOK=2_000
    const val MAX_COMMANDS_PER_BOOK=200_000
}
data class RecallCloze(val start:Int,val end:Int){init{require(start>=0&&end>start&&end<=20_000)}}
data class RecallRegion(val source:StudySourceVersionRef,val left:Double,val top:Double,val right:Double,val bottom:Double){
    init{require(listOf(left,top,right,bottom).all{it.isFinite()&&it in 0.0..1.0});require(left<right&&top<bottom)}
}
data class RecallPresentationRef(val id:String,val revision:Long){init{UUID.fromString(id);require(revision>0)}}
/** Separate semantic versioning; manual question state revisions never become scheduling history. */
data class RecallQuestionSpec(val questionId:String,val knowledgeRevision:Long,val cardId:String,val cardRevision:Long,
    val prompt:String,val kind:RecallQuestionKind=RecallQuestionKind.QUESTION,
    val sourceRefs:List<StudySourceVersionRef> = emptyList(),val sourcesComplete:Boolean=true,
    val clozes:List<RecallCloze> = emptyList(),val regions:List<RecallRegion> = emptyList(),
    val presentation:RecallPresentationRef?=null,val presentationKnown:Boolean=true) {
    init{
        listOf(questionId,cardId).forEach{UUID.fromString(it)};require(knowledgeRevision>0&&cardRevision>0)
        require(prompt.isNotBlank()&&prompt.length<=2000);StudySourceRefs.encode(sourceRefs)
        require(presentationKnown||presentation==null)
        require(clozes.size<=32&&regions.size<=32)
        require(clozes.zipWithNext().all{(a,b)->a.end<=b.start})
        when(kind){
            RecallQuestionKind.QUESTION->require(clozes.isEmpty()&&regions.isEmpty())
            RecallQuestionKind.TEXT_CLOZE->require(clozes.isNotEmpty()&&regions.isEmpty())
            RecallQuestionKind.SOURCE_MASK->require(clozes.isEmpty()&&regions.isNotEmpty()&&sourcesComplete&&regions.all{it.source in sourceRefs})
        }
    }
    fun validateBody(body:String){
        require(body.length<=20_000)
        clozes.forEach{range->
            require(range.end<=body.length)
            listOf(range.start,range.end).forEach{i->require(i==0||i==body.length||!(body[i-1].isHighSurrogate()&&body[i].isLowSurrogate()))}
        }
    }
    fun maskedText(body:String,revealed:Set<Int> = emptySet()):String {
        validateBody(body);require(revealed.all{it in clozes.indices})
        require(kind==RecallQuestionKind.TEXT_CLOZE){"RECALL_NOT_A_CLOZE_STIMULUS"}
        val result=StringBuilder();var offset=0
        clozes.forEachIndexed{i,range->result.append(body,offset,range.start);result.append(if(i in revealed)body.substring(range.start,range.end)else"［空位 ${i+1}］");offset=range.end}
        result.append(body,offset,body.length);return result.toString()
    }
}
data class RecallHistoryFilter(val due:RecallDueFilter=RecallDueFilter.ALL,val kind:RecallQuestionKind?=null,val usedHint:Boolean?=null,
    val fromInclusive:Long?=null,val untilExclusive:Long?=null){
    init{require(fromInclusive==null||fromInclusive>=0);require(untilExclusive==null||untilExclusive>=0);require(fromInclusive==null||untilExclusive==null||fromInclusive<untilExclusive)}
    fun matches(type:RecallQuestionKind,hintMask:Int,at:Long,dueAt:Long,now:Long):Boolean =
        (kind==null||kind==type)&&(usedHint==null||usedHint==(hintMask!=0))&&
        (fromInclusive==null||at>=fromInclusive)&&(untilExclusive==null||at<untilExclusive)&&when(due){
            RecallDueFilter.ALL->true;RecallDueFilter.DUE->dueAt<=now;RecallDueFilter.NOT_DUE->dueAt>now
        }
    companion object {
        /** Local dates affect filtering only; schedules stay absolute UTC instants across timezone changes. */
        fun localDays(from:String?,through:String?,zoneId:String):Pair<Long?,Long?> {
            val zone=ZoneId.of(zoneId)
            return from?.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli()} to
                through?.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()}
        }
    }
}
object RecallCodec {
    fun spec(value:RecallQuestionSpec):ByteArray=ByteArrayOutputStream().also{out->DataOutputStream(out).use{d->
        d.writeInt(0x49575232);d.writeUTF(value.questionId);d.writeLong(value.knowledgeRevision);d.writeUTF(value.cardId);d.writeLong(value.cardRevision)
        d.writeUTF(value.prompt);d.writeUTF(value.kind.name);d.writeUTF(StudySourceRefs.encode(value.sourceRefs));d.writeBoolean(value.sourcesComplete)
        d.writeInt(value.clozes.size);value.clozes.forEach{d.writeInt(it.start);d.writeInt(it.end)}
        d.writeInt(value.regions.size);value.regions.forEach{r->d.writeUTF(r.source.sourceId);d.writeLong(r.source.revision);d.writeDouble(r.left);d.writeDouble(r.top);d.writeDouble(r.right);d.writeDouble(r.bottom)}
        d.writeBoolean(value.presentationKnown);d.writeBoolean(value.presentation!=null);value.presentation?.let{d.writeUTF(it.id);d.writeLong(it.revision)}
    }}.toByteArray().also{require(it.size<=RecallLimits.MAX_SPEC_BYTES)}
    fun spec(bytes:ByteArray):RecallQuestionSpec {
        require(bytes.size<=RecallLimits.MAX_SPEC_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            val version=d.readInt();require(version in setOf(0x49575231,0x49575232));val question=d.readUTF();val qrev=d.readLong();val card=d.readUTF();val crev=d.readLong()
            val prompt=d.readUTF();val kind=RecallQuestionKind.valueOf(d.readUTF());val refs=StudySourceRefs.decode(d.readUTF());val complete=d.readBoolean()
            fun count()=d.readInt().also{require(it in 0..32)}
            val clozes=List(count()){RecallCloze(d.readInt(),d.readInt())}
            val regions=List(count()){RecallRegion(StudySourceVersionRef(d.readUTF(),d.readLong()),d.readDouble(),d.readDouble(),d.readDouble(),d.readDouble())}
            val known=version==0x49575232&&d.readBoolean();val presentation=if(version==0x49575232&&d.readBoolean())RecallPresentationRef(d.readUTF(),d.readLong())else null
            require(d.read()==-1);RecallQuestionSpec(question,qrev,card,crev,prompt,kind,refs,complete,clozes,regions,presentation,known)
        }
    }
    fun schedule(value:Sm2State,revision:Long,lastAttemptId:String?,specRevision:Long):ByteArray=ByteArrayOutputStream().also{out->DataOutputStream(out).use{d->
        require(revision>0&&specRevision>0);lastAttemptId?.let{UUID.fromString(it)}
        d.writeUTF(value.algorithm);d.writeInt(value.repetitions);d.writeInt(value.intervalDays);d.writeInt(value.easeHundredths);d.writeLong(value.dueAt)
        d.writeLong(revision);d.writeUTF(lastAttemptId.orEmpty());d.writeLong(specRevision)
    }}.toByteArray()
    data class ScheduleSnapshot(val state:Sm2State,val revision:Long,val lastAttemptId:String?,val specRevision:Long)
    fun schedule(bytes:ByteArray):ScheduleSnapshot=DataInputStream(ByteArrayInputStream(bytes)).use{d->
        require(bytes.size<=256);val algorithm=d.readUTF();val state=Sm2State(d.readInt(),d.readInt(),d.readInt(),d.readLong(),algorithm)
        val value=ScheduleSnapshot(state,d.readLong(),d.readUTF().ifEmpty{null},d.readLong());require(d.read()==-1&&value.revision>0&&value.specRevision>0);value.lastAttemptId?.let{UUID.fromString(it)};value
    }
    fun validateAnswer(text:String,ink:ByteArray){
        require(text.length<=RecallLimits.MAX_ANSWER_CHARS){"RECALL_ANSWER_TEXT_BUDGET"}
        require(ink.size<=RecallLimits.MAX_ANSWER_INK_BYTES){"RECALL_ANSWER_INK_BUDGET"}
        if(ink.isNotEmpty()){
            val file=InkPageFile.decode(ink)
            require(file.objects.isEmpty()&&file.imageSources.isEmpty()&&file.source==null&&file.authoring==null&&!file.world){"RECALL_ANSWER_INK_ONLY"}
            require(file.strokes.size<=RecallLimits.MAX_ANSWER_STROKES&&file.strokes.sumOf{it.samples.size}<=RecallLimits.MAX_ANSWER_POINTS){"RECALL_ANSWER_INK_BUDGET"}
        }
    }
    fun revealed(value:Set<Int>)=value.sorted().joinToString(",").also{require(value.size<=32&&value.all{it in 0..31})}
    fun revealed(value:String)=if(value.isEmpty())emptySet()else value.split(',').map{it.toInt()}.toSet().also{require(revealed(it)==value)}
}
