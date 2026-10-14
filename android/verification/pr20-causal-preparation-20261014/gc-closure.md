# GC 性能闭合：已有结论、0暴露原因和独立诊断设计

结论：**大批垃圾清理的扫描次数和无竞争总成本已得到原生证据支持；真实DELETE期间保存的排队/尾延迟仍未验证。** 当前0/30不等于竞争尾延迟PASS，也没有证据要求放松版本守卫。最小后续是一个默认关闭、明确标为条件到达的合成诊断，只增加非阻塞观测点，不暂停GC写事务；原公共probe、原自然诊断、样本、阈值及历史结果保持不变。

本文先完成只读因果审查，随后按父侧追加授权实现最小默认关闭诊断：新增 `HistoryDeletionCausalTest.kt` 与 `HistoryStorage` 可选 observer。未运行构建、设备、CI或推送；没有新性能结果。设计基点 HEAD 为 `607c891efe6afd70b4c3fd7db0e95d0e2ccabd8c`，诊断前 `HistoryStorage.kt` SHA256 为 `07046d5d7653723153744cff81ca63430a778112b49c31d16bd6e7535f856677`；本文行号引用均指诊断前源码。实现冻结、字段及限制见同目录 `gc-diagnostic-implementation.md` 和 `history-marker-schemas.json`。

## 1. 已证实和未证实的范围

| 证据 | 已支持的结论 | 不支持的结论 |
|---|---|---|
| 20261013归档KVM四组B/C配对，同2000历史根、4096可删来源；每个B为17次roots回调，每个C为1次 | 无竞争整次collect的重复根扫描已从17降为1；原始纯GC耗时四轮中位B 1656.414002ms、C 238.5570945ms | 不是最新C1重新测得的中位数，也不是根构建单独耗时或全部SQL次数 |
| 同一归档八次live | 首轮每次删除0并延期，保存完成后收尾删除4096；只验证扫描/延期阶段并发保存以及最终回收 | 不能把组合总时间下降或live保存分位数当真实删除竞争的尾延迟结论 |
| run38498392929方法56/57，C1=d66，每模式30个独立1281来源夹具 | 60/60保存Committed，内容/revision正确；每次首轮删0/defer、完成轮删1280；两组都是0/30暴露、INSUFFICIENT_EXPOSURE | 不支持真实DELETE竞争分位数，也不支持B→C1无回退。不得与旧45b样本合池 |
| 同run安全方法8–16，9/9原生PASS | observer、跨Room新增活根、真实DELETE后提交前作者排队、损坏引用、取消回滚/重开和缺guard日志均按原断言通过 | 门控测试的人工持锁时长不是自然阻塞时间，不进入任何性能分布 |

四轮纯GC原序：B=`[1646.734191,1666.093813,1643.198087,1670.106898]`ms；C=`[239.685958,237.428231,232.780579,240.581850]`ms。只在该历史配对内部计算上述中位数，没有合池新运行。B为固定a128加共同初始化工厂修正，C为7949011；原probe与SQLite设置一致。完整来源在 `android/verification/pr20-kvm-paired-20261013/evidence/visit-*-history-records.json` 与 `analysis/history-values.csv`，本次逐条读取原records核对。

更早TCG同设备单次配对也观察到17→1及131305.840304→11354.422681ms，但无KVM、设备速度与本轮不同，不能合池。该次六个保存p50/p95/max均上升，尤其暂停组p95上升73.22%；历史报告已保留。这进一步说明“清理总成本下降”不能替代“保存阻塞改善”。见 `/workspace/scratch/pr20-closure-evidence/final-paired-history-review.md`。

代码上的成本变化明确：旧a128每删最多256条就重开私有reader、重建全部根、重做占用统计与候选读取；4096条需要16个非空轮次加最后一个空轮次，共17次。当前先在一个私有读快照中生成TEMP根/候选，随后分256条短事务删除，整次只扫描一次；根插入另从逐个调用改为128个身份一批。实测总时间是这些改动的净效果，不能把全部加速单独归因于某一个改动。

## 2. 0暴露的实际机制：代码证明与推断分开

当前 `HistoryStorage.kt:49` 在主writer事务中先取expected guard；`:53–58` 用私有只读WAL快照建根、统计和候选，随后结束该快照。`:60` 才发 `after-scan`，`:64–66` 还需从私有TEMP表取首批256候选，然后在`:67`申请Room写事务。

自然诊断在 `HistoryDeletionExposureTest.kt:113–127` 收到after-scan时唤醒作者，真正调用rename；GC回调只通知、不等待。因此通知与首批GC取得writer之间有一个实际竞争窗口，至少包括候选cursor读取和Room调度。作者可以先取得writer并提交。

`NoteRepository.rename` 的事务会更新notes、插入note_revisions及receipt。它即使只是改标题、没有新增来源活根，仍是当前保守全库guard必须检测到的写入：

- 同Room经相同主连接写入会改变 `total_changes()`；Room 2.8.5清空invalidation标志的自身更新，用 `total_changes()+pending invalidated行数` 抵消，不能把正常reset误解释为作者提交。
- 另一Room/连接的提交会改变主连接的 `PRAGMA data_version`。值在Room事务内读取以固定同一primary连接；不能假设每次提交恰好+1。

GC取得writer后，`:69`首先比较guard。不同就直接返回false；`:79`设置changed并退出，`:88–89`返回 `collectionDeferred=true`。**此分支在第一条DELETE之前，因而不会出现before-batch-commit或after-batch。** 真正DELETE在`:70–74`；原before-batch-commit在所有DELETE已执行、提交前的`:75`。

本轮60条日志都只有after-roots→after-stats→after-scan，首轮firstDeleted=0、firstDeferred=true；保存完整调用区间位于after-scan之后、首轮结束之前，完成轮明确在保存和首轮结束后启动。结合源码，已证实它们都走首批guard不等的零删除分支，第二次调用才按新快照回收1280。

**边界：**日志未记录expected/actual两个分量、GC实际writer入口和物理commit时间。作者真实提交是足以触发上述分支的机制，也符合此夹具唯一业务写入；但不能把“具体哪一分量变化、是否还存在额外触发器/内部写入、各层队列等待多久”写成已观测事实。TEMP读取/调度解释了作者可能先赢的窗口，不证明某个调度器优先级是唯一原因。0暴露还不是“GC与保存完全没重叠”：扫描/首轮调用有重叠，只是没有实际删除重叠。

旧baseline也有保守检查，但机制不同：每轮在同一独立reader快照内外比较其data_version，取得Room writer后若变则本轮不删；不能用候选的两分量guard解释旧日志。更早无首次删除计数的旧TCG `liveFirstPassDeferred=true` 只表示整个首次collect延期，不能据此推断首批删0；上述KVM八轮有单独firstDeleted字段，才支持该结论。

## 3. 最小新诊断：条件到达，默认关闭，不制造锁持有时间

已新增独立 `HistoryDeletionCausalTest`，仅显式选择并传 `historyGcContentionDiagnostic=true` 时执行。默认CI、原公共probe及方法56/57不自动包含它。禁用时不建立记录器、不读时钟、不分配event或打印；保留原GC算法、根集合、事务边界、256批大小和guard判断。最小实现已冻结待父侧统一验证；本子任务未运行任何 native/build/CI。

每连接模式预先冻结30个独立夹具，每个仍1281来源、1280可删、最新1、0显式根，一次真实rename。保持同/异Room两种模式分开；固定种子、完整来源/环境/APK绑定以及原5秒保存watchdog。不加大行数、payload、根数量或DELETES来延长批次，不调整线程优先级，不根据命中率调参、补样或重跑直至20。

增加一个极小的**可选非阻塞观测点**：GC已取得writer、首个非空批次guard已通过，紧邻DELETE循环之前。它只记录单调时钟/批次编号并通知预先启动的独立作者协调协程，立即返回。作者从自己的协程上下文调用原rename，不能在GC回调内执行rename、不能继承GC的Room事务上下文。禁止latch.await、sleep、yield、数据库查询、JSON编码、sendStatus和磁盘写入出现在GC临界区观测回调中。

此触发点晚于guard检查，所以不会像after-scan到达那样在首个DELETE前把本批直接作废；本批必须先释放SQLite writer，竞争作者才能提交。作者提交后，后续批次仍按现有guard延期。调度若太慢，作者可能在本批或整次GC结束后才实际调用；必须按记录判为未暴露，不能等待作者“就位”或暂停GC补造覆盖。

这是一种**以正在执行删除为条件的请求到达模型**，用于检查“请求恰好赶上真实批次时，要等待多久”。它不是一般用户保存的随机到达概率，必须单独标为 `CONDITIONAL_DELETE_ARRIVAL`，不能覆盖现有 `NATURAL_AFTER_SCAN` 的0/30或把新分布当原验收通过。

现有before-batch-commit通知已经太晚：作者收到该通知后才开始的调用，不可能回头覆盖它的原时间点。仅改成在现有这个点唤醒作者，既不测DELETE循环竞争，也不能据此改暴露规则。

## 4. 分开测调用延迟、writer等待和提交，不能偷换计时窗口

最低限度记录现有GC `after-roots/after-stats/after-scan/before-batch-commit/after-batch`，外加新“writer已持有且guard通过/DELETE即将开始”事件；记录作者实际rename调用start/end和Committed、revision、内容验证。该组合能测真实删除竞争下的**端到端调用耗时**，不能独自把它拆成纯锁等待。

若后续目标明确要求“保存阻塞时间”，再加两个最小、默认关闭的时间标记：

1. 在原 `NoteRepository.rename` 的 `db.withTransaction` 块首行记录 `authorWriterEnteredNs`。`authorWriterEnteredNs-saveCallStartNs`量出Room执行器/连接队列及SQLite writer取得之前的累计等待，**不能命名为纯SQLite锁等待**。不要通过额外包一层外部withTransaction来悄悄改变原rename的事务/提交含义。
2. 在GC现有withTransaction返回后紧邻记录 `gcTransactionReturnedNs`。原before-batch-commit是commit前，返回点是事务结束之后；实际writer释放位于两者之间。这是提交/释放时间的界，不是精确fsync时间。作者可能已进入writer后GC协程才记录返回点，不应要求作者entry一定晚于这个返回时间戳。

已有 `BEFORE_RENAME_RECEIPT` 在作者SQL工作之后，不能冒充writer入口；`AFTER_RENAME_TRANSACTION`在原rename的withTransaction返回后可复用作作者事务完成边界。观测点只是纳秒时钟和预分配内存写入，计时/通知开销非零，需记录hook自身耗时并披露；不在热路径执行日志导出。若要声称纯SQLite锁等待，需另外审核底层锁追踪，这不属于本最小方案。

为把当前0暴露的机制直接观测出来，可同时记录GC已存在的expected/observed guard数值或两个差异布尔位。复用本来就要读取的writerVersion，**不增加SQL查询或引入新版本判定**；由一个诊断专用小回调记录，关闭时不构造对象。避免泛化事件框架、自动重扫或收集无关数据库内容。

推荐原始记录字段：

- 身份：scenario、connection、fixtureSeed/摘要、source/APK/probe SHA、SQLite writer设置。
- GC：callId/batchId、候选数、guard匹配及分量变化、writer/guard/DELETE起点、原before-batch-commit、事务返回/after-batch、首轮/收尾起止、根扫描数、首轮/收尾实际删除总数、defer。
- 作者：sample、计划触发/通知时间、实际调用start、writerEntry、beforeReceipt、transactionReturned、调用end、原样耗时、返回类型、Committed/revision、最终内容/保留集合校验。
- 完整性：失败阶段、异常类别、timeout/未知提交、记录数、时钟/丢事件错误；finally导出全部已收集结果，取消并join后再关两库。缺记录或丢事件为INCOMPLETE，不能剔除慢/失败调用。

此设计不在临界区额外查询“删了多少”。以固定夹具、原report计数、成功提交批和结束后的实际行集合核对删除，额外逐SQL查询会污染待测批次。已有门控安全用例可继续独立证明实际DELETE效果及新活根300保护，不把其时长转为性能样本。

## 5. 结果判定与停止规则

原自然暴露公式不变：`saveStartNs <= beforeBatchCommitNs <= saveEndNs`。新诊断保留该字段，另记录更强的条件证据：GC guard通过后作者实际请求已经开始、非空批次确实提交、作者直到DELETE结束后才进入writer、最终保存Committed。若这些条件不齐，只能解释各自记录到的区间；不能仅凭协程launch时间称为数据库竞争。

每模式必须公开全部30原序调用、失败/未知结果、全部事件及暴露子集索引。原20条展示门槛不变：不足20仍标INSUFFICIENT_EXPOSURE，暴露子集不报告p95，只保留原序/计数/max；完整30条的全部调用分布与暴露子集分开。达到20也只是新诊断的条件分布，不是旧probe的PASS、SLA或统计等价。5秒watchdog只是挂起保护，不是性能门槛。

首轮可执行一个或多个批次后因作者提交而延期，随后保存全部完成再做一次无竞争收尾，核对两次总删除1280、最新来源snapshot、笔记标题/正文/revision不变坏。不要要求首轮一定只删256，更不能要求持续提交作者与一整轮陈旧候选删除同时完成：后续延期正是既定安全合同。

若还需要用户负载下的暴露概率/长期公平性，应另预注册开放到达场景：按产品已有保存节奏或独立采样的合成输入轨迹驱动作者，计划到达不依赖GC phase；记录计划/实际到达与积压，绝不因未暴露而提高频率或放大夹具。当前没有该到达模型依据，因此不在本文发明具体频率。这是额外问题，不能混进上述最小条件诊断。

若要比较B→当前C的竞争尾部，必须在同设备、同夹具、同作者调用及两个版本各自“guard通过后且DELETE前”的等价位置加相同默认关闭观测，重新绑定来源并预先冻结配对顺序；基线也继续使用它原本的reader data_version守卫。不能拿当前新诊断去减旧公共probe保存值。此次未实现基线移植或执行这种新配对。

## 6. 后续范围

本轮已实现GC现有 guard 值及DELETE边界/事务返回观测、一个显式选择的合成测试类、封闭记录格式及失败清理；未增加rename writer入口，因此不能分解writer排队时间。静态审查后交由父侧统一编译、检查及决定独立运行预算。原公共方法、原20/30规则、数据负载、已归档工件全部冻结。没有理由为获得暴露去改GC批大小、忽略guard、插入等待、重试或放宽阈值。

参考：当前 `android/data-local/src/main/kotlin/org/inkweft/data/HistoryStorage.kt:27–89`、`NoteRepository.kt:33–48`；`android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionExposureTest.kt:109–180`；`HistoryCollectionTest.kt:65–155`；`/workspace/scratch/pr20-candidate3-results-20261014/audit/audit-history.md/json`。原a128 HistoryStorage SHA256为 `0a1c4df6b8fbe6d8a3033a03a8314586bbd0ecf13ce5412ac9b1a2a133580230`。
