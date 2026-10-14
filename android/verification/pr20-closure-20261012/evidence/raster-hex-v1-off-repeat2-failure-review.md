# hex-v1 Coff2 新失败：五个 ready 阶段未定位

最终回执：`candidate-raster-hex-v1-off-repeat2` 执行 2 项、失败 1 项，host 242.363 秒；ADB exit 0 不能算通过。原 `integralPanReusesInteriorTilesAndZoomPreviewNeverCompletesCapture` 报 30,000 ms TimeoutCancellationException。C/B/B/C 四次调用已经执行完，合计 **7/8 通过，区组验收 INCOMPLETE**；保留失败，不以重跑替换 Coff2。

`TileLocalRedrawTest.kt:35` 的同一个 ready helper 共调用五次：初始 cached、pan cached、pan cold、zoom cached、zoom cold（36/38/42 行）。现有异常栈没有调用序号；`TILE_PAN_EVIDENCE` 只在 46 行、全部断言之后输出，本次缺失。因此不能定位五者之一，也不能确认 pan 像素/局部瓦片重建或 zoom 预览/缓存断言已到达。缺少末尾日志也不等于这些像素断言本身失败；唯一明确失败是该 30 秒等待。

等待包含 Main 上的 draw/frameReady、worker 及数据库/渲染工作、发布和事件等待；当前日志不能区分这些成本，不能把原因归为 Formatter、原生复用或软件模拟器中的任何一个。

同一类随后 localAppend 完整通过，1/4/8 层原像素、局部/完整和破坏性更新等断言均执行；append 原值为 2047.746 / 5128.054 / 10283.711 ms，decoded/native 为 13/21/11、dirty 均 1。这些原值保留，但必须标为 **失败 pan prelude 之后**。pan 的 finally（47 行）调用 clear/cancel 后立即关闭资源，没有 join worker；异步 worker 的 finally 才释放所有权。因此不能仅由后续方法通过反推整个前置清理已严格完成，也不能把该值当作无条件清洁样本；若报告两样本描述性 median/range，必须同时注明 Coff2 类失败。

父侧过程检查 `candidate-raster-hex-v1-off-repeat2-process-review.json` 确认 instrumentation 已结束，app/appTest 两个 pidof 均 exit 1、stdout 空，没有残留目标进程，也没有 force-stop。这足以继续独立新进程的原计划 Frame/资源测试，**不是正常进程内严格释放断言的证明**。该后检查也不能追溯证明 localAppend 计时期间没有尚未完成的 pan 清理。

后续最低要求：保留当前失败和新增未闭合 pan 门槛；继续计划时绑定原源码/APK与新的独立回执，不拼全绿。如果需要定位 pan，另行记录一轮诊断，在五个 ready 前后只加阶段标签，超时记录当前标签及已有 pending/frameReady/lastBuild/资源快照，保持每次原 30 秒、夹具和全类顺序；新诊断不能覆盖本次失败。当前三个更早的原验收缺口也不被后续小夹具通过关闭。

本复核只读原源码、小日志/回执和父侧过程检查，写入本 MD/JSON；没有修改源码、运行构建、ADB 或测试，也没有 APK/源码全树哈希。
