# 本轮 62 方法运行的 GC 独立核对

**GC 功能记录全部通过，删除竞争暴露仍不足：同 Room 为 0/30，另一 Room 为 1/30。** 唯一暴露样本是另一 Room 的 sample9（第 10 次）：真实保存返回 `Committed`，耗时 **84.060999 ms**；首轮 GC 实际提交了 5 批、删除 1280 行。两组均低于原定至少 20 个暴露样本的要求，保持 `INSUFFICIENT_EXPOSURE`、`latencyAcceptance=NOT_EVALUATED`，竞争 p50/p95 为 null。不能将 62 方法执行完整解释为 GC 竞争尾延迟验收通过。

本报告只分析 run `38520196523`、job `115707901766` 的 C1 方法 56/57，各 30 个独立合成夹具。source `cb7e14218ac83aa8cf6b9d7906051a10da1a6547`，readiness `d70fcb23afecb6f80e8fc716a5145ecf851dd68e`，workflow `115734b0ed9aab07a1100087ce76978c0d829e0f`。官方记录为成功，job span 2662 秒；冻结提取器给出 `COMPLETE_SCOPED_MATCH_LOG_EVIDENCE`、issues=[]。本次未修改任何输入、生产代码或探针，没有运行设备、构建、网络或新测试。

## 本轮结果

| 指标（各模式 30 个夹具） | 同 Room：方法56 | 另一 Room：方法57 |
| --- | ---: | ---: |
| PASS / Committed / 内容验证 | 30 / 30 / 30 | 30 / 30 / 30 |
| 失败 / 提交未知 | 0 / 0 | 0 / 0 |
| 首轮 GC 调用与保存调用相交 | 30 | 30 |
| 原定义的删除事务时点暴露 | **0** | **1** |
| 保存内覆盖的 before-batch-commit 时点数 | 0 | 5，均为 sample9 |
| 首轮实际提交批数 | 0 | 5 |
| 首轮删除行数 | 0 | 1280 |
| 首轮 defer 次数 | 30 | 29 |
| 保存后收尾删除行数 | 38400 | 37120 |
| 两次调用合计删除行数 | 38400 | 38400 |
| 首轮根扫描 / 收尾根扫描 | 30 / 30 | 30 / 30 |
| 首轮 GC 总耗时 | 576.216202 ms | 627.612375 ms |
| 收尾 GC 总耗时 | 2007.209923 ms | 1994.869167 ms |
| 首轮＋收尾 GC 总耗时 | **2583.426125 ms** | **2622.481542 ms** |
| 暴露子集 p50 / p95 | null / null | null / null |
| 暴露子集观测 max | null | 84.060999 ms（仅1条） |

GC 耗时以原纳秒边界先作整数求和，再换算毫秒，是 30 个夹具的调用耗时总和；不含夹具建立、两次调用之间的空隙、最终验证或关闭数据库，也不是单次中位数、所有 SQL 数量或锁等待时间。两次扫描合计每夹具 2 次；保存后的收尾不能算并发删除成本。另一 Room 唯一首轮删完的 sample9，仍按原探针执行了零删除的收尾调用，其一次扫描和时间也完整计入。

每条均为 1281 个来源版本、1280 个可删版本、0 个显式来源根、保留最新版本 1281。60 条全部验证 revision 2→3、最终标题/正文及保留 snapshot 字节，saveOutcome 均为 COMMITTED，failure/collectorFailure 均为 null。两个实际 writer 均记录 1024/FULL(1)/WAL/synchronous2。探针没有暂停、sleep、凑曝光重试或删掉慢值；5 秒 watchdog 是功能防挂起边界，不是性能门槛。

原 summary 的两个 `allSaves` 完整 30 条原序数组、p50/p95/max 已逐项重算一致，保存在 JSON 的 `all_saves_summary_verified_not_competition` 中。本报告不把整体描述分布当作删除竞争分布展示。原 nearest-rank 定义为 30 条时 sorted[14]/sorted[28]/sorted[29]；暴露子集只有 0/1 条，不能计算或解释其 p50/p95。单条 84.060999 ms 仅作为原观测值及最大值保留。

## “实际 DELETE 暴露”的准确边界

原自然探针仅记录 phase 回调，暴露公式是 `saveStartNs <= before-batch-commit <= saveEndNs`；它没有启用新的 before-delete/after-delete batchObserver。该时点在真实 DELETE 循环之后、事务提交之前，因此“时点在保存调用内”不能单独给出 DELETE 精确开始/结束或保存是否开始于 DELETE。

同 Room 全部首轮 0 删除；另一 Room 除 sample9 外也全部首轮 0 删除。它们没有实际首轮 DELETE 暴露，虽然清理调用本身均与保存调用有重叠。所有收尾调用都始于保存返回之后。

另一 Room sample9 除五个提交前时点外，还有五个对应的 `after-batch`，首轮返回实际删 1280、提交 5 批。结合已验源码的串行批次顺序，可以进一步夹定：**第 2–5 批的完整 DELETE 循环分别在前一批 after-batch 与本批 before-batch-commit 之间，这四段都完全落在保存调用内。** 因此至少有 1/30 个样本能以源码顺序证明实际 DELETE 在保存请求尚未返回期间发生，而不是仅有抽象 GC 调用相交。第 1 批没有已记录的精确 DELETE 起点；四个夹定区间也包含选取候选、获取事务与 guard 开销，不能用其宽度当 DELETE 耗时。没有精确 DELETE interval 测量值。

该样本相对保存调用开始的原始时间如下：

| 事件 | ms |
| --- | ---: |
| 第1批 before-batch-commit | 7.947323 |
| 第5批 before-batch-commit | 54.288972 |
| 第5批 after-batch | 56.277436 |
| 首轮 collect 返回 | 59.222817 |
| 作者 BEFORE_RENAME_RECEIPT | 82.531005 |
| 保存调用返回 | 84.060999 |

作者请求可能跨多个短 GC 事务仍未返回；本轮这一条正好呈现该情况。但从 GC 返回到作者 receipt 前的约 23.308 ms 不能直接命名为 SQLite 锁等待。原探针没有作者进入事务的时间、SQLite busy wait 或调度追踪，不能区分 Room 排队、线程调度、SQLite 竞争/重试及作者 SQL/提交耗时，也不能凭一个样本认定饥饿或 B→C 回退。

## 守卫与安全解释

已核源码在收集前保存 writer 版本，先用私有快照构建候选，再以 256 条短事务逐批比较 `data_version` 与 `total_changes()+pending invalidation`。只有版本仍相等才删除；发现不等则返回 `collectionDeferred=true`。原自然探针在 `after-scan` 非阻塞通知作者；这里 59 次观察为保存调用先于首轮清理返回完成、首轮删0并延期，符合保守守卫合同。

本轮没有输出 guard 两个分量，不能分别量化本次同连接 changes 或异连接 data_version 的增量。sample9 的首轮未 defer、删完 1280，且作者 receipt 在 GC 返回之后；记录没有显示 guard 忽略了已观察到的变化。不能将“保存调用已开始”当作“作者已提交”，因此连续几个删除批次也不自动构成守卫失效。

60 次保存与最新来源内容断言均通过，但这是 rename、0 显式来源根的夹具；它不能替代“删除后提交前新增活根300”、损坏引用、取消、崩溃恢复等专门正确性测试。本报告只为方法56/57的记录作结论。

## 与历史证据的区分

- 历史 [纯 GC 配对审查](/workspace/scratch/pr20-closure-evidence/final-paired-history-review.md) 已在同场景基线/候选原生配对中观测根扫描 17→1、无竞争清理总成本下降。那是 2000 根/4096 垃圾、TCG/SwiftShader 环境的历史一次配对，其六个保存统计量同时上升。本轮的 0 显式根/1280 垃圾和运行环境不同，不能拿本轮毫秒值重算那次改善率或覆盖其保存回退。
- 历史 [条件到达诊断](/workspace/scratch/pr20-causal-execution-20261014/gc-analysis/gc-causal-review.md) 的 run38514139119 在 after-guard/before-delete 通知预启动作者，另记录 DELETE 窗口，两模式均获得 30/30 暴露。它允许解释该次条件分布；到达方式、探针和运行不同，不能与本轮的 0/30、1/30 合池，更不能增加本轮暴露分母来凑20。历史条件诊断本身也没有 B/C 等价配对。
- 本轮比先前自然两组0/30多出一个有实际删除证据的异 Room 样本，但不足以建立稳定竞争分布。它是 C1 单端诊断，不属于方法32–55的24公共比较；没有本轮等价 B/C0 GC 竞争组，不能声称 B→C1 持续整轮删除＋保存竞争无回退。

## 仍开放的最小范围

竞争尾延迟仍未闭合，当前没有足够证据支持某个最小生产修复；不应为凑曝光缩批、插入 sleep、跳过 guard 或降低门槛。若后续获授权继续定位，最小有效方向是在既有独立条件到达场景下取得等价基线对照，并只增加默认关闭的作者事务入口时间点，区分取得 writer 前累计延迟与之后 SQL/提交。该诊断仍不能取代自然到达验收，也不能直接命名为纯锁等待。本次没有实现或运行追加测量。

## 原始记录和可复算性

已从官方 `job-decoded.log` 独立读取两方法的全部 CONTEXT、31 条 RECORD、END、METHOD：逐条核对原 JSON 字节数/SHA/序号，重构 footer 和紧凑 records，确认与 extracted 文件逐字节相同。来源哈希：

- 官方 decoded log：`d1634bbeb118100737e2c9d6d6111fca972f2fb05999b5604ff34f0d0e7c9f62`。
- 方法56 records：`aefe54dcc3cdbac19d474b0cc4025e3b089eba800007549ff36db837c30f41ed`。
- 方法57 records：`c898fbbea2f6229f31bf1aada97c76fcdcda4a9c6a42aea67a5b13ddc4855757`。
- 两方法 test 源：`7e98a67eec4f9242fc4c18e6bf662ec1d86c346796ebc28ab0971be1a9f0224f`；HistoryStorage 源：`b96890478129bbb7b0bbb538c3cf8d5f2e97f2b8b5e2e34d8057c855c9ec6ca9`，均与 C1 冻结 manifest 对应文件相符。

本目录 `gc-review.json` 保留完整原序60样本、逐项结果、每夹具两次成本、暴露子集、原 phase/时间、源码绑定和输入文件摘要。`python -B recompute.py` 只读现有输入并重建该 JSON，断言失败会停止；不调用网络、Android 或构建。`recompute-result.json` 是该次复算的精简输出。

安装 APK、环境、raw instrumentation SHA 等仍是官方 compact 回执中的绑定声明，不能凭本报告宣称已独立重验 APK/native 库、设备安装字节、原 instrumentation 全文或 ACL。正式状态是 `RECORDS_CONSISTENT_INSUFFICIENT_EXPOSURE_NOT_ACCEPTANCE`。
