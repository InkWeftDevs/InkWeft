# 62 方法日志提取器独立审查

结论：最终冻结的 `analyze_logs.py`（`bafc7ca19ad2fb0523dd5d859ef0b521c78d8715d888c30dee274fdf97eae800`）在本次审查范围内没有尚未关闭的已确认阻断。最初发现的四个解析边界均已修复并独立复现拒绝；随后发现的输入目录字节码缓存写入问题也已修复。此结论仅支持使用该脚本作离线、范围受限的日志闭合与冻结统计复算，不能替代真实执行绑定审查，也不是原生测试、性能或产品最终验收通过。

审查日期为 2026-10-14。审查对象位于 `/workspace/scratch/pr20-match-log-analysis-20261014/`。本次没有修改提取器、控制器、生产代码或测试探针，没有构建、Android/ADB、CI、网络或 GitHub 操作。仅写本报告与同名 JSON 独立回执；真实 clock/readiness/prep/active/run/job 绑定仍由父任务另行检查。

## 最终字节与离线回执

| 文件（相对审查对象目录） | 字节 | SHA-256 |
| --- | ---: | --- |
| `analyze_logs.py` | 33703 | `bafc7ca19ad2fb0523dd5d859ef0b521c78d8715d888c30dee274fdf97eae800` |
| `test_analyzer.py` | 22637 | `9536712d7117c7ab5d763dad8b531f2cb0e7beeb2ffeb220ce4e3fdf3dc220d7` |
| `test_process_receipts.py` | 3218 | `ed9368f59ce921533cf0560b876b7eb8e979be687c7bb70616b575064fcc0056` |
| `check-final-reviewed-metadata/selftest-results.json` | 24280 | `68e83ee9bfbedbbe311bc95945d22c5a9e53314769939976df7fcaa465891673` |
| `process-check-final-reviewed/results.json` | 2554 | `13ee3ec781e017d675249b02d9a11e7a8a6b025ac28fd34a10222fec0dcafdc7` |

69/69 与 20/20 PASS 是作者运行、此处独立读取并核对哈希/项目数/状态的离线回执，本审查没有重新运行整套 checker。最终 69 项夹具来自 `preparation62/reviewed-final/bound-control`，包含最新 metadata 修订；真实身份仍未由这些合成夹具绑定。合成 run/job 为 900001/900002，dense 的 1.0 时间是明确标注的解析数据，不是新原生记录。

本审查另外直接执行五项内存检查，均使用最终脚本及最终合成输入；完整正例为 `COMPLETE_SCOPED_MATCH_LOG_EVIDENCE`、issues 为空，四项负例为 `INCOMPLETE`。未启用 `-B`，`sys.dont_write_bytecode=false`；调用 `frozen_inputs`、五次 `analyze` 与一次冻结 `calculate` 前后，对输入 control 全树 31 个目录/文件条目的路径、mtime、大小和文件 SHA 做快照，完全相同，无 `__pycache__`。独立检查详情保存在 [同名 JSON](pr20-match-extractor-review-20261014.json)。

## 发现、修复与独立验证

| 原问题 | 最小修复 | 最终独立负例结果 |
| --- | --- | --- |
| `END.ordinal=true` 可利用 Python 的 `True == 1` | 对 RECORD/END ordinal 显式要求 `type(...) is int` | `ValueError: END count differs` |
| footer `sequence=false` 可等于 0；重新计算 footer SHA 后仍被接受 | footer sequence、payload_bytes 显式整数类型检查 | `ValueError: Footer integer type differs` |
| 首个 process observation 可为负时间 | 观察时间必须有限且 `0 <= at`，再核单调及总窗口 | `ValueError: Process chronology differs` |
| setup phase 可处于 anchor 之后、actual job 之前 | 每个官方 phase 的开始/结束同时落在实际 job 区间内 | `ValueError: Official phase exceeds budget/sequence` |

以上四项在最初 `add214e…` 版本可被误收为完整，此事实应保留；`4cb0013a…` 已修复，最终 `bafc7ca1…` 再次独立验证保持拒绝。完整 62 项正例同时继续通过，不以全面拒绝输入掩盖问题。

作者之后发现 `importlib.exec_module` 可能向 frozen/control 写入 `__pycache__`；最终改为读取已验哈希的源码，以 `types.ModuleType + compile + exec` 加载，保留原模块源码与公式。本审查在允许 Python 正常写缓存的默认配置下验证了输入全树不变。旧 64/68 项回执保留为历史，最终有效回执为上述 69/20 项。

## 完整性和失败前缀

固定计划必须是 62 次调用、ordinal 1–62，阶段数量为 31 正确性、24 公共比较、7 候选单端方法；14 个安装组及 B/C0/C1 三身份同时检查。原 1–58 保持既有范围；59–62 是 Unicode、既有逐瓦片 native oracle、continuous 和 dense 补充，不能进入公共比较。

每一项必须按 CONTEXT → RECORD* → END → METHOD 顺序闭合。零记录的方法也需要 END；严格检查 ordinal、身份、selection/args/seed、测试源码与源 manifest、原 JSON UTF-8 字节数及 SHA、marker 白名单、封闭 schema、数量上限、记录序号、footer 与紧凑 records SHA。JSON 拒绝重复键及非有限值；单 envelope 上限 2 MiB，输入日志上限 64 MiB。冻结 marker 验证器再次验证整个方法的计数、顺序和语义关系，不仅验证每条记录的形状。

只有 62 个闭合块、62 个 PASS METHOD、精确 FINAL 方法清单、14 组 PASS、无缺失文件、打包与双清理成功声明、官方 run/job 成功、阶段时间与身份均满足时，才可能给出日志完整状态。晚到的 I/O/解析错误会降为 INCOMPLETE。

失败前缀保留已验证的原记录，并标注 `block_closed`、`method_pass`、`whole_run_complete=false`。最后 dense 失败的负例保留前 61 项及全部公共 720 条保存样本，但整体与统计状态都保持 INCOMPLETE；54 个已有观察分类不会变成完整验收分类。不能因为公共数据已齐全而忽略后续正确性失败。

## 原统计公式与调用范围

提取器的 `calculate()` 直接调用冻结 `analyze.py::analyze(summary, plan, protocol, records, issues)`，没有重新实现分位数或回退分类公式。冻结统计 SHA 为 `fdbf7b76ebbf587bccaede00910209ba2586c0982bbd0da80cb26cb94ce07740`；协议 SHA 固定为 `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba`。

独立 AST 对照旧 58 项统计脚本，7 个函数中只有 `analyze` 改动；改动是 58→62、候选单端数量/标签及执行完整性条件，其余统计函数 AST 相同。作者 checker 以同一历史公共记录实际调用两版，仅剔除变化的源/运行标签，逐项要求 `comparisons`、`paired_values`、`raw_saves`、`save_distributions` 和 `warm_comparisons` 全等。独立完整正例调用亦得到 720 保存、54 比较，状态为 `COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE`。

公共计算仅取方法 32–55 的 24 次调用、四 seed 配对；18 指标对应 9 个 raster 时间、6 个保存分布指标和 3 个搜索/索引完成时间，三组源比较产生 54 行。nearest-rank 在 30 样本时取第 15/29/30 个有序值；原相对与绝对 band、跨 seed 判定保持冻结定义。暖读仍只有描述性比较，没有新增独立 warm 门槛。方法 1–31、56–62 不进入这 18 指标的公共比较。

## 身份、预算、安装与清理声明

外部 binding 需提供可信的 run/job/attempt/workflow/prep/readiness/source 与控制文件哈希。脚本检查官方元数据对应同一 push、固定分支/工作流、唯一 job、attempt 1；B=a128、C0=d66、C1=外部固定源。5 个 readiness 输入与 2 个 clock 输入、prepare/statistics、源 manifest/测试 blob、哈希绑定的控制文件和唯一 active workflow 都分别核对。相对路径禁止绝对路径、`..` 和逃出 control root。优化 Python 模式被拒绝，避免禁用冻结验证器中的断言。

clock 使用先于 job 的 PR 服务器 anchor，累计 setup/correctness/common/diagnostics 截止为 38/41/53/55 分钟；实际 job 另受 60 分钟限制。绑定 workflow 的八个阶段必须唯一、顺序成功，处在 actual job 内且满足各自累计截止。块首尾还要落在对应官方阶段内；官方 `completed_at` 的秒精度允许最后一秒记录，累计 deadline 没有因此放宽。

三包 selected/installed/source-apk SHA 必须一致，单源 APK 稳定、组内安装信息稳定、所有源 signer 一致、全局环境哈希一致。FINAL 同时核对 trap/always 的进程停止、零错误、ACL 相等哈希和共同 origin。以上是绑定控制器的日志声明；没有原 APK、installed bytes、ACL 与 manifest，就不能写成这些字节已独立复验。20 MiB 文本工件、3 天保留和完整文件集合由绑定 packager 实施，日志提取器本身不能重新证明实际 ZIP 内容或大小。

## method17 唯一进程例外

普通方法只接受自然退出：完整三包 pidof 观察、空 stderr、合法 PID/absence、单调非负时间，最终全部 absent，窗口不超过 3 秒。

仅 ordinal 17、C1、指定原 UI 方法和指定 test runner 允许一次固定 `adb -s emulator-5554 shell am kill --user 0 org.inkweft.app.a0.test`。最后自然观察必须 app/data 已 absent、helper 仍有 PID；随后命令成功且无输出，后置三包均 absent，总窗口不超过 5 秒、命令后的窗口不超过 2 秒。实现保留原规则的 2.75 秒可启动边界（3 秒观察预算预留 0.25 秒），不能写成“保证完整等待 3 秒”。其他方法、force-stop、app/data kill、缺包/非空 stderr 均不被此例外授权。

这些顺序与命令只能从日志声明核对；不能证明没有未记录的命令，也不能由后来 absent 推断先前一定只是缓存进程。

## 保留边界

官方日志与元数据来源由外部获取记录和绑定提供，脚本不联网独立认证来源。raw instrumentation 日志 SHA 在回执间相互链接，但 compact 输出无法重构整份原始 JUnit 日志；转发的原 JSON 与 parsed records 可以独立验哈希。APK/ELF/依赖、签名有效性、安装内容、ACL 原文、工件 manifest 和 group post-environment 都不能仅凭这些声明做第二次字节审计。

旧失败与未闭合的性能问题不会因 69/20 个离线检查、合成 dense 成功形状或日志完整状态消失。真实 62 项执行闭合、原公共统计筛查与产品最终验收是三个不同结论。本报告不声明新候选无回退，不保证 62 项能在预算内完成，也不批准启动任何运行。
