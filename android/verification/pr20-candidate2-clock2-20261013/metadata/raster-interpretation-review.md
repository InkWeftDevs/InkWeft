# 45b 绘制计数解释：执行结果之前的源码审查

本文件仅根据冻结源码和固定探针推导预期，不是 run 38433834125 的执行结果或性能验收。未修改源码、测试、控制文件，未运行构建或设备测试。

本轮身份为 B=`a128bb07e34cb82f00eeafc8e62a47bf7b95b128`（保留既定 writer factory override），C0=`ebb48481e7e995288c1844d43011398c513b4f4e`，C1=`45b4861ae23ac19eda3246fcd34cf3d2132dc1ee`。不要将本轮 C0 与第四次运行的 C0=7949011 混同。核对 `android/verification/pr20-candidate2-readiness-20261013/source-spec.json`；C1 canonical manifest SHA-256 为 `7592c7990537bde681d0179539f58e1cd79822695bfcfd4b19882d9e1f738e90`。本地相关三个生产文件与两个测试文件相对冻结 45b 无差异；PR 报告 HEAD 不替代被测源码身份。

共同 `KvmLocalAppendPairProbe.kt` 仍取自 e1a813a，12932 字节，SHA-256 `a916d2470d54f069d1d6071d0b56a15ac6b07d05ab0b033d0aa7d4420fa74338`。已核对固定 git blob 与所读第四次工件探针逐字节一致，未调整夹具、计时、断言或阈值。

## 固定共同夹具的预期

探针在每个 1/4/8 层场景先绘制 320 笔，再通过当前层的正常 `CommitInk(Add)` 追加一笔。页 revision 从 1 到 2；配置 revision 始终为 1；非空 appendGeneration 身份、viewport、crop、budget、layer、allowed/omitted 均保持。没有 cut，旧笔 payload/visibility 不变。新增笔位于既有裁剪范围内，旧空间 key 和遮罩 epoch 保持。

因此 `StreamedInkRaster` 的严格 append 许可成立，沿用每层原有 `InkRasterCache`；`visitVisible` 在当前元数据中逐笔核对 spatialKey；`prepare` 只在同 page、同 epoch 和单调 revision 下保留内容。320 笔均为两点笔迹，既不触及每 cache 512 笔限制，也不触及 1 MiB 逻辑预算（总初始逻辑权重 204800 字节）。脏瓦片内旧几何应全部命中，只读/解码新增笔的 payload。

以下全部是**预期值**，新运行必须以原始回执为准：

| 指标，依次 1/4/8 层 | B / C0 | C1 预期 | 解释 |
|---|---|---|---|
| localDecoded | 13 / 21 / 11 | 1 / 1 / 1 | 旧实现跨页 revision 清空几何；新实现只解码新增笔。 |
| local reusedStrokes | B 无字段；C0 此阶段逻辑上 0 | 12 / 20 / 10 | 新旧笔共同参与重画，仅旧笔复用 Kotlin 几何；共同 probe **没有输出该字段**，这些值不可写成已观测计数。 |
| local nativeStrokeBuilds | B 无字段；C0 13 / 21 / 11 | 13 / 21 / 11 | native Stroke 在当前笔循环内创建，生命周期不跨帧；旧笔仍需要当前帧 native 构造。 |
| localRebuiltTiles | 1 / 1 / 1 | 1 / 1 / 1 | 瓦片签名、裁剪和完整瓦片复用规则未改变。 |
| first decoded | 320 / 320 / 320 | 320 / 320 / 320 | 首次没有旧几何缓存；探针断言此值，但未作为独立字段输出。 |
| fullDecoded | 321 / 321 / 321 | 321 / 321 / 321 | forced-full 使用独立 raster，无原帧缓存；现有断言保持。 |
| fullRebuiltTiles | 24 / 24 / 48 | 24 / 24 / 48 | 完整重建的网格/分辨率未改变。 |

一笔命中多个 dirty tile 时，已有 C0/C1 实现会在**这一帧内**只构造一次 native Stroke；本共同夹具只有一个 dirty tile，不能证明该项多瓦片收益。B 缺少 native counter，必须保持缺失/null，不能补零，也不能从 C1 的计数宣称已实测 B→C1 的 native 降幅。

共同 probe 不输出 payload readObserver、reusedStrokes 或 geometryBytes。`localDecoded=1` 是生产 BuildStats 的 visited-minus-cache-hits 计数；新增数据测试另用 readObserver 和对象身份验证实际旧笔读取减少。二者不能冒充共同 probe 同时采到的三个独立计数。保留更多旧几何会增加 append 后的逻辑 retained bytes；1 MiB/512 笔上限和每层预算 reservation 不扩大，不能宣称该优化减少所有内存占用。

## 像素、变换和层状态的边界

- 每层只有新笔相交的瓦片变脏；没有 dirty tile 的层不进入 visitVisible，其 cache 内部 revision 可能落后于 frame。下一次满足严格 append 许可时允许单调跳跃，但仍核对 epoch 和逐笔空间身份。
- 许可依赖现有 compatible/projection：同 repository/page、画布尺寸、zoom/density/world/embedded、layer、采样 scale、authoringRevision、viewport、allowed/omitted，以及相同非空 appendGeneration。普通数据调用默认不许可 revision 前进；同 revision 原有缓存规则保留。
- 正常 hide/undo/cut 会更换追加身份；cut 的增删/可见性变化还推进 epoch。payload/visibility 修改重建空间身份；每批及末尾的 head、authoring、固定 epoch 校验仍在。缓存不是可见性、绘制顺序或页版本的权威来源。
- 完整 bitmap 复用仍要求兼容投影、整数视图和整数采样位移、相同区域与签名。分数平移/缩放仍按精确矩阵重画；缩放预览不会变成 frameReady 或 capture 成功。同步发生 viewport 改变与 revision 前进时，这次追加复用许可不成立。
- 共同 probe 的 pixelsEqual 是**各版本内部**局部更新与独立冷重建的整图相等；还保留隐藏后不得显示旧像素及冷重建相等断言。没有跨版本像素 hash，也没有硬件屏幕捕获验收。
- metadataMs/rasterMs/publicationMs 是各层累加值，阶段可能交叠，不能相加充当端到端耗时。此改动直接减少旧 payload 读取/解码，不跳过全部 metadata 查询、native 构造或 Canvas 绘制；不能预先保证 append wall 降低，更不能解释 first/forcedFull 的五项旧 INCONCLUSIVE。

## 新增测试实际能证明什么

固定计划共 57 次方法；绘制相关正确性位于 ordinal 19–30。新增方法仍为数据层 3 项、app 1 项：

1. `explicitAppendReuseSkipsOldPayloadsAcrossRevisionJumpsAndDefaultsToInvalidation`：revision 1→3 后显式许可只读取两笔新 payload、两笔旧对象保持身份；普通调用读取全部四笔；旧 head 被拒绝。
2. `appendPermissionStillRefreshesChangedPayloadVisibilityUndoAndMasks`：许可存在时，预先替换同 id payload 仍按新空间身份读取；隐藏不返回、恢复重新读取；cut epoch 改变使缓存失效，包括同 revision cut 可见性变化。数据测试故意直接给予许可来检查底层防护，不代表 UI 对这些破坏性操作授予 append 许可。
3. `appendReuseRejectsHeadOrMaskChangesDuringCachedBatches`：已填充缓存并前进 revision 后，回调中的 head 改变或同 revision mask epoch 改变使后续批次拒绝，不能成功返回混合版本结果。
4. `appendRetainsGeometryAcrossIdleLayerRevisionsAndDestructiveChangesMatchColdFrames`：始终向真实当前层 0 写入，配置 revision=1 不变；两次视口外追加使两层 decode/rebuild=0，再向视口内追加，要求 cache 1→4 后 decode=1/reused=1/native=2，另一层 rebuild=0。每阶段对照冷重建像素；另检查 hide/undo/cut 刷新、同时改变 viewport 不授予追加许可，以及等待追加帧时 clear 后严格 join 和资源所有权回归。

其余已列入计划的测试补充已有分散区域载荷筛选、预算、平移/缩放预览、取消、omitted 状态守卫。分散区域测试证明干净间隙的 payload 未被解码，以及跨区域笔只访问一次；元数据仍查询脏区的 bounding union，不能说已经消除了全部间隙 metadata 扫描。

以上测试通过后，才可陈述对应功能成立。它们不提供大页持续手写延迟分布、不保证超过缓存容量时 decode 始终为 1、不覆盖所有投影/图层组合，也不是连续变换或稀疏多脏区的配对性能测量。当前固定计划未重跑原 dense 30 秒门槛或 NativeStrokeTileReuse 差分用例；不得把本次方法通过写成这些门槛已通过。任何新结果都应保留原失败、缺失字段、同版本像素范围及固定四个 seed 的完整原值。
