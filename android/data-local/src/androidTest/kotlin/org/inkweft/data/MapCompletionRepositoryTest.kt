// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.inkweft.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Room transactions, receipts, archive validation and graph projections; device required. */
class MapCompletionRepositoryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun id()=UUID.randomUUID().toString()
    private fun fixture(block:suspend(NoteDatabase,String)->Unit)=runBlocking{
        val name="map-completion-${id()}.db";val db=NoteDatabase.open(context,name)
        try{block(db,WorkspaceRepository(db).create("脑图补全",false,PaperStyle.BLANK).id)}finally{db.close();context.deleteDatabase(name)}
    }
    private suspend fun create(db:NoteDatabase,book:String,parent:String?=null):StudyCommand{
        val repo=StudyRepository(db);val c=StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=id(),title="主题",body="原正文",parentId=parent,expectedGraph=repo.readGraph(book).graphFingerprint)
        repo.submit(c);return c
    }
    @Test fun summaryCreateReplayDissolveAndRestoreKeepOccurrenceIdsAndVersions()=fixture{db,book->
        val root=create(db,book);val a=create(db,book,root.nodeId);val b=create(db,book,root.nodeId)
        val repo=StudyRepository(db);val before=repo.readGraph(book);val stale=StudyOrganization.arrange(before.state,before.orderedNodeIds.associateWith{StudyNodeSize(232.0,64.0)},"organization")
        val value=MapSummaries.selection(before.state,setOf(a.nodeId!!,b.nodeId!!),"共同结论")
        val command=KnowledgeCommand(id(),book,id(),0,value)
        val writes=KnowledgeRepository(db){if(it==KnowledgeFault.AFTER_COMMIT)error("synthetic lost reply")}
        assertEquals(KnowledgeOutcome.Success(command.id),writes.outcome(command));assertEquals(KnowledgeOutcome.Success(command.id),writes.outcome(command))
        assertEquals(1,db.knowledge().revisions(command.id).size)
        val grouped=repo.readGraph(book);assertEquals(value,grouped.state.summaryGroups.single().data);assertNotEquals(before.graphFingerprint,grouped.graphFingerprint)
        assertTrue(repo.outcome(stale.command(id())) is StudyOutcome.Rejected)
        val old=db.knowledge().get(command.id)!!;KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,old.id,old.revision,value,removed=true))
        assertTrue(repo.readGraph(book).state.summaryGroups.isEmpty());assertEquals(before.nodes,repo.readGraph(book).nodes)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,old.id,2,value))
        assertEquals(3L,repo.readGraph(book).state.summaryGroups.single().revision);assertEquals(3,db.knowledge().revisions(old.id).size)
        assertTrue(repo.readGraph(book).cards.all{it.revision==1L&&it.body=="原正文"});KnowledgeRepository(db).validateArchive()
    }
    @Test fun summaryInvalidStructureAndOverlapRollBackWithNoExtraReceipt()=fixture{db,book->
        val root=create(db,book);val a=create(db,book,root.nodeId);val b=create(db,book,root.nodeId);val c=create(db,book,root.nodeId)
        val repo=StudyRepository(db);val before=repo.readGraph(book);val summary=MapSummaries.selection(before.state,setOf(a.nodeId!!,b.nodeId!!),"归纳")
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,summary));val grouped=repo.readGraph(book)
        val split=StudyCommand(id(),book,StudyAction.REPARENT,nodeId=b.nodeId,expectedRevision=1,parentId=c.nodeId,expectedGraph=grouped.graphFingerprint)
        assertTrue(repo.outcome(split) is StudyOutcome.Rejected);assertNull(db.study().receipt(split.id));assertEquals(grouped.graphFingerprint,repo.readGraph(book).graphFingerprint)
        val remove=StudyCommand(id(),book,StudyAction.REMOVE_NODE,nodeId=a.nodeId,expectedRevision=1,expectedGraph=grouped.graphFingerprint)
        assertTrue(repo.outcome(remove) is StudyOutcome.Rejected);assertNull(db.study().receipt(remove.id));assertEquals(grouped.nodes,repo.readGraph(book).nodes)
        val overlap=KnowledgeCommand(id(),book,id(),0,KnowledgeData.MapSummaryGroup(null,"重复",listOf(b.nodeId!!,c.nodeId!!)))
        assertEquals(KnowledgeOutcome.Rejected(KnowledgeRejection.MAP_SUMMARY_CONFLICT),KnowledgeRepository(db).outcome(overlap));assertNull(db.knowledge().get(overlap.id));assertNull(db.knowledge().receipt(overlap.operationId))
        KnowledgeRepository(db).validateArchive()
    }
    @Test fun layoutMetadataPersistsForMainAndNamedMapsAndUndoRestoresIt()=fixture{db,book->
        val root=create(db,book);create(db,book,root.nodeId)
        val named=id();val definition=MapTemplates.instantiate(MapTemplates.builtins.single{it.title=="项目拆解"},"项目")
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,named,0,definition))
        val repo=StudyRepository(db)
        for(map in listOf<String?>(null,named)){
            val before=repo.readGraph(book,map);val sizes=before.orderedNodeIds.associateWith{StudyNodeSize(232.0,80.0)}
            val layout=if(map==null)"organization"else"left";val plan=StudyOrganization.arrange(before.state,sizes,layout)
            repo.submit(plan.command(id()));val current=StudyRepository(db).readGraph(book,map)
            assertEquals(layout,current.state.layout);assertEquals(plan.expectedAfterGraph,current.graphFingerprint)
            assertEquals(layout,(current.order!!.data() as KnowledgeData.MapOrder).layout)
            repo.submit(StudyOrganization.undo(current.state,plan).command(id()));val restored=repo.readGraph(book,map)
            assertEquals(before.state.layout,restored.state.layout);assertEquals(before.nodes.map{it.copy(revision=1)},restored.nodes.map{it.copy(revision=1)})
            assertEquals(before.orderedNodeIds,restored.orderedNodeIds)
        }
        KnowledgeRepository(db).validateArchive()
    }
    @Test fun namedStructuralSummaryAppearsInSharedSceneAndFixedSnapshot()=fixture{db,book->
        val named=id();val def=MapTemplates.instantiate(MapTemplates.builtins[2],"图")
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,named,0,def))
        val repo=StudyRepository(db);val graph=repo.readGraph(book,named)
        val peers=graph.orderedNodeIds.filter{id->graph.nodes.first{it.id==id}.parentId!=null}.take(2)
        val value=MapSummaries.selection(graph.state,peers.toSet(),"并列知识")
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,value))
        val scene=MapGraphAccess(db).read(book).single{it.ref.mapId==named}
        assertEquals("bilateral",scene.layout);assertEquals(listOf(value),scene.summaryGroups)
        val fixed=MapEmbed(scene.ref,policy=MapEmbedPolicy.PINNED,snapshot=scene)
        assertEquals(fixed,MapEmbedCodec.decode(MapEmbedCodec.encode(fixed)));KnowledgeRepository(db).validateArchive()
    }
    @Test fun independentCopyRemapsSummaryMembersAndKeepsCurrentLayoutWithOneReceipt()=fixture{db,book->
        val named=id();KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,named,0,MapTemplates.instantiate(MapTemplates.builtins[2],"原图")))
        val repo=StudyRepository(db);val before=repo.readGraph(book,named)
        val peers=before.orderedNodeIds.filter{n->before.nodes.first{it.id==n}.parentId!=null}.take(2)
        KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,id(),0,MapSummaries.selection(before.state,peers.toSet(),"共同依据")))
        val grouped=repo.readGraph(book,named);repo.submit(StudyOrganization.arrange(grouped.state,grouped.orderedNodeIds.associateWith{StudyNodeSize(232.0,80.0)},"organization").command(id()))
        val original=MapGraphAccess(db).read(book).single{it.ref.mapId==named};val operation=id()
        val duplicate=MapEmbedRepository(db).duplicate(original.ref,original.signature(),operation)
        assertEquals(duplicate,MapEmbedRepository(db).duplicate(original.ref,original.signature(),operation))
        val copy=MapGraphAccess(db).read(book).single{it.ref==duplicate}
        assertEquals(original.layout,copy.layout);assertEquals(original.nodes.size,copy.nodes.size)
        assertTrue(copy.nodes.map{it.id}.intersect(original.nodes.map{it.id}.toSet()).isEmpty())
        val summary=copy.summaryGroups.single();assertEquals(duplicate.mapId,summary.mapId);assertEquals("共同依据",summary.label)
        assertTrue(summary.memberIds.all{member->copy.nodes.any{it.id==member}});assertTrue(summary.memberIds.intersect(peers.toSet()).isEmpty())
        assertEquals(original.signature(),MapGraphAccess(db).read(book).single{it.ref==original.ref}.signature())
        assertNotNull(db.knowledge().receipt(operation));KnowledgeRepository(db).validateArchive()
    }

}
