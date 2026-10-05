import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
fun main() = runBlocking {
    for (lazy in listOf(false, true)) {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        var stranded = 0
        var synchronous = 0
        repeat(20_000) {
            var busy = true
            var tracked: Job? = null
            var bodyComplete = false
            val launched = scope.launch(start = if (lazy) CoroutineStart.LAZY else CoroutineStart.DEFAULT) {
                val request = currentCoroutineContext().job
                try { withContext(Dispatchers.IO) { "" } }
                finally {
                    if (tracked === request) { busy = false; tracked = null }
                    bodyComplete = true
                }
            }
            if (bodyComplete) synchronous++
            tracked = launched
            if (lazy) launched.start()
            launched.join()
            if (busy) stranded++
        }
        println("lazy=$lazy synchronous_before_assignment=$synchronous stranded_busy=$stranded / 20000")
        scope.cancel()
    }
}
