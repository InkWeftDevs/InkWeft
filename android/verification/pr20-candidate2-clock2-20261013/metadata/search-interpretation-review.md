# Run 38433834125：搜索结果判读预案

本文件是原始结果到达前的只读源码／固定探针复核，不是本轮测试结果。未运行构建、设备或新测量；只写此 scratch 文件。生产、测试、既有证据和固定控制均未修改。

## 1. 实际身份与比较单位

| 本轮身份 | 固定源码 | 比较含义 |
|---|---|---|
| B | a128bb07e34cb82f00eeafc8e62a47bf7b95b128，加固定 writer factory | 原始搜索／分页实现的受控基线；不能称未修改的 a128 APK |
| C0 | ebb48481e7e995288c1844d43011398c513b4f4e | 上一候选；不是历史第四次运行中的 C0=7949011 |
| C1 | 45b4861ae23ac19eda3246fcd34cf3d2132dc1ee | 当前延后目录组装候选 |

本轮库生产路径的 C0→C1 差异仅为：将全目录 metadata SQL／Note 头／分类组装移到 hits 和 LIMIT page 之后、pending count 之前，并在目录对象分配前调用已有取消检查。SQL 本身、成功返回的目录范围、排序、正文匹配、快照边界、150ms 条件、真实失效版本和 mapLatest 取消语义没有改动。目录仍是全库目录，不是当前结果页的目录。

逐字节核对：两个候选的 LibrarySearchObservation、LibrarySearchDiagnostics、LibraryTextIndex、NoteDatabase、NoteRepository、PreservingOpenHelperFactory 相同。LibraryReadPerformanceTest 也相同，SHA256 `7dea89680c6dcf4723b95603796d634bd51088076b9d7ff2277e888b54e663e6`。库 Repository 的 C0 SHA 为 `bf3e0f7ca9c5875b47f5f0a6b8a6777a504e14a5f10e5c75f8d4b65f81052320`，C1 为 `744cda25180c3f8411b2816d5025b65c5b5ab122f5a0b83bb0597b379c935522`。

固定公共库探针从 e1a813a 提取，18281 字节，SHA256 `90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c`；在 e1a813a、ebb 和 45b 的保存副本一致。源码重建应把这一相同文件覆盖到三端，而不是使用各端任意同名测试。

四区组为 4101 B/C0/C1、4102 C1/B/C0、4103 C0/C1/B、4104 B/C1/C0。C1 对 B、C0 均先后各两次；B→C0 是 3/1 顺序不平衡的旁证。四个区组才是比较单位，不能把 30 次保存或 5 次 warm 作为独立重复，也不能与历史四轮合池。

本轮公共库方法号是 **32、34、36、38、40、42、44、46、48、50、52、54**，均关闭阶段诊断。本轮单独 C1 阶段诊断是 **57**；历史 run 38419007471 的方法 44 才是旧诊断。本轮方法 44 是 C0/seed4103 的公共库方法。

## 2. 结果有效的前提

原始 instrumentation 的精确方法、terminal code、单次 OK(1)、退出码、进程清理、源码与九 APK 回执、环境／host 记录和双清理必须通过。每个 seed 的三端实际 fixture 逻辑哈希、行数和 writer 设置必须相同；writer 仍为 page_size=1024、auto_vacuum=1、WAL、synchronous=2。缺失、超时或身份问题不能用部分指标升级成验收通过。

公共 fixture 是 500 本正文各 20020 UTF-16 字符的笔记、20000 页来源，加独立作者笔记；三端相同 seed 控制实际 ID／源内容。fixture 哈希已读取数据库，因此 cold 是首次未索引查询，不是 OS／文件缓存冷启动。B 预载完整正文，而两个候选只读 header，是预期产品实现差异，不能为“公平”把候选改回预载。

探针的计时、并发门槛和清理保持一致：direct 30 保存在首次实际 source check 后开始；observe 30 保存在真实首批 64 行提交后开始；之后等待保存、20000 行索引和 pending=0 的正确结果，cancel/join observe，再做原序 5 次 warm。公共探针从不安装 stageObserver；方法57的时钟、队列和批量日志开销不得混入公共比较。

## 3. 什么足以判定 observe p50 的原门槛已关闭

逐次核对 KVM_DIRECT_INTERVALS、KVM_INDEX_INTERVALS 与 LIBRARY_READ_EVIDENCE：两组各30项，ordinal 0..29，`elapsedMs=(endNs-startNs)/1e6`，全部返回 Committed；direct revision 应为2..31，paused保存推进到32，observe为33..62，并保留最终正文／revision断言。保留原序，排序副本计算 nearest-rank **p50=索引14、p95=28、max=29**；不要用探针保留的旧 `saveP50Ms`（索引15）代替重算。

原筛选规则不变：p50 相对10%／绝对1ms，p95 15%／5ms，max 25%／20ms；单对超带为 `delta > max(relativeBand*baseline, absoluteBand)`。四对齐全时，至少3对变慢且配对中位相对、绝对增量均超带为 REGRESSION_SIGNAL；有任一超带但不满足前项为 INCONCLUSIVE；其余才是 WITHIN_PROPOSED_BAND。完整性失败覆盖为 INCOMPLETE。

**只有本轮 B→C1 的 observe p50 在上述固定规则内，并且正确性／输入／执行有效，才能称“本轮原 observe p50 工程门槛内”。** C0→C1 改善只证明相对上一候选改善，不能关闭对B仍在的回退。p95、max、冷搜或索引吞吐改善也不能抵销 p50。四轮结果只支持该单设备／fixture 的工程筛选，不能声称生产中永无回退。

同时分别报告 p95、max、direct 保存、cold、allIndexRowsCommittedMs 和 searchIndexReadyMs 的原门槛。warm 不另加阈值。即使取消 metadata 的机制成立，只要 B→C1 p50 仍超带，回退仍未关闭。

## 4. 取消 metadata 的机制与性能必须分开证明

本轮方法18 `cancelledSourceReadSkipsDirectoryAssemblyAndNextReadKeepsClassification` 是确定性正确性证据：真实来源扫描中取消时 metadata 尚未组装，取消并join后仍未组装；下一次正常读取保留 active header、folder/tags/favorite/unfiled/trash。方法2仍验证并发作者提交时，稍后读取的目录／标题仍属于旧的同一快照；方法3及搜索／UI用例验证下一次刷新。此组通过不等于 p50 已改善。

方法57原始 LIBRARY_STAGE_DIAGNOSTICS 应按 `(readId, elapsedNs)` 重建每次读取，不按跨线程队列顺序猜因果。`originNs + elapsedNs` 与保存的 startNs/endNs 同属 nanoTime，可精确求区间重叠。要保留并分别统计：

- 真实失效数／sourceVersion、调度数、debounce取消、派发前取消、已进IO后取消、完整结果数；取消次数本身未必减少，因为本次没有改变失效或 mapLatest。
- 对已运行且被取消的读取，metadata-start/end 的有无、在 page-end 前取消的次数，以及最后已到达的真实阶段。对成功读取应有 page-end→metadata-start→metadata-end→pending 的新顺序。
- 每次保存原序窗口、Committed/revision，以及窗口内读取阶段／取消事件。异常或取消调用没有返回确认时 committed=null，不能当作“数据库肯定未提交”。
- 取消前的 scope、page-hits 或 page 阶段是否承担了更多工作。目录工作消失不自动等于总废弃工作、保存时延或 CPU 已下降。

充分的机制证据是：确定性方法18通过，且自然场景中确有早取消读取，这些读取没有进入 metadata，成功读取分类完整。若自然场景没有早取消暴露，只能称该次未充分观察，不能把0次目录浪费作为性能证明。

历史方法44的解释基准仅供提出假设：30次observe调度、27次read取消，其中21次进IO、19次未完成metadata；这21次IO运行到取消合计181.675ms墙钟，19次metadata-start→snapshot-closed合计86.236ms。新方法57与这份历史候选诊断不是同次配对，UUID／索引顺序／host状态／调度均可能不同。不能把两者相减当作本轮 C0→C1 的性能效应，也不能把墙钟相加当CPU或锁等待。

公共探针**没有**逐读取阶段／取消计数，因此本轮没有 C0 对应的 stage 样本；它足以验收保存结果，但无法独立量化“节省了多少 metadata CPU”。阶段诊断与公共保存结果相互支持，不能互相替代。

## 5. 索引／保存重叠的正确含义

两候选 LibraryTextIndex 完全相同：普通读连接读取 pending/source、在 writer 外准备terms；每批最多64条／约1MB原始UTF-16预算（首条可超预算），writer 内核对 pending 与精确可空原文，再原子更新 docs/FTS/pending，提交后回调、yield10ms。全 stale 批重试。metadata移动没有缩小索引批次、改索引事务或增加作者暂停。

公共 KVM_INDEX_INTERVALS 应有313次递增提交，累计64、128…19968、20000。onBatch 时间是在 withTransaction 返回后的观察点，不是 writer 获锁时间。保存窗口覆盖一个提交点，或计数64→128，只证明提交发生在窗口内，不能直接认定整个保存延迟都是等待该事务；没有覆盖提交点也不能排除短暂无回调的writer活动。

要分列：与整个索引生命周期重叠的保存数、每次窗口内 batch提交数、sourceChecks前后增量、indexRows前后增量。sourceChecks是跨重复／取消读取的累计匹配回调，不是唯一来源数；目录推迟后命中扫描更早，sourceChecks增加本身并不表示回退。

派生吞吐保留明确口径：总有效速率 `20000/(allIndexRowsCommittedMs/1000)`；首批到末批增量速率 `19936/((lastCommittedNs-firstCommittedNs)/1e9)`。两者包括准备、yield、调度、并发读写与既有日志；不是纯FTS速度。searchIndexReadyMs 还包括等待正确 pending=0 结果及保存完成；direct 联合完成时间用 `max(coldEndNs,lastSaveEndNs)-coldStartNs`。不能只用总索引完成改善掩盖保存分布回退。

B 的direct读取占用Room事务，只有少数保存可能实际覆盖读取，其他样本在读完后运行；候选可有30次重叠。这是暴露差异，不是修改公共样本集合的理由，也不能把两组低p50都称作持续并发保存能力。

## 6. warm 与读取规模的边界

warm发生在保存完成、索引全部提交、observe cancel/join后；150ms观察调度和重启次数不在这个计时内。本次metadata延后仍会在每次成功warm返回前完整执行，并未减少warm的匹配 SQL 工作。

历史方法44中5次warm的page阶段411.849–429.121ms，占请求到read-end的96.69%–97.06%；其余阶段合计12.92–14.08ms。page SQL包括当前正文取得、Kotlin Unicode ignoreCase UDF、候选过滤、排序／LIMIT及预览构造，不能把全部成本单独归于某一项。当前索引只索引page/card来源；最终page SQL仍可能对约500本长笔记执行正文匹配。源码支持这个工作路径，但没有SQLite pager／UDF耗时细分能证明溢出页读取、磁盘或锁是主因。

方法57若再次显示page阶段主导，只能说新候选的成功warm仍受该粗阶段支配。要判断warm是否较慢，应使用本轮12次公共调用各自原序5项中位、按seed比较45b对ebb及B；没有独立warm验收阈值，不标“超／通过冷搜阈值”。上一完整运行的B→ebb warm是3/4变慢（+42.116、+14.812、−12.876、+34.101ms），不是4/4，且这些旧值不能替代新配对。

新结果仍须核对 header正文0、目录正文0、pageSize60时61个lookahead预览且最多61×600字符、展示60项、按需全文与原文一致。这证明游标／返回值规模；不证明底层只访问61条原文或只读若干SQLite页。覆盖索引、共享连接、ASCII特化或语义不同的lower/LIKE均不是本次候选内容，也不能仅凭这组诊断追加实施。

## 7. 原始数据回来后的结论模板

先报告本轮完整性和方法18等正确性；再按实际commit列出 B→45b、ebb→45b 的四对observe p50与原分类，分别列 p95/max、冷搜／索引；另列5项warm中位的描述性变化。最后用方法57说明是否自然观察到“早取消未进入metadata”，并明确没有本轮C0阶段配对或writer锁计时。

允许的结论是“结构机制被正确性／阶段证据验证，且本轮公共p50门槛内／仍有回退／证据不足”。不允许从更少metadata事件、更多来源扫描、一次索引提交或单次诊断时延直接宣告原p50回退关闭。

复核输入：仓库 `android/verification/pr20-candidate2-readiness-20261013/{source-spec.json,method-plan.json,original-protocol.json,PLAN.md,analyze.py}`，三个固定Git对象及公共探针；历史参照 `/workspace/scratch/pr20-triple-run-38419007471/evidence/method-44-C1-records.json` 与对应 analysis。本文不读取正在运行设备，不宣称本轮尚未返回的任何方法通过。
