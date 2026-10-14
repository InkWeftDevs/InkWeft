# Independent save-boundary review — compile1 snapshot

Scope: read-only source review of the five files bound by `compile1-start.json`. No build, device, network, CI, or source mutation was performed by this reviewer. Native behavior and diagnostic interpretation remain unverified by this review. This is an interim snapshot; final source hash review is pending the implementation owner's response.

## Finding: recorded background task may lose its final event

`LibrarySaveBoundaryDiagnosticsTest.kt:48` waits only for writer tasks whose sample is nonnegative. The recorder also records index/background writer tasks with sample `-1`. Room can resume the transaction caller before its executor Runnable has finished unwinding. Consequently, joining the search coroutine does not itself establish that all diagnostic writer-run-exit callbacks have executed. A background writer exit can arrive after `finishWindow` disables recording. `Recorder.complete()` currently checks overflow/invalid flags but does not reject that missing pair.

This can leave a nominally completed diagnostic event stream missing a recorded background task's terminal event; it does not change the production save result. Minimal remedy: await every writer-submit already recorded for the window after stopping/joining the producer jobs, or explicitly fail evidence for any unmatched recorded task. No scheduling or durability modification is needed. Sent to `/root` and `/root/save_boundary`; do not edit during the root-owned compile.

## Production path review

- Disabled diagnostics: `NoteRepository.save` takes its existing `saveTransaction(command, null)` path; Room transaction overrides immediately call their original `super` implementation; the transaction executor getter returns its original executor; the helper returns the original Requery database. These paths read no diagnostic clock, CPU counter, or ThreadLocal. There are new nullable fields/null branches, and close clears wrapper cache under a monitor; this is not a claim of literally zero machine instructions of overhead.
- Enabled diagnostics: each explicit begin variant, successful mark, and end calls the original delegate once. The existing Room serial executor is wrapped; no pool, queue, retry, SQL, listener, or invalidation request is introduced. Kotlin delegation preserves all operations not explicitly overridden.
- Observer failures are caught inside `emit` and executor `event` and set `invalid`. Original database failures are rethrown unchanged; failed END invalidates and clears the local trace stack rather than inventing a commit. Overflow is owned by the bounded test recorder and does not abort a healthy save.
- Executor context: captured sample/task tokens are installed for the delegated Runnable and the previous values are restored/removed in its finally block. Each submission receives a monotonic task ordinal. Room and SQLite stacks are per diagnostics object and thread. There is no global current-sample variable.
- Begin failure does not push a frame. Successful begin pushes a nesting frame; successful marking marks only that frame; a returning end pops it. All nested frames share the outer transaction ordinal. `successRequested` means exactly that; an outer mark by itself is not proof that nested rollback did not occur or that bytes are durable.
- Room and SQLite transaction IDs intentionally differ. Room BEGIN can first run a SQLite trigger-sync transaction. The test correctly selects the depth-one SQLite interval spanning author body entry, rather than assuming the first SQLite begin or a nested DAO end is the author commit.
- The legacy `saveStageObserver` retains its existing behavior, including intentionally injected exceptions used by rollback tests. The new safe observer does not redefine that pre-existing fault-injection hook as nonthrowing.
- No production correctness blocker was found in the reviewed snapshot. ThreadLocal restoration, executor rejection, and exact-once delegate forwarding are currently established by static review; a small direct-wrapper guard would strengthen coverage without changing the measurement windows.

## Test and interpretation review

The correctness guard covers committed save, replay, conflict, in-body exception rollback, in-body cancellation rollback, cancellation while Room's actual writer is occupied, subsequent save, close/reopen, and final stored content/revision. It checks one matching Room outer boundary and one matching SQLite author outer boundary, including same-thread SQLite endpoints. Observer failure, recorder overflow and a fake delegate END failure are separate guards.

The one-seed diagnostic keeps an independent IDLE control and a continuous DIRECT → original 750 ms paused-reader check → OBSERVE sequence. Public-sequence writer revisions are 1 at DIRECT start, 32 at OBSERVE start, and 62 after OBSERVE saves. These three windows are diagnostic-only; IDLE has different readiness, and a candidate-only one-seed trace cannot replace four-seed comparisons or establish B→C1 causality. Original probes/thresholds have not been edited.

Each event emits wall time, its own thread CPU counter and thread ID. No CPU subtraction occurs in these producers. Any consumer must require identical thread IDs and keep cross-thread CPU differences null. Even same-thread CPU includes any intervening execution on that thread. SQLite END measures delegate COMMIT/ROLLBACK plus connection release, not fsync alone; SQLite-end-exit → Room-end-exit includes refresh scheduling and checks, not refresh job completion; Room-end-exit → save-returned includes continuation and scheduling overhead, not a direct private resumeWith timestamp.

## Cached dependency support

The cached Room 2.8.5 bytecode (`androidx.room.RoomDatabase.javap.txt:1266–1324`) has overridable public begin/end/success methods. Its internal begin synchronizes triggers before the outer nonexclusive SQLite begin. Its internal end calls SQLite end, then checks `inTransaction`, then invokes `refreshVersionsAsync`; it does not wait for that refresh to finish. `RoomDatabaseKt__RoomDatabase_androidKt.javap.txt:77–85` calls `getTransactionExecutor().execute(...)` for this legacy withTransaction path. Actual runtime capture of the intended boundaries still needs the new native guard; compile success alone is insufficient.

The accompanying `binding-compile1.json` binds this review to exact source and dependency hashes. No original native results or performance classifications were replaced.
