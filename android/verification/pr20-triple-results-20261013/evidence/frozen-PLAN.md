# PR20 集中原生复验提案：三端、单 runner、最多 60 分钟

状态：**待批准，未执行**。本目录没有 GitHub 工作流或启动器。`prepare_sources.py` 只从本地固定 Git 对象重建并校验源码，不联网、不构建、不调用 adb、不改 Git 分支或系统权限。本轮现有云端编译/JVM 结果另列于 README，不能代替下面的原生执行。

## 1. 冻结身份与可重复的源码

`source-spec.json` 是精确身份入口：

| 标签 | 固定 Git 身份与允许 overlay |
| --- | --- |
| B | `a128bb07e34cb82f00eeafc8e62a47bf7b95b128`，仅替换为 C0 的同一 `PreservingOpenHelperFactory.kt`，再增加两份公共探针 |
| C0 | 旧四对候选 `7949011ca2b84492f6438d275a09d28faef3b472`，只增加两份公共探针 |
| C1 | 本轮代码 `ebb48481e7e995288c1844d43011398c513b4f4e`，tree和全输入固定在source-spec；只增加同样两份公共探针 |

B 的 factory SHA256 固定为 `a049af775caa688cd0dfd938acdf1539d34ee13de272ed35c930de9ef4e3d15d`。B 是受控旧基线，不称未经修改的远端；保留旧搜索、分页、绘制、GC。没有把 C1 优化回填 B/C0。

两份公共探针逐字节取自已推送 `e1a813aa24be20df0901f6aff9554632e1f531b7` 下原四对证据：`probe-KvmLibraryPairProbe.kt`、`probe-KvmLocalAppendPairProbe.kt`。其 SHA256 与此前原生执行相同，三端都新增为相同类；不覆盖原测试。新阶段诊断在这些公共调用中全部关闭。新 GC/Room/UI 用例及阶段诊断只在 C1 运行，不能与旧无诊断样本混成比较。

三个 `*-source-manifest.json` 固定实际 Android/ci 构建输入，排除不参与编译的 verification 文档；其余 Git 身份由完整 commit/tree 固定。重建命令（仅源码）：

```sh
python3 android/verification/pr20-native-readiness-20261013/prepare_sources.py \
  --repo /path/to/InkWeft --out /new/empty/path/pr20-native-sources
```

输出目录必须不存在。重建后分别在 B/C0/C1 的 android 目录串行、单 worker、同参数构建三个 debug APK，总计九个；共用已核验依赖缓存，不共享项目输出、不用构建缓存：

```sh
./gradlew --no-daemon --max-workers=1 --no-build-cache \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest
```

逐端设置 `INKWEFT_HEAD_SHA` 为固定 Git 基点，`formal=false`。APK内嵌 SOURCE_COMMIT 不涵盖 overlay；发布结果必须同时列出 source-spec、实际输入哈希、APK哈希/大小、公开签名指纹、依赖清单、MuPDF四ABI ELF回执。官方依赖或网络策略拒绝即停止；不用 Java-only AAR、不绕路取包。当前云端编译通过不等于这九个原生 APK 已构建或执行。

## 2. 环境及 60 分钟边界

一台标准 GitHub-hosted `ubuntu-24.04` runner，一个临时 API35 AOSP ATD x86_64 模拟器，KVM `-accel on`、SwiftShader、2 vCPU / 2048 MiB，1920×1200 / dpi240，字体1.0，window/transition1.0、animator设置删除。记录实际SDK/emulator版本、host CPU/内存/kernel/**负载及剩余磁盘**、设备配置及 `ro.hw_timeout_multiplier` 原值；不得混用另一 runner、TCG、用户设备或付费环境补齐结果。

旧同环境 run 的 setup 978.795 秒**已经包含** B 构建 514.607 秒与 C 构建 379.338 秒，不能再次相加。按第三端约6.3分钟外推，三端 setup 约22.6分钟，非保证。旧每轮 local+library 为43–49秒，12轮约9.2分钟，另加安装/清理；新用例尚无原生耗时，预算是上限而非测量承诺。

| 从 job 开始 | 固定工作 |
| --- | --- |
| 0–26 分钟 | 源码/依赖核验、九APK串行构建与身份核对、模拟器启动及初始记录 |
| 26–32 分钟 | C1 定向 Room/GC 正确性与新分页 UI；失败即停，不进入性能轮次 |
| 32–48 分钟 | 下述4个三端区组、12轮、24次公共方法调用 |
| 48–55 分钟 | C1 两种连接各30次自然GC暴露，加一次开启阶段诊断的 library 调用 |
| 55–60 分钟 | 停止本job拥有的进程组、核验资源/权限恢复、打包白名单文本工件 |

提前完成即可进入下一阶段，但不得用剩余时间重复慢样本或增减轮数。未在26分钟完成 setup，不开始原生方法。任一阶段超过所列累计截止，停止后续工作、标 `BUDGET_ABORTED/INCOMPLETE`；55分钟必须进入清理，job硬上限60分钟。每条命令同时受阶段deadline和工作总deadline约束。保留测试原内部时限，host预算中止单列，不伪称JUnit失败或通过。

预算不足不自动把4区组缩为3组，不自动删除C0/GC/UI，也不启动第二job。若用户选择分阶段，需另批第二次运行；本提案只申请一次完整尝试，接受可诚实报告 INCOMPLETE 的风险。

## 3. C1 正确性与诊断（不混入比较样本）

准确方法选择及参数见 `method-plan.json`。C1 正确性共17个JUnit方法，各自独立一次 instrumentation、核对确切方法身份及 `OK(1)`，包括：

- `LibraryPagingTest` 全部3项：SQL/Unicode/置顶与摘要边界、并发快照、旧请求下正文新鲜性。
- `LibrarySearchRepositoryTest` 中真实输入取消与清空/分类、已删除/过期页面及回收卡片不可复活两项。
- `SearchIndexFreshnessTest` 两项：笔迹/对象/层/页面变化与按需解码合同。
- `HistoryCollectionTest` 全部9项：含新同Room/另一Room DELETE后提交前作者请求、活根保护与延期，以及原跨批保护、损坏中止、中断回滚重开等。
- `NotebookShelfUiTest#pageSourceEditResetsSecondShelfPageWithoutDirectoryChanges` 一项。只修改页面原文，第二页须重置且两页恰有64个正确身份；notes/drafts/目录不变。

GC 闸门用例是正确性，**人工等待不产生保存性能结果**。两种连接都在 DELETE 已执行且事务未提交时提出真实作者调用，释放提交后保存必须成功，新增活引用阻止后续候选被删除。原 guard 保持不变，不以持续清理换掉延期安全性。

最后的自然GC用例各固定30个新合成库（1281来源版本，1280垃圾），总60个新库试验；同runner/进程的样本不视为统计独立。每次1个真实rename，另一Room提前打开，`after-scan`只发非阻塞通知；无闸门、sleep、挑样或补跑。所有保存起止/Committed/revision、GC阶段、首轮/收尾删除与延期、失败部分记录都保留。只有保存调用区间覆盖 `before-batch-commit` 事务内时间点才计明确删除事务暴露；总体30项与暴露子集分列，子集少于20不给p50/p95并标 `INSUFFICIENT_EXPOSURE`。这仍是作者调用延迟，不能直接当SQLite锁等待；不能称完整GC轮次持续删除。新小夹具不与旧2000根/4096垃圾的性能值比较。即使暴露充分，也只能描述C1，不能关闭B→C1的GC删除竞争性能无回退门槛，因为B/C0没有同位置观测。

C1 最后单独调用 `LibraryReadPerformanceTest#coldSearchConcurrentSavesAndShelfReadScale -e libraryStageDiagnostics true`。阶段事件只含运行内计数/版本/编号/时间，批量输出，无正文/标题/查询文本。用于区分 Room事件、索引完成、debounce/取消、实际IO读取与粗SQL阶段；它带观测开销，不能作为四组性能验收值，也不能推出B/C0→C1的阶段差值。没有CPU采样、SQLite锁等待或精确磁盘I/O证据，不作过度归因。

总计44次单方法调用＝17正确性＋24公共比较＋2自然GC＋1独立阶段诊断。正确性逐方法启动比聚合类增加少量进程开销，但免去新增多方法JUnit解析逻辑；各次核验3个合成包进程已退出。第一次正确性安装C1，12个公共visit各自重装，最后诊断组再重装C1，共14组安装。

## 4. 三端固定比较

| 区组 | pairSeed | 固定顺序 |
| --- | ---: | --- |
| 1 | 4101 | B / C0 / C1 |
| 2 | 4102 | C1 / B / C0 |
| 3 | 4103 | C0 / C1 / B |
| 4 | 4104 | B / C1 / C0 |

C1相对B、C1相对C0均为先后各2次，各身份都覆盖三个位置；B相对C0为3/1，只作旧回退复现的旁证，不作本轮主验收。

每轮完整卸载/安装该端三个已核验合成包，即使相邻同端也重新安装；每方法单独 instrumentation。固定顺序 `KvmLocalAppendPairProbe` → `KvmLibraryPairProbe`，共24调用。保留1/4/8层完整方法，包括4层append/forcedFull、8层forcedFull这三个原不确定项目，以及全幅像素、decode/rebuild、实际Job cancel/join与资源归零。单层控制成本很小，不裁方法改夹具。

library 保持500本/20,000页/20,000正文字符、原写入隔离门闩和750ms检查、direct30次保存、observe30次保存、索引完成后的5次warm及分页断言。公共探针的实际逻辑输入哈希、writer的1024/FULL/WAL/synchronous2、保存revision/Committed、直接读取/索引区间、原序样本均保留。冷定义仍为新库且pending=20,000的首次搜索，不称冷OS/磁盘。相同seed的三端四个fixture标签（3层+library）必须相等。

比较分三列独立报告：B→C0（顺序不平衡的旁证）、B→C1（新候选总效果）、C0→C1（本轮差异）。同区组配对、4区组为试验单位；不能把720次保存当720次独立试验，不与以前host的样本合池。warm每轮5次保留原序，以每轮中位作描述性比较，不追加事后数值门槛。

## 5. 原判据保持

原26项的阈值定义文件逐字节作为 `original-protocol.json` 保存。此次只重测其中绘制和library项目；旧GC数值项目未重测，不能把未选项目标新PASS。时间超带仍为 `C-B > max(relative*B, absolute)`：

| 指标 | relative | absolute |
| --- | ---: | ---: |
| 绘制各阶段 | 10% | 5ms |
| 保存p50 | 10% | 1ms |
| 保存p95 | 15% | 5ms |
| 保存max | 25% | 20ms |
| 原26项已定义的冷读/索引/observe-ready指标 | 10% | 500ms |

nearest-rank仍取每次30项排序第15/29/30项；旧legacy第16项原字段保留、不作p50。每指标至少3/4同向变慢且配对中位相对/绝对增量均超带才为 `REGRESSION_SIGNAL`；任一对超带但不满足该条件为 `INCONCLUSIVE`；4对均带内且正确性/身份/环境/清理通过才为有限的 `WITHIN_PROPOSED_BAND`。warm没有原数值门槛；不以p95/max或GC提速抵消p50回退。全部并发区间和暴露不足均单列，不能把原B 1/30与C 30/30直接当等量竞争尾部。

额外派生的direct冷读与保存联合终点继续仅作描述性补充，不借用500ms门槛，也不添加到原26项验收中。

原始1个REGRESSION、4个INCONCLUSIVE及缺口在新完整结果接受前保持。任一正确性/身份/fixture/清理失败，立即停止后续性能方法并保留原文；任何少轮/截断/超预算都不能报整体PASS。一次失败不自动dispatch/rerun。

## 6. 拟批准范围与交付边界

建议一次集中批准：允许按本目录冻结身份、参数和方法，在单一标准runner执行一次最多60分钟尝试、构建九个真实debug APK、创建一个上述临时KVM模拟器并上传非敏感白名单文本；只清理由本job创建的合成包/进程。若KVM当前UID已有rw，不改权限；否则仅在本次批准范围内按既有方案保存node身份与完整ACL，临时给当前UID rw（`--no-mask`，不扩大mask），最后trap及always两次逐字节恢复核对。不得chmod666、改组/udev/模块、使用自托管/付费/用户设备，或绕过依赖策略拒绝。

**现在没有实施上述任何安全操作或新GitHub/KVM执行。** 批准后才在新隔离执行分支准备一次性guard：仅指定分支/path push、`event.created=false`、固定准备提交的`event.before`、`run_attempt=1`；无dispatch/schedule/PR触发，旧一次性分支不复用。批准不允许中途改生产代码、阈值、夹具、补跑、合并或部署；执行包装器若与本提案不符必须先修正并复核，不能边测边改。

工件仅含协议/source manifests/overlay与依赖哈希、APK公开身份回执、完整原始构建与instrumentation日志/派生JUnit、所有原序及阶段记录、前后环境最小字段、权限与进程双清理回执、文件字节数/hash清单。上传归档总上限20MiB、保留3天；超限报告工件预算不足，不截断原始测量或自动扩大额度。不得上传APK、DB/WAL、真实笔记、密钥、全环境或AVD。方法失败也保留已有原文；打包只按白名单，不截断慢值，不把派生XML重复计作执行。
