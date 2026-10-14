# 本轮官方日志独立完整性核对

结论：在已取得的官方 API JSON 与 decoded log 范围内，身份、13项方法声明、两安装组、原始诊断 payload 和提取字节、阶段预算及终态闭合一致，未发现矛盾。可采用 `COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`；仍为 `DIAGNOSTIC_ONLY`，数值验收 `NOT_EVALUATED`。

本次只读取本地文件，用独立短脚本重算哈希、计数、序列化、顺序与时间窗；没有重跑原 parser、Android、构建、CI或下载工件，没有修改原始证据或代码。

## 身份与时间

- run `38514139119` / job `115689021611` / attempt 1，官方 run 与唯一 job 均 completed/success；push 分支 `codex/kvm-pr20-causal-20261014`，工作流路径 `.github/workflows/pr20-causal.yml`。
- workflow `d4a107dd84c6e309b5dbbbfb3a57d69371024408`；prep `cedffff8ff652189715f69eb4dac5e0ba22e5a7d`；source `78a5babeaeb92292026423222d5e08635989198b`；readiness `b2e1d9f9de66a26f5a4b122ea6754d77198005f8`。六份冻结输入、八份执行 control、四份原输入文件哈希均与 binding/parser 输出及本地冻结 clone 匹配。
- job `2026-10-14T04:58:11Z` 至 `05:13:09Z`，898秒（14分58秒）。run 的 created/run_started_at 为04:58:09，不能将它与 job 开始混称。
- 首步预算 anchor `1791953894`（04:58:14Z），比官方 job 开始晚3秒；实际首步采样1791953894.0391302。30/45/50/55分钟分别为 setup/data/raster/work 截止，60分钟是 job硬限；本次全部提前完成。

| 官方步骤 | 秒数 | 完成时相对 anchor | 截止分钟 |
|---|---:|---:|---:|
| 源准备、官方依赖与SDK | 30 | 34秒 | 30 |
| 构建及APK身份检查 | 651 | 685秒 | 30 |
| 临时ACL与模拟器 | 33 | 718秒 | 30 |
| data 12方法 | 158 | 876秒 | 45 |
| raster 1方法 | 8 | 884秒 | 50 |

阶段按官方 steps 顺序连续，没有把 setup/构建等时间重复相加。13个 CONTEXT首时刻至METHOD末时刻均位于对应 data/raster 步骤内，并早于真实 phase/work截止；结束时刻的1秒容差仅对应API秒精度，未用于延长预算。

## 方法与 payload 闭合

原日志为1,058,264字节，SHA-256 `fd9b4b5b71ecec3f8a59d5875678e6dfb2e43f4056b8916401aba7804ad21287`。独立读取：CLOCK 1、CONTEXT 13、RECORD 127、END 13、METHOD 13、FINAL 1。无遗漏/重复方法；13项顺序、selection、C1身份与冻结计划一致，均声明 host/JUnit PASS、adb_exit=0、NATURAL_EXIT、无error。没有辅助进程强制清理例外。

| ordinal | 方法 | 转发记录数 |
|---:|---|---:|
| 1 | `NoteRepositoryTest#sameCommandReturnsSameCommittedRevision` | 0 |
| 2 | `NoteRepositoryTest#staleEditorCannotOverwriteNewHead` | 0 |
| 3 | `NoteRepositoryTest#blankTextAndHistorySurviveReopen` | 0 |
| 4 | `NoteRepositoryTest#oldReceiptIsNotReplacedWithCurrentHead` | 0 |
| 5 | `LibraryTextIndexPreparationTest#cancelledFtsWriteRollsBackBeforeClearingPending` | 0 |
| 6 | `LibraryPagingTest#cancelledSetupStopsBeforeNextStageAndNextReadSeesCommittedAuthorState` | 0 |
| 7 | `LibraryPagingTest#sqlPagingMatchesUnicodePinnedOrderAndLoadsOnlyBoundedPreviews` | 0 |
| 8 | `LibrarySearchCausalTest#profiledContainsPreservesUnicodeAndSourceResults` | 1 |
| 9 | `LibrarySearchCausalTest#warmUdfCostsAndQueryPlanAgainstInMemoryReference` | 1 |
| 10 | `LibrarySaveCausalTest#separateTrackerReaderAndIndexContention` | 60 |
| 11 | `HistoryDeletionCausalTest#sameRoomWriterConditionalDeleteArrival` | 31 |
| 12 | `HistoryDeletionCausalTest#separateRoomWriterConditionalDeleteArrival` | 31 |
| 13 | `RasterCausalDiagnosticsTest#oneFourEightLayerAppendReportsFrameStagesAndMatchesColdFrames` | 3 |

127条原始 JSON payload 共829,662 UTF-8字节；逐条长度/SHA、sequence、END ordered footer与其SHA均独立复算。重建的13份compact records（含末尾换行）与提取文件逐字节一致，并同时匹配CONTEXT及extracted ledger哈希。方法1–7为空数组是预期无marker，不是缺失诊断。

记录量：Unicode 1、warm 1；save 12个结果+36个stage分片+12个END（6模式×正反两block、360次save、3792个记录事件）；GC 2×（30 trial+1 summary）；raster 1/4/8层各1条，共65 frame记录。raster三条均声明pixelsEqual与joined为true。这里只核数量和闭合，不将各事件当独立样本，也不据此评判因果或性能收益。

## 安装、环境及清理声明

两个组 `C1-data-diagnostics`、`C1-raster-diagnostics` 均PASS；单一 source manifest SHA `0b8edd425820f756686eabf0a4f1cbaeaa555e83512d817024c3c270257d8a7c`、APK回执SHA `0407261de03c4b675991e86cb38f0a2757ec1705b02cb218593b85ceb6216ce6` 贯穿全部context。三APK选择/安装/source_apk_sha256相等：

- data test：`9124bcfaea92848b6518d2a4801dde14c5e80cab74220d94b7bdb7e63da36561`
- app：`44474fdff519856a4e3cb6ba148d93e72bec1cb4a7d6ef953a6e8ae39f883a93`
- app test：`4daa78a45ead67f6492d61c83e65af7262300ae8729adb111405a24e0c0f303d`

三者签名指纹一致 `efaf3e090c1a16ad7f44243a76729300086f73565286c73aa0445771c546180b`。全局前后及context环境哈希均为 `bd8a6a68e3862125cb02a5bf4d9e4ca24b46bc961f2fce19815488711cd63cba`。

实际日志第524、546行两次cleanup JSON均声明 emulator/command_groups STOPPED_CONFIRMED、ACL RESTORED_EXACT、errors=[]；FINAL与之吻合，前后ACL哈希均 `f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89`、exact_match=true。没有把第530行shell脚本回显的NOT_STARTED示例误当实际cleanup结果。

日志包装声明102 payload、2,490,473字节、omitted=0/truncated=0、missing_files=[]、packaging_complete=true。上传日志声明artifact `11872300636`、ZIP445,040字节、SHA `360ffafb15fed87136ae447ba1931cce9851a139e70fa9e07c51e9db722ff74f`。本次未取得/下载ZIP，以上不是manifest逐文件或ZIP实测核验。

## 可引用范围与未证明部分

- 可以引用本轮官方终态、实际阶段时间、13项严格controller摘要、两组安装与清理声明，以及原日志转发的完整诊断数值。无需为读取这些已闭合payload重新索取ZIP。
- “13原方法PASS”应写成官方日志中13个原方法的JUnit/host摘要均PASS；原始instrumentation日志/JUnit XML并未在本次单独重验。raw_log_sha256只复核CONTEXT/FINAL一致，不能独立重建原始测试日志。
- 本次没有原APK/ELF/签名工具原回执、安装文件、设备环境文件或ACL文件；其字节与操作效果是已绑定controller的声明。不能声称已独立重验这些对象或102文件manifest。
- 全部是一个候选、单一云KVM模拟器的开启诊断数据，不是原公开配对性能协议或物理设备结果。不能将本轮成功说成既有性能回退闭合，不能将粗阶段差值称纯锁等待、独占CPU或可相加因果。
- 各领域数值解读由search/GC/raster审阅继续；本报告不代替这些计算，也不混入历史不同APK/不同诊断开关样本。

派生 `extracted/analysis.json` SHA-256：`54a2aa6890b4d36a92bf50c4a536cf8538ad8ed84ed54717ab545785f213bc01`；其已审 parser SHA `2c96309f3f8a847f86bdb62cf87f45405461ebcaa76b3659a2cee8181758fa57`，结果issues=[]。本报告的独立复算未发现与之冲突。
