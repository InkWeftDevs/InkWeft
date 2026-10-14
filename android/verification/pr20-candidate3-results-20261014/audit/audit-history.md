# Candidate3 原始工件：History/GC 独立审计

结论：本轮 History 安全正确性方法8–16 **9/9 PASS**；自然GC方法56/57各 **30/30 Committed、内容与revision验证通过**。但两连接仍均 **0/30实际删除事务暴露，INSUFFICIENT_EXPOSURE**；原阈值20保持。未发现新的确定性GC正确性缺陷，真实删除竞争下的延迟证据仍未闭合。

范围只含run `38498392929`、C1 `d66b60d6cf9587f646acb76dd8a00910b73a6d70` 的上述11个方法。不合并或比较前45b运行样本，不读取公共32–55及stage58的性能值，不据此推断B/C0/C1回退或改善。

## 来源核验

已重新哈希合法取得的原始ZIP：772054字节，SHA-256 `79c53a73e262a22205519f5a994e10e6f2c75ca396a39bbd251d5bbd6b17993c`。与已有完整审计回执一致；原ZIP有294个不重复条目。本审计另外把44个选定证据文件以及manifest/packaging共46个文件与解包raw逐字节比较，全部相同。已有完整verifier报告58 PASS、runtime issues/pending均空。

11个方法的原instrumentation、派生records、JUnit XML、summary选择器/结果/哈希一致；56/57的原始marker与冻结分析器保存的candidate diagnostics逐条一致。所有60条样本顺序、纳秒区间、暴露点、保存分位数及扫描/删除计数重新计算。完整原序数组和每条raw trial保存在 [audit-history.json](audit-history.json)。

从固定Git对象重算HistoryStorage、HistoryCollectionTest、HistoryDeletionExposureTest的大小/SHA，与本轮frozen C1 manifest匹配；三文件均与当前C0=45b字节相同。这是源码身份比较，没有引入45b测量数据。GC代码SHA为 `07046d5d7653723153744cff81ca63430a778112b49c31d16bd6e7535f856677`。

## 本轮自然诊断

每组30个全新合成数据库，每库1281个来源版本、1280个可删旧版本、0个显式根、1个最新保留版本；每库一次真实rename。作者在首轮 `after-scan` 通知后启动，没有等待型GC hook、sleep或为获得曝光而重试。

| 指标 | 56：同Room | 57：另一Room |
|---|---:|---:|
| 记录 / Committed / 内容验证 | 30 / 30 / 30 | 30 / 30 / 30 |
| 失败 / 提交结果未知 | 0 / 0 | 0 / 0 |
| 实际删除事务暴露 | **0/30** | **0/30** |
| 暴露组p50 / p95 / max | null / null / null | null / null / null |
| 全部rename p50 / p95 / max，ms | 3.043354 / 7.047771 / 7.266983 | 3.090302 / 6.850215 / 8.316213 |
| 首轮延期次数 / 已提交删除批 | 30 / 0 | 30 / 0 |
| 每样本首轮删除 / 完成轮删除 | 0 / 1280 | 0 / 1280 |
| 30样本总删除数 | 38400 | 38400 |
| 首轮roots扫描 / 完成轮roots扫描 | 30 / 30 | 30 / 30 |
| 首轮collect总耗时，ms | 431.113079 | 450.526104 |
| 完成轮collect总耗时，ms | 1296.726774 | 1350.123973 |
| 两轮collect调用耗时之和，ms | 1727.839853 | 1800.650077 |
| 首轮开始至完成轮结束跨度合计，ms | 1736.411853 | 1809.116111 |
| 完整方法JUnit / host wrapper，s | 5.715 / 6.132879 | 6.132 / 6.477279 |

分位数严格使用原nearest-rank定义：n=30时p50=`sorted[14]`、p95=`sorted[28]`、max=`sorted[29]`，不是中间两值平均。原5秒watchdog未变，仅作挂起保护；`latencyAcceptance=NOT_EVALUATED`，不是性能通过门槛。未剔除较慢样本。

全部60次保存均明确返回Committed，revision从2到3；最终标题、原正文、最新来源snapshot字节与精确保留集合 `[1281]` 通过。失败、collector异常和清理异常字段均为空。完成轮未延期由每条通过用例的 `assertFalse(finalReport.collectionDeferred)` 验证；raw字段没有另行记录finishDeferred。

两阶段的4份before/after设备记录相同；每条trial的GC writer与作者writer都记录并断言 `page_size=1024 / auto_vacuum=FULL(1) / WAL / synchronous=2`。结果来自同一API35 x86_64 KVM模拟器的合成debug负载，不是物理设备性能。

## 暴露及耗时解释

暴露原定义是保存调用区间覆盖 `before-batch-commit` 时间点：`saveStartNs <= phaseNs <= saveEndNs`。源码确认此hook位于非空批次真实DELETE之后、写事务提交之前。它证明时间点覆盖，不直接测SQL锁等待。

本轮60次首轮phase全部只有 `after-roots → after-stats → after-scan`，无任何 `before-batch-commit` 或 `after-batch`。每次保存完整区间都在after-scan之后、首轮collect结束之前；首轮确实删除0条并延期。每次实际1280条删除均在保存与首轮结束后启动的完成轮。因此表中保存分位数描述“首轮扫描/guard期间的rename调用”，不能当实际DELETE竞争延迟；也不能称整个GC与保存无交叠。

首轮和完成轮调用时间都包含各自的根/占用扫描及必要清理，不是纯DELETE耗时。两轮之和不含调用间隙；首轮开始至完成轮结束的跨度含间隙，但不含夹具生成、最后内容验证/删库。保存与首轮重叠，不再把保存时长加进GC总时间。roots计数是after-roots回调数，不是全部SQL SELECT数；自然夹具显式根为0，不能推断大量活根的扫描成本。

## 并发守卫及安全用例

源码先记录writer guard，再在独立只读快照中建立私有TEMP根和候选；快照结束后才逐批删除。每批最多256条，在短Room写事务内比较 `data_version` 与 `total_changes()+Room invalidated行数`。本批删除后在同一连接更新expected值；Room的invalidation重置由pending计数抵消，不猜测data_version增量。作者提交改变guard时，下一批在DELETE前延期；缺guard日志/引用解码失败则失败关闭。取消回滚当前批，保留已提交批。

| 方法 | 原JUnit，s | 原断言通过的范围 |
|---|---:|---|
| 8 | 0.616 | 1次roots、1次stats跨7批；删除1280来源+257预览；真实observer与逐批refresh有通知，最新来源保留、最终可回收量0。 |
| 9 | 0.577 | 同Room在首批提交后新增活根300：先删256再延期，后续仅删1023，300和1281保留。 |
| 10 | 0.583 | 另一Room新增活根竞争，同样256→延期→1023。 |
| 11 | 0.643 | 同Room作者请求发于真实DELETE后、提交前；持锁时不能进入；放闸提交后保护根300，最终精确保留[300,1281]。 |
| 12 | 0.635 | 另一Room同样的提交前竞争；Committed、revision2→3、标题/正文、来源snapshot与最终集合验证。 |
| 13 | 0.552 | 重复根跨满128条插入批和尾批；精确保留1..257及1281，删除1023。 |
| 14 | 0.528 | 满根批后遇损坏transform，异常在删除前发生，1281全保留。 |
| 15 | 0.597 | 第二删除批提交前取消，第二批回滚；重开后删除1024，只留最新1281。 |
| 16 | 0.538 | Room guard log缺失时抛SQLException，1281全保留。 |

方法11/12在写事务内直接查询已执行DELETE的效果：前256条为0、余1025条。作者从独立协调协程发起，随后真实提交rename和新活根300；第一轮报告删256/延期，完成轮删1023。原10秒阶段等待与2秒作者等待及所有断言保持。它们明确 `artificialGates=true, performanceMeasurement=false`，不能用人工持锁时间补自然延迟样本。

这些用例覆盖本次列明的并发、observer、损坏、取消/重开情形；不声称穷尽所有数据库历史。自然日志没有逐次guard值、事务入口/提交时间，无法把保存耗时拆成锁等待与执行/提交成本。

## 剩余实质问题与最小后续

当前没有证据要求继续改GC产品逻辑。实质缺口仍是两个连接模式的真实删除竞争延迟：各0/30，低于原20；高根数量和持续提交下的长期回收/公平性也不在本小负载范围内。保守guard在持续作者提交后延期剩余候选是既定安全合同，不能要求它同时持续整轮删除。

不建议盲目重跑同一个after-scan竞争来凑曝光，也不能合池旧45b数据或降低阈值。若必须闭合DELETE竞争延迟，最小可解释后续是**事先冻结一个独立命名的诊断场景**：在批次guard成功、实际DELETE循环之前增加一次只通知不等待的起点，由协调协程启动作者；GC hook不等待、不sleep、不人为延长事务。当前hook没有这个起点，因此这是需另行审查的后续仪表化建议，本次没有修改。

该独立场景保留1281版本/256批、每连接30次、原5秒watchdog、Committed/revision/内容/最终保留验证及原“覆盖before-batch-commit”暴露规则；全部样本原样记录，仍不足20就继续报告不足。由于作者请求触发时机变化，必须独立标识，不能覆盖或冒充当前自然负载结果。人工闸门正确性样本始终排除在性能分布外。

本次仅读取原ZIP、raw日志、冻结源码和已有分析输出并写入本audit目录；没有修改产品代码、探针、阈值，没有新构建、设备运行或网络操作。
