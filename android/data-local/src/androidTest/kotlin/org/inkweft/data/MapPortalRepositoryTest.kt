// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MapPortalRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{
        val name="map-portal-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("跨图入口测试",false,PaperStyle.DOTS).id)}
        finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun map(db:NoteDatabase,book:String,title:String,structures:List<MapStructure> = emptyList()):String {
        val map=id();KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,map,0,KnowledgeData.MapDefinition(title,structures=structures)));return map
    }
    private suspend fun card(db:NoteDatabase,book:String,mapId:String?=null):StudyCommand {
        val command=StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="原节点",body="原正文",mapId=mapId)
        StudyRepository(db).submit(command);return command
    }
    private suspend fun portal(db:NoteDatabase,book:String,sourceMap:String?,node:String,targetMap:String?):KnowledgeCommand {
        val command=KnowledgeCommand(id(),book,id(),0,KnowledgeData.MapPortal(sourceMap,node,targetMap))
        KnowledgeRepository(db).submit(command);return command
    }
    private suspend fun rejected(block:suspend()->Unit):KnowledgeRejection {
        try{block();fail("Expected KnowledgeRejected")}catch(e:KnowledgeRejected){return e.reason}
        error("Unreachable")
    }
    private fun count(db:NoteDatabase,table:String):Long=db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use{it.moveToFirst();it.getLong(0)}
    private fun authorCounts(db:NoteDatabase)=listOf("notes","note_revisions","command_receipts","study_cards","study_card_revisions","study_nodes","study_sources","study_receipts","knowledge_records","knowledge_revisions","knowledge_receipts","ink_strokes","ink_receipts").map{count(db,it)}

    @Test fun explicitSameBookEntriesKeepCardAndOccurrenceIdentities()=fixture{db,book->
        val structure=id();val a=map(db,book,"甲图",listOf(MapStructure(structure,null,"结构主题",40.0,80.0)));val b=map(db,book,"乙图")
        val main=card(db,book);val named=StudyCommand(id(),book,StudyAction.REUSE,cardId=main.cardId,nodeId=id(),mapId=a);StudyRepository(db).submit(named)
        val beforeCard=db.study().card(main.cardId!!)!!;val noteRevision=db.notes().note(book)!!.revision
        val mainEntry=portal(db,book,null,main.nodeId!!,a)
        val namedEntry=portal(db,book,a,named.nodeId!!,b)
        val structureEntry=portal(db,book,a,structure,null)
        val reads=MapPortalRepository(db)
        assertEquals(MapRef(book,a),reads.preview(book,mainEntry.id,1).target)
        assertEquals(named.nodeId,reads.preview(book,namedEntry.id,1).nodeId)
        assertEquals("结构主题",reads.preview(book,structureEntry.id,1).sourceTitle)
        assertEquals(MapRef(book),reads.preview(book,structureEntry.id,1).target)
        assertTrue(listOf(mainEntry,namedEntry,structureEntry).all{reads.preview(book,it.id,1).canOpen})
        assertEquals(1,db.study().cards(book).size);assertEquals(beforeCard,db.study().card(main.cardId!!))
        assertEquals(1,db.study().nodes(book).size);assertEquals(noteRevision,db.notes().note(book)!!.revision)
        assertTrue(db.knowledge().forBook(book).none{it.data() is KnowledgeData.Link||it.data() is KnowledgeData.Question})
        KnowledgeRepository(db).validateArchive()
    }

    @Test fun writesRejectForeignMissingAndRecycledEndpoints()=fixture{db,book->
        val source=card(db,book);val target=map(db,book,"本图")
        val foreign=WorkspaceRepository(db).create("另一本",false,PaperStyle.BLANK).id
        val foreignMap=map(db,foreign,"外图");val foreignNode=card(db,foreign,foreignMap)
        val repo=KnowledgeRepository(db)
        suspend fun create(data:KnowledgeData.MapPortal)=repo.submit(KnowledgeCommand(id(),book,id(),0,data))
        assertEquals(KnowledgeRejection.INVALID,rejected{create(KnowledgeData.MapPortal(null,source.nodeId!!,foreignMap))})
        assertEquals(KnowledgeRejection.INVALID,rejected{create(KnowledgeData.MapPortal(foreignMap,foreignNode.nodeId!!,null))})
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{create(KnowledgeData.MapPortal(null,foreignNode.nodeId!!,target))})
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{create(KnowledgeData.MapPortal(null,id(),target))})
        assertEquals(KnowledgeRejection.INVALID,rejected{create(KnowledgeData.MapPortal(null,source.nodeId!!,id()))})
        val accepted=portal(db,book,null,source.nodeId!!,target)
        assertEquals(KnowledgeRejection.INVALID,rejected{MapPortalRepository(db).preview(foreign,accepted.id,1)})
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=source.nodeId,expectedRevision=1))
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{create(KnowledgeData.MapPortal(null,source.nodeId!!,target))})
        val second=card(db,book);val row=db.knowledge().get(target)!!
        repo.submit(KnowledgeCommand(id(),book,target,row.revision,row.data(),true))
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{create(KnowledgeData.MapPortal(null,second.nodeId!!,target))})
        assertEquals(1,db.knowledge().forBook(book).count{it.data() is KnowledgeData.MapPortal})
    }

    @Test fun previewUsesCurrentTitlesWithoutAuthorWrites()=fixture{db,book->
        val a=map(db,book,"源图");val b=map(db,book,"目标旧名");val node=card(db,book,a)
        val entry=portal(db,book,a,node.nodeId!!,b)
        val question=KnowledgeCommand(id(),book,id(),0,KnowledgeData.Question(node.cardId!!,"独立问题"));KnowledgeRepository(db).submit(question)
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.MOVE,nodeId=node.nodeId,expectedRevision=1,x=580.0,y=260.0,mapId=a))
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.EDIT,cardId=node.cardId,expectedRevision=1,title="节点新名",body="保持独立正文",mapId=a))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,b,1,KnowledgeData.MapDefinition("目标新名")))
        val note=db.notes().note(book)!!;assertTrue(NoteRepository(db).rename(RenameNote(id(),book,note.revision,"笔记新名")) is SaveResult.Committed)
        val counts=authorCounts(db);val savedNote=db.notes().note(book);val savedCard=db.study().card(node.cardId!!);val savedNode=db.knowledge().get(node.nodeId!!)!!
        repeat(3){
            val preview=MapPortalRepository(db).preview(book,entry.id,1)
            assertTrue(preview.canOpen);assertEquals("节点新名",preview.sourceTitle);assertEquals("源图",preview.sourceMapTitle)
            assertEquals("目标新名",preview.targetTitle);assertEquals("笔记新名",preview.bookTitle)
            assertEquals(MapRef(book,a),preview.source);assertEquals(MapRef(book,b),preview.target);assertEquals(node.nodeId,preview.nodeId)
        }
        assertEquals(counts,authorCounts(db));assertEquals(savedNote,db.notes().note(book));assertEquals(savedCard,db.study().card(node.cardId!!))
        assertArrayEquals(savedNode.payload,db.knowledge().get(node.nodeId!!)!!.payload)
        assertEquals(1L,db.knowledge().get(question.id)!!.revision);assertEquals(1L,db.knowledge().get(entry.id)!!.revision)
    }

    @Test fun capturedRevisionRemovalAndStaleUndoNeverRedirectOrOverwrite()=fixture{db,book->
        val node=card(db,book);val a=map(db,book,"甲图");val b=map(db,book,"乙图")
        val entry=portal(db,book,null,node.nodeId!!,a);val repo=KnowledgeRepository(db);val reads=MapPortalRepository(db)
        val changed=(entry.data as KnowledgeData.MapPortal).copy(targetMapId=b)
        repo.submit(KnowledgeCommand(id(),book,entry.id,1,changed))
        assertEquals(KnowledgeRejection.CONFLICT,rejected{reads.preview(book,entry.id,1)})
        assertEquals(MapRef(book,b),reads.preview(book,entry.id,2).target)
        repo.submit(KnowledgeCommand(id(),book,entry.id,2,changed,true))
        assertEquals(KnowledgeRejection.CONFLICT,rejected{reads.preview(book,entry.id,2)})
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{reads.preview(book,entry.id,3)})
        assertEquals(KnowledgeRejection.CONFLICT,rejected{repo.submit(KnowledgeCommand(id(),book,entry.id,2,changed))})
        assertTrue(db.knowledge().get(entry.id)!!.removed)
        repo.submit(KnowledgeCommand(id(),book,entry.id,3,changed));assertTrue(reads.preview(book,entry.id,4).canOpen)
        repo.submit(KnowledgeCommand(id(),book,entry.id,4,entry.data))
        val counts=authorCounts(db)
        assertEquals(KnowledgeRejection.CONFLICT,rejected{repo.submit(KnowledgeCommand(id(),book,entry.id,4,changed,true))})
        assertEquals(counts,authorCounts(db));assertEquals(entry.data,db.knowledge().get(entry.id)!!.data())
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{reads.preview(book,id(),1)})
    }

    @Test fun removedStructuresAndRecycledMapsKeepUnavailableHistoricalEntries()=fixture{db,book->
        val structure=id();val a=map(db,book,"源图",listOf(MapStructure(structure,null,"可删除主题",20.0,30.0)))
        val target=map(db,book,"同名目标");val replacement=map(db,book,"同名目标")
        val entry=portal(db,book,a,structure,target);val repo=KnowledgeRepository(db);val reads=MapPortalRepository(db)
        val targetRow=db.knowledge().get(target)!!;repo.submit(KnowledgeCommand(id(),book,target,1,targetRow.data(),true))
        val unavailable=reads.preview(book,entry.id,1)
        assertFalse(unavailable.canOpen);assertEquals(MapRef(book,target),unavailable.target);assertNotEquals(MapRef(book,replacement),unavailable.target)
        repo.validateArchive();repo.submit(KnowledgeCommand(id(),book,target,2,targetRow.data()))
        repo.submit(KnowledgeCommand(id(),book,a,1,KnowledgeData.MapDefinition("源图")))
        assertFalse(reads.preview(book,entry.id,1).canOpen);assertTrue(db.knowledge().get(a)!!.data() is KnowledgeData.MapDefinition)
        assertTrue((db.knowledge().get(a)!!.data() as KnowledgeData.MapDefinition).structures.isEmpty())
        repo.validateArchive();repo.submit(KnowledgeCommand(id(),book,entry.id,1,entry.data,true))
        assertEquals(KnowledgeRejection.UNAVAILABLE,rejected{repo.submit(KnowledgeCommand(id(),book,entry.id,2,entry.data))})
        assertTrue(db.knowledge().get(entry.id)!!.removed);assertEquals(2L,db.knowledge().get(entry.id)!!.revision)
        val named=card(db,book,a);val namedEntry=portal(db,book,a,named.nodeId!!,target)
        val namedRow=db.knowledge().get(named.nodeId!!)!!
        repo.submit(KnowledgeCommand(id(),book,namedRow.id,namedRow.revision,(namedRow.data() as KnowledgeData.MapOccurrence).copy(mapId=replacement)))
        val originalScope=reads.preview(book,namedEntry.id,1)
        assertFalse(originalScope.canOpen);assertEquals(MapRef(book,a),originalScope.source);assertEquals(named.nodeId,originalScope.nodeId)
        assertTrue(MapGraphAccess(db).read(book).first{it.ref==MapRef(book,replacement)}.nodes.any{it.id==named.nodeId})
        repo.validateArchive()
        val main=card(db,book);val mainEntry=portal(db,book,null,main.nodeId!!,target)
        val workspace=db.workspace().get(book)!!;assertTrue(WorkspaceRepository(db).organize(book,workspace.revision,workspace.folder,workspace.tags,workspace.favorite,true))
        assertFalse(reads.preview(book,mainEntry.id,1).canOpen);repo.validateArchive()
    }

    @Test fun faultsAndDuplicatesLeaveOneCommittedIdentityAndReceipt()=fixture{db,book->
        val node=card(db,book);val target=map(db,book,"目标图")
        val entry=KnowledgeCommand(id(),book,id(),0,KnowledgeData.MapPortal(null,node.nodeId!!,target))
        assertEquals(KnowledgeOutcome.Unknown,KnowledgeRepository(db){if(it==KnowledgeFault.BEFORE_RECEIPT)error("rollback")}.outcome(entry))
        assertNull(db.knowledge().get(entry.id));assertNull(db.knowledge().revision(entry.id,1));assertNull(db.knowledge().receipt(entry.operationId))
        assertEquals(KnowledgeOutcome.Success(entry.id),KnowledgeRepository(db){if(it==KnowledgeFault.AFTER_COMMIT)error("lost response")}.outcome(entry))
        val repo=KnowledgeRepository(db);val counts=authorCounts(db)
        assertEquals(KnowledgeRejection.DUPLICATE,rejected{repo.submit(KnowledgeCommand(id(),book,id(),0,entry.data))})
        assertEquals(counts,authorCounts(db));assertEquals(entry.id,repo.submit(entry));assertEquals(counts,authorCounts(db))
        val row=db.knowledge().get(target)!!;repo.submit(KnowledgeCommand(id(),book,target,1,row.data(),true))
        val recycledCounts=authorCounts(db);assertEquals(entry.id,repo.submit(entry));assertEquals(recycledCounts,authorCounts(db))
        assertEquals(1,db.knowledge().revisions(entry.id).size);assertNotNull(db.knowledge().receipt(entry.operationId))
        assertEquals(1L,db.study().card(node.cardId!!)!!.revision);assertEquals(1L,db.study().node(node.nodeId!!)!!.revision)
    }

    @Test fun independentCopyRejectsOutgoingEntriesBeforeWritingAndReplaysFinishedCopies()=fixture{db,book->
        val a=map(db,book,"源图");val b=map(db,book,"目标图");val node=card(db,book,a)
        val entry=portal(db,book,a,node.nodeId!!,b);val copy=MapEmbedRepository(db)
        val scene=MapGraphAccess(db).read(book).first{it.ref==MapRef(book,a)};val operation=id();val counts=authorCounts(db)
        try{copy.duplicate(scene.ref,scene.signature(),operation);fail("Expected explicit unsupported copy")}
        catch(e:IllegalArgumentException){assertEquals("MAP_PORTAL_COPY_UNSUPPORTED",e.message)}
        assertEquals(counts,authorCounts(db));assertNull(db.knowledge().receipt(operation))
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,entry.id,1,entry.data,true))
        val copied=copy.duplicate(scene.ref,scene.signature(),operation)
        portal(db,book,a,node.nodeId!!,b);val replayCounts=authorCounts(db)
        assertEquals(copied,copy.duplicate(scene.ref,scene.signature(),operation));assertEquals(replayCounts,authorCounts(db))
        val inboundOnly=MapGraphAccess(db).read(book).first{it.ref==MapRef(book,b)}
        assertNotEquals(inboundOnly.ref,copy.duplicate(inboundOnly.ref,inboundOnly.signature(),id()))
    }

    @Test fun fullBackupRestoresExactEntriesHistoryAndUnavailableEndpoints()=fixture{db,book->
        val structure=id();val a=map(db,book,"甲图",listOf(MapStructure(structure,null,"旧结构",40.0,80.0)));val b=map(db,book,"乙图")
        val main=card(db,book);val named=StudyCommand(id(),book,StudyAction.REUSE,cardId=main.cardId,nodeId=id(),mapId=a);StudyRepository(db).submit(named)
        val entries=listOf(portal(db,book,null,main.nodeId!!,a),portal(db,book,a,named.nodeId!!,b),portal(db,book,a,structure,null))
        val repo=KnowledgeRepository(db)
        repo.submit(KnowledgeCommand(id(),book,a,1,KnowledgeData.MapDefinition("甲图")))
        repo.submit(KnowledgeCommand(id(),book,entries[2].id,1,entries[2].data,true))
        StudyRepository(db).submit(StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=main.nodeId,expectedRevision=1))
        val targetRow=db.knowledge().get(b)!!;repo.submit(KnowledgeCommand(id(),book,b,1,targetRow.data(),true));repo.validateArchive()
        val name="portal-restore-${id()}.db";var target=NoteDatabase.open(context,name)
        try{
            LibraryBackupRepository(context,db).snapshot().use{snapshot->
                val backup=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{backup.inspect(it)}.use{preview->
                    assertEquals(LibraryBackupRepository.RestoreResult.RESTORED,backup.restore(preview))
                    assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,backup.restore(preview))
                }
            }
            target.close();target=NoteDatabase.open(context,name)
            assertEquals(db.study().nodes(book),target.study().nodes(book));assertEquals(db.study().cards(book),target.study().cards(book))
            for(command in entries){
                val original=db.knowledge().get(command.id)!!;val restored=target.knowledge().get(command.id)!!
                assertEquals(original.id,restored.id);assertEquals(original.revision,restored.revision);assertEquals(original.removed,restored.removed)
                assertArrayEquals(original.payload,restored.payload);assertEquals(db.knowledge().receipt(command.operationId),target.knowledge().receipt(command.operationId))
                val historical=target.knowledge().revision(command.id,1)!!;assertEquals(command.data,KnowledgeCodec.decode(historical.payload))
            }
            val sourceHistory=target.knowledge().revision(a,1)!!
            assertEquals(structure,(KnowledgeCodec.decode(sourceHistory.payload) as KnowledgeData.MapDefinition).structures.single().id)
            assertFalse(MapPortalRepository(target).preview(book,entries[0].id,1).canOpen)
            assertFalse(MapPortalRepository(target).preview(book,entries[1].id,1).canOpen)
            KnowledgeRepository(target).validateArchive()
        }finally{target.close();context.deleteDatabase(name)}
    }

    @Test fun restoreFaultsRollbackThenReplayWithoutDuplicatingOrOverwriting()=fixture{db,book->
        val a=map(db,book,"甲图");val b=map(db,book,"乙图");val node=card(db,book);val entry=portal(db,book,null,node.nodeId!!,a)
        val name="portal-fault-restore-${id()}.db";val target=NoteDatabase.open(context,name)
        try{
            val retained=WorkspaceRepository(target).create("原有笔记",false,PaperStyle.BLANK)
            LibraryBackupRepository(context,db).snapshot().use{snapshot->
                val backup=LibraryBackupRepository(context,target)
                snapshot.file.inputStream().use{backup.inspect(it)}.use{preview->
                    try{LibraryBackupRepository(context,target){if(it==LibraryBackupRepository.BackupFault.BEFORE_RESTORE_COMMIT)error("rollback")}.restore(preview);fail()}
                    catch(e:IllegalStateException){assertEquals("rollback",e.message)}
                    assertNull(target.notes().note(book));assertNull(target.knowledge().get(entry.id));assertNull(target.knowledge().receipt(entry.operationId))
                    assertEquals(retained,NoteRepository(target).read(retained.id))
                    try{LibraryBackupRepository(context,target){if(it==LibraryBackupRepository.BackupFault.AFTER_RESTORE_COMMIT)error("lost response")}.restore(preview);fail()}
                    catch(e:IllegalStateException){assertEquals("lost response",e.message)}
                    val counts=authorCounts(target)
                    assertEquals(LibraryBackupRepository.RestoreResult.ALREADY_PRESENT,backup.restore(preview));assertEquals(counts,authorCounts(target))
                    assertEquals(1,target.knowledge().revisions(entry.id).size);assertEquals(db.knowledge().receipt(entry.operationId),target.knowledge().receipt(entry.operationId))
                    val changed=(entry.data as KnowledgeData.MapPortal).copy(targetMapId=b)
                    KnowledgeRepository(target).submit(KnowledgeCommand(id(),book,entry.id,1,changed))
                    assertEquals(LibraryBackupRepository.RestoreResult.IDENTITY_CONFLICT,backup.restore(preview))
                    assertEquals(changed,target.knowledge().get(entry.id)!!.data());assertEquals(retained,NoteRepository(target).read(retained.id))
                }
            }
        }finally{target.close();context.deleteDatabase(name)}
    }

    @Test fun archiveRejectsUnprovenSourceIdentityAndDuplicateActiveEntries()=fixture{db,book->
        val a=map(db,book,"源图");val b=map(db,book,"目标图");val invalidId=id()
        val invalid=KnowledgeRow(invalidId,book,1,KnowledgeCodec.encode(KnowledgeData.MapPortal(a,id(),b)))
        db.knowledge().insert(invalid);db.knowledge().revision(KnowledgeRevisionRow(invalid.id,1,book,invalid.payload,false))
        try{KnowledgeRepository(db).validateArchive();fail("Unproven source must fail archive validation")}catch(_:IllegalArgumentException){}
        try{LibraryBackupRepository(context,db).snapshot();fail("Invalid closure must not export")}catch(_:IllegalArgumentException){}
        // Remove only this deliberately malformed test fixture, then demonstrate an otherwise valid duplicate closure.
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_revisions WHERE id=?",arrayOf(invalidId))
        db.openHelper.writableDatabase.execSQL("DELETE FROM knowledge_records WHERE id=?",arrayOf(invalidId))
        val node=card(db,book,a);val entry=portal(db,book,a,node.nodeId!!,b)
        val duplicate=KnowledgeRow(id(),book,1,entry.payload)
        db.knowledge().insert(duplicate);db.knowledge().revision(KnowledgeRevisionRow(duplicate.id,1,book,duplicate.payload,false))
        try{KnowledgeRepository(db).validateArchive();fail("Duplicate author entries must fail archive validation")}
        catch(e:IllegalArgumentException){assertEquals("MAP_PORTAL_EXISTS",e.message)}
    }
}
