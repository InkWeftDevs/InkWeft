# Budget3 终态独立只读复核

结论：官方终态元数据和保存的 job 日志相互一致，run `38441089594` / job `115447545993`、attempt 1 已成功结束。日志含按冻结计划顺序报告的 57 条 host/JUnit PASS 摘要，以及两次成功清理声明。**当前没有原 ZIP；不能把这些摘要写成已经独立核验 57 份原始 JUnit、具体性能数据、APK/native、helper 清理顺序或 ACL 原始字节。**

本复核仅本地读取并解析指定四文件及冻结 method plan，没有下载、重跑检查、启动任务或修改控制器/验证器。

## 身份与时间

- run 为分支 `codex/kvm-pr20-candidate2-budget3-20261013` 的 push、attempt 1；head `6b180ef65fa0d966f42967fe6a592f7bfe0a8ae5`，与已审激活提交一致。
- job 开始 `2026-10-13T17:44:33Z`，结束 `18:25:31Z`，2458 秒（40 分 58 秒）。使用 job 时间而非 run 创建时间计算，低于 60 分钟硬限。
- 冻结 anchor 为 `17:42:19Z` / `1791913339`，早于实际 job 开始 134 秒。固定 setup/correctness/common/work 截止仍是 UTC 18:20:19、18:23:19、18:35:19、18:37:19。

下表仅为 GitHub API 步骤起止的秒级墙钟，不是纯 Gradle 时间或测试内测量：

| 阶段 | 秒 | 完成时间 UTC |
| --- | ---: | --- |
| 源码/SDK | 33 | 17:45:16 |
| B 构建步骤 | 625 | 17:55:41 |
| C0 构建步骤 | 542 | 18:04:43 |
| C1 构建步骤 | 473 | 18:12:36 |
| KVM/模拟器 | 33 | 18:13:09 |
| 30 项正确性 | 43 | 18:13:52 |
| 24 项公共比较 | 612 | 18:24:04 |
| 3 项诊断 | 54 | 18:24:58 |
| 成功路径清理 | 19 | 18:25:17 |
| always 清理 | 8 | 18:25:25 |

setup 完成距 anchor 为 1850 秒（30 分 50 秒），实际也早于历史 32 分钟截止 70 秒。因此本轮完成不能单独归因于扩大到 38 分钟。前次 C1 的 434 秒是被截止截断的步骤窗口，不能当作完整构建时长或计算加速比；CI 步骤差异也不能证明测试性能变化。历史 17 项正确性阶段和本轮 30 项不构成同清单速度对比。

## 日志可证明的范围

- 从保留 BOM 的原日志只解析时间戳后直接出现的 JSON，未把 shell 代码中的 echo/fallback 字符串当作执行结果。
- 行 392–421、452–475、506–508 共 57 条 compact method receipts，ordinal 完整为 1–57；逐项 ordinal/phase/identity/selection 与冻结 method plan 相同。分为 30 correctness、24 common-comparison、3 candidate-diagnostics；合计 35 个不同 class#method 名称，**57 是执行次数，不是 57 个不同测试方法**。
- 全部摘要为 `status=PASS`、`junit_status=PASS`、`adb_exit=0`、`error=null`。第 17 条（行 408）唯一报告 `AUXILIARY_AM_KILL_CONFIRMED`，其余 56 条报告 `NATURAL_EXIT`。这不替代原 instrumentation 输出、JUnit 派生一致性、逐次 pidof 与 helper 命令/观察窗口顺序的独立复核。
- 实际清理 JSON 位于行 525（18:25:17.4957263Z）和 547（18:25:25.0344407Z），两者均报告 `emulator=STOPPED_CONFIRMED`、`command_groups=STOPPED_CONFIRMED`、`acl=RESTORED_EXACT`、`errors=[]`。可确认两次清理执行并作出一致声明；没有原 ACL 文件，尚未独立比较 before/granted/after 字节或进程记录。
- 行 570 的实际 packaging 输出报告 289 payload、3,555,071 字节、omitted 0、truncated 0；行 595 的上传动作报告 291 文件，与另加 manifest/packaging 两文件相符。它们是日志声明，当前没有独立展开并核验这 291 个文件。
- 日志没有 `LIBRARY_READ_EVIDENCE`、`TILE_LOCAL_EVIDENCE`、`HISTORY_DELETION_EXPOSURE_SUMMARY` 或 `LIBRARY_STAGE_DIAGNOSTICS` 原值。不能计算配对阈值、720 保存样本/分位数、warm 原序、绘制计数/像素、GC 实际 DELETE 暴露，不能宣布剩余性能门槛关闭。

## 上传元数据与待核证据

API 和上传日志一致报告 artifact `11837544923`，名称 `pr20-kvm-triple-38441089594`，ZIP 769,104 字节，SHA-256 `e3218dda22503f2bc085cf2e96284ce0bd24f5b05c40dcee772c13b2ec7318aa`，过期时间 `2026-10-16T18:25:25Z`。这是记录的 ZIP 身份，**本复核未获得或计算 ZIP 本体哈希**。

后续原工件核验仍包括：原 ZIP/manifest/白名单与源绑定；57 份原 instrumentation/JUnit 和 host 结果；9 APK 的记录身份、签名及 native/依赖回执（工件按约不含 APK 本体）；14 安装组环境、同 seed 夹具及 writer 配置；第 17 条 helper 清理时序、其他方法自然退出；两次原 ACL 字节；完整配对和独立诊断原值。此前慢样本、失败与未闭合结论均保留，不由这次 job success 自动覆盖。

## 本次读取文件哈希

| 文件 | 字节 | SHA-256 |
| --- | ---: | --- |
| jobs-terminal.json | 4,532 | `aa16405f5acccfccae922d3afe067008464e406b75e4818af0636d0cccf5f39a` |
| run-terminal.json | 12,695 | `d5c6426aa0727054b53115c11d6d7291055b2791536cb63e8c2147f3b79512e5` |
| artifacts.json | 763 | `9ff9e540f80c605a355fcfdf6d25d48280fa4eefc8f0694fded2c5d37ff96d85` |
| job-decoded.log（保留 UTF-8 BOM） | 63,523 | `6717e63192b2dd43b6ca66f378f659efcdcd7b291354885e89b92466f6198b97` |
