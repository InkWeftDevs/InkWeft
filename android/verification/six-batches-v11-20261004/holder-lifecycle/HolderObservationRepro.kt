import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver

fun main() {
    val pending = ArrayDeque<() -> Unit>()
    val observer = SnapshotStateObserver { pending.addLast(it) }
    val state = mutableStateOf(0)
    Snapshot.sendApplyNotifications()
    val holder = Any()
    var attached = true
    var detachedNotifications = 0
    observer.start()
    val sibling = Any()
    val siblingState = mutableStateOf(0)
    Snapshot.sendApplyNotifications()
    val onChanged: (Any) -> Unit = { if(it === holder && !attached) detachedNotifications++ }
    observer.observeReads(holder, onChanged) { state.value }
    observer.observeReads(sibling, onChanged) { siblingState.value }
    state.value = 1
    Snapshot.sendApplyNotifications()
    check(pending.isNotEmpty())
    observer.clear(holder)
    attached = false
    while(pending.isNotEmpty()) pending.removeFirst().invoke()
    println("Direct holder observation: detached notifications=$detachedNotifications")
    check(detachedNotifications == 1)
    observer.stop()

    val guardedPending = ArrayDeque<() -> Unit>()
    val guardedObserver = SnapshotStateObserver { guardedPending.addLast(it) }
    val renderState = mutableStateOf(0)
    val otherSetterState = mutableStateOf(0)
    Snapshot.sendApplyNotifications()
    val composition = Any()
    val safeHolder = Any()
    var compositionNotifications = 0
    var safeHolderNotifications = 0
    var rendered = -1
    guardedObserver.start()
    fun render() {
        guardedObserver.observeReads(composition, { compositionNotifications++ }) {
            val value = renderState.value
            guardedObserver.observeReads(safeHolder, { safeHolderNotifications++ }) {
                Snapshot.withoutReadObservation {
                    rendered = value
                    otherSetterState.value // Models a native setter's synchronous Compose callback.
                }
            }
        }
    }
    render()
    check(rendered == 0)
    renderState.value = 1
    otherSetterState.value = 1
    Snapshot.sendApplyNotifications()
    guardedObserver.clear(safeHolder)
    while(guardedPending.isNotEmpty()) guardedPending.removeFirst().invoke()
    check(compositionNotifications == 1 && safeHolderNotifications == 0)
    render()
    check(rendered == 1)
    println("Composition-owned inputs: composition notifications=$compositionNotifications detached holder notifications=$safeHolderNotifications rendered=$rendered")
    guardedObserver.stop()
}
