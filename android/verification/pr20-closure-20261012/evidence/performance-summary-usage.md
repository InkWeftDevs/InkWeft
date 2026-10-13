# 新 1024 / FULL 配对汇总

脚本：`/workspace/scratch/summarize-pr20-closure-performance.py`。只读明确指定的原始日志与相邻同名 `.json` runner receipt，只向 `/workspace/scratch` 写 JSON；不会启动 Gradle、ADB 或 subprocess，也不会自动扫描旧性能目录。

默认运行：

```sh
python3 /workspace/scratch/summarize-pr20-closure-performance.py
```

默认输入 `performance-summary-input.json` 当前 `pairs=[]`，输出 `paired-performance-summary-new-format.json` 为 `NOT_RUN`，没有任何性能样本或比值。只有新实测日志完成后才填写配对。例如以下是**文件名格式示例**，不是已执行声明：

```json
{
  "schema": 1,
  "provenance_references": ["baseline-closure-provenance.json", "candidate-final-source-sha256.json"],
  "environment_references": ["paired-runtime-environment.json"],
  "pairs": [{
    "id": "pair-1",
    "baseline": {
      "label": "baseline-1",
      "logs": {
        "library": "baseline-1-library-probe.log",
        "history": "baseline-1-history-probe.log",
        "raster": "baseline-1-raster-probe.log"
      }
    },
    "candidate": {
      "label": "candidate-1",
      "logs": {
        "library": "candidate-1-library-probe.log",
        "history": "candidate-1-history-probe.log",
        "raster": "candidate-1-raster-probe.log"
      }
    }
  }]
}
```

路径相对 manifest 所在目录；可用 `--manifest` / `--output` 指定其他 scratch 文件。每项日志的 receipt 必须同目录同 stem，例如 `.log` 对应 `.json`。如暂只完成 library，可省略另两项：各 probe 独立报告状态，不补值、不混合运行、不将半对当作完整配对。

结果保留每项的原日志 / receipt SHA-256、来源、runner 元数据和完整探针 JSON。Library 的 `saveSamplesMs`、GC 的 `pausedScanSaveSamplesMs` / `liveSaveSamplesMs` 保留 30 个数的原序；检查分位数时排序副本。Library p50 是 `sorted[15]`，GC 两组 p50 是 `sorted[14]`；两者 p95 都是 `sorted[28]`，max 都是 `sorted[29]`。不得把这些 p50 误写为偶数样本中间两数的均值。

输入必须具备探针记录的 `writerSqlite`，且实际 `page_size=1024`、`auto_vacuum=1`。缺少或旧连接的 `sqlite` 字段不能代替 writer 信息；旧 4096 / INCREMENTAL 记录的性能值不会进入新汇总。配对还要求 `journal_mode` / `synchronous` 相同、receipt 的 emulator serial / environment 相同，并核对原始 JUnit `OK (1 test)` 和 runner 成功、样本数 / 分位数、夹具规模与 14 个 raster case。

`PAIRED_RECORDED_ENVIRONMENT` 仅指脚本能够核对的记录一致，不能自动证实最终 APK/source 一致、屏幕 / 字体 / 超时设置一致或没有同期 build。必须人工核对引用的实际环境、安装 / 签名、源码证据和执行顺序。`provenance_references` / `environment_references` 是来源引用，不会由脚本变成已经核验的结论。

Raster 保留所有 case 的 decode、snapshot rows、rebuild、native shape 计数及 pixelsEqual。baseline 不支持的 `nativeStrokeBuilds` 等计数及其总和保持 `null`，不填零；cold reference 的 native shape 数探针本身未输出，汇总也为 `null`。12 次指定连续平移 / 缩放的中位数是 sorted[5] / sorted[6] 均值，不能描述为 12 次独立重复实验。

GC 保留无竞争的 `collectionMs` / root scans、暂停扫描保存组、不暂停的保存组及 `liveCollectionAndSavesMs`。最后一个计时包含 30 次保存和随后的补充 GC，不能称纯清理耗时。Library 的行数 / 字符数是数据层跨 cursor 的读取规模，不等于操作系统磁盘字节。所有变化百分比为 `(candidate / baseline - 1) * 100`，分母零或任一指标缺失即 `null`。

这个脚本只汇总这 3 个性能 probe；`TileLocalRedrawTest`、native shape 专项回归和其他功能门槛需另行记录。空 manifest 的运行只验证脚本能保持未执行状态，不是 Android 性能测试。

新版 Library probe 另加 `observeIndexConcurrentSaves`：该对象及其中 `saveSamplesMs` / `saveProgressSamples` 原样保留，不与第一组或 GC 样本混合。新组 30 项 p50 / p95 / max 仍分别是 sorted[15] / [28] / [29]；每个 progress sample 的 `sample`、`elapsedMs` 必须对应原序时延数组。

脚本逐项校验 `indexRowsBeforeSaves <= indexRowsAfterSaves <= indexRowsFinal`、最终已提交行数等于 20000 页，以及每次保存的 `indexRowsStart/End`、`sourceChecksStart/End` 和总 `sourceChecks` 的非负单调边界。另输出每个保存样本中两种计数的推进量、整组索引推进量，以及分别有索引 / 原文扫描 / 两者推进的样本窗口个数。没有推进、只在样本之间推进，或没有同一样本窗口的双计数推进，都明确不能称为已证实三方重叠。即使两种计数在同一窗口增长，也只支持该采样窗口内的进展，不是严格同时运行或锁等待耗时。

`firstCommittedBatchMs` 和 `allIndexRowsCommittedMs` 分别在首个提交批次与全部索引行提交的实际回调中计时。`searchIndexReadyMs` 则在全部 30 次保存完成，且 `allRowsCommitted.await()` 与 `observedIndexReady.await()` 都返回之后才采样；它是从 observe 开始到“保存完成、索引全部提交、实际观察到正确 pending=0 结果”的联合完成时间。pending=0 结果可能更早到达，因此该字段不能称为首次 zero-pending 回调时间或纯索引耗时。

缺少整个 nested 组时报告 `NOT_RECORDED`，不从原第一组推断；nested 存在但字段、原序对应关系或分位数错误时拒绝配对。baseline/candidate 一侧是旧手动 builder 而另一侧是新 observe 时，也拒绝配对，避免混淆热搜索的前置负载。
