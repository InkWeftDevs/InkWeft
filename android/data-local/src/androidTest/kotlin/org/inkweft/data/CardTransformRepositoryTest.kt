// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CardTransformRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{
        val name="transform-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("合成多来源资料",false,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun card(db:NoteDatabase,book:String,title:String,body:String="正文\n 空格 "):StudyCardRow {
        val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
        val current=InkRepository(db).read(book).revision
        assertTrue(InkRepository(db).save(CommitInk(id(),book,current,InkMutation.Add(stroke))) is InkCommitResult.Committed)
        val command=StudyCommand(id(),book,StudyAction.CREATE,id(),id(),title=title,body=body,
            source=StudySourceDraft(book,current+1,CanvasBounds(10.0,10.0,30.0,30.0),listOf(stroke.id)))
        StudyRepository(db).submit(command)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,KnowledgeData.CardPresentation(command.cardId!!,"注释：$title",CardTint.BLUE,CardTint.ROSE)))
        return db.study().card(command.cardId!!)!!
    }
    private fun counts(db:NoteDatabase)=LibraryBackupRepository.SCHEMA.map{table->db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `${table.name}`").use{it.moveToFirst();it.getLong(0)}}
    @Test fun previewCancelMergeReplayUndoAndFixedSourcesRetainAllOriginalIdentities()=fixture{db,book->
        val a=card(db,book,"甲");val b=card(db,book,"乙","另一原文")
        val question=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(a.id,"旧题问法",ManualState.UNDERSTOOD));KnowledgeRepository(db).submit(question)
        val reference=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Link(TargetRef(TargetKind.CARD,b.id),TargetRef(TargetKind.CARD,a.id),pinnedRevision=1));KnowledgeRepository(db).submit(reference)
        val repo=CardTransformRepository(db);val before=counts(db);val note=db.notes().note(book);val nodes=db.study().nodes(book);val bytes=db.study().snapshotBytes()
        val preview=repo.preview(book,listOf(a.id,b.id))
        assertEquals(before,counts(db));assertEquals(note,db.notes().note(book));assertEquals(1,preview.impact.first().questions.size)
        val plan=preview.plan(CardTransformKind.MERGE,listOf(CardTransforms.mergeTarget(preview.cards,"合并内容")))
        val result=CardTransformRepository(db){if(it==CardTransformFault.AFTER_COMMIT)error("lost response")}.outcome(plan)
        assertTrue(result is CardTransformOutcome.Success);assertEquals(result,repo.submit(plan));assertEquals(3,db.study().cards(book).size)
        val target=plan.targets.single();assertEquals(a.body+"\n\n"+b.body,db.study().card(target.id)!!.body)
        assertEquals(nodes,db.study().nodes(book));assertEquals(a,db.study().card(a.id));assertEquals(b,db.study().card(b.id));assertEquals(bytes,db.study().snapshotBytes())
        assertEquals(question.payload.toList(),db.knowledge().get(question.id)!!.payload.toList());assertEquals(1L,db.knowledge().get(question.id)!!.revision)
        val sources=StudySourceVersions(db).read(target.id);assertEquals(2,sources.sources.size);assertNull(sources.singleLegacy(target.id));assertNull(StudyRepository(db).source(target.id))
        assertEquals(1,repo.resolve(a.id).size);assertEquals(target.id,repo.resolve(a.id).single().targets.single().id)
        assertEquals(a.body,db.study().cardVersion(a.id,1)!!.body)
        val undoId=id();val undone=CardTransformRepository(db){if(it==CardTransformFault.AFTER_COMMIT)error("lost inverse response")}.undoOutcome(plan.operationId,1,undoId)
        assertTrue(undone is CardTransformOutcome.Success);assertEquals(undone,repo.undo(plan.operationId,1,undoId))
        assertTrue(repo.resolve(a.id).isEmpty());assertEquals(nodes,db.study().nodes(book));assertNotNull(db.study().card(target.id)!!.trashedAt)
        assertEquals(target.body,db.study().cardVersion(target.id,1)!!.body);assertEquals(2,StudySourceVersions(db).read(target.id,1).sources.size)
    }
    @Test fun splitRequiresExplicitMultipleTargetsAndUndoRejectsLaterDependencies()=fixture{db,book->
        val a=card(db,book,"拆分原卡","甲乙\n丙丁")
        val repo=CardTransformRepository(db);val preview=repo.preview(book,listOf(a.id));val targets=CardTransforms.splitTargets(preview.cards.single(),2)
        val plan=preview.plan(CardTransformKind.SPLIT,targets)
        repo.submit(plan);assertEquals(targets.map{it.id},repo.resolve(a.id).single().targets.map{it.id})
        assertEquals(a.body,targets.joinToString(""){it.body});assertEquals(1,db.study().nodes(book).size)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(targets.first().id,"显式新建题")))
        val counts=counts(db)
        assertEquals(CardTransformOutcome.Rejected("TRANSFORM_UNDO_DEPENDENCIES_CHANGED"),repo.undoOutcome(plan.operationId,1,id()))
        assertEquals(counts,counts(db));assertTrue(targets.all{db.study().card(it.id)!!.trashedAt==null})
    }
    @Test fun transactionFaultStalePreviewAndSummaryUseOneAtomicSemanticGraph()=fixture{db,book->
        val cards=listOf(card(db,book,"甲"),card(db,book,"乙"));val repo=CardTransformRepository(db)
        val preview=repo.preview(book,cards.map{it.id});val target=CardTransforms.mergeTarget(preview.cards,"我的归纳").copy(body="用户写的总结")
        val plan=preview.plan(CardTransformKind.SUMMARY,listOf(target));val before=counts(db);val nodes=db.study().nodes(book)
        assertEquals(CardTransformOutcome.Unknown,CardTransformRepository(db){if(it==CardTransformFault.BEFORE_RECEIPT)error("rollback")}.outcome(plan))
        assertEquals(before,counts(db));assertNull(db.cardTransforms().get(plan.operationId));assertNull(db.study().card(target.id))
        repo.submit(plan)
        val links=db.knowledge().forBook(book).mapNotNull{it.data() as? KnowledgeData.Link}.filter{it.relation==RelationKind.SUMMARY}
        assertEquals(cards.map{it.id}.toSet(),links.map{it.source.id}.toSet());assertTrue(links.all{it.target.id==target.id});assertEquals(nodes,db.study().nodes(book))
        val stale=preview.plan(CardTransformKind.MERGE,listOf(CardTransforms.mergeTarget(preview.cards,"旧预览")))
        assertEquals(CardTransformOutcome.Rejected("TRANSFORM_VERSION_CHANGED"),repo.outcome(stale))
        val undo=id();repo.undo(plan.operationId,1,undo);assertTrue(db.knowledge().forBook(book).filter{it.data() is KnowledgeData.Link}.all{it.removed})
    }
    @Test fun recropKeepsExactOldCardSourceAndOldReviewNeverReadsCurrentCrop()=fixture{db,book->
        val bitmap=android.graphics.Bitmap.createBitmap(20,20,android.graphics.Bitmap.Config.ARGB_8888)
        fun jpeg(color:Int)=java.io.ByteArrayOutputStream().also{bitmap.eraseColor(color);bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,it)}.toByteArray()
        val blue=jpeg(android.graphics.Color.BLUE);val red=jpeg(android.graphics.Color.RED);bitmap.recycle()
        val create=StudyCommand(id(),book,StudyAction.CREATE_EXCERPT,cardId=id(),title="原截图",body="原文与旧备注逐字保留",source=StudySourceDraft(book,0,CanvasBounds(10.0,10.0,30.0,30.0),emptyList(),blue,0))
        val study=StudyRepository(db);study.submit(create)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(create.cardId!!,"根据原迹回答")))
        val review=BranchReviewRepository(db);val plan=review.prepareCard(MapRef(book),create.cardId!!,1);val old=study.sources(create.cardId!!,1).sources.single()
        study.submit(StudyCommand(id(),book,StudyAction.RECROP_EXCERPT,cardId=create.cardId,expectedRevision=1,source=StudySourceDraft(book,0,CanvasBounds(10.0,10.0,40.0,40.0),emptyList(),red,0)))
        val current=study.sources(create.cardId!!,2).sources.single()
        assertEquals(old.sourceId,current.sourceId);assertEquals(2L,current.revision);assertFalse(old.snapshot.contentEquals(current.snapshot))
        val frozen=review.load(plan).single();assertArrayEquals(old.snapshot,frozen.sources.sources.single().snapshot);assertEquals(create.body,frozen.card.body)
        assertEquals(old.snapshot.size.toLong()+current.snapshot.size,study.snapshotBytes())
    }
    @Test fun reopenAndFullBackupRestoreExactTransformSourceVersionsAndUndoReceipt()=fixture{db,book->
        val a=card(db,book,"甲");val b=card(db,book,"乙");val repo=CardTransformRepository(db)
        val p=repo.preview(book,listOf(a.id,b.id));val command=p.plan(CardTransformKind.MERGE,listOf(CardTransforms.mergeTarget(p.cards,"目标")))
        repo.submit(command);val name="transform-restore-${id()}.db";var target=NoteDatabase.open(context,name)
        try{
            val backup=LibraryBackupRepository(context,db);val restore=LibraryBackupRepository(context,target)
            backup.snapshot().use{snapshot->snapshot.file.inputStream().use{restore.inspect(it)}.use{preview->
                assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview));assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,restore.restore(preview))}}
            target.close();target=NoteDatabase.open(context,name)
            val restored=CardTransformRepository(target);assertEquals(command.targets.single().id,restored.resolve(a.id).single().targets.single().id)
            assertEquals(command.digest(),target.cardTransforms().get(command.operationId)!!.digest)
            assertEquals(2,StudySourceVersions(target).read(command.targets.single().id).sources.size)
            assertEquals(repo.submit(command),restored.submit(command));restored.undo(command.operationId,1,id());assertTrue(restored.resolve(a.id).isEmpty())
            LibraryBackupRepository(context,target).snapshot().close()
        }finally{target.close();context.deleteDatabase(name)}
    }
    @Test fun schema13MigrationPreservesLegacyBodyAndOnlyFreezesSurvivingSource()=runBlocking{
        val name="transform-migration-${id()}.db";val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=org.json.JSONObject(context.assets.open("org.inkweft.data.NoteDatabase/13.json").bufferedReader().use{it.readText()}).getJSONObject("database")
        val book=id();val card=id();val stroke=InkStroke(id(),InkPen.PEN,0xff123456.toInt(),2f,InkTool.STYLUS,listOf(InkSample(20f,20f,0)))
        val snapshot=InkPageFile("原迹","",listOf(stroke),false,PaperStyle.BLANK).encode();val body="旧摘录\n 旧混合备注  不猜迁移😀"
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use{sql->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName");sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:org.json.JSONArray();for(j in 0 until indices.length())sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            sql.execSQL("INSERT INTO notes VALUES (?,1,'旧本','',1234)",arrayOf(book));sql.execSQL("INSERT INTO note_revisions VALUES (?,1,'旧本','',1234)",arrayOf(book))
            sql.execSQL("INSERT INTO study_cards VALUES (?,?,2,'旧卡',?,NULL)",arrayOf(card,book,body))
            sql.execSQL("INSERT INTO study_card_revisions VALUES (?,1,'旧卡',?,NULL)",arrayOf(card,body));sql.execSQL("INSERT INTO study_card_revisions VALUES (?,2,'旧卡',?,NULL)",arrayOf(card,body))
            sql.execSQL("INSERT INTO notebook_pages VALUES (?,?,0,0,0,500,707,0,NULL,NULL)",arrayOf(book,book))
            sql.execSQL("INSERT INTO ink_pages VALUES (?,1)",arrayOf(book));sql.execSQL("INSERT INTO ink_strokes VALUES (?,?,?,1,1,1)",arrayOf(stroke.id,book,InkStrokeCodec.encode(stroke)))
            sql.execSQL("INSERT INTO study_sources VALUES (?,?,1,10,10,30,30,?,?)",arrayOf(card,book,stroke.id,snapshot));sql.version=13
        }
        val db=NoteDatabase.open(context,name)
        try{
            assertEquals(14,db.openHelper.readableDatabase.version);assertEquals(body,db.study().card(card)!!.body);assertEquals(body,db.study().cardVersion(card,1)!!.body)
            assertFalse(StudySourceVersions(db).read(card,1).complete);assertNull(StudySourceVersions(db).read(card,1).singleLegacy(card))
            val current=StudySourceVersions(db).read(card,2);assertTrue(current.complete);assertArrayEquals(snapshot,current.sources.single().snapshot)
            assertEquals(1L,current.refs.single().revision);assertEquals(0L,db.images().totalBytes());assertEquals(snapshot.size.toLong(),db.study().snapshotBytes())
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun schema13BackupPromotionAndCorruptSourceReferenceAreCheckedBeforeLiveWrites()=fixture{db,book->
        val original=card(db,book,"旧备份卡")
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=original.id,expectedRevision=1,title=original.title,body=original.body))
        val schema=LibraryBackupRepository.SCHEMA_V13;val sql=db.openHelper.readableDatabase
        val bytes=java.io.ByteArrayOutputStream()
        LibraryArchive.write(bytes,schema,object:LibraryArchive.Rows{
            override fun count(table:Int)=sql.query("SELECT COUNT(*) FROM `${schema[table].name}`").use{it.moveToFirst();it.getLong(0)}
            override fun visit(table:Int,consume:(List<Any?>)->Unit){
                val t=schema[table]
                sql.query("SELECT "+t.columns.joinToString(","){"`${it.name}`"}+" FROM `${t.name}` ORDER BY "+t.keys.joinToString(","){"`$it`"}).use{cursor->
                    while(cursor.moveToNext())consume(t.columns.mapIndexed{i,c->if(cursor.isNull(i))null else when(c.kind){
                        'I'->cursor.getLong(i);'F'->cursor.getDouble(i);'B'->cursor.getBlob(i);else->cursor.getString(i)
                    }})
                }
            }
        },0)
        val name="legacy-transform-${id()}.db";val target=NoteDatabase.open(context,name)
        try{
            val restore=LibraryBackupRepository(context,target)
            bytes.toByteArray().inputStream().use{restore.inspect(it)}.use{preview->assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,restore.restore(preview))}
            assertEquals(original.body,target.study().card(original.id)!!.body)
            assertFalse(StudySourceVersions(target).read(original.id,1).complete)
            assertTrue(StudySourceVersions(target).read(original.id,2).complete)
            val before=counts(target)
            db.openHelper.writableDatabase.execSQL("UPDATE study_card_source_sets SET sourceRefs=? WHERE cardId=? AND cardRevision=2",arrayOf("${id()}@1",original.id))
            try{LibraryBackupRepository(context,db).snapshot().close();fail("dangling immutable source must be rejected")}catch(_:IllegalArgumentException){}
            assertEquals(before,counts(target))
        }finally{target.close();context.deleteDatabase(name)}
    }

    @Test fun independentMapCopyKeepsAllSourcesAndCopiesPresentationWithoutSharingQuestions()=fixture{db,book->
        val a=card(db,book,"甲");val b=card(db,book,"乙");val transforms=CardTransformRepository(db)
        val preview=transforms.preview(book,listOf(a.id,b.id));val merge=preview.plan(CardTransformKind.MERGE,listOf(CardTransforms.mergeTarget(preview.cards,"多来源")))
        transforms.submit(merge);val merged=merge.targets.single()
        val study=StudyRepository(db);study.submit(StudyCommand(id(),book,StudyAction.REUSE,cardId=merged.id,nodeId=id()))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(merged.id,"原题",ManualState.UNDERSTOOD)))
        val main=MapGraphAccess(db).read(book).single{it.ref.mapId==null};val beforeBytes=study.snapshotBytes()
        val ref=MapEmbedRepository(db).duplicate(main.ref,main.signature(),id());val scene=MapGraphAccess(db).read(book).single{it.ref==ref}
        val newCard=scene.nodes.single{it.title=="多来源"}.cardId!!
        assertNotEquals(merged.id,newCard);assertEquals(2,study.sources(newCard).sources.size);assertEquals(beforeBytes,study.snapshotBytes())
        val rows=db.knowledge().forBook(book);val presentation=rows.single{(it.data() as? KnowledgeData.CardPresentation)?.cardId==newCard}
        assertEquals(KnowledgeData.CardPresentation(newCard,merged.annotation,CardTint.BLUE,CardTint.ROSE),presentation.data())
        assertTrue(rows.none{(it.data() as? KnowledgeData.Question)?.cardId==newCard})
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,presentation.id,presentation.revision,(presentation.data() as KnowledgeData.CardPresentation).copy(annotation="副本独立编辑")))
        assertEquals(merged.annotation,(db.knowledge().forBook(book).single{(it.data() as? KnowledgeData.CardPresentation)?.cardId==merged.id}.data() as KnowledgeData.CardPresentation).annotation)
    }

    @Test fun inverseRejectsASecondTransformationThatDependsOnTheNewIdentity()=fixture{db,book->
        val a=card(db,book,"甲");val b=card(db,book,"乙");val repo=CardTransformRepository(db)
        val p=repo.preview(book,listOf(a.id,b.id));val merge=p.plan(CardTransformKind.MERGE,listOf(CardTransforms.mergeTarget(p.cards,"合并")))
        repo.submit(merge)
        val split=repo.preview(book,listOf(merge.targets.single().id));val splitPlan=split.plan(CardTransformKind.SPLIT,CardTransforms.splitTargets(split.cards.single(),2));repo.submit(splitPlan)
        assertEquals(CardTransformOutcome.Rejected("TRANSFORM_UNDO_DEPENDENCIES_CHANGED"),repo.undoOutcome(merge.operationId,1,id()))
        assertNull(db.study().card(merge.targets.single().id)!!.trashedAt)
        repo.undo(splitPlan.operationId,1,id());repo.undo(merge.operationId,1,id())
        assertNotNull(db.study().card(merge.targets.single().id)!!.trashedAt);assertNull(db.study().card(a.id)!!.trashedAt)
    }

}
