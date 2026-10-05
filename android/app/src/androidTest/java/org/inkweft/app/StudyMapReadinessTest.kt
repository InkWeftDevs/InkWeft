// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.inkweft.core.*
import org.inkweft.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real production ViewModel and Room: neither the graph Flow nor selectMap is replaced. */
class StudyMapReadinessTest {
    private fun id()=UUID.randomUUID().toString()
    private fun <T> main(block:()->T):T {
        var result:Result<T>?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync{result=runCatching(block)}
        return checkNotNull(result).getOrThrow()
    }

    @Test fun rapidMapSelectionNeverPublishesLoadedGraphForAnotherCurrentMap()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="study-map-readiness-${id()}.db";val db=NoteDatabase.open(context,name)
        var vm:StudyViewModel?=null;var observers:CoroutineScope?=null
        try {
            val book=WorkspaceRepository(db).create("合成切图就绪",false,PaperStyle.BLANK).id
            val repository=StudyRepository(db);val maps=listOf<String?>(null,id(),id())
            val nodes=maps.associateWith{map->
                if(map!=null)KnowledgeRepository(db).submit(KnowledgeCommand(id(),book,map,0,KnowledgeData.MapDefinition("合成图 ${map.take(6)}")))
                id().also{node->repository.submit(StudyCommand(id(),book,StudyAction.CREATE,cardId=id(),nodeId=node,title="唯一主题",body="保持原图归属",mapId=map))}
            }
            val current=main{StudyViewModel(book,repository,SavedStateHandle()).also{vm=it;it.attach()}}
            suspend fun loaded(map:String?)=withTimeout(10_000){current.ui.first{
                !it.loading&&!it.readFailed&&it.graph?.ref==MapRef(book,map)&&it.nodes.map{row->row.id}.toSet()==setOf(nodes.getValue(map))
            }}
            loaded(null)
            val violations=mutableListOf<String>()
            main {
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);observers=scope
                fun checkProjection(event:String){
                    val state=current.ui.value;val expected=MapRef(book,current.mapId.value)
                    if(!state.loading&&!state.readFailed&&state.graph?.ref!=expected)violations+="$event expected=$expected loaded=${state.graph?.ref}"
                }
                // Both observers run synchronously on Main, like the current-map and graph UI collectors.
                scope.launch(start=CoroutineStart.UNDISPATCHED){current.mapId.collect{checkProjection("mapId")}}
                scope.launch(start=CoroutineStart.UNDISPATCHED){current.ui.collect{checkProjection("ui")}}
            }
            repeat(4) {
                main{current.selectMap(maps[1])};loaded(maps[1])
                // One Main turn: StateFlow may conflate the intermediate key, but the final
                // selection must still finish a fresh projection instead of staying loading.
                main{current.selectMap(maps[2]);current.selectMap(maps[1])};loaded(maps[1])
                main{current.selectMap(maps[2]);current.selectMap(null)}
                loaded(null)
                main{current.selectMap(maps[1]);current.selectMap(null)};loaded(null)
            }
            main {
                assertTrue("Loaded graph must always belong to the current map: $violations",violations.isEmpty())
                assertNull(current.mapId.value)
                assertEquals(MapRef(book),current.ui.value.graph!!.ref)
                assertEquals(setOf(nodes.getValue(null)),current.ui.value.nodes.map{it.id}.toSet())
            }
            maps.forEach{map->assertEquals(setOf(nodes.getValue(map)),repository.readGraph(book,map).nodes.map{it.id}.toSet())}
        } finally {
            main{observers?.cancel();vm?.detach();vm?.viewModelScope?.cancel()}
            db.close();context.deleteDatabase(name)
        }
    }
}
