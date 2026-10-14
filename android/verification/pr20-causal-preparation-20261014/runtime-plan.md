# 单源码因果分解诊断：只读执行方案

状态：**PREPARED_PLAN_ONLY / DIAGNOSTIC_ONLY，未改控制器、未构建、未启动 CI、未推送。** 后续新诊断源码、字段和方法编译结果仍由各 owner 冻结；本文件不是可直接激活的执行清单。保留原公共 probe、样本、时限、阈值及历史失败。本轮只定位一个候选中的工作量/等待区间，不计正式验收，不关闭原 B 回退、绘制 dense 门槛或 GC 暴露不足。

## 最小构建与执行安排

仅一个新固定 source commit，身份沿用 `C1` 并明确别名 `causal-diagnostic-candidate`。新 source-spec/完整 manifest 冻结生产、诊断测试、Gradle/依赖声明和工具入口；无源码切换、无基线 overlay。公共 KvmLibraryPairProbe / KvmLocalAppendPairProbe 文件继续以已有 SHA 证明未变，本轮无需把它们覆盖进源树或执行。原公共方法不被新诊断方法替换。

仍需 **3 个真实 APK**：`app-debug.apk` 是 app 测试的 target，另外是 `app-debug-androidTest.apk` 和 `data-local-debug-androidTest.apk`。只构建两个测试 APK不足以启动 app instrumentation。最小方案一次 Gradle 调用共享当前源树内正常任务依赖：

```text
./gradlew --no-daemon --max-workers=1 --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  :data-local:assembleDebugAndroidTest :app:assembleDebug :app:assembleDebugAndroidTest
```

不启用新增 task cache、fastmode、APK 直接复用或上传。依赖由正常 Gradle 从官方来源解析；同一次构建可用自身已解析依赖和普通任务依赖，不据此宣称缓存收益。app 恢复真实 MuPDF 依赖要求：可复用旧三端控制器的单次、有限时官方 POM 可用性检查；真正依赖成功仍以 Gradle + 完整 AAR/APK native 校验为准。官方来源拒绝/超时则保留失败，不换代理、身份、镜像或 Java-only 包。

构建之后、模拟器启动之前完成源码前后 hash、三个 APK hash/签名、app embedded source/build identity、MuPDF 四 ABI ELF、data SQLite native 与依赖清单校验。复用 focused 轮的 `aapt dump xmltree` 检查每个测试 APK 的 instrumentation name/targetPackage，不能用 `aapt dump badging` 查不存在的 instrumentation 行。安装时继续先核 host APK hash，随后 `pm path` + `sha256sum` 比对已装 base.apk，记录每个实际包的 hash。

同一专用 KVM 模拟器、两安装组即可：

1. `C1-data-diagnostics`：只装 data 测试 APK，顺序执行下面前五项；每项独立合成数据库和原有 finally 清理。
2. `C1-raster-diagnostics`：确认前组进程已自然退出，卸载本轮拥有的测试包，装 app + appTest 两个 APK，执行最后一项。

每个方法后检查三个已知包 `org.inkweft.data.test`、`org.inkweft.app.a0`、`org.inkweft.app.a0.test` 均自然消失；未安装包同样应为空。此轮没有 Compose UI 方法，不移植历史 method17 helper 例外，不 `am kill` 或 force-stop 掩盖 teardown。仍严格分开原 JUnit、host 隔离、证据完整性；skip/Assume 跳过不等于真实诊断 PASS。

## 待源码冻结后采用的准确方法

当前 owner 提供的最小名单共 **6 次 instrumentation / 6 个方法，两个安装组**；每次只选一方法，要求原 `OK(1)`、class/method/count、adb0 和无负状态码。新增诊断参数只传给对应方法，普通生产调用的 observer 保持 null/关闭。

| 顺序 | selection | 参数/边界 |
|---:|---|---|
|1|`org.inkweft.data.LibrarySearchCausalTest#profiledContainsPreservesUnicodeAndSourceResults`|新 profiling Unicode/结果守卫；先通过才进入诊断。未要求额外参数，最终以 owner 冻结源码为准。|
|2|`org.inkweft.data.LibrarySearchCausalTest#warmUdfCostsAndQueryPlanAgainstInMemoryReference`|`-e libraryCausalDiagnostics true`；3 次生产 read +3 次内存参考，完整正常夹具，无 fastmode。|
|3|`org.inkweft.data.LibrarySaveCausalTest#separateTrackerReaderAndIndexContention`|6 固定模式 `IDLE/TRACKER/INDEX_ONLY/INDEX_TRACKER/READS_ONLY/FULL`，每模式500本/20k页、30保存，首批后测保存，之后取消/join，不等待全索引。显式 opt-in 参数名称待 root 源码冻结，不猜名称。仅这个新增入口固定 host 命令上限300秒，并仍受 phase/work 截止约束；不更改任何公共 probe 时限。|
|4|`org.inkweft.data.HistoryDeletionCausalTest#sameRoomWriterConditionalDeleteArrival`|`-e historyGcContentionDiagnostic true`；30 独立1281版本夹具/1 rename，同 Room。|
|5|`org.inkweft.data.HistoryDeletionCausalTest#separateRoomWriterConditionalDeleteArrival`|同参数/30试验，独立第二 Room。两模式仍5s保存 watchdog；20个真实暴露样本门槛，少于20不显示暴露子集分位数。|
|6|`org.inkweft.app.RasterCausalDiagnosticsTest#oneFourEightLayerAppendReportsFrameStagesAndMatchesColdFrames`|`-e rasterCausalDiagnostics true -e pairSeed 4101`；一次调用含1/4/8层、原320→321笔夹具与冷帧/隐藏后像素/严格 join 守卫。共3个聚合对象、最多65 frame，8层对象最多40 frame；不减规模、不改原 ready 时限。|

第1–5项使用 `org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner`，第6项使用 `org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner`。目前这些是 owner 已给出的拟定真实名称；实现尚未完成/编译的项不能当已存在或已执行。最终冻结时逐个核对 `@Test`、参数拼写与期望标记，遇不符先修计划，不运行模糊类级选择。

GC 新方法采用 `CONDITIONAL_DELETE_ARRIVAL`：after-guard-before-DELETE 仅通知已预启动作者，绝不等待作者或强造锁持有时间。它是有条件到达场景，与旧“自然曝光”分布不同，不能混池或把高曝光直接当产品延迟验收。Library6模式也只做分因素观察；单次运行不能自行把任何墙钟余量命名为“调度噪声/锁等待”。

## 时间预算和估算依据

沿用 focused 首步 runner 本地时钟，在独占新目录一次写入；明确不是旧 PR 服务器时间合约或实际 job start。建议新的累计硬截止：**setup30 / data-diagnostics45 / raster-diagnostics50 / work55 / job60 分钟**。每个子命令仍取自身 cap、当前 phase 剩余、55分钟剩余的最小值；方法没有重试。阶段完成即前进，不能因为还剩时间增加样本。50–55分钟不安排额外工作，55停止拥有的工作；双清理/打包/上传沿用261秒总上限与39秒余量，job timeout60不变。

现有实测参考的口径如下，均只是规划依据：

- 完整 run38498392929 的三个 API 构建步骤分别585/504/457秒，均包含 Gradle 后签名/hash等验证。首个 B 是冷任务输出，而 C0/C1 共用了本 job 已下载依赖；**不能直接用后两者保证单新源码冷 job 的耗时**。另一历史慢 runner 的首个完整构建733秒说明实际可超过12分钟。
- focused data-only 两构建步骤209/147秒；本次加回 app 与 MuPDF 后，**不能据此承诺3分钟构建**。
- 最近 source/SDK39秒、KVM23秒；完整轮 source/SDK23秒、KVM32秒。网络/SDK冷启动不是固定上界。
- 完整轮原 GC 两模式方法6.133/6.477秒；旧 library stage 方法40.891秒。新 GC 条件到达、warm UDF和 observe6模式并非相同工作量。root 对新6模式保存诊断估计30–90秒，并指定该方法300秒外层上限；这是估计，不是已测结果。
- 旧公共绘制方法含1/4/8层约4.37–4.47秒；新细分 frame 记录有开销，不能把旧值当其预计不超时承诺。

据此，**合理排程预估12–20分钟，较慢路径约20–25分钟仍可容纳**：完整单源码构建约9–13分钟，加 source/SDK/emulator/安装/清理约1–2分钟，新的数据+绘制诊断预留约2–5分钟。没有对新方法或网络的真实上界保证；setup30和后续阶段截止才是可执行限制。任一超时原样记 INCOMPLETE/FAIL，不降夹具、不增 watchdog、不重跑以补齐。

## 最小可复用实现边界

新目录独立创建，例如 `ci/pr20-causal/` 与 `android/verification/pr20-causal-20261014/`，不覆盖旧 `ci/pr20-search-screen`、`ci/pr20-kvm-triple` 或其历史证据。

- 从已实测 focused driver 复制 `run/seconds/phase/owned/source_manifest/host/environment/raw_result/junit`、严格 marker 失败→EvidenceIncomplete、已装 APK hash、自然退出、顺序/无重试状态机。保留原始 instrumentation、records、XML，任何代码复用需实际 diff/AST 核对，不动态 import 旧执行脚本。
- 从已成功三端 driver 的 `build` 取 app + appTest 完整native/embedded identity 校验；与 focused 的真实 xmltree/已装 hash 合并。一个固定 `C1`，一个构建调用，不创建两端或三端假身份。
- `cleanup.py`可逐字复制已审版本，仍限定自己的 process group/UID/start identity，`setfacl --no-mask -m u:<uid>:rw`只在必要时执行，before/granted/after字节与节点身份校验、trap和always双回执不减。
- 为两安装组显式写包清单，data组一个APK、raster组两个APK；所有已知包的自然退出检查保持。
- source freeze/materialization只把身份循环减为一个 `C1`，完整文件 manifest及固定commit/blob校验保留；新诊断直接来自该commit，不复制未提交源文件。公共 probe只核字节未改。
- method-plan记录6个精确selection、enable args、method kind/phase、ordinal、group、runner、expected1；只新增方法3的300秒 cap。最终方法数以完成的源码冻结为准，任何变动须在激活前审阅，不能动态发现/临时扩大。
- packager沿用缺必需文件→EVIDENCE_INCOMPLETE、保留原before-packaging summary、失败NOT_RUN原值、secret pattern拒绝、20MiB明确白名单、3days。完整声明时须有3APK/依赖/source回执、6套raw/records/XML、诊断forwarding、两组前后env/host、双cleanup。不得上传APK/AAR/DB/私钥/全量env/logcat。

模拟器保持API35 AOSP ATD x86_64、KVM、2 vCPU/2GiB、SwiftShader、1920×1200/density240/font1.0、原动画设置。ATD/软件渲染结果不是物理GPU/UI截图验收。所有构建/hash/安装重活与原生测量串行，计时中不另做树/APK扫描或平行构建。

## 官方 job logs 能直接复算的证据

复用 fixed-prefix `CLOCK / CONTEXT / RECORD / END / METHOD / FINAL`闭合机制，新前缀可为 `INKWEFT_CAUSAL_`，scope始终 `DIAGNOSTIC_ONLY`。CONTEXT必须绑定source commit/canonical manifest/probe或诊断源码hash、method-plan、实际运行workflow SHA、ordinal/selection/args/group、所需APK各自verified/installed hash、signer、install/environment/raw-log/records SHA；方法3的六模式和全部试验都归同一精确调用，不伪装独立job样本。

拟允许的原始 JSON marker仅来自被冻结诊断源码：

- `LIBRARY_CAUSAL_WARM`：一个汇总，3生产read+3内存reference、4字段聚合、静态SQL与一个实际query plan，owner目标<32KiB。只允许固定合成SQL模板/有界plan细节；不转发任意作者文本、参数值、环境、路径或下载URL。
- 保存诊断的marker名称及固定mode/parts/cardinality由root最后给出，**当前未定**。至少保留6模式×30原序保存值/区间、成功状态、首批/索引/读/tracker边界和实际工作计数，不只打印分位数。不能把各因素差直接解释成专属锁等待或纯CPU。
- `HISTORY_GC_CAUSAL_TRIAL`：每方法30；`HISTORY_GC_CAUSAL_SUMMARY`：每方法1。保留所有未曝光/失败/超时原样值、实际guard/DELETE/作者区间、全部保存值和暴露子集索引；连接枚举、条件到达标记与NOT_EVALUATED验收状态明确。
- `RASTER_CAUSAL_DIAGNOSTIC`：每层一个，共3；按first/local/full/hidden与frame/generation/layer关联最多65个frame。保留wall/同线程CPU或null、decode/read/native构造/rebuild计数、enqueue/callback/next-draw时序、像素/严格join守卫，无笔迹payload/UUID/路径。不同线程CPU端点不得造差值。

每个 marker使用**封闭 key schema**、有限枚举/安全字符串、数组最大长度、有限非负数（允许的nullable字段明确）、逐payload字节上限/整方法总上限、逐条原JSON hash及有序END完整性hash。默认沿用64KiB/条、256KiB/方法，仅在owner提供真实最大形状且审查证明必要时为新诊断明确调整；不盲目截断。绘制40-frame对象若超单条上限，先在诊断设计中固定part编号/总数/末尾汇总，不能运行时偷偷丢帧或省略字段。总输出预计远低于20MiB，但源码冻结后须做最大形状合成预检。

原 marker JSON不得被摘要重写。观察器先在内存有界收集，计时/严格join后输出；host再次验证整块后才转发。捕获任何已知或同namespace前缀时必须完整解析，截断/array/empty/未知key/NaN/duplicate/多余记录一律证据INCOMPLETE，不能跳过后继续用正常块宣称通过。对于必要的跨线程稀疏checkpoint，保持已冻结行为并明确其开销，不能把诊断日志开销当正常产品性能。

官方日志只有拿到6 METHOD原始controller PASS +所有marker块/END +两组状态 + FINAL missing_files=[]/双cleanup/环境hash +成功run/job metadata及精确源码/control hashes，才可离线复算“本次诊断完整”。END只是一个block完整，不是整个方法或job完成。缺一项就标INCOMPLETE；GitHub绿色不能替代这些门槛。

这样 root 可经已授权官方 job_logs 通道直接核对数值和分解，不必每轮索取ZIP。仍保留文本artifact作为更强原始审计来源：日志中的APK签名/native/ACL/rawJUnit只是绑定回执，**不宣称重新验过APK本体、完整原JUnit、ACL原字节或全部host/env原文件**。本轮不访问曾被拒绝的artifact storage；缺ZIP不妨碍限定诊断数值，但该边界必须保留。

## 精确的后续激活顺序

1. 各owner完成默认关闭实现及新诊断测试，root串行host编译/JVM/lint；冻结一个真实候选commit、方法/args/marker字段和完整source manifest。复核公共probe字节、正常调用不开诊断/不开fastmode。
2. 独立readiness提交：诊断PLAN、6方法plan、单source spec/manifests、固定schema/预算、source-only prepare/analyzer。此时无活动workflow。不得先写假candidate/plan SHA。
3. root在独立执行clone/新隔离分支复制最小新control，绑定真实readiness/hash和授权scope；控制器/workflow仍无可触发guard。离线跑实际新包的故障用例：严格OK1/身份、skip拒绝、marker坏行/缺块、APK/installedhash不符、预算边界、缺必需文件、cleanup不确认、第一步clock真正写入/二次拒绝。
4. 独立review通过，root提交并push一个**没有活动workflow**的prep，确认没有同范围在跑；只此一次prep流程。随后把新workflow精确绑定 `event.before==prepSHA`、指定分支、`event.created==false`、`run_attempt==1`、contents:read、cancel-in-progress:false。完整EXECUTION_SHA256SUMS包括活动与proposed工作流，复核后一个普通push激活。不得改旧执行分支、重设旧anchor或触发重叠/第二次run。
5. 单job终态后只取授权官方metadata/job log，检查实际job60与阶段截止、marker闭合、APK/source/参数身份、双cleanup和字段完整性。保留失败/NOT_RUN；用独立DIAGNOSTIC_ONLY报告解释可定位区间与未证实因果，不覆盖旧正式结论。若失败，先诊断最小原因，不自动重试。

当前尚未具备激活条件：新commit/源码编译与schema未最终冻结，保存诊断opt-in键和marker待root给出；其余owner方法名/上界仍为实现前设计。本文件只完成执行方案准备，不创建controller、授权文件或workflow。
