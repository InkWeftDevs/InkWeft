package org.inkweft.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.inkweft.data.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class ShadowSemanticTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun cardTitleAndPageChoicesAreWholeBranchesAndConverge()=runBlocking{
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val binding=listOf("synthetic","issuer","account",id());val keys=mapOf(1 to EncryptedBackupFile.random(32))
        val a=ShadowReplica.open(ctx,binding,id(),keys);val b=ShadowReplica.open(ctx,binding,id(),keys);var cursor=0L
        assertThrows(IllegalArgumentException::class.java){ShadowReplica.open(ctx,binding,id(),keys,directory=ctx.filesDir)}
        suspend fun exchange(){
            val pending=(a.outgoing()+b.outgoing()).distinctBy{JSONObject(it).getString("operation")}
            for(e in pending){cursor++;a.receive(cursor,listOf(e));b.receive(cursor,listOf(e))}
            for(r in listOf(a,b))for(e in r.outgoing())r.acknowledged(JSONObject(e).getString("operation"))
        }
        try{
            var book="";val card=id()
            a.author{db->book=WorkspaceRepository(db).create("共同标题",false,PaperStyle.GRID).id;StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=card,nodeId=id(),title="共同卡片",body="原正文"))};exchange()
            for((r,name)in listOf(a to "A",b to "B"))r.author{db->
                NoteRepository(db).rename(RenameNote(id(),book,NoteRepository(db).read(book)!!.revision,"$name 标题"))
                StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=card,expectedRevision=1,title="$name 卡片",body="$name 正文"))
                val stroke=InkStroke(id(),InkPen.PENCIL,0xff3355bb.toInt(),4f,InkTool.STYLUS,listOf(InkSample(20f,30f,0),InkSample(if(name=="A")80f else 160f,90f,20)))
                InkRepository(db).save(CommitInk(id(),book,0,InkMutation.Add(stroke)))
            };exchange()
            val frozen=a.semanticConflicts();assertEquals(setOf("笔记","知识卡","页面"),frozen.map{it.kind}.toSet())
            assertEquals("A 正文",a.db.study().card(card)!!.body);assertEquals("B 正文",b.db.study().card(card)!!.body)
            for(conflict in frozen){val op=id();val choice=conflict.choices.single{!it.local};val result=a.resolve(conflict,choice.revision,op);assertEquals(result,a.resolve(conflict,choice.revision,op))}
            exchange();assertEquals(a.authorFingerprint(),b.authorFingerprint());assertTrue(a.semanticConflicts().isEmpty());assertTrue(b.semanticConflicts().isEmpty())
            assertEquals("B 标题",NoteRepository(a.db).read(book)!!.title);assertEquals("B 正文",a.db.study().card(card)!!.body)
            assertEquals(160f,InkRepository(a.db).read(book).strokes.single().stroke.samples.last().x)
            val stale=frozen.first();try{b.resolve(stale,stale.choices.first().revision,id());fail("Stale choice accepted")}catch(e:IllegalArgumentException){assertEquals("SHADOW_CONFLICT_CHANGED",e.message)}
            // Editing the resolved card creates a consistent next revision and outbox again.
            a.author{db->StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=card,expectedRevision=2,title="新修订",body="统一后继续编辑"))};exchange()
            assertEquals(a.authorFingerprint(),b.authorFingerprint());assertEquals(3,a.db.study().card(card)!!.revision)
        }finally{a.close();b.close()}
    }
}
