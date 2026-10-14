# 保存争用诊断：独立静态审查

结论：当前所列保存/索引钩子及六模式诊断未发现剩余确定性事务安全或编译接线阻断，可进入根节点统一编译。未运行Gradle、JVM、Android、字节码检查或任何新CI；此报告不宣称原生通过、默认路径实测零开销或已识别性能根因。LibrarySearchRepository这里只核索引钩子接线与notes监听保留，其余UDF诊断由另一独立范围审查。

## 已发现并关闭的问题

- 新测试最初引用尚不存在的 `indexBatchStepObserver`；当前已新增并传入 `LibraryTextIndex.build(onBatchStep=...)`，FULL可以收到索引细阶段。
- 初版finally在STAGES/END输出抛错时会跳过db.close/delete并覆盖原异常；当前取消并join后，输出、关闭、删除分别尝试，cleanup异常保留为suppressed，不替换原body失败。普通取消不会跳过NonCancellable join；若worker不退出，join不会假装成功再启动下一模式。
- `indexRowsAfterSaves`原在join后取值；当前在saves-end冻结，与清理期间进度区分。
- 新index step lambda初版捕获mutable firstBatch；当前先取immutable firstStep，默认null沿用旧回调，不引入该捕获盒子。
- save初版在withTransaction后统一执行后置回调，存在默认路径新增suspend状态机风险；当前公开save默认尾调saveTransaction，只有启用时走tracedSave。只确认源代码尾调用结构，不冒称已量测分配数。
- footer原returnedSaves仅数已验证Committed，现改committedSaves，保留lastAttemptedSample，失败标unknown，不把可能提交的异常当作未提交。

## 生产与事务边界

默认saveStageObserver=null只快照引用并走空分支，无新clock或event构造。原receipt重放、digest、head读取、revision CAS、revision/receipt写入顺序与Room事务边界保持。事务内诊断回调抛异常走原事务异常回滚；transaction-returned属于提交返回后的诊断，若这里失败只能记录提交未知，不能推断回滚。命令receipt仍提供原幂等语义。

LibraryTextIndex新增onBatchStep默认null，保留原onFirstBatchStep和最后onBatch尾lambda调用；当前所有旧callsite兼容。prepare/精确nullable source守卫/pending检查/64行与1MB预算/10ms yield/末尾取消检查均未改变。已有notes观察没有从生产observe或NoteRepository.observeNotes移除。原LibraryReadPerformanceTest及共同KvmLibraryPairProbe未被本补丁更改门槛或测量逻辑。

## 六模式能证明及不能证明的内容

- 固定IDLE、TRACKER、INDEX_ONLY、INDEX_TRACKER、READS_ONLY、FULL，两个block正反顺序，各30save，共12case/360save；显式opt-in，默认assume跳过，只是独立诊断，不能当正式验收或与旧无诊断probe性能混比。
- 同block固定seed、合成501本/20000页面原文；测量前规范合成时间并输出逻辑表行数/hash。应先核同block各模式fixture一致。不同模式readiness明确不同，反序只减轻顺序影响，不能使六种状态成为等价对照或把各差值相加。
- TRACKER仅Room notes invalidation tracking；不执行旧observeNotes全正文DAO投影。它不能单独量化旧fullnotes消费者的正文读取/映射成本。READS_ONLY仍含六表tracking；FULL还含index和不同调度诊断，模式差非独占组件成本。
- FULL仅等待首index commit后开始save，没有保证reader已进入source扫描。必须把read/index事件与30save区间关联；若实际没有reader重叠，只能说本次未暴露reader争用，不能排除reader影响。READS_ONLY虽等待首source check，也须检查随后窗口，不可只看模式名。
- waiting-writer→writer-acquired混合Room调度、事务排队、触发器同步及SQLite begin等成本；不是纯锁等待。head-read区间还含digest和receipt查找；transaction-returned还含提交及返回调度。阶段可以定位区间，不能直接分解为精确JNI/磁盘/锁份额。
- threadCpuNs是线程累计计数，只能对相同threadId端点作差；跨suspend时同线程仍可能运行其他工作，所以不是操作独占CPU。CPU时钟、队列事件、每候选scan Atomic计数有开启开销，不可将诊断数值当原probe性能。
- 每条save的index/source结束计数现紧接endNs采样，位于断言/JSON之前；仍是相邻而非原子同一时点，只作进度旁证，精确重叠需事件时间线，不能当每条save期间精确索引工作量。
- event上限在未冻结阶段调整为2048；overflow仍硬失败，保留droppedEvents，不静默丢事件拼完整结论；30samples、6mode、2block均未变。emit与清理位于计时外，没有临界区暂停门控。

## 所审位置

- `android/data-local/src/main/kotlin/org/inkweft/data/NoteRepository.kt:21`
- `android/data-local/src/main/kotlin/org/inkweft/data/NoteRepository.kt:25`
- `android/data-local/src/main/kotlin/org/inkweft/data/NoteRepository.kt:31`
- `android/data-local/src/main/kotlin/org/inkweft/data/LibraryTextIndex.kt:36`
- `android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchRepository.kt:27`
- `android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchRepository.kt:45`
- `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveCausalTest.kt:30`
- `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveCausalTest.kt:57`
- `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveCausalTest.kt:137`
- `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveCausalTest.kt:154`
- `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveCausalTest.kt:170`

## SHA-256绑定

| 文件 | SHA-256 |
|---|---|
| `android/data-local/src/main/kotlin/org/inkweft/data/NoteRepository.kt` | `e1570c26f61ba352f7a636d934e7acbff6f0b3250985f1e9caf0a55046432c9a` |
| `android/data-local/src/main/kotlin/org/inkweft/data/LibraryTextIndex.kt` | `34fac20efe18b5c8c88039b9303114569ca17e1b0f2c88be2b45fd201494b50b` |
| `android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchRepository.kt` | `e7f0c439bce5217849772d03695662992d8e6601853c016932d6ec53353de2e1` |
| `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibrarySaveCausalTest.kt` | `f2faf22c8eff0c5f37c981c389917e17214d86edc38535e68dd9b0ab69a1eb8e` |

只绑定上述读取快照；后续任何源码改动需要对应更新记录。

## 最后两处窄复核补充

首次host编译发现step被推断为Function<String, Unit?>，原静态审查未捕获此编译问题；原失败须保留。最终显式声明`val step: ((String) -> Unit)?`，保留immutable firstStep捕获与原回调顺序。上述表格已更新LibraryTextIndex及LibrarySaveCausalTest当前SHA；本次只复核这两处小补丁，未执行编译。LibrarySearchRepository表格仍绑定此前仅索引接线审查快照，其随后UDF/空CTE变更属另一审查范围，不将旧SHA冒称当前全文件SHA。
