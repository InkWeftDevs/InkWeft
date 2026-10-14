# 默认关闭 GC 条件到达诊断交接

状态：源码 READY / 静态检查完成，**本子任务未编译、未运行原生测试、未启动CI**。这是新增诊断，不能替代原公共probe或自然到达0/30结果，也不是竞争尾延迟验收PASS。

## 冻结源码与入口

- `android/data-local/src/main/kotlin/org/inkweft/data/HistoryStorage.kt` SHA256 `b96890478129bbb7b0bbb538c3cf8d5f2e97f2b8b5e2e34d8057c855c9ec6ca9`。
- 新 `android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionCausalTest.kt` SHA256 `4f80b8ce32e1aa2bae1d919b96c06b1897e7a8500c748822efd05119ba87ec4a`。
- 未修改 `HistoryDeletionExposureTest.kt`：`7e98a67eec4f9242fc4c18e6bf662ec1d86c346796ebc28ab0971be1a9f0224f`；未修改 `HistoryCollectionTest.kt`：`de78e40a7038e4380f81443d3438803dafbc2695c663c9d96fd002e9295a0df5`。

显式选择以下方法之一，并传instrumentation参数 `-e historyGcContentionDiagnostic true`：

1. `org.inkweft.data.HistoryDeletionCausalTest#sameRoomWriterConditionalDeleteArrival`
2. `org.inkweft.data.HistoryDeletionCausalTest#separateRoomWriterConditionalDeleteArrival`

缺少参数时Assume跳过；不能把默认跳过当运行成功。每方法固定30独立夹具/30次rename，同/异Room分开，共60次，不补样/重试。父侧统一构建、安装、run绑定与运行；本交接不提供未经确认的runner包名或伪造APK/run SHA。

## 最小机制

每夹具1281来源版本、1280可删、最新1保留、无显式根，每批仍256。作者先在独立 `Dispatchers.Default` 协程ready，协调者随后在IO协程启动GC，作者不会继承GC的Room事务上下文。GC通过现有guard后、第一条DELETE之前通知作者一次并继续执行；没有人工暂停、sleep、yield、latch等待或以作者到达为条件的GC延迟。

内部默认null `batchObserver` 新增四个事件：`guard-checked`、`before-delete`、`after-delete`、`transaction-returned`。直接复用本来就有的writerVersion读取，不增加SQL，不改变guard比较、根扫描、删除循环或事务边界。外部原二参/尾lambda构造保持。默认null不构造event、时钟、callback或诊断holder；`diagnosticObserved`是immutable nullable引用，仅开启诊断时分配一元素数组，避免捕获可变局部产生默认路径Ref。

诊断回调只读时钟、写预分配数组，首个before-delete额外 `CompletableDeferred.complete(Unit)` 通知。数组为64条batch event、32条原phase；计满失败，不静默丢弃。每条enabled event对象分配在callback开始计时前，`callbackNs`只覆盖callback自身工作，**不是完整观测开销**。其通知/调度、时钟、event对象分配均有开销，不能声称零扰动。

作者收到通知后执行原rename，保存watchdog仍5秒；通知/ready等待10秒，作者返回后collector await10秒。5秒只是挂起保护，不是延迟门槛。任何失败finally先取消/join子协程，再关另一Room和主Room、删本夹具；最后尝试导出已收集trial及summary，不隐去失败/timeout/未知提交。独立review后最小修复：trial记录构建/emit及summary构建/emit若再失败，保留原功能异常并把导出错误附为suppressed；无原异常时导出错误本身使JUnit失败。导出失败可能造成缺失marker，因此runner还必须判EVIDENCE_INCOMPLETE，不能以部分PASS marker替代JUnit失败。没有生产文件或真实笔记访问。

两连接的writer PRAGMA均在计时前读取，并断言1024/FULL(1)/WAL/synchronous2。完成后验证Committed、revision2→3、标题/正文、实际来源只剩1281且snapshot字节一致；首次删除可为256/512/768/1024/1280，不能要求恰好256。一次普通无observer收尾后总删1280。后续作者写入触发guard延期是安全合同；不要求整轮删除与保存持续并行。

## marker与封闭schema

`history-marker-schemas.json` 提供两个marker的标准JSON Schema形状：`properties/required/additionalProperties=false`，nullable为type数组，数组为`minItems/maxItems`，计数/枚举/长度均有界。顶层不是原生结果，明确 `STATIC_SOURCE_CONTRACT_NOT_NATIVE_EVIDENCE`。

- 每完成或失败一夹具导出 `HISTORY_GC_CAUSAL_TRIAL`；完整方法正好30条，sample原序0..29。出现失败后方法停止，不额外补满30。
- 方法finally导出 `HISTORY_GC_CAUSAL_SUMMARY` 一条。正常30，失败0..30条trial仍保留。
- 每条marker建议hard limit65536 UTF-8字节；畸形/未知marker、重复JSON key、非有限number、超界/缺记录均判 evidence incomplete，原raw保留，不能修补成PASS。
- trial中仅 `initialRevision/writerSqlite/authorWriterSqlite/savedRevision/finalRevision/contentVerified/retainedRevisions` 七字段可因前期失败不存在；其余总是导出（时间/结果允许null）。PASS必须七字段全部存在。
- phase允许 `after-roots/after-stats/after-scan/before-batch-commit/after-batch`。batch event允许上述四种。完整本夹具最多22 batch events、13 phases、5 delete intervals；实际预分配硬容量64/32，失败不裁剪。
- 保存结果包括 `NOT_STARTED/THREW/COMMITTED/Conflict/ReusedCommandId/OutcomeUnknown`。当前rename实际返回路径只产生Committed/Conflict/ReusedCommandId；THREW时committed=null（提交结果未知），NOT_STARTED为false。未知异常类只保留简单类名，不记录异常正文/内容。

所有Ns是同进程 `System.nanoTime()`，按signed int64保留，不当UTC。`writerEntryNs`和`pureSqliteLockWaitMs`固定null：未增加rename入口hook。`beforeRenameReceiptNs`在作者SQL后、写receipt前；`afterRenameTransactionNs`在原事务返回后。后者不等于物理fsync时间。GC的transaction-returned在withTransaction及maintenance锁返回后，writer可能更早已释放；不能要求作者start/entry一定晚于该返回事件。

每个batch event重复展示该批guard开始时的expected/observed数值，不是每个phase重新读SQL；`afterSequence`是前一已处理candidate序号，不是批序号。transaction-returned也会出现于guard不符的延期批，**不能单凭此事件称已DELETE或删除提交**。

## 解析关系与展示规则

完整PASS应核对（失败必须保留原始记录，不能用以下关系删掉记录）：

1. 方法连接模式一致；30个sample唯一且原序0..29；summary的recorded/passed/failed/committed/unknown/notStarted逐条复算。
2. 原phase按单调时间排序；firstRootScans=after-roots数，firstCommittedBatches=after-batch数。firstRootScans=finishRootScans=1，firstDeleted为256倍数且256..1280；firstDeleted/256=firstCommittedBatches；finishDeferred=false；firstDeleted+finishDeleted=1280。
3. 每个实际before-delete有相同afterSequence的guard-checked且匹配，随后恰好一个after-delete和transaction-returned；对应events按时间顺序。before-delete候选数之和=firstDeleted。最后允许额外guard mismatch及transaction-returned（defer）或候选0的guard及transaction-returned（完成）。guardMatched必须用两个分量同时相等复算，不假设data_version每次加1。
4. `deleteIntervals`由before-delete事件按afterSequence寻找after-delete/transaction-returned构成；失败时允许缺终点=null。saveStartedDuringDelete精确为 `deleteStartNs <= saveStartNs <= deleteEndNs`，不只是协程已launch。
5. 原暴露判据未变（它只证明提交前时点被保存调用覆盖，不能独自证明实际DELETE循环重叠）：从phase中筛选 `saveStartNs <= before-batch-commit.atNs <= saveEndNs`，原序填coveredBeforeBatchCommitNs；非空才deletionTransactionExposed=true。新增DELETE起止用于诊断，**不替换这条旧公式**。
6. 以原始调用边界复算 `saveMs=(saveEndNs-saveStartNs)/1e6`。作者ready应不晚于collectorStart；first before-delete.atNs <= notificationNs <= saveStartNs；通知完成不会保证实际覆盖DELETE或precommit。saveStart<=beforeRenameReceipt<=afterRenameTransaction<=saveEnd，最终内容和revision已按源断言核对。
7. allSaves保留全部非null saveMs及其sample索引，包含失败调用；exposedSaves为其中按原公式暴露者，原序不能排序导出。count/indices/samples完全对应。max在n>0时可展示；空集max=null。
8. 全方法完整30且functionalStatus=PASS时allSaves才有p50/p95。nearest rank `ceil(p*n)-1`：n30 p50索引14、p95索引28、max索引29。暴露子集只有完整运行且n>=20才显示p50/p95；不足20仍INSUFFICIENT_EXPOSURE，仅原序/计数/max。失败为FAILED_INCOMPLETE/RUN_INCOMPLETE且全部分位数null。latencyAcceptance固定NOT_EVALUATED，不人为失败、不调参凑20。
9. collector与finish起止可分别算首轮/收尾耗时；二者之和是两次GC调用总耗时，不含间隔/检查/fixture创建。finish在保存与首次GC均完成后启动；不把收尾删除列为并发暴露。

## 静态检查和剩余边界

`git diff --check`通过；两个既有测试SHA未变；源码中没有新sleep/latch/retry；事件禁用路径无诊断holder分配。没有运行Gradle、ADB、instrumentation或新性能测试。父侧编译需核验androidTest的internal构造可见性、Kotlin类型和运行时marker大小/完整性。纯schema/静态来源核对不能证明设备正确性、实际DELETE暴露率或保存延迟改善。

新诊断只能解释条件到达的端到端调用分布，不能分解纯SQLite锁等待、一般用户自然暴露概率或推出B→C1无回退；旧自然0/30和公共比较原样保留。要比较基线，需另行绑定同等hook位置及同设备配对，当前没有此类新结果。
