import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

// Addendum only: synthetic readers and exact current-key vs proposed epoch flow shapes.
// This does not instantiate Room, Android Main, Compose, or the production StudyViewModel.
data class Graph(val map:Int,val revision:Int)
data class Ui(val loading:Boolean=true,val readFailed:Boolean=false,val graph:Graph?=null,val nodes:List<Int> = emptyList())
class SteppedDispatcher:CoroutineDispatcher() {
    val queue=ArrayDeque<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable){queue.addLast(block)}
    fun one(){check(queue.isNotEmpty());queue.removeFirst().run()}
    fun drain(){var steps=0;while(queue.isNotEmpty()){check(++steps<10000);one()}}
}
@OptIn(ExperimentalCoroutinesApi::class)
class Protocol(val scope:CoroutineScope,val epochs:Boolean,val reader:(Int,Int)->Flow<Graph>) {
    val mapId=MutableStateFlow(0)
    val reload=MutableStateFlow(0)
    val state=MutableStateFlow(Ui())
    val events=mutableListOf<String>()
    val observation=if(epochs)scope.launch {
        reload.flatMapLatest{r->
            val m=mapId.value
            reader(m,r).map{r to it}.catch{e->
                if(e is CancellationException)throw e
                events+="error(m=$m,r=$r,current=${mapId.value},epoch=${reload.value})"
                if(r==reload.value&&m==mapId.value)state.update{it.copy(loading=false,readFailed=true)}
            }
        }.collect{(r,graph)->
            events+="collect(g=$graph,r=$r,current=${mapId.value},epoch=${reload.value})"
            if(r==reload.value&&graph.map==mapId.value)publish(graph)
        }
    }else scope.launch {
        combine(mapId,reload){m,_->m}.flatMapLatest{m->
            reader(m,0).catch{e->
                if(e is CancellationException)throw e
                events+="error(m=$m,current=${mapId.value})"
                if(m==mapId.value)state.update{it.copy(loading=false,readFailed=true)}
            }
        }.collect{graph->
            events+="collect(g=$graph,current=${mapId.value})"
            if(graph.map==mapId.value)publish(graph)
        }
    }
    private fun publish(graph:Graph){state.update{it.copy(graph=graph,nodes=listOf(graph.map),loading=false,readFailed=false)}}
    fun selectMap(id:Int) {
        check(id!=mapId.value)
        state.update{it.copy(loading=true,nodes=emptyList())}
        mapId.value=id
        if(epochs)reload.value++
    }
}
fun abaNoNewEmission(epochs:Boolean) {
    val d=SteppedDispatcher();val scope=CoroutineScope(SupervisorJob()+d)
    val newRead=CompletableDeferred<Unit>();val starts=mutableListOf<Pair<Int,Int>>()
    val p=Protocol(scope,epochs){m,r->starts+=m to r;flow{
        if(r!=0)newRead.await()
        emit(Graph(m,r));awaitCancellation()
    }}
    d.drain();check(p.state.value.graph==Graph(0,0)&&!p.state.value.loading)
    p.selectMap(1);p.selectMap(0) // Both key changes happen before StateFlow collectors resume.
    d.drain()
    println("ABA_NO_NEW_EMISSION epochs=$epochs beforeNew=${p.state.value} starts=$starts active=${p.observation.isActive}")
    check(p.state.value.loading&&p.state.value.nodes.isEmpty())
    if(epochs){
        check(starts==listOf(0 to 0,0 to 2))
        newRead.complete(Unit);d.drain()
        check(p.state.value.graph==Graph(0,2)&&!p.state.value.loading)
        println("ABA_NO_NEW_EMISSION epochs=true afterNew=${p.state.value}")
    }else check(starts==listOf(0 to 0)&&p.observation.isActive&&d.queue.isEmpty())
    scope.cancel();d.drain()
}
fun abaBufferedOldSuccess(epochs:Boolean) {
    val d=SteppedDispatcher();val scope=CoroutineScope(SupervisorJob()+d)
    val oldUpdate=Channel<Unit>();val newRead=CompletableDeferred<Unit>()
    val starts=mutableListOf<Pair<Int,Int>>();var queuedOld=false
    val p=Protocol(scope,epochs){m,r->starts+=m to r;flow{
        if(r==0){emit(Graph(m,0));oldUpdate.receive();emit(Graph(m,1));queuedOld=true;awaitCancellation()}
        else{newRead.await();emit(Graph(m,2));awaitCancellation()}
    }}
    d.drain();check(p.state.value.graph==Graph(0,0))
    check(oldUpdate.trySend(Unit).isSuccess)
    var steps=0;while(!queuedOld){check(++steps<100);d.one()}
    check(p.state.value.graph==Graph(0,0)) // Old success is buffered but not yet consumed.
    p.selectMap(1);p.selectMap(0);d.drain()
    println("ABA_BUFFERED_OLD_SUCCESS epochs=$epochs beforeNew=${p.state.value} starts=$starts events=${p.events}")
    if(epochs){
        check(p.state.value.loading&&p.state.value.graph==Graph(0,0)&&p.state.value.nodes.isEmpty())
        check(starts==listOf(0 to 0,0 to 2))
        newRead.complete(Unit);d.drain()
        check(p.state.value.graph==Graph(0,2)&&!p.state.value.loading)
        println("ABA_BUFFERED_OLD_SUCCESS epochs=true afterNew=${p.state.value}")
    }else check(!p.state.value.loading&&p.state.value.graph==Graph(0,1)&&p.state.value.nodes==listOf(0)&&starts==listOf(0 to 0))
    scope.cancel();d.drain()
}
fun abaOldFailure(epochs:Boolean) {
    val d=SteppedDispatcher();val scope=CoroutineScope(SupervisorJob()+d)
    val oldFailure=CompletableDeferred<Unit>();val newRead=CompletableDeferred<Unit>()
    val starts=mutableListOf<Pair<Int,Int>>()
    val p=Protocol(scope,epochs){m,r->starts+=m to r;flow{
        if(r==0){emit(Graph(m,0));oldFailure.await();throw IllegalStateException("synthetic stale A failure")}
        else{newRead.await();emit(Graph(m,2));awaitCancellation()}
    }}
    d.drain();oldFailure.complete(Unit)
    p.selectMap(1);p.selectMap(0);d.drain()
    println("ABA_OLD_FAILURE epochs=$epochs beforeNew=${p.state.value} starts=$starts events=${p.events}")
    if(epochs){
        check(p.state.value.loading&&!p.state.value.readFailed&&p.state.value.nodes.isEmpty())
        check(starts==listOf(0 to 0,0 to 2))
        newRead.complete(Unit);d.drain()
        check(p.state.value.graph==Graph(0,2)&&!p.state.value.loading&&!p.state.value.readFailed)
        println("ABA_OLD_FAILURE epochs=true afterNew=${p.state.value}")
    }else check(!p.state.value.loading&&p.state.value.readFailed&&starts==listOf(0 to 0))
    scope.cancel();d.drain()
}
fun epochImmediateAndExternalObserver() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val p=Protocol(scope,true){m,r->flow{emit(Graph(m,r));awaitCancellation()}}
    val observed=mutableListOf<Pair<Int,Ui>>()
    val observer=scope.launch{p.mapId.drop(1).collect{observed+=it to p.state.value}}
    p.selectMap(1);check(p.state.value.graph==Graph(1,1)&&!p.state.value.loading)
    p.selectMap(0);check(p.state.value.graph==Graph(0,2)&&!p.state.value.loading)
    println("EPOCH_IMMEDIATE final=${p.state.value} external=$observed events=${p.events}")
    check(observed.map{it.first}==listOf(1,0)&&observed.all{it.second.loading&&it.second.nodes.isEmpty()})
    observer.cancel();scope.cancel()
}
fun main() {
    println("ABA addendum; synthetic protocol copy; Kotlin 2.3.0; coroutines 1.10.2; Java="+System.getProperty("java.version"))
    abaNoNewEmission(false);abaNoNewEmission(true)
    abaBufferedOldSuccess(false);abaBufferedOldSuccess(true)
    abaOldFailure(false);abaOldFailure(true)
    epochImmediateAndExternalObserver()
    println("ALL_ABA_PROTOCOL_ASSERTIONS_PASS")
}
