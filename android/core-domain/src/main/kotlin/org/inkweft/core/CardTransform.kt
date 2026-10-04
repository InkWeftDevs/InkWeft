// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.core

import java.io.*
import java.util.UUID

/** Source identity is independent of cards. A reference always names immutable bytes. */
data class StudySourceVersionRef(val sourceId:String,val revision:Long) {
    init { UUID.fromString(sourceId);require(revision in 1 until Long.MAX_VALUE) }
}
object StudySourceRefs {
    fun encode(refs:List<StudySourceVersionRef>):String {
        require(refs.size<=200&&refs.distinct().size==refs.size)
        return refs.joinToString(","){"${it.sourceId}@${it.revision}"}
    }
    fun decode(value:String):List<StudySourceVersionRef> = if(value.isEmpty())emptyList()else value.split(',').map {
        val parts=it.split('@');require(parts.size==2);StudySourceVersionRef(parts[0],parts[1].toLong())
    }.also { require(encode(it)==value) }
}
enum class CardTransformKind(val label:String) { MERGE("合并"), SPLIT("拆分"), SUMMARY("总结") }
data class CardTransformCard(val id:String,val revision:Long,val title:String,val body:String,
    val presentation:KnowledgeData.CardPresentation,val sources:List<StudySourceVersionRef>,val sourcesComplete:Boolean=true)
data class CardTransformTarget(val id:String,val title:String,val body:String,val annotation:String,
    val sources:List<StudySourceVersionRef>,val cardColor:CardTint=CardTint.DEFAULT,val titleBarColor:CardTint=CardTint.DEFAULT)
data class CardTransformPlacement(val nodeId:String,val mapId:String?,val parentId:String?,val x:Double,val y:Double,val expectedGraph:String) {
    init{listOfNotNull(nodeId,mapId,parentId).forEach{UUID.fromString(it)};require(x.isFinite()&&y.isFinite()&&x in -40000.0..40000.0&&y in -40000.0..40000.0);require(expectedGraph.matches(Regex("[0-9a-f]{64}")))}
}
/** The input fingerprint covers content, presentation, references, questions and independent placements. */
class CardTransformPlan(val operationId:String,val notebookId:String,val kind:CardTransformKind,val expectedFingerprint:String,
    inputs:List<CardTransformCard>,targets:List<CardTransformTarget>,val summaryPlacement:CardTransformPlacement?=null) {
    val inputs=inputs.map{it.copy(sources=it.sources.toList())}.toList()
    val targets=targets.map{it.copy(sources=it.sources.toList())}.toList()
    init {
        listOf(operationId,notebookId).forEach{UUID.fromString(it)}
        require(summaryPlacement==null||kind==CardTransformKind.SUMMARY)
        require(expectedFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(this.inputs.size in 1..16&&this.targets.size in 1..16)
        require(this.inputs.map{it.id}.distinct().size==this.inputs.size)
        require(this.targets.map{it.id}.distinct().size==this.targets.size)
        require(this.inputs.none{input->this.targets.any{it.id==input.id}})
        this.inputs.forEach{UUID.fromString(it.id);require(it.revision in 1 until Long.MAX_VALUE);require(it.presentation.cardId==it.id);KnowledgeCodec.validate(it.presentation);StudySourceRefs.encode(it.sources)}
        this.targets.forEach{UUID.fromString(it.id);require(it.title.isNotBlank()&&it.title.length<=120);require(it.body.length<=20_000){"TRANSFORM_BODY_TOO_LONG"};require(it.annotation.length<=CardPresentationRules.MAX_ANNOTATION){"TRANSFORM_ANNOTATION_TOO_LONG"};StudySourceRefs.encode(it.sources)}
        val allSources=this.inputs.flatMap{it.sources}.distinct()
        require(this.targets.all{target->target.sources.all{it in allSources}})
        require(allSources.all{source->this.targets.any{source in it.sources}}){"TRANSFORM_SOURCE_UNASSIGNED"}
        this.inputs.map{it.presentation.annotation}.filter{it.isNotEmpty()}.forEach{annotation->
            require(this.targets.any{annotation in it.annotation}){"TRANSFORM_ANNOTATION_UNASSIGNED"}
        }
        when(kind){
            CardTransformKind.MERGE->{require(this.inputs.size>=2&&this.targets.size==1);require(this.targets.single().body==this.inputs.joinToString("\n\n"){it.body}){"TRANSFORM_BODY_CHANGED"}}
            CardTransformKind.SPLIT->{require(this.inputs.size==1&&this.targets.size>=2);require(this.targets.all{it.body.isNotEmpty()});require(this.targets.joinToString(""){it.body}==this.inputs.single().body){"TRANSFORM_BODY_UNASSIGNED"}}
            CardTransformKind.SUMMARY->{require(this.inputs.size>=2&&this.targets.size==1);require(this.targets.single().body.isNotBlank()){"TRANSFORM_SUMMARY_REQUIRED"}}
        }
    }
    fun digest()=ContentTransfer.hash(CardTransformCodec.encode(this))
}
object CardTransforms {
    fun newId()=UUID.randomUUID().toString()
    fun mergedAnnotation(cards:List<CardTransformCard>)=cards.map{it.presentation.annotation}.filter{it.isNotEmpty()}.joinToString("\n\n")
    fun mergeTarget(cards:List<CardTransformCard>,title:String):CardTransformTarget {
        val first=cards.first().presentation
        return CardTransformTarget(newId(),title,cards.joinToString("\n\n"){it.body},mergedAnnotation(cards),cards.flatMap{it.sources}.distinct(),first.cardColor,first.titleBarColor)
    }
    fun splitTargets(card:CardTransformCard,offset:Int):List<CardTransformTarget> {
        require(offset in 1 until card.body.length)
        require(!(card.body[offset-1].isHighSurrogate()&&card.body[offset].isLowSurrogate())){"TRANSFORM_SPLIT_SURROGATE"}
        return listOf(card.body.substring(0,offset),card.body.substring(offset)).mapIndexed{i,body->
            CardTransformTarget(newId(),(card.title.take(114)+" · ${i+1}"),body,card.presentation.annotation,card.sources,card.presentation.cardColor,card.presentation.titleBarColor)
        }
    }
}
/** Plan bytes contain bounded text and IDs only, never source snapshots. */
object CardTransformCodec {
    const val MAX_BYTES=2_000_000
    fun encode(plan:CardTransformPlan):ByteArray=ByteArrayOutputStream().also{out->DataOutputStream(out).use{d->
        fun text(s:String){val b=s.toByteArray(Charsets.UTF_8);d.writeInt(b.size);d.write(b)}
        d.writeInt(if(plan.summaryPlacement==null)0x49575431 else 0x49575432);d.writeUTF(plan.operationId);d.writeUTF(plan.notebookId);d.writeUTF(plan.kind.name);d.writeUTF(plan.expectedFingerprint)
        d.writeInt(plan.inputs.size);plan.inputs.forEach{c->d.writeUTF(c.id);d.writeLong(c.revision);text(c.title);text(c.body);text(c.presentation.annotation);d.writeUTF(c.presentation.cardColor.name);d.writeUTF(c.presentation.titleBarColor.name);text(StudySourceRefs.encode(c.sources));d.writeBoolean(c.sourcesComplete)}
        d.writeInt(plan.targets.size);plan.targets.forEach{c->d.writeUTF(c.id);text(c.title);text(c.body);text(c.annotation);text(StudySourceRefs.encode(c.sources));d.writeUTF(c.cardColor.name);d.writeUTF(c.titleBarColor.name)}
        plan.summaryPlacement?.let{p->d.writeUTF(p.nodeId);d.writeUTF(p.mapId.orEmpty());d.writeUTF(p.parentId.orEmpty());d.writeDouble(p.x);d.writeDouble(p.y);d.writeUTF(p.expectedGraph)}
    }}.toByteArray().also{require(it.size<=MAX_BYTES)}
    fun decode(bytes:ByteArray):CardTransformPlan {
        require(bytes.size<=MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use{d->
            fun text():String {val n=d.readInt();require(n in 0..MAX_BYTES&&n<=d.available());val b=ByteArray(n);d.readFully(b);return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString()}
            fun count()=d.readInt().also{require(it in 1..16)}
            val magic=d.readInt();require(magic==0x49575431||magic==0x49575432);val op=d.readUTF();val book=d.readUTF();val kind=CardTransformKind.valueOf(d.readUTF());val fingerprint=d.readUTF()
            val cards=List(count()){val id=d.readUTF();val rev=d.readLong();val title=text();val body=text();val annotation=text();val color=CardTint.valueOf(d.readUTF());val titleColor=CardTint.valueOf(d.readUTF());CardTransformCard(id,rev,title,body,KnowledgeData.CardPresentation(id,annotation,color,titleColor),StudySourceRefs.decode(text()),d.readBoolean())}
            val targets=List(count()){CardTransformTarget(d.readUTF(),text(),text(),text(),StudySourceRefs.decode(text()),CardTint.valueOf(d.readUTF()),CardTint.valueOf(d.readUTF()))}
            val placement=if(magic==0x49575432)CardTransformPlacement(d.readUTF(),d.readUTF().ifEmpty{null},d.readUTF().ifEmpty{null},d.readDouble(),d.readDouble(),d.readUTF())else null
            require(d.read()==-1);CardTransformPlan(op,book,kind,fingerprint,cards,targets,placement)
        }
    }
}
