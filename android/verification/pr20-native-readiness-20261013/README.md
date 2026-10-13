# PR20 下一轮原生验证准备

从 `e1a813aa24be20df0901f6aff9554632e1f531b7` 继续，范围仅为已发现的搜索/保存回退与GC暴露缺口。本轮增加验证代码和可选阶段诊断，**未执行新的 GitHub/KVM、Room、UI 或原生性能测试，未更改安全配置**。没有猜测性暖查询优化，GC生产算法和 generation guard 保持原样。

## 已准备的代码

`HistoryCollectionTest` 原7项不变，新增同Room/另一Room两项。它们在实际DELETE已执行、事务尚未提交时确认前256条已删除并发出真实作者请求；提交释放后保存和新增根300成功，要求GC只删256条后延期，收尾仅删1023条，300和1281保留且作者revision/内容正确。人工闸门仅证明正确性，输出明确标记 `performanceMeasurement=false`。

`HistoryDeletionExposureTest` 两个方法各30个新的1281来源小夹具，每次1个真实rename。无sleep/闸门/补跑；5秒保存防挂起时限不是性能通过阈值。记录两连接writer配置、保存原序时间/结果/revision、根扫描、真实批次时间点、首轮/收尾删除数、延期和清理状态。失败仍在finally输出已取得记录；保存区间覆盖事务内 `before-batch-commit` 才计明确暴露。总体与暴露子集分开，子集不足20不报分位数并标 `INSUFFICIENT_EXPOSURE`；它不制造JUnit失败，也不代表延迟通过。

`LibrarySearchRepository.stageObserver` 默认null。仅安装observer时才分配诊断计数器，启用时才创建每次read的trace并读时钟；默认路径只有少量边界null检查，没有新增逐行诊断分支、额外SQL/COUNT、缓存或索引工作。粗边界覆盖：

- request、Room真实失效/version、索引完成和调度；debounce开始/完成/取消、观察读取完成/取消/错误。
- IO请求与实际IO开始、连接/快照打开关闭、草稿/元数据/范围/命中/有序预览/pending阶段。

`LibraryReadPerformanceTest` 仅在 `-e libraryStageDiagnostics true` 时启用；默认关闭。`LIBRARY_STAGE_DIAGNOSTICS` 分块记录运行内readId、generation、sourceVersion、stage/count、共同单调时钟origin及相对时间，并带direct/observe保存原序窗口；不记录查询文本、标题、正文或持久笔记ID。warm固定5次保留原序。保存异常/取消只记录异常类别且提交状态为未知；正常返回按回执记录。退出时先等待全部读写子任务结束，再输出最后事件并关闭/删除合成库。旧 `KvmLibraryPairProbe` / `KvmLocalAppendPairProbe` 不变，供无新诊断的三端配对使用。

诊断只度量墙钟：不能拆成SQLite CPU/磁盘/锁等待；Room事件可被combine合并，版本不是每次作者提交的时间；read-end不是UI发布；connection-closed只在正常返回的use之后发出，异常路径缺少事件不能推断连接泄漏。IO前被取消的读取不算已执行SQL。C1-only阶段不能解释B/C0的阶段差值，也不能抵消保存p50回退。所有SQL匹配、Unicode、正文新鲜性、分页、取消和150ms调度合同保持原逻辑。

## 云端验证

现有云环境离线、单worker、JDK17/Gradle9.4.1。实际检查：

- `:data-local:testDebugUnitTest :data-local:compileDebugAndroidTestKotlin :app:compileDebugAndroidTestKotlin` 成功（7分43秒）；**31项JVM，0失败/错误/跳过**，其中观察调度11项（原8＋新3）。数据层和应用Android测试仅编译，未运行。
- 搜索探针失败窗口/清理修正后，`:data-local:compileDebugAndroidTestKotlin :data-local:lintDebug` 成功（4分32秒），lint **0 Error、14 Warning**：1项SDK版本提示、13项已有存储空间建议；本轮没有修改这些位置。
- lint后仅将自然GC探针的异常提交状态由false改为null，保留成功路径；最后一次仅重编译受影响数据层Android测试成功（1分50秒），准确回执见 `evidence/checks.json`。未重复无变化的JVM/应用编译或扩大检查范围。

[检查回执](evidence/checks.json)、原始日志、5份JUnit XML、lint原文和最终源码哈希随目录归档。JVM虚拟时钟覆盖启用/关闭诊断时的调度、模拟失效事件、取消和异常身份；不覆盖真实IO生命周期，也不模拟Android SQLite性能。新增GC正确性、自然暴露和分页UI的原生结果均为 **NOT_RUN**。

## 集中复验方案

[PLAN.md](PLAN.md) 与 [method-plan.json](method-plan.json) 固定一次标准runner、最多60分钟、三个源码身份、九个APK、44次单方法调用。三端四个区组保留旧公共工作负载、原阈值和全部原序；C1另测17项正确性、两种连接各30次自然GC和一次阶段诊断。没有把旧运行、host JVM或单次诊断混进新配对。

[source-spec.json](source-spec.json) 和三份完整manifest固定旧基线B、旧候选C0、本轮代码C1及overlay；[prepare_sources.py](prepare_sources.py) 只重建本地源码并校验。C1固定代码提交 `ebb48481e7e995288c1844d43011398c513b4f4e`；三端共2871个文件条目已在新目录重建并逐字节哈希校验通过，未构建/运行这三份导出。后续报告提交不改变其被测字节。

[budget-evidence.json](budget-evidence.json) 使用旧原始时长，setup估计约22.6分钟、公共方法约9.1分钟再加安装检查。固定累计截止26/32/48/55/60分钟；新增用例没有原生时长保证，超预算报告INCOMPLETE，不缩轮、不自动重跑或启动第二job。原1项REGRESSION、4项INCONCLUSIVE及GC删除竞争比较缺口保持。

C1自然GC即使获得足够暴露，也只能描述候选，不能关闭B→C1删除竞争性能无回退门槛。若要补B/C0同位置观测及配对，属于后续另批范围；本次不扩大生产GC或基线补丁。

本轮推送仅更新既有隔离开发分支/草稿PR；不增加工作流、不运行提案、不合并或部署，不改用户源分支、main、backup-server、旧证据或共挂载AniMemo。
