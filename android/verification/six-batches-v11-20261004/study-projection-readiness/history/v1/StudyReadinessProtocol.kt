import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

// Protocol copy only: the production combine/flatMapLatest/collector shape with synthetic readers.
// This does not instantiate StudyViewModel, Android Main, Compose, or Room.
data class Graph(val map:Int,val revision:Int=0)
data class Ui(val loading:Boolean=true,val readFailed:Boolean=false,val graph:Graph?=null,val nodes:List<Int> = emptyList())
@OptIn(ExperimentalCoroutinesApi::class)
class Protocol(val scope:CoroutineScope,val loadingFirst:Boolean,val currentKeyGuard:Boolean,val reader:(Int)->Flow<Graph>) {
    val mapId=MutableStateFlow(0)
    val reload=MutableStateFlow(0)
    val state=MutableStateFlow(Ui())
    val events=mutableListOf<String>()
    val observation=scope.launch {
        combine(mapId,reload){m,_->m}.flatMapLatest{m->
            reader(m).catch{e->
                if(e is CancellationException)throw e
                events+="error($m,current=${mapId.value})"
                if(!currentKeyGuard||m==mapId.value)state.update{it.copy(loading=false,readFailed=true)}
            }
        }.collect{graph->
            events+="collect(${graph.map}:${graph.revision},current=${mapId.value})"
            if(!currentKeyGuard||graph.map==mapId.value)state.update{it.copy(graph=graph,nodes=listOf(graph.map),loading=false,readFailed=false)}
        }
    }
    fun selectMap(id:Int) {
        check(id!=mapId.value)
        events+="select-start($id)"
        if(loadingFirst)state.update{it.copy(loading=true,nodes=emptyList())}
        mapId.value=id
        if(!loadingFirst)state.update{it.copy(loading=true,nodes=emptyList())}
        events+="select-end($id,loading=${state.value.loading},graph=${state.value.graph})"
    }
}
class SteppedDispatcher:CoroutineDispatcher() {
    val queue=ArrayDeque<Runnable>()
    override fun dispatch(context:CoroutineContext,block:Runnable){queue.addLast(block)}
    fun one(){check(queue.isNotEmpty()){ "No queued task" };queue.removeFirst().run()}
    fun drain(){var steps=0;while(queue.isNotEmpty()){check(++steps<10000);one()}}
}
fun immediate(loadingFirst:Boolean) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val p=Protocol(scope,loadingFirst,false){m->flow{emit(Graph(m));awaitCancellation()}}
    check(!p.state.value.loading&&p.state.value.graph?.map==0)
    p.selectMap(1)
    println("IMMEDIATE loadingFirst=$loadingFirst final=${p.state.value} events=${p.events}")
    check(p.state.value.graph?.map==1)
    check(p.state.value.loading==!loadingFirst)
    scope.cancel()
}
fun realIo(loadingFirst:Boolean,iterations:Int) {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    val p=Protocol(scope,loadingFirst,false){m->flow{emit(withContext(Dispatchers.IO){Graph(m)});awaitCancellation()}}
    fun awaitGraph(id:Int)=runBlocking{withTimeout(5000){p.state.first{it.graph?.map==id}}}
    awaitGraph(0)
    var stuck=0
    for(id in 1..iterations){p.selectMap(id);if(awaitGraph(id).loading)stuck++}
    println("REAL_IO loadingFirst=$loadingFirst iterations=$iterations loadingLeftTrue=$stuck")
    if(loadingFirst)check(stuck==0)
    scope.cancel()
}
fun bufferedOldSuccess(guard:Boolean) {
    val dispatcher=SteppedDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
    val oldUpdate=Channel<Unit>();val newRead=CompletableDeferred<Unit>()
    var queuedOld=false
    val p=Protocol(scope,true,guard){m->flow{
        if(m==0){emit(Graph(0));oldUpdate.receive();emit(Graph(0,1));queuedOld=true;awaitCancellation()}
        else{newRead.await();emit(Graph(m));awaitCancellation()}
    }}
    dispatcher.drain();check(!p.state.value.loading)
    check(oldUpdate.trySend(Unit).isSuccess)
    var steps=0;while(!queuedOld){check(++steps<100);dispatcher.one()}
    // emit to flatMapLatest's default output channel has completed, collector has not run.
    check(p.state.value.graph==Graph(0))
    p.selectMap(1);check(p.state.value.loading)
    dispatcher.drain()
    val beforeNew=p.state.value
    println("BUFFERED_OLD_SUCCESS guard=$guard beforeNew=$beforeNew events=${p.events}")
    check(beforeNew.loading==guard)
    check(if(guard)beforeNew.nodes.isEmpty()else beforeNew.graph==Graph(0,1)&&beforeNew.nodes==listOf(0))
    newRead.complete(Unit);dispatcher.drain()
    check(p.state.value.graph==Graph(1)&&!p.state.value.loading&&!p.state.value.readFailed)
    scope.cancel();dispatcher.drain()
}
fun oldFailureBeforeCancellation(guard:Boolean) {
    val dispatcher=SteppedDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
    val oldFailure=CompletableDeferred<Unit>();val newRead=CompletableDeferred<Unit>()
    val p=Protocol(scope,true,guard){m->flow{
        if(m==0){emit(Graph(0));oldFailure.await();throw IllegalStateException("synthetic old read failure")}
        else{newRead.await();emit(Graph(m));awaitCancellation()}
    }}
    dispatcher.drain();check(!p.state.value.loading)
    oldFailure.complete(Unit) // Queue the old read's failure before combine processes the new key.
    p.selectMap(1);check(p.state.value.loading)
    dispatcher.drain()
    val beforeNew=p.state.value
    println("OLD_FAILURE_BEFORE_CANCELLATION guard=$guard beforeNew=$beforeNew events=${p.events}")
    check(beforeNew.loading==guard&&beforeNew.readFailed==!guard)
    newRead.complete(Unit);dispatcher.drain()
    check(p.state.value.graph==Graph(1)&&!p.state.value.loading&&!p.state.value.readFailed)
    scope.cancel();dispatcher.drain()
}
fun main() {
    println("Protocol copy: Kotlin 2.3.0; kotlinx-coroutines-core-jvm 1.10.2; Java="+System.getProperty("java.version"))
    immediate(false);immediate(true)
    realIo(false,1000);realIo(true,1000)
    bufferedOldSuccess(false);bufferedOldSuccess(true)
    oldFailureBeforeCancellation(false);oldFailureBeforeCancellation(true)
    println("ALL_PROTOCOL_ASSERTIONS_PASS")
}
