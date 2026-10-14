# 62 方法官方日志离线提取器

`analyze_logs.py` 已冻结供独立审阅；仅本地读取和写入新输出目录，不含网络、子进程、Gradle、ADB、GitHub 或工件下载操作。尚未处理本轮真实原生日志，不构成新的原生通过或性能结论。历史草稿/首轮回执保留，最终脚本对应 `check-final-reviewed-metadata` 与 `process-check-final-reviewed`。

固定范围是 B=a128+共同安全 factory、C0=d66、C1=cb7e142，31 原正确性 +24 公共比较 +7 候选单端方法，14 安装组、9 个逻辑 APK 身份。旧1–58不变；59 Unicode、60 既有逐瓦片 native 构造 oracle、61 continuous、62 dense。NativeStrokeTileReuse 既有 finally 是 best-effort，不能称为严格 join；原27/28等清理门槛仍在清单中。

## 提取与计算

- 严格绑定调用者预先核实的 run/job/attempt/workflow/prep/readiness/source、7项 runner input hashes、控制文件哈希及 source manifest/test blob；模板、未冻结时钟或不匹配统计脚本拒绝。`prepare_sources.py` 另由 binding 单独绑定，不伪称其属于 runner 的7键 `input_hashes`。
- 所有62方法必须按 CONTEXT→RECORD*→END→METHOD 完整有序闭合，零记录方法也必须有 END；原 JSON UTF-8 字节、SHA、记录序号、闭合 schema、单方法上限和 footer/records SHA 全部校验。输入日志限制64MiB、单 envelope2MiB；20MiB工件限制由哈希绑定 packager 执行，离线日志不能重新证明ZIP大小。
- 精确检查三包 selected/installed SHA 声明、单端 APK 不变、同安装组信息、统一签名和环境、14组终态，以及双 cleanup/ACL 相等哈希声明。仅原 method17 的 helper `am kill` 例外允许，核自然观察、固定命令、app/data先退出、2秒后置/总5秒窗口；其他方法要求自然退出。不会凭事后进程已消失推断之前只是缓存进程。
- 时钟是先于实际 job 的不可后移 PR 服务器 anchor；38/41/53/55分钟累计截止及实际单job60分钟分别核验。块首/尾均核对官方对应步骤时间；只有官方秒精度 `completed_at` 允许记录在最后一秒内，阶段 deadline 不放宽。
- 公共统计直接调用冻结 `analyze.py::analyze(summary,plan,protocol,records_by_ordinal)`，未复制分位数/门槛公式。仍只24公共调用、720保存、18指标、54比较；nearest-rank30的15/29/30，按四seed配对。新单端方法和stage诊断不加入比较，warm仅描述。C0/C1身份以本轮固定树为准，不跨run池化。
- 原始失败/中断会保留已有方法块/数值，整体保持 INCOMPLETE。即使720公共保存全部齐全，只要最后dense失败或FINAL/清理/安装/官方终态不完整，就不能给完整统计状态。

输出 `analysis.json` 区分日志闭合与统计状态，`statistics/analysis.json` 保留冻结原计算结构；每方法 records、原序save/配对/warm/绘制/读取量CSV独立输出。输出目录必须尚不存在，输入不改写。`COMPLETE_SCOPED_MATCH_LOG_EVIDENCE` 和 `COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE` 都不是最终产品验收。

## 已完成的离线检查

`check-final-reviewed-metadata/selftest-results.json`：69项PASS，使用明确标记的历史58 raw、历史Unicode/NativeReuse/Frame作为解析夹具，dense成功形状中的1.0时间完全是合成值；合成run/job900001/900002不对应真实运行。覆盖62闭合、坏身份/seed/args、部分失败前缀、最后dense失败、原始JSON/哈希/schema/容量/缺记录、安装/签名/环境/ACL、时间/官方失败，以及解析成功后I/O故障不得保留COMPLETE。

`process-check-final-reviewed/results.json`：20项PASS，基于历史自然退出与method17观察记录，覆盖错误命令、错误runner/身份、app仍在、缺包、stderr/PID非法、超时窗口、无最终退出等。

独立审查另发现并已修复4项fail-closed边界：END ordinal及footer sequence拒绝bool冒充int、process观察时间不得为负、官方phase必须在实际job区间内。各自负例已纳入最终69项；旧64项回执保留。最终workflow展示名从已绑定文件动态读取，没有硬编码旧名称。

模块加载采用读取源码、内存compile/exec，不使用可能生成输入目录`__pycache__`的importlib loader。最终69项回执由默认Python运行（未用`-B`），另核frozen/control目录文件清单和全部内容哈希前后不变。最终夹具使用runtime `preparation62/reviewed-final/bound-control`，修正3个当前metadata字段；之前回执全部保留。

统计检查另外确认：旧58与新62的所有函数AST除 `analyze` 外一致；`analyze` 中已由runtime冻结的改动是清单计数/源和单端标签。实际以同一历史公共records调用两版，720条保存原序、分布、54比较、配对原值及warm全部一致（仅剔除变化的Git源标签）。这些是离线正确性检查，不能声明新版Android更快。

## 真实运行使用前

根节点需提供可信外部 `binding.json` 和官方原run/jobs JSON。binding要有精确 run_id/job_id/workflow_sha/source_commit/readiness_git、run_attempt、prep_sha、scope、官方四个status/conclusion、input_hashes、control_hashes（含唯一active workflow）、statistics_sha256、prepare_sources_sha256。不能直接用 `check-final-reviewed-metadata/binding.json`：它是合成夹具。控制器真实clock/active绑定仍由根节点另行审计，不由本工具生成。

```sh
python /workspace/scratch/pr20-match-log-analysis-20261014/analyze_logs.py \
  --job-log /workspace/scratch/REAL/job-decoded.log \
  --frozen /workspace/scratch/REAL/frozen-readiness \
  --control-root /workspace/REAL-EXECUTION-CLONE \
  --binding /workspace/scratch/REAL/binding.json \
  --run-metadata /workspace/scratch/REAL/run-terminal.json \
  --jobs-metadata /workspace/scratch/REAL/jobs-terminal.json \
  --run-id REAL_RUN --job-id REAL_JOB --workflow-sha REAL_ACTIVE \
  --source-commit cb7e14218ac83aa8cf6b9d7906051a10da1a6547 \
  --readiness-git REAL_READINESS \
  --out /workspace/scratch/REAL/extracted-new
```

原始官方日志 provenance 仍由外部元数据及获取记录证明。Compact JUnit PASS、APK/ELF/依赖、installed bytes、ACL字节和工件manifest哈希是受控runner的转述；没有相应原文件就不能写成独立ZIP/原JUnit/APK/ACL重新核验。可以直接核验转发的原始JSON指标，但不能从粗阶段墙钟直接推断锁等待、CPU归因或最终无回退。
