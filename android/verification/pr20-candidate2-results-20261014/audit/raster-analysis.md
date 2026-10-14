# run 38441089594 绘制独立复核

旧笔重复解码已在原生共同夹具中实际减少；时间验收仍有缺口。仅纳入 ordinal31–54 的12次公共绘制调用（31、33、…、53），共36条图层记录；另独立核对C1绘制正确性ordinal19–30。未修改代码、probe、样本或阈值，未运行新基准。

B=`a128bb07`+既定writer factory override；C0=`ebb4848`；C1=`45b4861`。共同probe SHA-256 `a916d2470d54f069d1d6071d0b56a15ac6b07d05ab0b033d0aa7d4420fa74338`。原始日志、records JSON和固定analysis的36条记录逐项相同；12组seed×层数的三端逻辑夹具哈希与SQLite配置一致。配套`raster-analysis.json`保留全部原值、阶段值、回执、来源和输入哈希。

## 实测计数与功能

| 层数 | B/C0 local decode | C1 local decode | C0/C1 native构造 | local rebuild | full decode / rebuild |
|---|---:|---:|---:|---:|---|
| 1 | 13 | 1 | 13 | 1 | 321 / 24 |
| 4 | 21 | 1 | 21 | 1 | 321 / 24 |
| 8 | 11 | 1 | 11 | 1 | 321 / 48 |

以上四个seed全部一致。B native字段缺失/null，不能补零。C1减少的是Kotlin payload解码；native Stroke仍在当前帧构造，绝不是跨帧native mesh缓存。共同probe未输出reusedStrokes、readObserver或geometryBytes，不能把推导的12/20/10次旧笔命中写成额外的原始测量。

36/36整图像素对照true，均为各版本内部局部结果与独立冷重建比较；方法还执行隐藏后不显示旧笔及再次冷重建相等断言。36/36 cleanup均joined=true、totalBytes=0、inFlightJobs=0。没有跨版本像素hash或物理Android屏幕验收。

## 原阈值四组复算

每对超带条件为Δ>max(基准×10%,5ms)。REGRESSION_SIGNAL要求至少三对变慢，且配对相对变化中位数>10%、配对绝对变化中位数>5ms；否则任意一对超带即INCONCLUSIVE。这里的中位数是四个配对变化的中位数，不是汇总中位数之比，也不是统计显著性检验。结果与固定分析完全一致。

| 指标 | B→C1 中位Δ ms / % | 状态 | C0→C1 中位Δ ms / % | 状态 |
|---|---:|---|---:|---|
| 1层 first | +8.322247 / +3.142% | WITHIN | +11.311452 / +4.296% | WITHIN |
| 1层 append | -0.972283 / -1.806% | INCONCLUSIVE | -0.347017 / -0.633% | INCONCLUSIVE |
| 1层 forcedFull | -0.329165 / -0.163% | WITHIN | -1.160828 / -0.576% | WITHIN |
| 4层 first | -6.182197 / -2.638% | WITHIN | +4.999634 / +2.194% | WITHIN |
| 4层 append | +0.113228 / +0.220% | WITHIN | +2.395591 / +2.158% | WITHIN |
| 4层 forcedFull | -0.798007 / -0.443% | WITHIN | -4.806560 / -2.581% | WITHIN |
| 8层 first | +19.956132 / +7.815% | INCONCLUSIVE | +17.963352 / +6.509% | INCONCLUSIVE |
| 8层 append | +4.005755 / +2.749% | WITHIN | +5.213983 / +3.587% | WITHIN |
| 8层 forcedFull | -8.516691 / -3.182% | INCONCLUSIVE | +9.748598 / +3.958% | INCONCLUSIVE |

两种比较均为 **6 WITHIN_PROPOSED_BAND + 3 INCONCLUSIVE，0 REGRESSION_SIGNAL**。WITHIN不等于改善或证明无回退：C1单层first四对均慢于B/C0；C0→C1八层append三对变慢。decode=1不能证明端到端普遍加速。

全部超带样本：

| 比较 | 指标 | seed | 原值→C1 ms | Δ ms / % |
|---|---|---:|---:|---:|
| B->C1 | raster.1.appendCompleteMs | 4104 | 53.501635→64.348551 | +10.846916 / +20.274% |
| B->C1 | raster.8.firstCompleteMs | 4102 | 256.169127→301.920953 | +45.751826 / +17.860% |
| B->C1 | raster.8.firstCompleteMs | 4104 | 261.891556→315.241174 | +53.349618 / +20.371% |
| B->C1 | raster.8.forcedFullCompleteMs | 4102 | 263.615802→300.340891 | +36.725089 / +13.931% |
| C0->C1 | raster.1.appendCompleteMs | 4104 | 53.369809→64.348551 | +10.978742 / +20.571% |
| C0->C1 | raster.8.firstCompleteMs | 4102 | 267.892978→301.920953 | +34.027975 / +12.702% |
| C0->C1 | raster.8.forcedFullCompleteMs | 4102 | 248.803073→300.340891 | +51.537818 / +20.714% |

## 全部原始时间

每格按 **B / C0 / C1** 排列，单位ms；包括改善、变慢和异常，未删样或重试替换。

### 1层

| seed | first | append | forcedFull |
|---:|---|---|---|
| 4101 | 259.291195 / 259.119239 / 268.584258 | 53.771687 / 53.744186 / 52.348589 | 201.872370 / 218.216607 / 201.413897 |
| 4102 | 270.537235 / 258.661878 / 275.432601 | 53.037074 / 53.665989 / 49.411032 | 201.351283 / 201.421618 / 201.719376 |
| 4103 | 263.852344 / 266.464892 / 274.673230 | 53.979447 / 52.756416 / 53.457980 | 201.174931 / 201.564270 / 200.975073 |
| 4104 | 272.188132 / 266.381679 / 279.539564 | 53.501635 / 53.369809 / 64.348551 | 201.361169 / 201.300908 / 199.568449 |

### 4层

| seed | first | append | forcedFull |
|---:|---|---|---|
| 4101 | 230.521853 / 242.830003 / 220.970005 | 109.596370 / 106.755772 / 78.569648 | 208.583459 / 186.593056 / 181.096094 |
| 4102 | 221.802174 / 228.032007 / 240.642802 | 109.394129 / 108.555450 / 111.472633 | 183.866979 / 185.697694 / 181.581537 |
| 4103 | 246.712071 / 228.634704 / 226.023177 | 126.857343 / 115.269074 / 125.005296 | 185.610704 / 192.345754 / 186.406609 |
| 4104 | 248.331221 / 229.868691 / 245.518675 | 111.875763 / 115.118800 / 116.992799 | 192.620638 / 195.580173 / 193.310065 |

### 8层

| seed | first | append | forcedFull |
|---:|---|---|---|
| 4101 | 261.756184 / 246.128593 / 255.916622 | 143.921141 / 148.879732 / 147.459818 | 265.977407 / 244.374577 / 244.843721 |
| 4102 | 256.169127 / 267.892978 / 301.920953 | 147.145961 / 144.094789 / 151.618793 | 263.615802 / 248.803073 / 300.340891 |
| 4103 | 272.353177 / 294.522861 / 254.677448 | 155.030607 / 144.896373 / 147.800336 | 273.103729 / 244.807707 / 247.604083 |
| 4104 | 261.891556 / 289.102498 / 315.241174 | 148.486336 / 152.354864 / 160.231419 | 259.127451 / 246.526936 / 263.227755 |

## 阶段证据与最小后续建议

单层append seed4104：C0→C1 wall 53.369809→64.348551ms；metadata 15.266704→27.340796ms，raster 17.467149→20.429778ms，publication 0.722045→0.529498ms。decode仍由13降到1、native仍13、重建仍1。主要增加发生在既有metadata阶段，但该wall含事务取得、查询/摘要等，不能据此断言额外SQL、CPU或锁等待。

八层first的seed4102/4104、forcedFull的seed4102全部保留。probe只输出append的metadata/raster/publication，不能借此解释first/full异常。多层阶段是各层累加且可交叠，不能相加当成端到端wall。

当前证据支持保留已验证的旧笔解码减少；**不足以指定新的最小生产修复，也不支持盲目回撤或扩展native缓存**。若必须继续闭合耗时，最小下一步是另行授权诊断，保持原probe及本次结果不动：相同夹具分别记录first/local/full的逐层metadata、raster、publication到frame-ready阶段；复用默认关闭的metadata observer，区分admission/preparation、query/digest、hex/result的wall及同线程CPU，并对齐decode/native/rebuild。优先检查上述三个异常目标，保留完整四组及原阈值；不能先按墙钟值改算法或筛样。

本次为同一KVM x86_64/API35 AOSP ATD模拟器、2vCPU/2048MiB、SwiftShader，host为4CPU AMD EPYC9V74（上一轮Intel host的绝对耗时不得混入）。24个visit前后设备快照相同。seed4102 C1 visit的host一分钟负载1.138→2.431，seed4104为1.104→1.103；快照跨绘制及资料库整个visit，缺逐帧CPU/GC/调度证据，不能归因或把异常认定为噪声。

## 正确性原文

ordinal19–30各自具有`OK (1 test)`、最终status0、instrumentation code -1，并与冻结selection相符。新增四项为21/22/23/26。

| ordinal | 方法 | 原始JUnit秒 | 结果 |
|---:|---|---:|---|
| 19 | separatedRegionsSkipEmptyMetadataBatchesAndDecodeCrossingStrokeOnce | 0.836 | PASS |
| 20 | payloadMaskAndRevisionChangesCannotReuseStaleDecodedGeometry | 0.308 | PASS |
| 21 | explicitAppendReuseSkipsOldPayloadsAcrossRevisionJumpsAndDefaultsToInvalidation | 0.284 | PASS |
| 22 | appendPermissionStillRefreshesChangedPayloadVisibilityUndoAndMasks | 0.336 | PASS |
| 23 | appendReuseRejectsHeadOrMaskChangesDuringCachedBatches | 0.622 | PASS |
| 24 | decodedCacheRemainsBoundedForManyStrokesAndDenseMasks | 0.755 | PASS |
| 25 | sameRevisionMaskChangeDuringCachedVisitRejectsTheWholeRead | 0.627 | PASS |
| 26 | appendRetainsGeometryAcrossIdleLayerRevisionsAndDestructiveChangesMatchColdFrames | 0.715 | PASS |
| 27 | integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture | 0.851 | PASS |
| 28 | interruptedPanWaitJoinsItsWorkerBeforeClosingResources | 0.162 | PASS |
| 29 | clearingPendingTransformReleasesPinnedGeometryWithoutPublishing | 0.214 | PASS |
| 30 | omittedStrokeCannotReappearWhileItsReplacementFrameIsDelayed | 0.753 | PASS |

新增数据测试验证显式许可后只读新payload/默认调用仍失效、payload/visibility/undo/cut刷新，以及批间head/mask变化拒绝后续批。新增app测试始终写真实当前层0：两次视口外追加不重建，再视口内追加要求decode1/reused1/native2，并逐步对照冷重建；另保留viewport不兼容拒绝复用、破坏性更新刷新、取消与严格释放。这些由冻结45b中的断言通过证明；原始日志未逐项输出数值，不能伪造额外telemetry。

既有pan原文另输出rebuilt=12、tiles=20、panPixelsEqual=true、zoomPixelsEqual=true、previewCaptureReady=false。它是候选功能检查，不是跨版本连续变换性能配对或硬件截图。分散区数据用例证明干净间隙payload筛除，未消除bounding-union metadata扫描。

本轮未重跑原dense30秒门槛或NativeStrokeTileReuse差分。不能外推更大页面、超缓存容量、持续手写尾延迟及所有变换/层组合；历史失败和旧运行必须独立保留。
