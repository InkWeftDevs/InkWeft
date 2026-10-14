# PR20 剩余性能：一次同 KVM 配对方案（待批准，未执行）

推荐一次标准 `ubuntu-24.04` GitHub-hosted runner、一个临时 API35 AOSP ATD x86_64/KVM 模拟器，串行构建两端并执行 **4 个相邻配对、8 轮、24 次 instrumentation**。本轮仅准备此目录内的源码草案、重建说明和协议；没有创建/推送测试工作流，没有 build、adb、权限修改或原生执行。

这是有上限的小样本工程验收，不是统计上证明无回退。当前 KVM 五方法 PASS 仍只属于之前固定候选的一次执行，不能并入这里的重复样本或改变其含义。旧 TCG 数值只用来指出待查问题，不参与本次比值。

## 1. 两端完整源码与共同测试

| 名称 | 来源与允许改动 |
| --- | --- |
| B：受控旧版 | Git `a128bb07e34cb82f00eeafc8e62a47bf7b95b128`；仅把 `PreservingOpenHelperFactory.kt` 换成 C 的相同文件，再增加本目录三份共同探针 |
| C：候选 | Git `7949011ca2b84492f6438d275a09d28faef3b472`；仅增加相同三份探针，生产文件不改 |
| 报告 HEAD | `320ca865ce17efeed1c112f115be57e1bd05a1fb` 仅增加报告，不作为另一份生产候选 |

安全 factory 的 SHA-256 为 `a049af775caa688cd0dfd938acdf1539d34ee13de272ed35c930de9ef4e3d15d`。B 保留旧搜索、分页、raster、GC 实现，不拷入优化，也不使用早期 hardlink/4096/INCREMENTAL 工厂。B 是明确修改过的受控基线，不称原始远端。

`source-spec.json` 固定所有 overlay 路径、字节数、哈希；`B-source-manifest.json` / `C-source-manifest.json` 固定完整 Android 源码/构建配置（排除 verification 证据）及 ci 文件。其余仓库文件由对应完整 Git commit/tree 固定。不能从旧可写 scratch、旧 APK 或移动分支头取源码。`reconstruct_sources.py` 只负责从本地 Git 对象重建并逐文件核验；不构建/执行、不改仓库分支。

共同探针是由 C 已有方法派生的新类，双方复制相同字节，不覆盖原测试：

1. `KvmLocalAppendPairProbe#oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions`：保留原 1/4/8 层、320→321 笔、3000×2000、追加和隐藏像素参照及每次 ready 的 60 秒时限；两端都保留实际 Job 的严格 cancel/join。移除未选的 pan 方法及可选 metadata observer 的类型依赖，使 B 不需生产诊断补丁。nativeStrokeBuilds 继续反射读取，B 缺失则 null，不能当 0。
2. `KvmLibraryPairProbe#coldSearchConcurrentSavesAndShelfReadScale`：保留 500 本、20,000 页、20,000 字符正文、原扫描门闩/750 ms 写入隔离检查、直接搜索30次保存、observe期间30次保存及分页断言。增加实际保存/reader起止单调时钟、扫描进度、每批已提交索引累计行/时钟和5个暖查询原值。batch 明细先缓存在内存，完成后一次写日志，不逐行记日志。
3. `KvmHistoryPairProbe#collectLargeGarbageWithRootsAndConcurrentAuthorSaves`：保留2,000根、4,096垃圾、256条原生产批次、纯清理、暂停扫描30次保存和自然live30次保存。新增逐次 `Committed`/revision 断言和最终 revision/title；记录live首次调用区间、收尾区间、每次保存区间及各自删除数。暂停/自然场景分别报告。B 仅传 `historyScanBaseline=true` 关闭“恰好一次根扫描”的新实现专属断言，不关闭活引用/删除数/保存断言。

三份 `.review.diff` 显示相对于 C 原方法的全部测试差异。源码草案**未编译、未在 Android 验证**；不能将文本生成或哈希核验当作测试通过。双方必须先编译成功、核验包和原生库，才能开始测量；编译失败即停止这次 job，保留日志，不在运行中边改边测。

两个data探针都把测量 `coroutineScope` 放在数据库 `try` 内，等待其子协程完成/取消后才close/delete；异常路径也保留这个次序。绘制输入摘要包含 `page_layer_memberships` 的完整主键顺序，核对实际笔迹归属，不能只核对层配置头。

## 2. 同环境、初始化和冷暖定义

- 一台 runner、同一模拟器贯穿八轮；SDK/JDK/Gradle/MuPDF及其他依赖一次解析后冻结并记录版本/文件哈希，两端相同。Gradle参数相同、串行、一个worker、不开构建缓存、不共享项目构建输出，允许共用依赖下载缓存。无付费/自托管/用户机器回退。
- 模拟器沿用已成功规格：API35 AOSP ATD x86_64、KVM `-accel on`、SwiftShader、2 vCPU/2048 MiB、1920×1200/dpi240、字体1.0、window/transition1.0、animator设置删除。记录实际版本、`ro.hw_timeout_multiplier` 原值（空值也原样保留）、CPU/内存/kernel/负载/磁盘余量；同轮前后配置改变则不接受比较。不调整host CPU governor、不清宿主页缓存、不改模拟器权限以外的主机设置。
- 每轮都按同一顺序卸载三个已知合成包、安装该端三个已核验APK；即使连续两轮同端也完整卸载/安装。每方法独立 `am instrument`；完成后核方法JUnit结果与3包进程退出，再执行下一方法。仅能清理本job安装的合成包。无需恢复别人的应用。
- pair1–4分别用固定 `pairSeed=4101/4102/4103/4104`，同对两端同seed；合成UUID及工作区operationId一致。测试范围内的创建时间在测量前规范到相同常量；后续真实保存使用正常生产时钟。前置操作不计入性能指标，也不改生产逻辑。
- 三探针在计时外对指定实际逻辑表做带长度/类型边界的SHA-256、输出行数；同对每个fixture标签必须匹配。仅对列出的输入表宣称逻辑内容相同，不宣称SQLite文件、WAL字节或全部缓存相同。绘制每层都记录输入标签。
- 在实际writer的短事务中只读核验 `page_size=1024, auto_vacuum=1(FULL), journal_mode=wal, synchronous=2`；两端及前后必须一致。不要用读连接的设置代替writer，也不执行PRAGMA改格式。初始合成时间规范操作与格式读取均在计时外。
- “冷搜索”严格指**新合成库、pending=20,000、没有FTS构建后的首次查询**。种子插入/fixture hash已访问数据库，故不是冷磁盘、冷OS缓存或真实应用冷启动。B仍走原完整header正文读取，单列其耗时/返回量，不隐藏准备成本。暖查询在索引/保存都结束、pending=0后执行5次，保留原序，不能混为5次独立试验。
- 本次观测补丁和fixture预检也会影响缓存/调度；两端完全相同，但不能和原TCG或之前无此预检的KVM值合池。

## 3. 固定顺序、数量与执行命令

| 配对 | seed | 第一轮 | 第二轮 |
| --- | ---: | --- | --- |
| 1 | 4101 | C | B |
| 2 | 4102 | B | C |
| 3 | 4103 | B | C |
| 4 | 4104 | C | B |

全序为 **C/B/B/C/B/C/C/B**。不按早期结果调整顺序、重复数、数据量或阈值。无额外预热轮，无重试；第一个完整轮也是样本。每轮方法顺序固定为 localAppend → library → history，共24次方法调用。单层是绘制主指标；4/8层是同一次调用中的保护指标，不算额外独立重复。

构建命令（分别在重建的 B/android、C/android 执行一次；`INKWEFT_HEAD_SHA`设为该端Git基点，`GITHUB_SHA`由被批准的执行工作流提供）：

```sh
./gradlew --no-daemon --max-workers=1 --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest
```

APK保持 `formal=false`。内嵌 `SOURCE_COMMIT` 是各端Git基点，**不是声称overlay已在该commit中**；完整被测字节身份由基点、source-spec、完整输入manifest及APK哈希共同绑定，必须在结果中同时列出。不得只展示内嵌commit而隐藏共同测试和B工厂替换。

每轮的三条调用由runner填入固定seed和端别；完整stdout/stderr落盘，逐条读JUnit，不只读adb exit code：

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e pairSeed 4101 \
  -e class 'org.inkweft.app.KvmLocalAppendPairProbe#oneFourEightLayerLocalAppendMatchesForcedFullFrameAndDecodesOnlyDirtyRegions' \
  org.inkweft.app.a0.test/androidx.test.runner.AndroidJUnitRunner

adb -s emulator-5554 shell am instrument -w -r \
  -e pairSeed 4101 \
  -e class 'org.inkweft.data.KvmLibraryPairProbe#coldSearchConcurrentSavesAndShelfReadScale' \
  org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner

adb -s emulator-5554 shell am instrument -w -r \
  -e pairSeed 4101 -e historyScanBaseline false \
  -e class 'org.inkweft.data.KvmHistoryPairProbe#collectLargeGarbageWithRootsAndConcurrentAuthorSaves' \
  org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner
```

上面示例为pair1/C；B只把history标志改true。所有metadata/tilePan详细诊断保持关闭，两端同一套公共计时/统计观察器保留。没有备份、pan/zoom、密集帧或全套UI/Room重复运行；以前的五项PASS不计入24次。

## 4. 必须保存的原值与比较口径

| 场景 | 原始证据与导出统计 |
| --- | --- |
| localAppend | 每轮1/4/8层 first/append/forcedFull毫秒，metadata/raster/publication原值（跨层求和不解释成wall-time分解）；实际decode/rebuild/full计数、native构造/null、位图预算、追加/隐藏全幅像素相等、join后资源。四个配对分别给B/C/差值/比值，不将三个层数混池 |
| 直接冷搜索+保存 | header成本/正文返回量、冷查询区间、扫描计数；30个保存原序耗时、开始/结束、扫描进度、Committed/revision；全部30项p50/p95/max和各项是否与reader调用区间相交 |
| observe索引+保存 | subscribe起点、每批累计已提交行/时钟、首64行、全20k提交、最终pending=0/搜索ready、联合完成；30次保存原序/区间/索引行进度及p50/p95/max。报告 `20,000 / subscribe到全提交秒数` 为**含初始查询、30保存和调度的有效吞吐**；另给首批到末批增量速率，均不叫纯SQL索引速度 |
| GC纯清理 | 2,000根/4,096垃圾、after-roots回调计数（一次对应一次根扫描）、完整collect调用毫秒、删除数、2,001活/最新版本保留 |
| GC暂停与live保存 | 两组各30项原序耗时/区间/Committed/revision和p50/p95/max；paused明确含人为固定扫描状态；live首次collect起止、after-roots事件、是否deferred及删除数，收尾collect起止/删除数、全部工作组合时间。保存延迟含生产调用的排队/IO/工作，不等同SQL锁等待 |
| 完整性 | 源码/overlay/依赖/APK/签名与4 ABI MuPDF ELF身份、fixture摘要/行数/SQLite配置、方法原始开始/完成及JUnit、设备前后、ACL和进程双清理 |

新原值统一离线用 nearest-rank：`p50=sorted[ceil(n*.50)-1]`、`p95=sorted[ceil(n*.95)-1]`、max。30项时是第15/29/30项；原探针library旧字段用了第16项，其值保留但标legacy，不能与GC第15项混比。4轮作为4个独立试验单位；保存的120项只可附带描述，不混池扩大独立样本量。

对实际并发单独计算区间交集、保存开始时后台仍活跃、跨越后台结束以及完全后台结束后的数量。observe还按保存前后索引行进展列出暴露程度。若任一端某次自然场景没有实际重叠，该场景只能评估“发起后台工作后的用户保存序列”；**不能关闭持续竞争下的保存阻塞门槛**。不为凑满30个重叠样本而延长、暂停或重复GC/索引；重叠子集不足20项时不解释其p95为可靠尾部估计，只列原值/最大值和样本数。

## 5. 提前冻结的建议判据（需随方案批准）

这些是工程容忍带建议，不是已有用户SLA，也不构成统计显著性。每指标独立评估，不用GC提速抵消保存变慢、不用长尾改善抵消p50/索引吞吐回退。

定义每对B/C值，时间回退超带：`C-B > max(relative*B, absolute)`。所有4对的值和符号都公开。

| 指标 | relative | absolute |
| --- | ---: | ---: |
| 单层append（主指标）；4/8层append及各层first/forcedFull保护指标 | 10% | 5 ms |
| 每保存场景p50 | 10% | 1 ms |
| 每保存场景p95 | 15% | 5 ms |
| 每保存场景max | 25% | 20 ms |
| 冷搜索、subscribe到20k提交、search-ready及联合完成时间 | 10% | 500 ms |
| GC纯清理及清理+保存组合时间 | 10% | 100 ms |

- **FAIL_CORRECTNESS/SAFETY**：原断言、原时限、像素/活引用/保存一致性、包身份、格式、逻辑输入摘要或清理失败；不产生“完整通过”。测试失败后保留原日志并停止后续性能轮次，避免失败污染下一个样本。
- **REGRESSION_SIGNAL**：同指标至少3/4对同向变慢，配对中位时间增幅>相应relative，且中位绝对增量>absolute。吞吐以对应时间为主判据，同时原样报告rows/s和下降百分比，不反转公式制造不同结论。
- **WITHIN_PROPOSED_BAND**：预定4对完整、正确性/身份/输入/环境/清理成立，且该指标4对均未超带。这只能表述为“本次四对落在预设容忍带内”，不能说证明没有回退。
- **INCONCLUSIVE**：某对超带但不满足重复回退信号、尾部样本/重叠不足、环境波动无法解释等；不强归PASS。最大值任一对超带即保留尾部风险，即使其余均值较好。
- **INCOMPLETE/BUDGET_ABORTED**：缺任何预定轮次或因总预算停止。保留所有已完成/失败/截断记录，不删慢值，不用另一runner补成同环境配对，不自动重跑。原测试超时与host总预算停止分别标记。

若任一关键场景INCONCLUSIVE/INCOMPLETE或回退信号未解释，PR继续Draft，整体性能门槛不关闭。即使所有带内也只认可这套合成工作负载和标准KVM配置；真实设备、真实笔记、长期负载另属未验证范围。

## 6. 60分钟预算与停止规则

| 从job开始计时 | 分配 |
| --- | --- |
| 0–26分钟 | 源码/overlay/依赖核验、SDK、串行B/C三APK构建、签名/ELF/包身份检查、临时KVM授权及启动/设备配置 |
| 26–55分钟 | 固定8轮、24方法；预计每轮约1–3分钟，含统一安装、fixture建立及清理，实际未知 |
| 55–60分钟 | 无论成功/失败均停止本job进程，trap和always双恢复ACL，校验/脱敏/打包上传 |

已知同规格单候选构建约660.990秒，五个原方法总JUnit约54.809秒。**这不是本方案完整Library/GC探针或B构建耗时**，不据此保证八轮。冷双构建约22分钟只是预算估计，共用依赖缓存有望减少第二次下载；CPU/网络会波动。开始测量前若已超过26分钟，整批不开始并记预算不足。早完成可提前进入测量，但绝不超过55分钟工作deadline。

可行性边界很具体：若准备耗尽26分钟，余下29分钟要求每轮三个探针、fixture和安装/清理合计平均不超过217.5秒。举例仅作预算算术：每轮150秒则总约51分钟；每轮210秒则约59分钟；每轮240秒则约63分钟，不能装进单job。后三个时长都是假设，不是实测预测。

若要更保守地拆开，应**事前另选并批准**两项独立任务：A在一台runner内完成raster+library四对（16次方法调用、两端6 APK）；B在另一台runner内完成GC四对（8次方法调用，只构建两端dataTest各1 APK）。B只比较自己runner上的B/C，不能拿A的B当对照，也不能混池跨runner原始耗时。这两种较小组合减少各自的未知负载，仍没有新的KVM完整探针时长保证；墙钟总计应为60–100分钟的规划区间，外加约10–20分钟证据核验，故向用户报1–2小时比承诺60分钟全部交付诚实。若首个完整方案因预算停止，剩余组合及其新一次runner需要再次批准，不自动转为第二job。

runner对每个子进程取 `min(其管理上限, 55分钟剩余时间)`，到限终止仅本job进程组。方法内部原超时不放大；管理上限建议localAppend180秒、library240秒、history180秒，均属于外部防悬挂预算，不是新的产品验收阈值。三个上限相加×八轮不承诺全部可用；所有工作共享总deadline。没有为了“总时长可行”而假设每项会更快，也不因预算缩成3对后宣称四对验收完成。

若KVM不可用、官方依赖被403/网络策略拒绝、fixture/构件不一致，停止并记录；不绕路下载、不用Java-only AAR、不改用TCG完成配对。一次失败不得自动dispatch或rerun。

## 7. 权限、工件与下一步需要批准的动作

本轮没有新执行授权。建议提交给用户的批准范围为：

> 允许按本协议在一台标准GitHub-hosted ubuntu-24.04 runner上执行一次、最多60分钟的配对job：固定B/C及已审阅的三份共同测试overlay，串行构建6个debug APK；新建一个临时API35 KVM模拟器，必要时仅给当前UID临时rw ACL（不扩大已有mask），执行固定8轮/24次instrumentation；失败/超预算不补跑。允许上传本文列出的非敏感文本工件。该许可不包括第二次job、放宽门槛、变更生产代码、访问用户设备、合并、部署或付费runner。

批准后才创建**新的隔离执行分支**，不复用已消耗的一次性分支。先写带“不运行”guard的准备提交；最终触发提交仅匹配该分支/指定workflow路径，并要求 `event.created == false`、`event.before == 已固定准备SHA`、`run_attempt == 1`，没有dispatch/schedule/PR触发。只用一次受控push触发，push前读回完整差异/guard和B/C清单；后续push或rerun不能再执行。不会修改main、用户源分支或PR20生产源码。当前方案目录本身未推送。

临时ACL沿用已经核验的node identity及完整`getfacl -pn`字节保存、`setfacl --no-mask -m u:当前UID:rw`、原样恢复及两次字节比较；已有rw则不改。不得chmod666、加组、修改udev/模块或绕过mask。仅追踪自己启动的emulator和命令进程组；资源恢复不确认时不得报成功。

工件白名单：协议与源码/overlay哈希、Git tree及实际构建输入清单、构建原始日志、依赖及6 APK哈希/大小/公开签名证书指纹/4 ABI ELF回执、host/device最小字段、24份完整instrumentation原始日志及派生XML、全部原序JSON/CSV、固定顺序/起止/超时/NOT_RUN状态、fixture摘要/行数、ACL前/授予/双恢复及进程清理回执、原始与上传长度/hash manifest。XML与原log是同一执行，不重复计数。

不上传APK、数据库/WAL/原迹、AVD、密钥、全环境、完整emulator日志或私人路径；emulator仅摘录版本/启动/必要错误并复查。保留SDK/构建/测试失败日志中的非敏感部分；敏感值脱敏必须记录，测试raw若截断/缺失则明确降低完整性，不能改为通过。预计文本<10MiB，设置20MiB总预算、3天保留；超限/脱敏失败不静默丢证据或升级存储权限。最终报告安全归档到不触发CI的PR20开发分支是后续独立交付步骤，本次没有推送。

在用户批准前，仅可继续静态审阅此方案/overlay；不把此计划文件当作执行许可。
