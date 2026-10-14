# Run 38441089594：GC 独立核对

方法 8–16 的正确性检查 **9/9 PASS**；方法 55/56 的自然诊断各 **30/30 功能通过、30/30 Committed**，但两种连接都为 **0/30 删除事务暴露，INSUFFICIENT_EXPOSURE**。本报告不包含公共比较方法 31–54，不合并旧 run，也不据此判断 B→C1 有无回退。

## 自然诊断：全部样本保留

每个样本使用新的合成数据库：1281 个来源版本、1280 个可删旧版本、0 个显式根、1 个最新保留版本；在首轮 `after-scan` 通知后启动一次真实 rename。没有暂停、sleep、挑选或为暴露而重试。

| 指标 | 55：同 Room | 56：另一 Room |
|---|---:|---:|
| 样本 / Committed / 内容验证 | 30 / 30 / 30 | 30 / 30 / 30 |
| 失败 / 提交结果未知 | 0 / 0 | 0 / 0 |
| 实际删除事务暴露 | **0/30** | **0/30** |
| 暴露组 p50 / p95 / max | null / null / null | null / null / null |
| 全部保存 p50 / p95 / max，ms | 3.071672 / 4.852563 / 7.651819 | 2.945655 / 4.375943 / 4.966373 |
| 首轮延期次数 / 首轮已提交删除批 | 30 / 0 | 30 / 0 |
| 每样本首轮删除 / 完成轮删除 | 0 / 1280 | 0 / 1280 |
| 30 样本首轮删除合计 / 完成轮合计 | 0 / 38400 | 0 / 38400 |
| 首轮根扫描 / 完成轮根扫描 / 总计 | 30 / 30 / 60 | 30 / 30 / 60 |
| 首轮 collect 调用耗时合计，ms | 421.984351 | 440.291067 |
| 完成轮 collect 调用耗时合计，ms | 1310.676733 | 1317.184065 |
| 两次 collect 调用耗时之和，ms | 1732.661084 | 1757.475132 |
| 首轮开始至完成轮结束跨度合计，ms | 1740.899724 | 1766.258899 |
| 完整方法 JUnit / host wrapper，s | 5.349 / 5.787015 | 5.623 / 6.033604 |

分位数按已冻结的 nearest-rank 定义重算：30 条时 p50=`sorted[14]`、p95=`sorted[28]`、max=`sorted[29]`，不是两个中间值的平均数。5 秒 watchdog 保持原样，仅防止挂起；`latencyAcceptance=NOT_EVALUATED`，不是 5 秒性能验收。JSON 保存两组完整的 30 条原序 `saveSamplesMs`、所有原始 trial、原 summary 及各调用耗时原序数组。

所有 60 条样本都有 `saveOutcome=COMMITTED`、revision 2→3、最终标题和原正文验证、精确保留来源 `[1281]` 及 snapshot 字节验证。失败、清理异常和 collector 异常字段均为空。完成轮不延期由每条通过用例中的 `assertFalse(finalReport.collectionDeferred)` 保证；raw trial 本身没有 `finishDeferred` 字段，不应把它说成独立记录字段。

## 暴露定义与时间边界

只有保存调用区间 `saveStartNs <= t <= saveEndNs` 包含 `before-batch-commit` 时间点，才记为删除暴露。源码确认该 hook 位于非空批次实际 DELETE 之后、GC 写事务提交之前。这是时间点落入调用区间的定义，不是锁等待计时。

本次每条首轮 phase 都严格只有 `after-roots → after-stats → after-scan`，没有任何 `before-batch-commit` 或 `after-batch`。保存完整区间位于 `after-scan` 之后、首轮结束之前；全部真实删除发生在保存和首轮 collect 都结束后启动的完成轮。因此这里并非仅“漏捕到删除 hook”：首轮实际删除就是 0，不能将上表保存分位数解释为实际删除竞争下的延迟。`firstDeferred=true` 在此源码中对应 guard 不匹配，但日志没有逐次 guard 值，不能拆解具体 `data_version`/`total_changes` 差值。

首轮耗时包含 roots/stats 扫描、guard 与清理；完成轮耗时也包含扫描和删除。两轮调用相加不含协调间隙；首轮开始至完成轮结束的跨度含间隙，二者均不含夹具生成、末尾内容验证和删除夹具。保存已与首轮重叠，不再加到“总耗时”。根扫描数是 `after-roots` 次数，不是所有 SQL 查询次数；此自然夹具显式根为 0，不能证明大量活根枚举成本。`beforeRenameReceiptNs` 位于 rename 内部、receipt 之前，不是写事务入口或物理提交时间。

## 正确性方法 8–16

| 方法 | 原始 JUnit 时间，s | 通过的关键断言 |
|---|---:|---|
| 8 | 0.572 | Room 实际 observer + 每批显式 refresh；1 次 roots、1 次 stats、7 批；删 1280 sources + 257 previews，最新来源保留，最终 reclaimableBytes=0。 |
| 9 | 0.537 | 同 Room 在首批提交后新增活根 300；首轮删 256 后延期，随后仅删 1023，300/1281 保留。 |
| 10 | 0.548 | 另一 Room 的同一新增根竞争；首轮 256 后延期，随后仅删 1023，300/1281 保留。 |
| 11 | 0.573 | 同 Room 作者请求在真实 256 条 DELETE 后、提交前发起；持锁时作者未进入；释放后作者提交，GC 延期，完成轮 1023，精确保留 [300,1281]。 |
| 12 | 0.575 | 另一 Room 的同一提交前竞争；验证作者 Committed、revision 2→3、标题/正文、根300 snapshot 与精确保留集合。 |
| 13 | 0.522 | 重复根跨满 128 条插入批及尾批；精确保留 1..257 和 1281，删 1023。 |
| 14 | 0.482 | 满根批后遇损坏 transform；异常发生于删除前，1281 全保留。 |
| 15 | 0.566 | 第二删除批提交前取消；第二批回滚，第一批256已删；重开后删1024，只留1281。 |
| 16 | 0.463 | Room guard log 缺失时抛 SQLException，1281 全保留。 |

方法 11/12 在持有第一批写事务时，直接查询确认前 256 行已被 DELETE、事务内余 1025 行，随后从协调协程发起作者请求，并确认作者尚未进入该写事务。放闸后作者在原 2 秒等待内返回 Committed，并新增活根 300；第二个人工闸门确保作者提交先于下一批 guard 检查。两例均原样通过 10 秒阶段等待和 2 秒作者等待，证据明确为 `artificialGates=true`、`performanceMeasurement=false`。这证明特定竞争顺序下的并发安全，不提供自然 p50/p95 或持续整轮删除和保存并行证据。

## 来源和一致性

- 冻结 C1：`45b4861ae23ac19eda3246fcd34cf3d2132dc1ee`；canonical source manifest：`7592c7990537bde681d0179539f58e1cd79822695bfcfd4b19882d9e1f738e90`。
- 从本地固定 Git 对象重新计算 HistoryStorage、HistoryCollectionTest、HistoryDeletionExposureTest 的文件大小和 SHA-256，均匹配本次 frozen manifest，且三个文件都与 C0 `ebb4848` 字节一致。这里只确认文件未变，不由此推断跨版本性能。
- 11 个目标方法的 raw JUnit、summary、XML 选择器/通过数一致；原日志哈希匹配 summary 和上传 manifest。44 个选定证据文件的大小和原始/上传 SHA-256 全部匹配。方法 55/56 的 raw marker JSON、`*-records.json`、固定 `analysis/candidate-diagnostics-raw.json` 逐项相等；全部样本索引、数组顺序、纳秒区间、暴露点、分位数及保存状态已独立重算。
- `apk-C1.json` 绑定相同源 manifest；correctness/diagnostics 两组安装记录均成功。APK 哈希来自已归档收据，本核对没有重新构建或读取设备中已安装的二进制。
- 环境为同一 KVM 加速的 Android Emulator 37.2.12 / API 35 AOSP ATD x86_64，2 核、2048 MiB、SwiftShader；C1 两阶段的四份 before/after 环境记录一致。全部 60 个 trial 的 GC writer 和作者 writer 都记录并断言 `1024/FULL(1)/wal/synchronous=2`。这是原生 Android 模拟器上的合成 debug 负载，不是物理设备性能。

## 仍缺的证据与最小下一步

仍缺每种连接至少 20 条自然、无闸门、确实覆盖实际删除事务的完整保存样本。若要声称纯写锁等待/锁持有成本，还缺作者写事务入口和批次提交边界；若要判断 B→C1 GC 回退，还缺另行定义、三方可共用的配对 GC 负载。这两项自然方法本身不提供该比较。

当前结果保留为 INSUFFICIENT_EXPOSURE，不修改原 30 条负载、门槛或暴露规则。最小可解释下一步是先冻结一个独立命名的、仅增加非阻塞时间线记录的诊断方案和固定停止规则：复用现有 `after-scan`、`before-batch-commit`、`after-batch`、`BEFORE_RENAME_RECEIPT` 定位调度顺序；若需要纯写锁等待结论，再单独补充相应事务入口证据。任何新的代表性负载都必须事先定义并独立标识，不能覆盖本次结果、反复运行直到足量、挑选旧样本或绕过保守 defer 合同。连续作者提交本来就可令剩余候选延期，不能要求它同时证明整轮持续删除与保存并行。

本次只读取证据和固定源码并生成本目录报告；未修改生产/测试源码，未执行 Gradle、ADB、设备测试或 GitHub 操作。完整可复核数据见 [gc-analysis.json](gc-analysis.json)；原记录见 [方法55](../raw/method-55-C1-instrumentation.log)、[方法56](../raw/method-56-C1-instrumentation.log) 和 [运行 summary](../raw/summary.json)。
