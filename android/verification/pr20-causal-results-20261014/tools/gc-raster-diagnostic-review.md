# GC 与绘制诊断：独立静态审查

结论：两位作者 READY 后的五文件快照未发现剩余确定性事务、合帧或资源安全阻断。所报测试清理问题已最小修复。此结论仅来自源码及与当前 HEAD 的差异；未运行编译、JVM、Gradle、Android、ADB、CI 或网络请求，不宣称默认路径实测零开销、原生通过或争用根因已确定。

## 默认路径与事务

`HistoryStorage.kt:26–38,79–98` 保留公开二参数/default phase 构造入口，默认 batchObserver=null。诊断事件构造立即受 null 分支保护，新增 immutable holder 只在启用时分配；writerVersion 仍使用原两条读取，未为诊断增加 SQL。maintenance mutex、Room 写事务、guard 比较、每条 DELETE 前取消检查、更新 expected、提交后统计的顺序保持。guard mismatch 仍先退出、不进入 DELETE。私有只读扫描和所有活引用规则未改。

诊断开启时，guard/before-delete/after-delete 回调仍在写事务内，回调异常走事务失败；transaction-returned 回调位于事务返回后，若它抛异常，不能声称该批未提交。此次事件只复用已有 guard 值，不输出作者正文。默认路径仍多了空检查/方法调用及捕获引用；源代码未显示新增 event、clock 或 diagnostic holder 分配，不把这等同于字节码/运行时零成本证明。

`StreamedInkRaster.kt:32–35,57–78,169–171,194–236` 默认 diagnostics=null 时不调用新诊断 clock、不建 frame/map、不会执行 safe-call 参数中的 native 测量 lambda。原 nativeStroke 复用、变换/epoch 验证、发布和取消分支、workerOwner/geometry/bitmap 归属及回收顺序均保留。新诊断仅记录阶段，未改 clear 的原义，也没有把 clear 当作 worker 已退出。

InkFrameInvalidations 的 callback 集合仍按原规则去重并只投递下一次 Choreographer frame。诊断 map 只为启用的 frame 建立，最多将同 callback 最后一个 frame 关联到真正通知，并为被替换者计 superseded；不增加额外 redraw。map/frame 不持有 Stroke、Bitmap 或 geometry。主线程 enqueue/dispatch 顺序未变。

## 已关闭的测试阻断

- GC：trial 的 finally 记录构建/emit 及外层 summary emit 原先可能覆盖实际失败；最终均捕获次错，原失败优先并添加 suppressed。原 sample 数、字段、10s/5s watchdog 和断言未变。
- 绘制：数据库和两张大 bitmap 原先在 try 外分配；现均在 try 中并保留已成功分配的 holder。finally 先 NonCancellable 取消并 join 所有捕获的真实 Job；仅 joined 后分别尝试 channel、两 bitmap、DB、delete，删除 false 明确失败，次错不覆盖原失败。join 不假定完成、不以超时吞掉活 worker。
- 默认 suite：GC 在 runProbe 首部 Assume；绘制先 Assume，pairSeed/pairRandom 为 lazy，未启用时不会因缺 seed 构造失败。启用绘制后仍要求 seed4101。

## 诊断数据的可证明边界

GC 两个方法各计划30次、每次新合成数据库；1281版本/1280垃圾，先等待作者准备，再在首个成功 guard 后的 before-delete 通知作者，没有暂停 DELETE 或为了曝光重试。这是条件到达诊断，不是冻结的自然争用验收。回调仍有时钟、事件构造及通知成本，不能与未开启诊断的旧性能数据直接作收益比较。

`deletionTransactionExposed` 的准确含义是 before-batch-commit 时点落入 saveStart..saveEnd；它不能证明保存开始于实际 DELETE 内。另有 saveStartedDuringDelete 和 deleteStart/deleteEnd 可以更窄核对。exposed 分母须从原始样本重算；少于20个仅报 INSUFFICIENT_EXPOSURE，零曝光绝非并发延迟通过。nearest rank 对30个为索引14/28，max29；5s只是失败 watchdog，不是延迟阈值。开始尝试 rename 后先把 committed 设 null，异常路径保留未知提交；NOT_STARTED 的 false 与未知值可区分。缺少 transaction-returned 也不能反证 SQLite 未提交。

绘制保留共同夹具的1/4/8层、320→321笔、局部与强制全量像素相等、破坏性 hide 不显示旧像素、预算及全局资源基线断言。ready 原共同 probe 就是60s，本诊断未放宽。新增 frame/metadata/read 缓冲有上限并对 dropped 非零失败；输出位于计时外，独立 opt-in，不替换公共 probe 样本。

nativePreparationAndBuildThreadCpuNanos 在不悬挂的同步函数内取同线程端点，且拒绝负/逆向 CPU 值；其区间包含 render-input/brush 准备和 Stroke 构造，不是单独 native constructor 时间。metadata 的已有同线程 CPU 守卫仍适用。各粗 wall 区间包含调度/其他工作，不能直接命名为纯锁等待、JNI 或磁盘份额。

PREPARE_END→PUBLICATION_ENTER 是工作线程结束准备到主线程发布入口的粗区间；NOTIFY_CALLBACK→READY_DRAW_START 也包含调用者事件循环和 draw 调度。多层可以由另一层通知促成 draw，二者未必一一因果或严格正差。worker join 和 resources 回基线不等于 flush Choreographer 队列；已排队 callback 可能在 snapshot 之后发生，因此 notificationCallbacks 小于 requests 不应单独判丢通知或死锁。源码保持旧合帧逻辑，不能由本轮静态审查断言所有通知已完成。

## 最终 READY 快照 SHA-256

| 文件 | SHA-256 |
|---|---|
| `android/data-local/src/main/kotlin/org/inkweft/data/HistoryStorage.kt` | `b96890478129bbb7b0bbb538c3cf8d5f2e97f2b8b5e2e34d8057c855c9ec6ca9` |
| `android/data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionCausalTest.kt` | `4f80b8ce32e1aa2bae1d919b96c06b1897e7a8500c748822efd05119ba87ec4a` |
| `android/app/src/main/java/org/inkweft/app/StreamedInkRaster.kt` | `feab7a6d9c4eec09feb870f25b82fbc96f0ba915e2fdf4a5720f5d89943efa8e` |
| `android/app/src/main/java/org/inkweft/app/RasterFrameDiagnostics.kt` | `24f139a486d6603ea5bc62616f05f0d55d2a4fd90a3e5dd67b87b883c6689232` |
| `android/app/src/androidTest/java/org/inkweft/app/RasterCausalDiagnosticsTest.kt` | `f7a1424cfec939344584c86a1c11005d9f5d70447d31651bc9764907938cccc1` |

只绑定本次所读五文件；若后续修改，需重新绑定。原公共探针/样本/阈值未在这五文件范围内被改写。编译及原生结果由根节点统一记录。
