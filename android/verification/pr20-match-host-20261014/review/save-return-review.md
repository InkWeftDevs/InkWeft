# Save return boundary review — existing run 38514139119

The existing evidence does **not** identify a redundant application operation that can safely be removed to fix save p50. Stop at this conclusion; no production or test change is justified by this review. The measured `receipt-written → transaction-returned` interval combines outer transaction completion and a return to a different coroutine thread. It is neither a commit timer nor a lock-wait timer. The new text matcher addresses the independently measured warm-read CPU hotspot; it cannot be credited with closing this save regression.

This is an offline review of the already completed diagnostic run, source `78a5babeaeb92292026423222d5e08635989198b`, job `115689021611`, and locally cached dependency bytecode. No network, Gradle, device, or native execution was used. No repository file was edited. [Binding](save-return-binding.json) records source and bytecode hashes; all six checked source files, including `NoteRepository`, `NoteDatabase`, the open-helper factory, observation scheduling, and the save diagnostic, match the measured commit byte for byte. The generated DAO is a local compiler artifact corroborating that source/dependency path, not an independently measured device trace.

The actual dependency is Room **2.8.5**, using `PreservingOpenHelperFactory` → Requery **3.49.0**, WAL and `synchronous=FULL`, with no `setDriver`, custom transaction/query executor, or auto-close configuration. Room therefore takes its OpenHelper compatibility path. Cached Room runtime AAR SHA-256 is `ab6f951f6e7b63ed419eaf740b34dbe51560532f78289fd9c5dadabf46401dba`; extracted classes SHA-256 is `17ac25750e9b7e3f4ef13e69db6493539aa962f541a4ce1570924a68b6c218d7`. The decompiled text in this directory is inspection evidence, not modified library code.

## What the tail actually contains

`NoteRepository.kt:47` emits `receipt-written` after the generated receipt insert has returned, while the enclosing `db.withTransaction` is still active. It then constructs `SaveResult.Committed(row.domain())`. The bytecode chain is:

1. `RoomDatabaseKt__RoomDatabase_androidKt$withTransaction$2.invokeSuspend`, offsets 75–88: `setTransactionSuccessful()` followed by `endTransaction()`; the exceptional path also ends the transaction.
2. `RoomDatabase.internalEndTransaction`, offsets 0–25: call the underlying `SupportSQLiteDatabase.endTransaction()`, check `inTransaction()`, then call `InvalidationTracker.refreshVersionsAsync()` only after the outer transaction ends. The first call is the synchronous database boundary. Cached Requery `SQLiteSession.endTransactionUnchecked`, offsets 99–153, executes `COMMIT;` and releases the connection only when the transaction stack has no parent. These hooks do not measure either native commit or release separately.
3. `refreshVersionsAsync` delegates to `TriggerBasedInvalidationTracker.refreshInvalidationAsync`. That method uses `pendingRefresh.compareAndSet(false,true)` and `launch` on the database's own coroutine scope, then returns. It **does not await/join the refresh job or a UI search result**. Pending requests are already coalesced. The refresh job later checks/reset modification-log flags in an IMMEDIATE transaction and advances observed table versions/notifies observers. Its transaction can contend for database resources, but its runtime is not a synchronous child that this save explicitly waits for.
4. `startTransactionCoroutine$2$1$1.invokeSuspend`, offsets 113–145: after the transaction block's `withContext` returns, `continuation.resumeWith(result)` requests return to the suspended caller. The outer transaction ran on Room's transaction executor/event loop; this diagnostic calls saves inside `withContext(Dispatchers.IO)`. Caller dispatch/resumption is another uninstrumented boundary. The Room executor task's final return and the caller's resumption need not have the same timestamp.
5. `NoteRepository.kt:28` emits `transaction-returned` on the caller, after all of `saveTransaction` has returned. The diagnostic then records the ordinary save end.

The bytecode supports the sequence, not a numerical split of this tail. In particular, SQL commit completion, refresh scheduling, actual refresh execution, continuation resume request, and execution of the resumed caller each need their own boundary to apportion the observed time. Current hooks cannot supply that split. CPU counters at the two tail endpoints cannot be subtracted because their thread IDs differ.

The existing generated `NoteDao_Impl` routes `receipt`, `note`, CAS, revision insert and receipt insert through `performSuspending`. In Room's actual `performSuspending` compatibility branch, an already-open/current-thread transaction avoids another executor dispatch. The write helper's `sync` and `refreshAsync` calls are guarded by `!connection.inTransaction()`. Its nested transaction wrappers remain inside the outer author transaction; AndroidX's `SupportSQLiteStatement.TransactionSQLiteStatement` translates them to SupportSQLite transaction calls, and Requery retains the parent transaction stack. Consequently the bytecode does **not** support a claim of separate durable commits or redundant full invalidation refreshes for each DAO call. Replacing the DAO chain with raw SQL to remove these wrappers would broaden correctness risk without explaining the measured tail.

## Recomputed observations from all 360 saves

The supplemental [raw review JSON](save-return-raw-review.json) was independently reconstructed from all `LIBRARY_SAVE_CAUSAL_RESULT` and `LIBRARY_SAVE_CAUSAL_STAGES` records. Input SHA-256: `1bb57986e65d56c4e27ccd65466b0793f7a364e638c93b17b1b00b40bb0e8bd6`. All 360 saves were Committed; all 360 tail endpoints have different thread IDs. No sample was removed. Quantiles use nearest rank: for 30 saves, p50 index 14, p95 index 28, max index 29 (zero based).

Tail `receipt-written → transaction-returned`, milliseconds:

| Mode | Block 0 p50 / p95 / max | Block 1 p50 / p95 / max |
|---|---:|---:|
| IDLE | 0.889 / 1.181 / 1.255 | 0.546 / 0.839 / 0.849 |
| TRACKER | 0.904 / 1.083 / 2.586 | 0.764 / 1.040 / 4.051 |
| INDEX_ONLY | 1.088 / 7.827 / 9.201 | 1.126 / 4.600 / 10.282 |
| INDEX_TRACKER | 1.318 / 6.692 / 7.355 | 1.282 / 5.825 / 8.755 |
| READS_ONLY | 1.661 / 3.223 / 4.087 | 1.293 / 5.375 / 8.309 |
| FULL | 3.042 / 7.724 / 12.261 | 2.682 / 9.414 / 9.446 |

FULL save p50 itself was 5.927 / 5.607 ms; IDLE was 2.551 / 1.751 ms. FULL entered-body CPU p50 was 1.120 / 1.121 ms and request-to-entry wall p50 0.476 / 0.491 ms. These are separate distribution summaries, so their medians must not be added/subtracted to claim an exact decomposition of save p50.

The tail is also temporally concurrent with downstream work:

| Event observed strictly inside a save tail | FULL block 0: tails containing event | FULL block 1 |
|---|---:|---:|
| Observation receives an invalidation | 7 / 30 | 14 / 30 |
| IO read starts | 13 / 30 | 14 / 30 |
| IO read cancellation finishes | 15 / 30 | 11 / 30 |

For example, block 0 sample 28's 12.261228 ms tail runs from case-relative 5,630,936,900 to 5,643,198,128 ns (receipt thread 44, return thread 55). Within it the previous observation cancels, another read starts, opens its connection/snapshot, prepares drafts/scope/hits and reaches `page-hits-start`. Block 1 sample 14's 9.445716 ms tail likewise contains invalidation delivery, cancellation, scheduling and the next read's scope preparation. This establishes concurrent work during return latency. It does **not** establish that the save synchronously awaited it, that a particular table event belongs to this specific command, or how much time is scheduler competition versus database commit/release. Room events may be coalesced, and these table-level callbacks carry no command revision.

Both FULL cases had 23 started IO reads, all cancelled and none reaching `page-start`; therefore the measured warm body matcher is absent from these save-window read paths. Previously identified long save outliers overlapped one actual index writer body, but the other 29 saves in each FULL case retained the same save p50. Index writer hold explains a concrete tail/outlier exposure; it cannot alone explain the median. Mode readiness differs, execution order is reversed in the second block, and these are instrumented cases, so mode differences are diagnostic evidence rather than additive causal coefficients or replacements for the frozen public acceptance samples.

## Covered and missing boundaries

| Boundary | Existing evidence | What remains unknown |
|---|---|---|
| Save request → writer body entry | `waiting-writer`, `writer-acquired` | Executor dispatch, transaction trigger sync, actual DB begin/lock components |
| Author SQL body | Same-thread staged events through receipt | Native statement subcomponents; diagnostic callback overhead |
| Outermost SupportSQLite end/commit | Bytecode order only | Start/end wall and same-thread CPU, physical I/O and release time |
| Refresh scheduling | Bytecode async launch/CAS only | Enqueue latency and whether this request coalesced |
| Refresh log check/reset and version publication | Bytecode and later flow events | Refresh job start/end; causal command association |
| Continuation resume request → caller execution | Bytecode plus cross-thread endpoint IDs | Dispatch queue/wakeup interval |
| Read cancellation/restart | Existing read/observation stages | OS runnable time and resource competition; events are not profiler CPU attribution |

The event function reads a wall clock, CPU clock and thread ID and appends to a concurrent queue. Its enabled diagnostic overhead is part of these timings. The `receipt-written` timestamp also precedes the remainder of its own hook. These records must remain separate from uninstrumented public p50 comparisons.

There is no supported minimal production change here: do not remove notes invalidation, postpone immediate cancellation, alter debounce, bypass Room's outer transaction, defer durability, or remove Room's refresh to shorten a measured endpoint. No duplicate manual refresh call exists in `NoteRepository.save`. If a later authorized investigation must split the remaining tail, the missing data is an outermost SupportSQLite end entry/exit pair on the same thread plus a continuation/refresh scheduling boundary; another broad public run with the same hooks cannot recover that information. This review does not request or implement such instrumentation, and does not close or waive any original save threshold.
