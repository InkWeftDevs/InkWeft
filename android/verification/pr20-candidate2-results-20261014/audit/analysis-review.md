# Run 38441089594 配对分析独立复核

结论：独立计算与冻结分析输出一致，未发现阈值、原序保存样本、配对或分类计算错误。保留 `COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE`：B→C1 仍有 1 项回退信号、4 项不确定；C0→C1 有 5 项不确定，不能写作性能全部通过。

本次只读取本轮原始文件，从 24 份 common instrumentation 日志独立提取 JSON 标记并重算，未导入或执行原 analyzer，未启动构建、设备或网络操作。与其派生 records 逐项一致；720 个保存原序值及其 start/end/elapsed/committed、全部 216 个配对值和 54 个分类一致；原 analysis 的 118 个输入哈希均匹配。没有合池其他 run。

## 身份、口径与原阈值

- B=`a128bb07e34cb82f00eeafc8e62a47bf7b95b128`，仅叠加已冻结安全初始化 factory 和共同探针；C0=`ebb48481e7e995288c1844d43011398c513b4f4e`；C1=`45b4861ae23ac19eda3246fcd34cf3d2132dc1ee`。本轮 C0 不是历史 run 的 7949011。
- 四组 seed 4101/4102/4103/4104 的顺序分别为 B–C0–C1、C1–B–C0、C0–C1–B、B–C1–C0。C1 相对 B/C0 先后各 2 次；B→C0 为 3/1 顺序不平衡旁证。
- 单次 30 样本使用 nearest-rank：排序后零基索引 14/28/29 对应 p50/p95/max。24 组 legacy `saveP50Ms` 使用索引 15；这些原字段保留，但没有用于本轮分位数或筛查。
- 每个指标先计算四个同 seed 差值 Δ=右−左与相对变化 (右−左)/左；分别取四个差值/相对变化的中位数。没有用 `median(右)−median(左)` 替代配对差中位数。
- 至少 3/4 对变慢，且配对绝对、相对变化中位数分别严格大于对应阈值，才是 `REGRESSION_SIGNAL`；任一对同时超两阈值而未满足该条件为 `INCONCLUSIVE`；否则为 `WITHIN_PROPOSED_BAND`。不完整或输入问题优先阻止验收。
- 只有 24 次公共调用进入 18 指标。warm、首 64 条、吞吐派生值、direct 联合终点以及 C1 自然 GC/阶段诊断不增设或混入这 18 项门槛。

| 指标类型 | 相对阈值 | 绝对阈值 |
| --- | ---: | ---: |
| 所有绘制阶段 | 10% | 5 ms |
| 保存 p50 | 10% | 1 ms |
| 保存 p95 | 15% | 5 ms |
| 保存 max | 25% | 20 ms |
| 冷读、全部索引提交、检索索引就绪 | 10% | 500 ms |

## 18 指标分类矩阵

“带内”表示本轮没有触发预设回退筛查，不能证明等效、普遍更快或最终验收。

| 指标 | B→C0（旁证） | B→C1 | C0→C1 |
| --- | --- | --- | --- |
| raster.1.firstCompleteMs | 带内 | 带内 | 带内 |
| raster.1.appendCompleteMs | 带内 | 不确定 | 不确定 |
| raster.1.forcedFullCompleteMs | 带内 | 带内 | 带内 |
| raster.4.firstCompleteMs | 带内 | 带内 | 带内 |
| raster.4.appendCompleteMs | 带内 | 带内 | 带内 |
| raster.4.forcedFullCompleteMs | 带内 | 带内 | 带内 |
| raster.8.firstCompleteMs | 不确定 | 不确定 | 不确定 |
| raster.8.appendCompleteMs | 带内 | 带内 | 带内 |
| raster.8.forcedFullCompleteMs | 带内 | 不确定 | 不确定 |
| library.direct.p50 | 带内 | 带内 | 带内 |
| library.direct.p95 | 不确定 | 不确定 | 带内 |
| library.direct.max | 带内 | 带内 | 带内 |
| library.observe.p50 | 回退信号 | 回退信号 | 不确定 |
| library.observe.p95 | 带内 | 带内 | 不确定 |
| library.observe.max | 带内 | 带内 | 带内 |
| library.coldMs | 带内 | 带内 | 带内 |
| library.allIndexRowsCommittedMs | 带内 | 带内 | 带内 |
| library.searchIndexReadyMs | 带内 | 带内 | 带内 |

| 比较 | 带内 | 不确定 | 回退信号 |
| --- | ---: | ---: | ---: |
| B→C0 | 15 | 2 | 1 |
| B→C1 | 13 | 4 | 1 |
| C0→C1 | 13 | 5 | 0 |

## 未闭合信号的精确计算

下表“变慢/超带”分别是四对中 Δ>0 的数目、同一对同时超过相对与绝对阈值的数目。所有增量均为配对计算；完整四值保留在 `analysis-review.json`。

| 比较 / 指标 | 变慢/超带 | 配对 Δ 中位数 ms | 配对相对中位数 | 分类 |
| --- | ---: | ---: | ---: | --- |
| B→C0 / raster.8.firstCompleteMs | 3/4 · 1/4 | +16.946767 | +6.3583% | 不确定 |
| B→C0 / library.direct.p95 | 4/4 · 1/4 | +3.504987 | +128.0143% | 不确定 |
| B→C0 / library.observe.p50 | 4/4 · 4/4 | +3.210130 | +140.0703% | 回退信号 |
| B→C1 / raster.1.appendCompleteMs | 1/4 · 1/4 | -0.972283 | -1.8063% | 不确定 |
| B→C1 / raster.8.firstCompleteMs | 2/4 · 2/4 | +19.956132 | +7.8145% | 不确定 |
| B→C1 / raster.8.forcedFullCompleteMs | 2/4 · 1/4 | -8.516691 | -3.1817% | 不确定 |
| B→C1 / library.direct.p95 | 4/4 · 1/4 | +2.900107 | +106.9923% | 不确定 |
| B→C1 / library.observe.p50 | 4/4 · 4/4 | +3.970117 | +169.8326% | 回退信号 |
| C0→C1 / raster.1.appendCompleteMs | 2/4 · 1/4 | -0.347017 | -0.6335% | 不确定 |
| C0→C1 / raster.8.firstCompleteMs | 3/4 · 1/4 | +17.963352 | +6.5091% | 不确定 |
| C0→C1 / raster.8.forcedFullCompleteMs | 4/4 · 1/4 | +9.748598 | +3.9584% | 不确定 |
| C0→C1 / library.observe.p50 | 3/4 · 2/4 | +0.940515 | +17.6091% | 不确定 |
| C0→C1 / library.observe.p95 | 1/4 · 1/4 | -0.416866 | -2.9484% | 不确定 |

C0→C1 observe p50 虽有 3/4 对变慢、2 对超带，配对相对中位数 +17.6091%，但绝对中位数仅 +0.940515 ms，未超过 1 ms，因此仍为不确定，不能改称新增回退信号或带内。其两个单版本中位数之差为 +0.806982 ms，与 +0.940515 ms 配对中位数不同；JSON 明确区分。

## 解释边界

- 四个 seed 区组才是比较单位。720 个保存、每轮五次 warm 都不是独立运行次数；不删首样本、不跨 run 池化、不用一项改善抵消另一项回退。
- baseline direct 每组仅 1/30 保存与冷读区间重叠，C0/C1 均为 30/30；observe 三端均 30/30 与索引大区间重叠。区间重叠与 progress 不能证明实际 SQLite 写锁争用。不能把 direct 尾延迟改善解释成等暴露的持续争用改善。
- “cold”是在夹具哈希后第一次未建索引查询，不能声称 OS/file-cache 冷启动。warm 原序五值及描述性中位数保持，没有预先门槛，不能追加强行 PASS/FAIL。
- 栅格像素相等指同版本 local 与 forced-full；不等于跨版本像素 hash 相等。B 缺 nativeStroke 计数不能填 0。
- 此复核范围是计算与分类。已读取工件验证回执无 issue 的结论，但未重复其他代理负责的全部 APK/native/ACL/helper 原始记录核验；自然 GC 的实际 DELETE 暴露与阶段诊断因果解释仍须各自评审。

## 绑定证据

| 输入 | SHA-256 |
| --- | --- |
| analysis/analysis.json | `732863e26c6159d1f67486fd907a86342d5e8933572c2227023222cc2293bf7a` |
| evidence-archive-verification.json | `fdae6b5ad44d64ea67e2a21ff555ba28923f5af6373d5917facf7ebef41550f4` |
| evidence/frozen-original-protocol.json | `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba` |
| evidence/frozen-method-plan.json | `808dd0cf1fece09185c59ae94ce41f51fdbc26dcde5ed196df7e803860f49765` |
| evidence/frozen-source-spec.json | `6e9c422d8be60728967226de674b750cb7b3633485956d537dd0c2dac21a6dfb` |

冻结 analyzer SHA-256：`a26427d7394999a079e4af2a731cada736617a903edd682c7b5c35b5e14848eb`。独立 JSON SHA-256：`89cdfb50de0c098ec1f1e34fe2bb2610f049a5c90c9f7776c0166c7201311cc9`。
