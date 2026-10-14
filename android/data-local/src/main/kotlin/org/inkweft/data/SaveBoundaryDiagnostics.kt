// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.data

import android.database.sqlite.SQLiteTransactionListener
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Opt-in synthetic-test hooks. The test owns clocks, bounded storage and serialization.
 * No SQL, IDs, contents or exception messages enter the callback. A callback failure only
 * invalidates the trace; it must never turn a committed save into an apparent failure. */
internal class SaveBoundaryDiagnostics(
    private val observer: (stage: String, sample: Int, task: Long, transaction: Long, depth: Int,
        successRequested: Boolean, outcome: String) -> Unit,
) {
    internal val sample = ThreadLocal<Int?>()
    private val task = ThreadLocal<Long?>()
    private val ordinal = AtomicLong()
    private class Frame(val transaction: Long, var successful: Boolean = false)
    private val room = ThreadLocal.withInitial { ArrayList<Frame>() }
    private val sqlite = ThreadLocal.withInitial { ArrayList<Frame>() }
    @Volatile internal var invalid = false
        private set

    internal fun point(stage: String) = emit(stage, 0, 0, false, "returned")

    private fun emit(stage: String, transaction: Long, depth: Int, success: Boolean, outcome: String) {
        try { observer(stage, sample.get() ?: -1, task.get() ?: 0, transaction, depth, success, outcome) }
        catch (_: Throwable) { invalid = true }
    }

    internal fun executor(delegate: Executor): Executor = Executor { command ->
        val number = ordinal.incrementAndGet()
        val token = sample.get()
        fun event(stage: String, outcome: String) {
            try { observer(stage, token ?: -1, number, 0, 0, false, outcome) }
            catch (_: Throwable) { invalid = true }
        }
        event("writer-submit", "entered")
        try {
            delegate.execute {
                val previousSample = sample.get(); val previousTask = task.get()
                sample.set(token); task.set(number)
                event("writer-run-enter", "entered")
                var outcome="returned"
                try { command.run() }
                catch(failure:Throwable) { outcome="threw";throw failure }
                finally {
                    event("writer-run-exit", outcome)
                    if (previousSample == null) sample.remove() else sample.set(previousSample)
                    if (previousTask == null) task.remove() else task.set(previousTask)
                }
            }
        } catch (failure: Throwable) {
            event("writer-submit-failed", "threw")
            throw failure
        }
    }

    internal fun begin(layer: String, action: () -> Unit) {
        val stack = checkNotNull(if (layer == "room") room.get() else sqlite.get())
        val transaction = stack.firstOrNull()?.transaction ?: ordinal.incrementAndGet()
        val depth = stack.size + 1
        emit("$layer-begin-enter", transaction, depth, false, "entered")
        try { action() }
        catch (failure: Throwable) {
            emit("$layer-begin-exit", transaction, depth, false, "threw")
            throw failure
        }
        stack.add(Frame(transaction))
        emit("$layer-begin-exit", transaction, depth, false, "returned")
    }

    internal fun success(layer: String, action: () -> Unit) {
        val stack = checkNotNull(if (layer == "room") room.get() else sqlite.get())
        val frame = stack.lastOrNull()
        if (frame == null) invalid = true
        emit("$layer-success-enter", frame?.transaction ?: 0, stack.size, frame?.successful == true, "entered")
        try { action() }
        catch (failure: Throwable) {
            emit("$layer-success-exit", frame?.transaction ?: 0, stack.size, frame?.successful == true, "threw")
            throw failure
        }
        if (frame != null) frame.successful = true
        emit("$layer-success-exit", frame?.transaction ?: 0, stack.size, frame?.successful == true, "returned")
    }

    internal fun end(layer: String, action: () -> Unit) {
        val stack = checkNotNull(if (layer == "room") room.get() else sqlite.get())
        val frame = stack.lastOrNull(); val depth = stack.size
        if (frame == null) invalid = true
        emit("$layer-end-enter", frame?.transaction ?: 0, depth, frame?.successful == true, "entered")
        try { action() }
        catch (failure: Throwable) {
            // The underlying transaction state after a failed END is unknown. Preserve the
            // delegate exception and reject this trace instead of inventing a commit boundary.
            invalid = true
            emit("$layer-end-exit", frame?.transaction ?: 0, depth, frame?.successful == true, "threw")
            stack.clear()
            throw failure
        }
        emit("$layer-end-exit", frame?.transaction ?: 0, depth, frame?.successful == true, "returned")
        if (frame != null) stack.removeAt(stack.lastIndex)
    }

    internal fun database(delegate: SupportSQLiteDatabase): SupportSQLiteDatabase =
        object : SupportSQLiteDatabase by delegate {
            override fun beginTransaction() = begin("sqlite") { delegate.beginTransaction() }
            override fun beginTransactionNonExclusive() = begin("sqlite") { delegate.beginTransactionNonExclusive() }
            override fun beginTransactionReadOnly() = begin("sqlite") { delegate.beginTransactionReadOnly() }
            override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
                begin("sqlite") { delegate.beginTransactionWithListener(transactionListener) }
            override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
                begin("sqlite") { delegate.beginTransactionWithListenerNonExclusive(transactionListener) }
            override fun beginTransactionWithListenerReadOnly(transactionListener: SQLiteTransactionListener) =
                begin("sqlite") { delegate.beginTransactionWithListenerReadOnly(transactionListener) }
            override fun setTransactionSuccessful() = success("sqlite") { delegate.setTransactionSuccessful() }
            override fun endTransaction() = end("sqlite") { delegate.endTransaction() }
            override fun yieldIfContendedSafely(): Boolean {
                invalid = true // Yield can end/re-enter internally, beyond these public hooks.
                return delegate.yieldIfContendedSafely()
            }
            override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean {
                invalid = true
                return delegate.yieldIfContendedSafely(sleepAfterYieldDelayMillis)
            }
        }
}
