# 绘制性能的可闭合范围与最小因果诊断

**目前可以确认实际重复解码减少及覆盖夹具内的像素/状态安全；不能关闭全部绘制耗时门槛。** 最新完整run38498392929的C0=`45b4861`、C1=`d66b60d`虽然绘制源码相同，单层append仍按原规则触发REGRESSION_SIGNAL。相同代码只排除了“这两版本新改了该绘制算法”这一直接解释，不能证明墙钟差异是噪声、没有用户可感知回退，或可以删除样本。

本说明只读既有源码与原始审计；没有修改生产/公共probe、样本、阈值，也没有启动CI、构建或原生测试。当前四个绘制文件与d66的diff为空。来源是[最新完整绘制审计及全部原值](../pr20-candidate3-results-20261014/audit/audit-raster.md)、[对应JSON](../pr20-candidate3-results-20261014/audit/audit-raster.json)。后续数据层两源码筛查不包含绘制，不能用于关闭这里的缺口。

## 已证实的收益，必须附带其验证范围

| 内容 | 已有直接证据 | 可说的结论与限制 |
|---|---|---|
| 同配置append跨revision保留Kotlin几何 | 最新四seed、1/4/8层：B local decode13/21/11，C0/C1全部1/1/1；局部重建均1 | 旧笔payload重复解码确实减少。公共probe没有readObserver/geometryBytes/reusedStrokes输出，不能另造读取行数、命中数或内存实测。 |
| 像素、删除状态和释放 | 最新36条同版本local对cold像素相等；隐藏后再次冷重建断言通过；36次joined、tracked bytes0、inFlightJobs0；绘制正确性19–30共12/12 PASS | 证明这些固定夹具的结果/释放；不是跨版本bitmap hash、进程native heap为0、所有变换或真实硬件截图验收。 |
| 单笔命中多瓦片时的native构造复用 | 历史candidate-v2的实际构造2/2/1/0，对测试内旧per-tile参考24/32/16/0；四阶段整图像素一致 | 是当前笔的native shape在命中的dirty tiles间复用；参考不是安装旧APK的baseline计数。最新单tile append仍native13/21/11，没有跨帧native缓存。 |
| 连续变换的Kotlin读/解码复用、分散脏区筛除 | 历史hex-v1软件Android配对中，12种变换read/decode3760→0，rebuild212→212；分散append322→24，rebuild2→2；各自冷重建像素通过 | 是历史固定APK/环境的工作量证据；12种变换不是12次重复采样。本轮KVM共同probe只测固定viewport局部append，未重新配对连续变换/分散脏区耗时。 |

历史来源：[native复用差分](../pr20-closure-evidence/candidate-v2-native-stroke-review.md)、[FrameProbe及身份绑定](../pr20-closure-evidence/candidate-raster-hex-v1-frame-paired-review.md)。两份历史报告中的“随后待测”是当时状态，不可据此推断本轮状态；这里只引用其已有原始计数与像素结果。历史dense原30秒失败及一次pan/zoom超时均保留，不能用小夹具PASS代替。

## 最新未闭合信号的准确位置

最新B→C1九个绘制指标为7带内、2不确定（4层full、8层first）；C0→C1为5带内、3不确定（4层append/full、8层first）、1回退信号（1层append）。原规则是每对Δ>max(左值×10%,5ms)超带；至少3对变慢，且配对绝对中位数>5ms、相对中位数>10%，即REGRESSION_SIGNAL，不要求3对分别超带。

单层C0→C1的四对均慢、2对超带；配对中位数为**+9.4687515ms / +26.3722813%**。decode=1、native=13、rebuild=1在两端/四组都相同。

| seed | append C0→C1 ms | metadata C0→C1 ms | raster C0→C1 ms | publication C0→C1 ms | 未分段wall C0→C1 ms |
|---:|---:|---:|---:|---:|---:|
|4101|35.984269→53.101253|11.149244→14.933214|15.652024→16.314942|0.512477→0.449049|8.670524→21.404048|
|4102|35.168245→36.988764|11.472928→14.407678|14.835840→14.914547|0.467784→0.483382|8.391693→7.183157|
|4103|34.420208→52.069215|11.116322→17.998797|15.453343→15.122456|0.438079→0.490707|7.412464→18.457255|
|4104|36.204486→37.054168|11.244983→14.693193|14.642239→14.901802|0.465410→0.476440|9.851854→6.982733|

“未分段wall”只是单层append总wall减现有三段wall的算术余量。4101/4103分别增加12.733524/11.044791ms；它不是直接测得的锁等待、调度、vsync或GC耗时。metadata四对均增加，但原公共日志没有该段CPU/查询行数，因此也不能称为已证实额外SQL或CPU工作。多层阶段为各层累加且有交叠，不能套用这一单层减法解释多层端到端时间。

不能遗漏另外的慢值：4层full seed4101为B184.793970/C0181.459630/C1204.392540ms；8层first seed4102为269.233317/255.763821/313.135320ms；4层append seed4101为106.426519/95.906129/105.833601ms。公共日志只输出append的阶段值，不能用它们解释first/full。

## 源码中尚未分开的等待，存在可直接观测的边界

[StreamedInkRaster.kt](/workspace/InkWeft/android/app/src/main/java/org/inkweft/app/StreamedInkRaster.kt:75)当前时序是：Main上draw提交工作→单线程worker→Room元数据→读取/解码缺失payload及画dirty tiles→prepareToDraw/Main发布→通知→下一次draw确认frameReady。

现有`metadataMs`包含`rasterRegions`整体，包括准备、Room事务调度/取得及查询；`rasterMs`包含瓦片配置、数据读取/解码、native准备/构造、Canvas绘制和最终revision校验；`publicationMs`从prepareToDraw之前到Main中设置lastBuild为止。三个字段都不是专属CPU。

一个应优先观测的具体边界是[InkFrameInvalidations](/workspace/InkWeft/android/app/src/main/java/org/inkweft/app/StreamedInkRaster.kt:210)：`notifyChanged`合并到`Choreographer.postFrameCallback`，并非当场调用changed。公共`ready()`等`events.receive()`后才再次draw。因此**publication止点之后的frameCallback等待、接收唤醒及下一次draw都不在现有三段里**。源码允许存在这一等待，原日志却没有enqueue/callback/再次draw时间；12ms余量不能仅凭“接近一帧”归因它。

另一边界是共享单线程worker。`withContext(worker)`排队/恢复和Room挂起可以分开测wall；不能把跨挂起段两端的thread CPU差直接当成该帧CPU，因为同一线程期间可能执行其他层/帧的工作。

## 最小下一步：一次独立诊断，先分工作与等待

现在没有证据支持直接改生产算法。若获准执行，保留共同probe文件、四seed/平衡顺序、方法时限、原断言与阈值原样，另设**一个独立诊断入口**：使用同一固定1/4/8夹具，只附加默认关闭的局部观察器与计时外输出；诊断值不进入原筛查分布，也不覆盖原样本。先诊断单层append；同一次运行保留first/full/layer标签，避免再借错阶段。无需通用遥测框架或逐点日志。

只需以下有限记录，用frame/generation与layer关联；每帧常数个时间戳及每批/实际构造累计数，内存收集，完成并join后输出：

| 要区分的部分 | 最小新增/复用记录 | 能回答什么，不能回答什么 |
|---|---|---|
| 请求/worker调度 | draw首次请求、worker首次开始、每次Room返回恢复的wall时间；现有异步`InkWeft.ink.frame` trace保留 | 请求到worker开始是派发间隔，不命名为CPU或DB锁等待。不要在整个suspend函数首尾求专属CPU。 |
| 元数据 | 复用per-repository默认关闭`rasterMetadataObserver`：admissionAndPreparation、queryAndDigest、hexAndResult的wall；query/hex同线程CPU；准备batch/rows、query batch/metadata rows | 首先验证是否有准备工作/行数差异、同步段CPU差异。admission含准备/yield/Room调度，不能等同锁等待。CPU是该线程累计值，端点tid不同时必须null。 |
| payload读取与Kotlin decode | 复用`readObserver`的freeze/decode wall、rows/bytes/points；仅在`readVisibleSnapshot`已冻结行之后至同步decode/cut组装结束加一对thread CPU，按批累计 | 将真的decode成本与DB快照wall分开。缺失payload预计1笔，但以观测为准；缓存命中不伪造一次read事件。这里无挂起，CPU口径比跨Room整段明确。 |
| native准备/构造与画笔渲染 | 围绕每次实际`InkBrushes.stroke(stroke)`同步调用累计wall/同线程CPU，并保留nativeStrokeBuilds；render.draw/Pencil路径按同步批累计wall/CPU | 此第一层名称应为“native stroke准备+构造”，因为包括renderSamples、输入batch和brush，不假称纯JNI。若它确为主导，再仅在该函数中细分`Stroke(brush,batch)`调用；第一轮不先扩四个层次。 |
| 发布和下一帧通知 | prepareToDraw开始/结束、Main发布入口、frame设定完成、首次invalidate enqueue、实际Choreographer callback分发、下一次draw入口/出口（frameReady=true） | 可定位最后未分段wall落在prepare、Main排队、合帧通知等待还是重新draw。明确记录合并/重复通知计数和generation，不能把“无回调”写成0耗时。 |

观察器采用现有per-repo/per-raster所有权，默认null时不采clock、不分配记录；不要常驻跨帧native对象或全局无限事件表。为了观察Choreographer，不改变合并集合/回调顺序：只为启用诊断的当前callback附加有界时间信息，其他行为不变。取消/异常记录为aborted，不发布伪完整帧；`finally`解除观察器并完成原clear/cancel/join，计时外输出。不得吞取消、OOM或断言异常。

若第一轮显示wall增加而同步CPU稳定，还只能定位等待区间，不能自动命名为“调度噪声”。仅当确需区分该区间的线程可运行排队/休眠/GC暂停时，再做范围受限的主线程+worker+Room调度/GC trace；不先采全机通用profile。已有host安装前后load跨整个library/raster visit，不能替代逐帧时序。

## 何种证据才能支持最小修复

- **额外decode或native重复构造：** 必须观测同身份/同payload/同epoch的重复工作，并证明计数超过实际需要。当前数据是decode1/native13，而不是native1；重画一块tile需要13笔native几何是现设计行为，没有发现新重复构造。不能为了降低计数跳过旧笔，也不能盲增跨帧native cache。若新证据指出同一笔在单次dirty-tile循环中仍被重复构造，才局部修其重用边界。
- **元数据同步CPU主导：** 须先看到同逻辑夹具的batch/rows及query/digest/hex CPU。只有定位到具体重复扫描/结果构造，才考虑等价的局部减少；保证paint顺序、范围去重、epoch/revision和图层过滤。单看metadata wall四次变慢不支持改SQL/锁策略；分散区域当前仍扫描bounding union，只有真实分散夹具证明干净间隙扫描主导时才讨论该路径。
- **发布/通知等待主导：** 先证明enqueue→callback或callback→draw的实际占比和是否重复丢失/多等一次。正常跨下一帧的合并本身有产品目的，直接同步changed可能增加每层重绘、重入和卡顿；只有发现冗余一轮通知/错误generation，才能在保持合并及UI线程语义下局部修。即使等待与刷新相位相关，也要以原公共probe的新同环境结果检验端到端效果。
- **只有环境/排队解释：** 必须具备逐帧调度/GC证据，并在相同APK、同夹具、同一环境下按预定平衡安排观察。不能把四对统计筛查信号自行降级为“噪声”；保留本轮结论，新证据另列，不重写旧门槛。

所有修复均须保持：同repo/page、显式appendGeneration身份、兼容投影/viewport/scale/authoringRevision、allowed/omitted；缓存每笔spatialKey与page/epoch、最终head和authoring校验；默认调用不允许跨revision；1MiB/512笔预算及owner释放；裁剪、半透明层绘制顺序、笔迹采样、擦除mask、隐藏/undo不改变；失败/取消绝不发布半帧。当前同revision cut批间变化拒绝及跨revision跳跃测试不能删除。

## 关闭范围所需的同环境验证

1. **最新局部append回退：** 诊断仅供定位。任何生产修复需冻结新源码/APK，并在同一KVM环境对旧C0/当前C1/新候选的必要比较执行原共同probe、原四seed与平衡顺序；诊断关闭，全部原值保留。原9个绘制指标按原10%/5ms规则复算，不能只取单层快样本。若仍有INCONCLUSIVE/REGRESSION_SIGNAL，范围仍未闭合。
2. **正确性与资源：** 继续覆盖现有RasterReadCache7项、Tile append跨revision/整数平移/缩放预览/取消、Streamed clear/omitted；有native路径修复时重跑NativeStrokeTileReuse整图差分及mask/裁剪/半透明/层状态。每阶段自身独立cold帧像素一致，所有拥有的Job实际join，tracked资源回到基线，候选正式超时断言原样。
3. **用户原始连续变换/稀疏更新目标：** 要宣称最新候选性能已闭合，仍需同数据同设备下复跑既有固定FrameProbe对照，逐case给出read/decode/native/rebuild及像素。12种不同变换的中位数只作描述，不能变成重复试验p95；整数采样位移外完整瓦片重用仍有限，缩放预览不能算已完成精确帧。
4. **原dense门槛：** 历史TCG的1201笔、76802点、frame30秒失败是独立未闭合项。该夹具每笔单tile，本轮减少跨tile native重复构造对它无收益；历史worker采样在StrokeNative构造、资源远低预算，也只说明当时观察到的瓶颈，不证明环境是唯一原因。需要新候选在明确绑定的同环境保留原夹具与30秒断言实际执行；小258笔通过或本轮13笔局部append不能替代。

在这些验证前，准确可交付表述是：**限定夹具的旧笔重复解码与跨tile重复native构造已减少，像素和状态/资源守卫通过已有原生检查；最新单层端到端回退与部分first/full耗时、原dense门槛仍开放，下一步优先对元数据及合帧通知之间的未分段wall做最小诊断。**
