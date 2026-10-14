# GC boundary 最小封闭契约草案（预算阻塞，未实现/未冻结）

绑定 source 4c4a2d67a27b56f351b3433d4f99deb7ce3e2591 和 capacity.json 中 test 哈希。以下只描述源码已有三个 marker；**不要求源码不存在的 schema/version 字段**。封闭对象拒绝未知/重复 key、非有限数字、错误布尔/整数类型；保留原始 JSON 字节、原顺序及 SHA256。它不是新控制器或原生验证结果。

## 外层顺序及字段

每方法独立 connection（same-room / separate-room），sample 必须 0..29 原序。每 sample 先 collector EVENTS 连续 part，再（仅 separate-room）author EVENTS，随后唯一 TRIAL；30 次之后唯一 SUMMARY。不能跨方法/DB 合并 ordinal 或用排序掩盖缺片。失败允许原序前缀证据，但不得获得完整方法或 30 样本资格。

- EVENTS 精确 keys：`sample, connection, database, part, parts, events`。
- database：same-room 只有 collector；separate-room 为 collector/author。part 从 0 起连续、parts 与实际块数一致、非末块 48、末块 1..48；最多 11 块/DB，累计 ≤512，精确等于 TRIAL 对应 count。没有事件时没有该 DB 的 EVENTS；不能凭空生成零事件块。
- Boundary 精确 keys：`stage, ownerToken, task, transaction, depth, successRequested, outcome, atNs, cpuNs, thread`。ownerToken 为本 trial 的 `3s/3s+1/3s+2` 或背景 -1；整数保持原始值，task/transaction 是 DB 内序号，线程与 CPU 配对仅限相同 thread。outcome 为 entered/returned/threw。stage 封闭为 writer-submit/run-enter/run-exit/submit-failed，room/sqlite 各 begin/success/end × enter/exit，以及 save-request/writer-acquired/head-read/head-written/revision-written/receipt-written/returned/failed。这里斜线表示穷举规则，不是新发出的文字值。

## TRIAL 封闭 keys

始终字段：

```
sample connection scenario replacesFrozenProbe
fixtureSourceVersions fixtureDetachedSources fixtureExplicitRoots fixtureLatestRetained
artificialGates committed saveOutcome saveWatchdogMs batchLimit authorBoundaries
boundaryCapacityPerDatabase batchCapacity phaseCapacity pureSqliteLockWaitMs
gcOwnerToken authorOwnerToken finishOwnerToken
status stage failureClass suppressedFailureClasses collectorFailureClass
collectorStartNs collectorEndNs saveStartNs saveEndNs saveMs
beforeRenameReceiptNs afterRenameTransactionNs
firstDeleted firstDeferred firstRootScans firstCommittedBatches
finishStartNs finishEndNs finishDeleted finishRootScans finishDeferred
deletionTransactionExposed deleteWindowIntersected saveStartedDuringDelete
deleteIntervals batchEvents batchDropped phaseDropped
workersExited helpersClosed fixtureDeleted boundaries coveredBeforeBatchCommitNs phases
```

按执行进度才可能出现的字段（失败早退不得伪造）：`initialRevision, writerSqlite, authorWriterSqlite, savedRevision, finalRevision, contentVerified, retainedRevisions`。完整 PASS 必须全部出现。scenario 固定 AFTER_SCAN_ARRIVAL_BOUNDARIES；fixture 固定1281/1280/0/1；replacesFrozenProbe/artificialGates 为 false；容量512/64/32、batchLimit256、saveWatchdog5000。saveOutcome 封闭 NOT_STARTED/THREW/COMMITTED/Conflict/ReusedCommandId/OutcomeUnknown，committed 对应 false/null/true；不得将未知提交当成功或丢弃。

stage 封闭 open-fixture/create-fixture/await-scan/author-rename/await-first-collection/finish-collection/verify/complete。status PASS/FAIL；failureClass/collectorFailureClass 可 null，suppressedFailureClasses 为原序字符串数组。源码未界定任意失败类名/数组文本的有限总字节；此缺口明确保留，**不擅设短字符串上限删除失败**。

嵌套对象也精确封闭：

- writerSqlite / authorWriterSqlite：page_size=1024、auto_vacuum=1、journal_mode=wal、synchronous=2。
- boundaries：database/count/dropped/invalid。数据库顺序固定，count≤512；完整 PASS 的 dropped=0、invalid=false。
- Batch（≤64）：phase/afterSequence/candidates/expectedDataVersion/expectedChanges/observedDataVersion/observedChanges/atNs/cpuNs/thread/callbackNs。phase 仅 guard-checked/before-delete/after-delete/transaction-returned；candidates 0..256，其余计数/时间为原整数。
- Phase（≤32）：phase/atNs。phase 仅 after-roots/after-stats/after-scan/before-batch-commit/after-batch。
- Delete interval：afterSequence/candidates/deleteStartNs/deleteEndNs/transactionReturnedNs/saveIntersected/saveStartedDuringDelete。失败可缺 end/returned（null），完整成功须有同 afterSequence 唯一匹配。derived intervals 数量不得超过其 before-delete 原始事件数。
- authorBoundaries：databaseTask/sqliteTransaction/roomTransaction/writerThread/segments/sqliteBeginThreadCpuNs/sqliteEndThreadCpuNs/pureSqliteLockWaitMs。segments 恰11，每项 name/startNs/endNs/elapsedNs；name 原序为 requestToSubmit、writerQueue、workerToSqliteBegin、sqliteBeginCall、sqliteBeginToRoomBegin、roomBeginToReceipt、receiptToSqliteEnd、sqliteEndCall、sqliteEndToRoomEnd、roomEndToRenameReturn、renameReturnToCallEnd。

完整 PASS 必须同时具备：COMMITTED、savedRevision=initialRevision+1=finalRevision、contentVerified、retainedRevisions=[1281]、firstDeleted+finishDeleted=1280、首轮256整批、finishDeferred=false、零 drop/invalid、workersExited/helpersClosed/fixtureDeleted=true、failureClass/collectorFailureClass=null、suppressedFailureClasses=[]。不得仅凭 SUMMARY 或 JUnit 文本填补遗漏 payload。

每个 DB 所有已录 writer-submit 与唯一 terminal 原序完整配对，无重复；作者 token 的唯一 task 要求 run-enter/run-exit，不能跨 DB 合并。作者 Room/SQLite outer 各 begin/success/end 的 enter/exit 完整唯一、depth=1、task/thread/transaction 一致、entered→returned，成功 END successRequested=true；Room 与 SQLite transaction ID 不要求相同。SQLite 选择覆盖实际 rename receipt 的 outer 区间，不能误选 trigger-sync 前置事务或嵌套 DAO。作者失败事件/outcome=threw 不得混进完整成功样本。原流按各线程校验 wall/CPU 非降，禁止跨线程 CPU 相减。

11 段端点须由原始事件及 receipt/return 时间复算、相邻相接、非负、求和恰为 saveEndNs-saveStartNs；writer-run-exit 可能晚于 caller 返回，不能拿它替代返回端点。所有 pureSqliteLockWaitMs 始终 null。batch guard、before/after-delete、transaction-returned 和报告必须交叉核对；收尾 collection 不算 save 竞争。

## SUMMARY 封闭 keys 与资格

```
scenario replacesFrozenProbe connection plannedTrials recordedTrials passedTrials
committedSaves failedTrials unknownCommitOutcomes notStartedSaves
functionalStatus exposureStatus minimumExposedSamplesForQuantiles
latencyAcceptance saveWatchdogMs watchdogIsLatencyAcceptance quantileDefinition
allSaves exposedSaves deleteWindowIntersectedSaves saveStartedDuringDeleteSaves
pureSqliteLockWaitMs
```

四个 distribution 对象精确 keys：count/sampleIndices/saveSamplesMs/maxMs/p50Ms/p95Ms。indices 必须是原 trial 原序子集，值从 saveMs 原值复算，不删失败或慢值。planned=30；完整方法需30 TRIAL、30 PASS/COMMITTED、0 failed/unknown/notStarted，并满足 JUnit/自然退出/外层来源与清理收执。否则 functionalStatus=FAILED_INCOMPLETE，exposureStatus=RUN_INCOMPLETE，无完整分位数资格。

完整功能但暴露不足20：INSUFFICIENT_EXPOSURE；三个暴露定义各自使用原时间窗口独立复算。子集 n<20 时 p50/p95=null，n=0 时 max=null；nearest-rank 与源码定义一致。latencyAcceptance 固定 NOT_EVALUATED，watchdogIsLatencyAcceptance=false。0/30 是合法结果；不重试、不扩垃圾、不加 gate、不并入旧条件到达样本。

## 外层不能省略的门

仍需 exact source→readiness→prep→active、官方 run/job/attempt/head/终态、同标准设备/APK安装字节/native依赖、7个独立 JUnit PASS、一个安装组、自然退出、trap+always ACL 精确恢复、完整文本预算与打包收执。非 PASS 失败封套只供原文诊断；hash/UTF8/secret/256KiB 限制失败应明确 REFUSED，不能救活方法或全局 PASS。当前预算阻塞，以上都只是草案，无执行资格。
