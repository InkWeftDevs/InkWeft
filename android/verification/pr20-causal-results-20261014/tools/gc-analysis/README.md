# GC 因果诊断分析器（等待真实输入）

准备完成；当前只有14项明确合成检查，无真实运行结果。只读取指定本地records/receipt并写单独scratch目录，不下载、不执行artifact代码、不启动设备/Gradle/网络，不改变源码、probe或schema。

`analyze_gc.py`固定处理方法11 same-room、12 separate-room，逐方法30试验；所有原序trial、summary、保存数组、guard分量、首次及收尾计数/时间都进入JSON，禁止合池旧45b/d66或公共比较。`check_analyzer.py`及`synthetic-check.json`是解析器/算术测试，不是原生性能证据。

两种输入方式保持不同证据层级：

1. 原始本地工件目录：每方法包含`method-11-C1-records.json`、`-instrumentation.log`、`-log-forwarding.json`、`-junit.xml`（12同理）。核对原日志解析与records相同、SHA及方法上下文、JUnit1PASS，但外部ZIP/APK/ACL完整性仍由父侧审计。
2. 官方终端日志完整提取：传runtime `analyze_logs.py` 的`analysis.json`，必须为`COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE`且issues为空。逐方法核对state.blocks的context、ended/method_pass/ordered，以及extracted_records的records SHA、record_count、block_closed、method_pass、whole_run_complete。**仅验证限定日志记录的绑定，不宣称拥有原始instrumentation、APK、ACL或ZIP字节。** caller必须明确提供真实run/job/workflow身份，不设伪造默认。

原始工件方式：

```sh
python /workspace/scratch/pr20-causal-20261014/gc-analysis/analyze_gc.py \
  --evidence "$VERIFIED_LOCAL_EVIDENCE_DIR" \
  --output "$SEPARATE_SCRATCH_OUTPUT_DIR" \
  --integrity-receipt "$PARENT_INTEGRITY_RECEIPT"
```

终端提取方式（路径由root给定，不能用check-01合成输出当真实结果）：

```sh
python /workspace/scratch/pr20-causal-20261014/gc-analysis/analyze_gc.py \
  --evidence "$REAL_EXTRACTED_RECORDS_DIR" \
  --terminal-analysis-json "$REAL_LOG_ANALYSIS_JSON" \
  --expected-run-id "$REAL_RUN_ID" --expected-job-id "$REAL_JOB_ID" \
  --expected-workflow-sha "$REAL_ACTIVE_COMMIT" \
  --output "$SEPARATE_SCRATCH_OUTPUT_DIR"
```

冻结source默认为`78a5babeaeb92292026423222d5e08635989198b`、readiness为`b2e1d9f9de66a26f5a4b122ea6754d77198005f8`；test SHA固定`4f80b8ce…`。如实际身份改变必须先审查，不能悄悄混样。方法上下文中实际APK、环境、来源SHA原样保留供父侧核对。

输出`gc-analysis.json`和`gc-analysis.md`；缺失、畸形、重复key、NaN、extra字段、身份/SHA/时序/计数/分位数不符均非完整结果，exit2；完整且记录一致exit0，状态也只是`CONDITIONAL_RECORDS_CONSISTENT_NOT_ACCEPTANCE`。如原日志不完整，原raw留在输入目录，解析器不会覆写或补齐。`externalIntegrity`明确不是本脚本认证范围。

三项暴露定义分别报告：

- 原事务点暴露：`saveStartNs <= before-batch-commit.atNs <= saveEndNs`。单独不能证明实际DELETE循环交叠。
- DELETE窗口正长度交叠：`max(saveStart,deleteStart) < min(saveEnd,deleteEnd)`。
- 请求实际开始于DELETE窗口：`deleteStart <= saveStart <= deleteEnd`，与probe `saveStartedDuringDelete`逐条一致。

DELETE窗口来自默认关闭observer，包含通知/时钟/回调等开销，不能称纯SQL执行或纯锁等待。`transaction-returned`也包括guard不符而零删除的事务；实际删除需before/after-delete、guard、report及保留集合共同核对。

完整30保存的allSaves与各暴露子集分开；保留全部原序索引和样本。原nearest-rank定义：n30 p50 sorted[14]、p95 sorted[28]、max sorted[29]。每暴露子集少于20时p50/p95都null，只保留计数、原序和max；原阈值不变，不补样。失败/不完整记录不能提升为功能或延迟PASS；任何存在issues的输出都需要先解决证据问题，不能引用其试算统计做验收。

GC总成本同时列首轮与保存完成后收尾的原序耗时及两者之和，排除fixture创建、两次调用间隔和最后内容检查。收尾的删除不能算并发暴露。首次defer和对应guard差值保留，不能把整个后续回收过程解释成连续删写并行。

这只是一轮条件到达诊断。原自然0/30、竞争尾延迟未闭合、纯SQLite锁等待未知以及B→C1性能验收均维持各自边界。
