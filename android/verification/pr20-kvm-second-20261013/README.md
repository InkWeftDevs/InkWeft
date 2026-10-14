# PR20：固定候选的五项 KVM 原门槛验证

**5 个不同方法，各执行一次，5/5 JUnit PASS。** 搜索首批索引、写入中取消备份、原规模密集完整帧、pan/zoom 的原时限在本次 KVM 环境均通过；1/4/8 层局部更新的像素和资源断言通过。**整体性能验收仍未完成**：本轮只有候选，没有同 KVM 环境的受控基线或重复配对，不能关闭历史单层性能回退。

本报告提交只归档文本证据，不改变被测生产源码、测试、工作流或安全设置，不新增执行。历史失败不删除、不改判：见[前次完整证据](../pr20-closure-20261012/README.md)与[平移诊断](../pr20-pan-diagnostics-20261013/README.md)。上述旧报告按各自当时的执行范围保留，当前状态以本补充为准。

## 身份、执行与原始证据

| 项目 | 固定值 |
| --- | --- |
| 被测源码 | `7949011ca2b84492f6438d275a09d28faef3b472` |
| 工作流提交 | `a65f8599300b42444ab516714645b4e6f28d0432` |
| 执行 | [38365926272](https://github.com/InkWeftDevs/InkWeft/actions/runs/38365926272)，attempt 1，单 job，success |
| 时间 | 2026-10-13 07:56:39–08:09:56 UTC，13 分 17 秒 |
| 工件 ID / ZIP 大小 | `11796344544` / 23,742 bytes |
| ZIP SHA-256 | `520e09565b4973fd90fb2a8e8c7e1bbd9faae9da2e996f7588c704deeca8994d` |

日志存储下载曾返回 Forbidden；没有绕过或重试被拒端点。用户提供现有 ZIP 后独立核验：压缩包哈希与 [GitHub 工件元数据](evidence/github-metadata.json)一致，31 项共 103,751 解压字节；[原始 manifest](evidence/manifest.json)中 30 个 payload 共 95,433 字节，长度、原始/上传哈希全部匹配，无截断。

逐份原始 instrumentation 输出核对指定 class/method、唯一开始/结束状态、`numtests=1`、`OK (1 test)`、最终 `INSTRUMENTATION_CODE: -1`，无失败/负状态码。没有把 ADB exit 0 当作通过。[独立核验回执](evidence/independent-validation.json)记录五项解析结果。XML 由工作流从同份原始日志生成，属于派生证据，不计作另一轮测试；XML 的 time 为 host 调用时长，下面 JUnit 时长取原始 `Time:`。

归档保留 29 个原始 payload 和原始 manifest。完整 emulator.log 含临时 ADB 公钥及服务路径，因此仅归档[版本与启动摘录](evidence/emulator-selected.log)，不改动原 ZIP；摘录不是原始完整日志。没有提交 APK、数据库、AVD、私钥或全量环境。

[SHA256SUMS](SHA256SUMS)覆盖本目录实际发布的文件（不含校验文件自身）。两轮原始 manifest 各自描述完整原 ZIP，不用于宣称其所有 payload 均已在本目录公开。

## 五个原方法的结果

| 方法 / 原始日志 | JUnit 秒 | host 调用秒 | 本次结果 |
| --- | ---: | ---: | --- |
| [搜索](evidence/search-instrumentation.log) | 37.381 | 37.801 | 1/1 PASS |
| [备份写入中取消](evidence/backup-instrumentation.log) | 10.038 | 10.388 | 1/1 PASS |
| [原规模密集完整帧](evidence/dense-instrumentation.log) | 2.712 | 3.473 | 1/1 PASS |
| [pan/zoom](evidence/pan-instrumentation.log) | 0.879 | 1.618 | 1/1 PASS |
| [1/4/8 层局部追加](evidence/local-append-instrumentation.log) | 3.799 | 4.525 | 1/1 PASS |

完整方法名、执行顺序与原始状态见 [summary.json](evidence/summary.json)。夹具规模、原时限和断言均来自固定源码，没有为通过测试修改它们；仅开启既有 `tilePanDiagnostics` 和 `rasterMetadataDiagnostics`。

### 搜索：20,000 页，原 128 行 / 10 秒门槛

500 本 / 20,000 页。未建索引的前台完整冷查询 **1,484.266 ms**，正确返回目标页，pending=20,000。observe 后首次读取 15 ms、首条分词完成 22 ms、首批预计算完成 210 ms、进入 writer 215 ms、64 行提交完成 252 ms；**128 行在 392 ms 完成**，满足原 10 秒门槛。这里的阶段差值含调度与事务，不能解释为纯 SQL 锁等待。

原方法继续通过 Main 响应、快速输入的最新 generation、完整 pending 消化、清空及分类断言。5 次暖查询的日志报告中位数 **17.192 ms**，原五值未输出。`FTS_EVIDENCE elapsedMs=33021` 从冷读前起算，至暖查询之后，包含冷读、观察/索引和暖查询，**不是纯全索引完成耗时**。

本方法没有并发保存 p50/p95/max 探针，也没有分页读取规模探针；不以本轮冷读值替换旧同环境配对的保存延迟或数据层读取证据。

### 备份：原 15 秒窗口内实际 partial 后取消

沿用 160 × 100 KB 源行夹具。基准快照完成 1,425.295 ms、inspect 完成 3,017.875 ms（均为该操作的累计时间）。取消操作在 **1,136.765 ms** 观察到 partial，**1,141.817 ms** 报告 `CancellationException`，`partialObserved=true`、`cancelled=true`，不是超时。

原方法的 partial 删除、scratch 清空、作者内容 canonical 一致、真实 Room close/reopen 与新快照 inspect 断言均通过。没有借较小夹具或扩大 15 秒窗口代替原方法。

### 密集帧：1,201 笔 / 76,802 点，原 30 秒门槛

编辑窗口仍只有 128 笔 / 8,192 点；完整帧读取 **256 + 256 + 256 + 256 + 177 = 1,201 行**，共 76,802 点。完整帧阶段 **1,272.966 ms**，发布 callback=1；4/4 瓦片重建，decoded=1,201，nativeStrokeBuilds=1,201。末批红色笔迹像素与 worker/geometry 释放断言通过，日志到达 `verified-and-released`。

这是原密集夹具通过，不是先前较小的 258 笔替代用例。该用例检查完整覆盖和指定像素，不能表述为跨版本全幅像素比较。

### pan/zoom：五个独立 30 秒等待点

| 等待点 | 本次 ms |
| --- | ---: |
| initial-cached | 322.542 |
| pan-cached | 61.900 |
| pan-cold | 116.775 |
| zoom-cached | 87.144 |
| zoom-cold | 99.715 |

整数平移重建 **12/20** 瓦片；pan 和 zoom 与本 APK 的冷完整重建逐像素一致。预览不能完成 capture，同 revision 缩放零新解码和几何复用等原断言通过。严格 join 后 `inFlightJobs=0`、`totalBytes=0`。这不追溯历史 C2 的超时阶段，也不把完整瓦片复用扩展为任意分数位移复用。

### 局部追加：单次 1/4/8 层，像素及实际工作量

| 层数 | 首帧 ms | 局部追加 ms | 强制全帧 ms | 局部 decode / native 构造 | 局部重建 / 全帧重建 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 291.368 | 52.339 | 201.608 | 13 / 13 | 1 / 24 |
| 4 | 241.631 | 107.969 | 199.914 | 21 / 21 | 1 / 24 |
| 8 | 278.595 | 147.947 | 255.054 | 11 / 11 | 1 / 48 |

每组原 320→321 笔。全帧 decoded=321 经原测试 `full.sumOf(lastBuild.decoded)` 断言核验，日志写出该已校验常量。更新后的完整图像及随后隐藏新增笔迹的破坏性更新均与同 APK 的强制全帧像素相同，保留原位图预算和清理断言。

这些是每层数**一个候选样本**，不是重复中位数；强制全帧是同 APK 的正确性参照，不是旧版本基线。诊断字段 metadata/raster/publication 是各层统计之和，不能相加解释单次 wall time。本轮诊断开启，不能与旧关闭诊断的 C/B/B/C 时间直接合并。

## 环境、构件和清理

标准 GitHub-hosted ubuntu-24.04，AMD EPYC 7763、4 个逻辑 CPU、约 16 GiB 内存，JDK Temurin 17.0.20.1。API35 AOSP ATD x86_64 rev1；Emulator 37.2.12.0 / build 16428233；KVM version12 可用，`-accel on`，SwiftShader，2 vCPU / 2,048 MiB，1920×1200 / dpi240。字体 1.0、window/transition 动画 1.0、animator 设置为字面 null；`ro.hw_timeout_multiplier` 返回空字符串，不推断其有效默认值。

[设备前](evidence/device-before.json)和[设备后](evidence/device-after.json)所有共同字段一致。这不证明运行中的瞬时负载、缓存或数据库字节完全相同。它与旧 TCG 云模拟器是不同执行环境，不能把相隔环境的时间变化归因于代码，也不是实体设备性能或硬件窗口截图验收。

构建 **660.990 秒**，主包、appTest、dataTest 安装均成功；APK 的源提交内嵌为 `7949011…`、构建提交为 `a65f859…`、`formal=false`，不沿用旧包的 `local-unknown` 或字节相同结论。构建未运行全套 JVM/lint/Room/UI，也未重测 GC、并发保存或 Docker。

| APK | 字节 | SHA-256 |
| --- | ---: | --- |
| app-debug | 229,736,415 | `e7e48e124e6c7e94522b5a08beb8dd81fb52fd878dde2122d8767a0f0fbe42d5` |
| app-debug-androidTest | 10,470,675 | `3c9b8072bbd5fd280dcde7e618e2ee5e8b42589adf366d35add3d513c5957dc6` |
| data-local-debug-androidTest | 22,948,888 | `86d8b2a9b02bcda6319bf1ffa22d7fcfa6e9f3c6d3c6d687aeeb3c2c60a09e3b` |

[构件回执](evidence/apk-hashes.json)含 arm64-v8a、armeabi-v7a、x86、x86_64 四 ABI 的真实 MuPDF ELF 大小/哈希；工作流在安装前验证 ELF 头、体积和签名。此处复核的是经 ZIP 哈希绑定的回执，没有再次下载 APK。

仅为当前 UID1001 临时增加 `/dev/kvm` rw ACL，使用 `--no-mask`，保留原 ACL 与节点 identity。trap 与 always 两份回执均为 `STOPPED_CONFIRMED`、`RESTORED_EXACT`、`errors=[]`；[before](evidence/kvm-before.acl)、[after-trap](evidence/kvm-after-trap.acl)、[after-always](evidence/kvm-after-always.acl)逐字节一致，SHA-256 均为 `f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89`。额外 UID/mask 条目已移除。结论限所记录的模拟器与进程组，不扩大为任意脱离组的后代。

每个方法后，app、appTest、dataTest 的 pidof 都为 exit1/空输出；这是方法内资源断言之外的进程复核，不是用进程消失替代资源安全。没有 chmod666、加组、udev、模块加载、自托管/本地回退或付费 runner。

## 第一轮失败和本次最小脚本修复

[第一轮 38362557447](https://github.com/InkWeftDevs/InkWeft/actions/runs/38362557447)固定相同源码、工作流 `8d927024ee76f30e7970d44d4e55efa1b896d8ff`：构建成功、模拟器启动完成，但 APK 安装前的 `emulator -version` 返回127。该非核心元数据命令的异常被作为致命错误，五方法均 **NOT_RUN**。错误输出未保存，127 的底层原因不能据此确定；不归因于某个缺失库。第一轮清理和 ACL 恢复也成功，失败保留为单独记录。

第一轮用户提供的 ZIP 为14,645字节，SHA-256 `81b4c910c72a430806fc2b814ce610f4afb762b718c4559c8146c9c2de6276de`，与 GitHub 工件 `11795244148` 元数据一致，15 个 payload 的大小和哈希均匹配。此处另存[失败 summary](evidence/first-run/summary.json)、[trap 清理](evidence/first-run/cleanup-trap.json)、[always 清理](evidence/first-run/cleanup-always.json)、前后 ACL 与[原 manifest](evidence/first-run/manifest.json)，作为第一轮的有限归档；不与第二轮的五项通过合并计数。

用户另行批准第二轮后，工作流仅改为从已有启动日志读取版本，缺失记 null，并保留失败命令的末8,192字符输出。触发保护限定从 `8d927024…` 推进的那次 push 且 attempt1。没有 rerun 第一轮、重复诊断或改变原五方法。本次运行正确写出了版本及设备环境。

## 可关闭与仍未关闭的范围

- **本次候选 / KVM 原方法验证已闭合**：搜索128行/10秒及后续新鲜性断言；备份15秒内真实写入取消和恢复断言；1,201笔/76,802点完整帧30秒；pan五等待点各30秒；局部追加/隐藏的像素、工作量及资源断言。
- **历史记录不改判**：旧 TCG 超时仍是原版本/原环境失败；历史 C2 等待点未知，旧 C/B/B/C 仍 INCOMPLETE。此次没有证明旧失败仅由 TCG 造成。
- **性能验收仍缺**：没有同 KVM 环境的基线、重复样本或顺序配对，单层历史回退没有关闭。没有新的搜索并发保存 p50/p95/max、GC 保存阻塞或分页实际读取量结果；旧配对中的直接保存 p50、全部索引时间和 GC 保存延迟回退仍保留。

最小后续动作是先固定可审查的受控基线与候选、相同测试补丁和诊断设置，再在同一临时 KVM runner 预先规定配对顺序/样本数，重点测原 localAppend，保留所有失败和原值，同时核验像素、decode/rebuild/native 数和清理。若要改变搜索保存/GC 保存回退结论，也需对应的同环境配对。**这需要新的执行授权，本次没有启动。** 当前不需为凑绿继续修改产品代码或放宽断言。

## 报告推送边界

报告基点为 PR20 的 `7949011…`，目标 `codex/android-perf-integration-20261012`，PR base 为 `codex/storage-optimization-20261011`。已检查该基点所有 workflow：a0/source-snapshot 的 push/PR 只匹配 main；六批和只读诊断的 push 只匹配旧六批分支；一次性 KVM workflow 仅存在于另一分支且有精确 before-SHA/attempt 保护。本次仅增加此证据目录及更新 PR 描述，不复制 workflow，不触发新 KVM。

没有修改 main、合并、部署、访问用户电脑/真实笔记或编辑 backup-server。模型快速模式没有可用独立控制，实际状态未核实，未改模型或思考设置。
