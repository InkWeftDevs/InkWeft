# Minimal controlled snapshot experiment — proposed scope

Status: design only; no source, CI, controller, production setting, historical test,
or frozen analyzer changed. The parent owns scope selection and the combined run.

## Question grounded in run 38543698114

The actual OBSERVE median save spent 4.924512 ms in SQLite END with 0.119026 ms
worker CPU; DIRECT's actual p95 spent 7.674103 / 0.121790 ms there. Only one of
30 OBSERVE saves had an overlapping index writer. The existing trace cannot
distinguish a retained read snapshot from active SQL/UDF work, storage waiting,
or descheduling inside END.

## One independently opted-in Android test

Proposed method:
`LibrarySnapshotHoldDiagnosticsTest#idleHeldSnapshotAndActiveSearchSaveBoundaries`

Proposed argument: `librarySnapshotHoldDiagnostics=true`.

Three independent copies of the same seed-4101 fixture; each starts at author
revision 1, the existing exact six-table initial digest, WAL and synchronous=2.
Each arm makes the same 30 sequential author-save commands and records the full
writer/Room/SQLite boundaries. No index builder runs in any arm. The original
public probe, prior 90-save test, and production diagnostics remain frozen.

| Arm | Readiness before save 0 | Work during the 30 saves | Required end witness |
|---|---|---|---|
| IDLE | Open the same readonly Requery connection; BEGIN deferred, read author's revision 1, close cursor, END and close connection | No reader | Author revision 31 |
| HELD_SNAPSHOT | Same readonly BEGIN; actually read author revision 1 and close cursor | Same reader thread waits on a bounded latch; no polling, SQL or scan | Before END, same snapshot still reads revision 1; after END, repository reads 31 |
| ACTIVE_SEARCH | Existing `search.read(target-only)` reaches its original first-source-check | Original query continues, no artificial pause, loop or background indexer | Correct target; real read completion after the last save |

BEGIN alone and SELECT 1 are not readiness witnesses: neither proves a main
database snapshot was fixed. HELD must keep BEGIN/query/wait/recheck/END on the
same thread and release in finally on failure/cancellation. The post-save
revision recheck is outside all timed saves. Holding a snapshot is intentionally
artificial and must never be reported as a public performance window.

ACTIVE requires its actual read interval to cover all 30 saves. An early-completing
query makes that arm's coverage invalid rather than silently switching to a
repeated query or presenting partly idle saves as active. Capture snapshot-open,
first-source readiness, last save, snapshot-close, and read-end explicitly.

## Measurements and bounds

- Thirty committed saves per arm; 22 required author stages plus all nested and
  trigger-sync boundary events, original sample/task/transaction/depth association,
  same-thread CPU and Java thread ID. Cross-thread CPU stays unavailable.
- Three controlled lifecycle/readiness records, actual reader wall/CPU/thread
  endpoints, initial/final revision witnesses, and complete original search stages.
- WAL file length and wal_autocheckpoint may be read outside the save window as
  context only. File length is not an active-frame count. No checkpoint command,
  synchronous change, WAL change, priority change, or freshness/cancellation change.
- Bounded latch/worker cleanup; fail closed on missing lifecycle, invalid chains,
  reader coverage failure, dropped events or overflow.
- Tentative compact wire format: closed schema and column-defined integer arrays,
  preserving every field, with stage/outcome enum codes. Existing 90-save marker
  format is not changed. Previous run had 1386 boundary events per non-index arm;
  proposed cap 1536/arm plus 128 read/control events/arm, 30 saves/arm.
- CI budget target: raw plus forwarding no more than 1 MiB; final byte ceiling,
  integer ranges, method name and schema need to be frozen against implemented
  source. These are proposed limits, not a completed artifact contract.

## Interpretation fixed before observing results

1. A clear HELD-versus-IDLE END increase while HELD itself uses negligible CPU
   shows that retaining the snapshot can reproduce the exposure in this window.
   It does not by itself prove checkpoint or fsync causality. A future local
   candidate would shorten work inside the same consistent read snapshot, never
   assemble one result from multiple author snapshots.
2. ACTIVE-only inflation with HELD similar to IDLE means a retained snapshot
   alone was insufficient in this window. Active SQL/UDF work, temporary I/O,
   CPU competition, and scheduling remain distinguishable possibilities. Do not
   rename this finding “CPU overhead” without a scheduler/operation witness.
3. Similar distributions, a single unexplained tail, or inconsistent component
   movement are inconclusive. Do not select a production change from them.
4. One fixed arm order can retain warmup/cache/order effects; this is mechanism
   screening, not an acceptance test or a measured B-to-C1 improvement. Do not
   subtract arm quantiles or sum segment medians. No observer-off calibration is
   implied.

## Optional scheduler evidence is not assumed available

The repository has an older `/proc/self/task/.../stat` tick reader, but no evidence
that Android `/proc/self/task/<LinuxTid>/schedstat` is readable and enabled in this
runner. A read-only capability probe can report availability; permission denial,
missing fields or unsupported/always-zero statistics must be explicit unavailable
states. No permission escalation or sysctl write is part of this scope.

If schedstat is later selected, record Linux TID separately from Java thread ID,
retain both sampling-clock brackets, and account for the sampling interval around
the actual END call. A raw runqueue counter difference includes the measurement
brackets and cannot simply be labeled END scheduler delay. Without a valid
operation/scheduler witness, END's off-CPU cause remains unresolved.
