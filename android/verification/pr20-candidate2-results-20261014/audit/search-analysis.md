# Run 38441089594：库搜索独立复核

结论：**metadata 延后确实让取消读跳过目录组装，但没有关闭 observe 保存 p50 回退。** 本轮 C1 相对 B 四对均超过既定 `max(10%, 1 ms)` 门槛；C0→C1 的改善也未成立。现有证据支持优先评估同步 SQL 前后的协作取消检查，不足以把回退归因于 writer 锁，也不足以直接采用连接复用或额外防抖。

本复核只读已有原始资料和源代码，未运行构建、Android、测试或 GitHub 操作，未修改生产或探针。数值明细、原序保存窗口、逐读阶段、来源 SHA 和独立断言见同目录 `search-analysis.json`。

## 范围与复算

- B=`a128bb07` 加固定 preserving writer 工厂；C0=`ebb48481`；C1=`45b4861a`。本轮 C0 不是旧轮的794版本。
- 比较仅使用公共31–54中的12个库方法：32/34/36/38/40/42/44/46/48/50/52/54。四个seed4101–4104，每版本每seed各一次；方法57完全排除公共比较。
- 逐行解析原 instrumentation log 的全部 JSON，与13份 records 文件精确相等；12个最终 evidence 与冻结 analysis 相等。720个保存窗口均按 start/end 复算原序耗时，全部 Committed，direct revision2–31、observe33–62。每轮20000条索引完成，所有313次提交计数递增。
- p50取30个值排序后第15个（下标14），p95第29个（下标28）；旧探针 `saveP50Ms` 下标15，不用于正式p50。独立重算27个库比较，所有逐对值、门槛状态和中位差均与冻结分析相等。
- 公共探针SHA=`90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c`；各seed三端实际逻辑夹具hash相等，writer均1024/FULL/WAL/synchronous2。源码差异中，C0→C1搜索只移动 metadata及分类组装到page之后，同一快照；Observation/TextIndex/page SQL不变。

## 公共结果（毫秒，seed依次4101–4104）

| 指标 | B | C0 ebb | C1 45b |
|---|---|---|---|
| cold首次未索引 | 3604.125 / 3635.890 / 3645.937 / 3622.370 | 2127.271 / 2106.788 / 2104.801 / 2114.796 | 2140.033 / 2099.237 / 2120.942 / 2120.240 |
| warm五次中位 | 574.212 / 574.403 / 575.600 / 577.013 | 633.919 / 645.146 / 634.008 / 638.496 | 630.890 / 693.497 / 631.031 / 639.898 |
| observe保存p50 | 2.278 / 2.383 / 2.289 / 2.295 | 6.463 / 5.227 / 5.494 / 5.510 | 5.045 / 6.326 / 6.848 / 6.292 |
| observe保存p95 | 82.099 / 81.039 / 81.292 / 86.516 | 18.601 / 13.217 / 14.444 / 15.031 | 11.566 / 12.834 / 23.368 / 14.581 |
| observe保存max | 3437.778 / 3489.198 / 3460.106 / 3491.168 | 34.387 / 32.041 / 32.585 / 30.070 | 33.086 / 33.425 / 40.971 / 30.229 |
| direct保存p95 | 2.877 / 3.373 / 2.558 / 2.471 | 7.038 / 5.484 / 5.407 / 9.309 | 5.826 / 5.814 / 5.409 / 9.015 |

B→C1 observe p50 的配对中位差为 **+3.970117 ms / +169.832555%**（REGRESSION_SIGNAL）；四对各自均过门槛。C0→C1三对变差、一对改善，中位 +0.940515 ms / +17.609098%，两对过门槛，按原聚合规则为 INCONCLUSIVE，不能描述为新候选改善。

B→C1 cold 配对中位差 -1513.562800 ms / -41.647707%，四对改善；这里“cold”是夹具hash后首次未索引查询，不是冷OS/文件缓存。warm四对分别 +56.677388/+119.093596/+55.430992/+62.884761 ms，配对中位 +59.781074 ms / +10.384392%，作为不利观察保留；原协议未单列warm门槛。C0→C1 warm为 -3.029013/+48.350583/-2.976946/+1.401486 ms，混合结果，不能说metadata变化解释了全部warm差值。

B→C1 direct p95 第4104对 +6.544476 ms，超过 `max(15%,5 ms)`；另外三对绝对差未过5 ms，因此聚合 INCONCLUSIVE。observe p95/max明显降低是单独收益，不能抵消p50回退。B direct max约3.48–3.52秒，C1约毫秒级的收益也不能覆盖p95不确定性。

## 保存、索引重叠与读取规模

公共C1每轮30次observe保存期间index64→128，一次64条提交的时间点落在一个保存窗口内；相同窗口内 sourceChecks 也增长。B每轮64→448，六个保存窗口内发生批提交，另一个长保存窗口发生source扫描增长；这两个类别不在同一窗口。C0四轮也均64→128，但4104整组保存期间sourceChecks未增长，不能声称该组保存实际覆盖了原文扫描。每轮索引都完成20000条，不把“任务在运行”当成每次保存都有持续索引/扫描竞争。

这些30次提交式测量的墙钟长度不同，B的长阻塞允许更多批提交；不可将batch数量差解释为C1索引吞吐变差，也不可由某个窗口counter不变推断writer从未工作（可能存在尚未提交的事务）。INDEX batch时间均是事务返回后的通知，不提供锁等待/持有时长。

| 数值 | B四轮范围 | C1四轮范围 |
|---|---|---|
| 完整20000条提交墙钟 | 28.436–29.104秒 | 28.456–28.840秒 |
| 首批→末批增量速率 `(20000-64)/(last-first)` | 687.301–703.385 rows/s | 693.886–703.296 rows/s |
| 索引完成且搜索ready联合时间 | 29.029–29.695秒 | 29.134–29.502秒 |

完整索引B→C1配对中位 -291.074180 ms（-1.005855%），联合完成 -210.118957 ms（-0.711526%），均在原band内；C0→C1反而约 +373.176 / +370.628 ms。完整索引含启动、10ms批间yield、读取/作者竞争和进度输出；增量速率排除第一批但仍包括这些间隔。不能称其为writer临界区吞吐。每批回调timestamp与first/final字段是独立nanoTime读取，分别相差约0.006–0.010ms；JSON保留差值，未强行当作完全同一时点。

所有公共C0/C1：header返回正文0、目录正文0；60条最终shelf预览35425字符，SQL投影含lookahead共61条/36025字符，按需全文相等为true。B header/目录各返回10010000字符，最终shelf正文1181180字符；B不存在投影observer，因此projection是null，不是0。候选暂停只读快照时作者能提交，B不能。返回规模证明游标/对象正文有界；**不代表文件页读取为0，也不代表非空搜索只匹配61本正文**。正常warm仍对候选本子的当前正文进行匹配。

## 方法57：独立诊断，不能并入上述计时

同一originNs=`702318040590`，所有60次保存有原序开始/结束，全部Committed且revision连续。observe30保存窗口为origin后4251.471–4451.244ms。共有30次真实失效、25次调度、24次observe-read-start：22次取消、2次成功；另一次初始debounce被取消。22次读取消中16次进入IO，6次在IO前取消。

**16次IO取消读全部没有metadata-start，全部有scope-end和page-hits-start。** 十次成功读（cold、暂停读、两个observe、五次warm、一次shelf）才组装metadata。本轮确实没有浪费取消读的目录对象构造；没有同run的C0阶段对照，所以不能给出“节省了X ms”的因果差值。

| 16次取消IO读阶段 | 累加墙钟ms | 单次中位ms | 与30保存窗口交集ms |
|---|---:|---:|---:|
| read-start→connection-open | 4.548539 | 0.199893 | 4.548539 |
| connection-open→snapshot-open | 8.304682 | 0.103244 | 8.304682 |
| snapshot-open→drafts-ready | 24.563090 | 1.051289 | 24.480376 |
| scope-start→scope-end | 72.689297 | 4.749585 | 72.528916 |
| scope-end→hits-ready | 22.389256 | 0.799280 | 19.718330 |
| read-start→page-hits-start（包含上面阶段） | 133.130195 | 8.475860 | 130.213591 |
| read-start→read-cancel（完整取消IO） | 189.776976 | 9.852991 | 173.951698 |

各行是墙钟，包含被抢占/调度/同步调用；重叠只证明同时发生，不能作为保存延迟的可相加解释。总计行与分项重复，不能相加。scope段包含CREATE TEMP TABLE和INSERT SELECT；hits-ready前包含两张TEMP表、一个索引及query terms。注册函数→snapshot混合段含四次addFunction和begin，不能独立归因锁。

具体窗口（sample为零基序号）：

- sample14（revision47）4328.752921–4341.309561ms，保存12.556640ms。read13的connection-open→snapshot-open为4334.313808–4340.899932ms（6.586124ms）；index64→64、source0→0。这是异常前置墙钟与保存重叠的确定事实，尚不能分离Java函数注册、事务开始或抢占。
- sample25（revision58）4391.898100–4432.209152ms，保存40.311052ms，全组最大；index64→128、source0→146，也是全组唯一计数推进窗口。read21的scope4393.810036–4397.315334ms，page-hits开始4397.854817ms，后续invalidation4421.848200ms，snapshot关闭4424.663410ms，observe取消确认4425.343963ms。此窗口同时发生扫描/索引提交/取消和下一次scope；不能把40.31ms全称为某次index锁持有。
- 另外29次保存没有index/source计数推进，但许多窗口与TEMPscope/hits准备重叠；没有推进不排除索引未提交工作，也不意味着它们是空闲基准。

11个最终取消读（4/5/6/8/10/13/14/15/18/20/23）在scope过程中已有下一次真实失效，却继续做hits准备并到page-hits-start。例如read14收到version17/18/19三次失效，仍完成7.129092ms的scope。**invalidation发出不等于mapLatest已经向该Job送达取消**；现有事件只有最终取消捕获，没有实际cancel-request时间，不能断言全部后续耗时都属于“已取消后仍运行”。

## 能确认的结构与最小下一步

C1 `LibrarySearchRepository.kt:62–87` 每读新开readonly reader、注册四函数、begin；`:98–150` 的空draft路径在首个原文UDF/返回行之前没有coarse ensureActive。CREATE/INSERT scope及建hits使用execSQL，`:81–84` CancellationSignal包装只覆盖query。对与工件同SHA的Requery3.49.0 AAR进行静态API/字节码检查：execSQL/SQLiteStatement没有公开CancellationSignal重载；不能以反射或私有session接口伪造稳定API。

优先候选是**在现有读生命周期及execSQL粗边界调用已有active.ensureActive/check**：IO入口、connection-open后、snapshot的try/finally内进入临时作业前，以及scope/hits各同步语句前后。它可以阻止取消已送达后的后置作业，不改变即时失效/150ms/结果新鲜性，也不能中断已经执行中的execSQL。现有数据支持尝试此小改，不能预称它解决p50。先以阶段暂停→取消→释放的确定性测试证明后继SQL不再执行，并验证下一次新快照正常；继续保持原公共四组和门槛复验。若需确认归因，诊断单独记录边界处Job是否active/取消到达，不能把invalidation当成取消时间。

B还存在一项必须保留的语义差异：固定公共probe向observe传flowOf(request)，作者save只改notes/revisions/receipts；a128的invalidation表不含notes，候选包含notes。因此候选会因作者正文保存重读，B该固定collector不会逐次由这项保存触发重读。方法57的真实失效和重复前置作业与这一差异一致。这解释额外作业来源，**不豁免p50回退，也不应通过删除notes监听恢复旧的新鲜性缺口**。

连接复用可静态实现为单collector所有、每读重新begin/end的reader，但现在不应优先：16次取消读开库仅4.549ms，复用不会免除fresh snapshot下的scope72.689ms。还须处理下列真实约束：

- 现有所有TEMP DDL在事务内创建，未setTransactionSuccessful，Requery字节码确认end发ROLLBACK；按SQLite事务语义TEMP创建和填充随rollback撤销。复用仍需每读重建；不能假设跨读保留就绪scope。若实施，需原生确认成功/取消/异常后三类sqlite_temp_master清理及新快照可见性，当前没有运行此实验。
- Requery的session是ThreadLocal；同一次begin→所有query/exec→end必须在同一线程、期间不挂起。不同读取之间允许换IO线程，但不允许旧读未结束时下一读接管。mapLatest顺序取消并join是基础，close必须在全部read和CancellationSignal watcher退出后。
- UDF现在捕获request和activeContext，不能保留旧闭包；addFunction在configuration里追加，逐次重注册会累积。真正复用需要一次注册及严格每读状态绑定，已经比补取消检查更复杂。每读还需新CancellationSignal；旧watcher不能取消下一读。rollback失败须废弃连接，不能复用未知状态。多collector和直接read不得共享可变read状态。

warm问题另行保留：方法57五次warm的page SQL为613.167–628.118ms，占read-requested→read-end的96.925–98.003%；page/card FTS命中阶段约0.5ms，metadata5.762–12.696ms。B从已预载完整Note里匹配正文，候选SQL对当前正文经UDF匹配，表达式仍是Kotlin contains(ignoreCase=true)。该page段还包含SQLite执行、正文提取/JNI、排序及预览，不足以把全部差值归因contains或解码。若下一轮诊断需要定位，可在独立开关下分别计正文提取/匹配和作者事务排队/执行、index prepare/writer区间；这些有额外采样开销的数据必须继续排除公共比较。现有证据不支持更改Unicode、target-only特化、schema/cache框架或以防抖掩盖刷新成本。

## 建议的门控 Room 回归（尚未实现/运行）

复用现有stageObserver，不增加生产框架。对 connection-open、snapshot-open、drafts-ready、scope-start、scope-end 五个门控点依次运行小夹具：一份已保存正文含old-token、带非空folder/favorite的笔记；repo.read使用固定旧headers和查询old-token。IO回调以CompletableDeferred发出entered，再等待CountDownLatch release。测试在entered后通过真实NoteRepository.save把正文/标题改成fresh-token，断言Committed与revision；随后cancel读取Job、释放门控并join。

对原readId分别断言没有下一工作阶段snapshot-open / drafts-ready / scope-start / scope-end / hits-ready；允许snapshot-closed和read-cancel清理事件。不得把成功结果吞成null当作取消，应assert抛CancellationException。移除observer后fresh查询必须看到新revision、fresh-token结果和原分类，old-token查询不再命中，即使request.notes仍是旧headers。snapshot-open的ensureActive须放在保护endTransaction的try/finally里面，避免begin后在finally建立前抛取消。每个门控单独重置夹具/计数，finally先release、再NonCancellable cancelAndJoin，最后关闭数据库。此测试证明取消已送达后的边界，不证明正在执行的execSQL可以中途取消，也不直接证明性能门槛已过。

## 限制

四个seed块是比较单位，不能把720个保存或每轮warm5次当独立试验。B→C0顺序3/1不平衡，仅辅助。方法57单端单次且诊断开启，只能解释该次阶段；不与旧方法44不同运行的绝对毫秒做配对。当前没有CPU、GC/分配、实际文件页读取、作者/索引锁等待与持有、read Job取消送达或UI发布时间。read-end在withContext返回前；取消路径无connection-closed事件来自打点位置，不证明连接未关闭。整包APK/host/环境连续性核验由父侧完成，本复核未重复其全部过程；未宣告剩余验收完成。
