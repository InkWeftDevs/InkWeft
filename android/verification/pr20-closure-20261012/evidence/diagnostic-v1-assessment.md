# PR #20：initial-v1 / diagnostic-v1 验收判读

本说明只判读本目录已经完成的两轮原始 JSON / instrumentation 日志：`initial-v1` 为 **9 执行、9 通过**；`diagnostic-v1` 为 **7 执行、2 通过、5 失败**。5 个失败仍是未闭合的验收项。当前已写入但未包含于 v1 APK 的修复不能据此判为通过；本说明不报告 v2 结果。

环境为 API 35、x86_64 Android 软件模拟器，无 KVM。这些是实际 Android instrumentation 结果，包含 Requery / AndroidX Ink 原生执行；不是物理设备性能，也不是 host SQL 或编译结果的替代描述。数据均为测试生成的笔记、页面、来源版本和笔迹；下文只保留规模、状态与计时，不记录用户内容、随机文档标识、进程编号或应用 UID。

证据入口：`initial-v1-results.json`、`diagnostic-v1-results.json` 及下表同名 `.log`。源版本依据 `diagnostic-source-sha256.json`；APK、签名和安装对应 `diagnostic-v1-artifacts.json`、`diagnostic-v1-signatures.json`、`diagnostic-v1-apk-installation.json`。这轮已采用 Requery 原生新库创建默认值，不能用此前 4096 / INCREMENTAL 初始化条件下的性能数字代表本轮最终性能。

## 计时口径与结果

JSON `seconds` 是 host 等待 instrumentation 命令的整段墙钟时间；日志 `Time` 是 JUnit 测试轮次时间，包含夹具和清理。两者均不是被测操作延迟。阶段 `elapsedMs` 是探针起点至该事件的时间；两个事件之差也包含调度和记录开销。协程或 Compose 的时限是代码中的预算，超时日志出现稍晚不代表预算被放宽。

| 轮次 / 日志后缀 | 实际 JUnit 结果 | JUnit 秒 | host 命令秒 | 判读 |
| --- | --- | ---: | ---: | --- |
| initial-v1-factory | 5 / 5 通过 | 11.808 | 23.136 | 工厂原生创建、保留和恢复的这 5 个用例通过 |
| initial-v1-process-initialization | 4 / 4 通过 | 27.659 | 37.578 | 真实独立进程的这 4 个用例通过 |
| diagnostic-v1-small-search | 1 / 1 通过 | 18.831 | 33.172 | 小夹具取消 / 清空 / 分类语义通过 |
| diagnostic-v1-small-frame | 1 / 1 通过 | 50.584 | 79.326 | 两个数据库批次的完整帧及释放通过 |
| diagnostic-v1-history | 1 执行、1 失败 | 249.399 | 262.660 | 首次 `after-roots` 等待 30 秒超时 |
| diagnostic-v1-rapid-search | 1 执行、1 失败 | 219.865 | 230.166 | 等首 128 个索引完成行的 10 秒门槛超时 |
| diagnostic-v1-backup-cancel | 1 执行、1 失败 | 189.234 | 203.810 | 15 秒内仍在写文件前的校验阶段 |
| diagnostic-v1-large-frame | 1 执行、1 失败 | 115.802 | 146.808 | 30 秒内未发布完整密集帧 |
| diagnostic-v1-shelf-rename | 1 执行、1 失败 | 113.778 | 156.338 | 创建后首次等待编辑器语义节点 15 秒超时 |

所有命令的 shell exit code 都是 0，包括上述 5 个失败。通过与否依据 JUnit 的 `OK` / `FAILURES!!!` 和实际执行数，不能依据命令退出码。失败记录中的 `tests: null` 不表示没有执行；同一 JSON 的 `executed: 1`、`failures: 1` 与原始日志一致。

## initial-v1：已通过范围与边界

`BundledSqliteFactoryTest` 的 5 个用例为：

| 用例 | 已达到的断言 | 计时窗口 / 未覆盖范围 |
| --- | --- | --- |
| `nativeRtreeTransactionsReopenAndCorruptionKeepOriginalFiles` | no-backup 路径与数据库名正确；新库为 1024 / FULL 并启用 WAL；真实 RTree 查询正确；未提交插入回滚；关闭重开保留已提交行；损坏触发保留异常，损坏文件逐字节不变，无额外种子文件 | 无单独操作延迟日志或性能门槛；本例只覆盖主动构造的损坏形式 |
| `existing1024ByteDatabaseRetainsPageSizeAndAuthorContent` | 已有 1024 / NONE 库保持格式和 WAL，512 KiB 合成 BLOB 完整保留 | 无单独延迟日志；不覆盖所有历史格式 |
| `independentHelpersWaitForCompleteCreationAndKeepTheSameAuthorRows` | 两个 helper 等待完整创建后读到同一提交行，创建回调仅一次 | 进入闸门 / 启动 / 释放后 future / 清理均为各自 10 秒预算；150 ms 内第二 helper 不得完成；不是同进程“绝不阻塞”的断言 |
| `emptyInterruptedCreationAndFailedSchemaCreationCanResume` | 空文件首次 schema 创建故意失败后可重开；第二次成功，版本、1024 / FULL 和唯一合成行正确，创建尝试为两次 | 此例是回调异常，不等同于真实进程被杀；真实进程覆盖见下表 |
| `nonemptyUnversionedDatabaseNeverEntersEmptyInitialization` | 未版本化但非空的既有库进入显式恢复错误；已有格式 / 内容可读，失败后整文件逐字节不变 | 不主张自动恢复任意未知 schema |

当前源码新增的 `existing4096IncrementalDatabaseRetainsFormatVersionAndAuthorContent` **不在 initial-v1 的 5 个用例内**。移除该新增方法后的工厂测试源码 SHA-256 与 v1 清单一致；不能把当前源码中的第 6 个用例计入 v1 通过数。

`DatabaseCreationProcessTest` 的 4 个用例为：

| 用例 | 已达到的断言 | 计时窗口 / 未覆盖范围 |
| --- | --- | --- |
| `lateLegacyCreatorKeepsFullAndNoneModesAndAuthorBytes` | 旧进程在候选打开前创建，覆盖 NONE / FULL × DELETE / WAL 四种组合；候选拒绝覆盖未版本化作者库；整主文件、页大小、模式、版本与合成 BLOB 保持 | 绑定、各消息应答、打开闸门、完成 future 和清理各有 30 秒预算；日志未提供各组合独立延迟 |
| `twoNewProcessesWaitForCommittedSchema` | 第二进程在首进程 schema 未提交时不能完成；首进程提交后第二进程成功读到完整 schema / 内容 | 启动 10 秒；150 ms 不得提前完成；提交后完成 30 秒；不是吞吐量或保存 p95 测试 |
| `killedCreatorRollsBackAndReleasesItsProcessLock` | 首次创建期间真实杀死独立 peer，重开时未提交表不存在，可重新创建完整 schema / 合成内容 | 绑定、阶段消息、确认进程死亡各为 30 秒预算；无单独重开延迟日志 |
| `killedLegacyWriterKeepsCommittedAuthorBytesWithWalAndRollbackJournal` | DELETE、WAL 下真实杀死修改已提交数据的 peer；重开保留已提交合成内容、版本 / FULL 模式且 integrity 检查通过 | 同上；不是全部故障时刻或全部 Android 版本的穷举 |

原始 `DB_PROCESS` 事件及源码断言确认 peer 与父进程不同进程、同应用 UID。这是跨进程证据，不是用线程模拟进程退出。

## diagnostic-v1：逐项阶段判读

### small-search：通过，但不能替代两万页门槛

用例 `pausedSourceReadCancelsOnRapidInputThenClearAndCategoryStayFresh` 使用两个合成笔记。原始日志为 `OK (1 test)`，无各步延迟样本。

- 旧搜索进入扫描闸门预算 5 秒，闸门最大等待 10 秒；扫描回调断言不在主线程。
- 主线程连续更新输入的预算为 1 秒；释放旧扫描后，最新 generation 4、清空后的 generation 5、分类后的 generation 6 各自等待预算 5 秒。
- 已断言最新查询命中第二个页面且无失败；清空后 board 类型结果为空；分类结果属于第二笔记；被暂停的 generation 1 从未发布。
- 没有两万页索引完成、并发保存分位数、冷 / 热搜索分布或资料库分页读取规模的证据；这些不能由小夹具通过外推。

### small-frame：完整跨批帧和资源释放通过

用例 `completeFrameCrossesBothTheEditorPointWindowAndDatabaseBatchBoundary` 保持完整帧 30 秒预算，释放 worker 5 秒预算。夹具共 258 笔、8226 点；编辑窗口只有 256 笔 / 8192 点且不含最后的红色目标笔迹，相关前置断言已通过。

| 阶段 | 探针时间 / 区间 |
| --- | --- |
| 夹具开始 → 完成 | 7.836 → 20.944 秒，区间 13.108 秒，不在帧预算内 |
| 夹具完成 → 编辑窗口完成 | 20.944 → 32.227 秒，区间 11.283 秒，不在帧预算内 |
| 完整帧开始 | 相对测试探针 32.280 秒 |
| 第 1 批 | 相对帧起点 2974.228 ms；256 行、8192 点；freeze 498.604 ms、decode 708.933 ms |
| 第 2 批 | 相对帧起点 17075.593 ms；2 行、34 点；freeze 129.719 ms、decode 17.383 ms |
| 完整帧完成 | `frameElapsedMs=17625.474`，在 30 秒预算内 |
| 验证并释放 | 相对测试探针 49.998 秒，距 frame-complete 事件 98.168 ms |

已发布统计：4 tiles、4 rebuilt、258 decoded、0 reusedStrokes、bitmapBytes 800000、geometryBytes 658560；metadata 468.143 ms、raster 16611.405 ms、publication 37.958 ms。累计读取 258 行、2 次改变回调。已断言目标像素为最后一批红色笔迹、单批不超过 256 行、全部行跨多批读取，clear 后无 pending worker 且 geometry reservation 回到进入测试时基准。

这不是整幅逐像素参考图比较，也不是连续缩放、稀疏修改或所有图层状态的覆盖。v1 没有 `nativeStrokeBuilds` 计数；258 decoded 不能当作 258 次原生形状创建。该小夹具通过不能代替下项密集夹具。

### history：第一轮根扫描门槛失败，尚未测试作者保存

用例 `largeLibraryAuditAllowsAuthorCommitAndCancellation` 保留 20000 个合成来源版本和 20000 个历史卡片根。原日志：

| 事件 | 相对探针毫秒 |
| --- | ---: |
| `fixture:start` | 142.416 |
| `fixture:complete` | 215418.154 |
| `paused-audit:roots:start` | 215610.411 |
| `paused-audit:roots:timeout` | 245766.836 |

夹具区间为 215275.738 ms。实际进入首个 30000 ms 等待后，日志间隔 30156.425 ms；没有 `paused-audit:after-roots`。明确失败是首次审计到达根扫描完成事件的时限，不是后续审计完成或取消 join 时限。

v1 源码逐根执行私有 TEMP 表插入；这提供优化依据，但当前事件不足以分解打开连接、逐行读取、解码和 TEMP 插入各自耗时，不能声称 SQL 插入独占全部超时时间。

尚未达到：暂停根扫描时 500 ms 内作者提交断言、作者编辑完成 10 秒门槛、审计完成 30 秒门槛及 `detachedSources=0`、不暂停审计时 30 次编辑及其 p50 / p95、第二轮取消审计到根完成 30 秒门槛、取消 join 5 秒门槛，以及取消后 20000 来源版本仍保留的断言。`HISTORY_CONCURRENCY_EVIDENCE` 未产生，因此本轮没有 GC 保存阻塞或取消延迟通过证据，也没有这个用例的根扫描次数 / 完整 GC 总时间。

### rapid-search：冷读取结果正确，首批索引门槛失败

用例 `twentyThousandPageRapidInputClearAndCategoryCancelOldGenerationOffMain` 的 500 本 × 40 页夹具建立完成。首次 `read` 返回目标页面唯一命中且 `pendingIndex=20000`，相应断言之后记录 `foregroundComplete elapsedMs=38943.880463`。这是一次当前模拟器的冷读观测，代码此处没有独立时限；不是 p50，也不能替代搜索同时保存基准。

启动 observe 后，等待已完成 128 个索引行的 **10000 ms** 门槛失败：日志 `observeElapsedMs=10240`、`completedRows=0`、`slowScanRows=1300`、`observerActive=true`。这表明首个完成批次回调尚未上报；**不表示零行已经处理，也不能判定 writer 未获取、分词卡住或 FTS 写入失败**，v1 没有细到这一步的阶段事件。

尚未达到：1 秒主线程连续改输入窗口；generation 4 的 120 秒结果等待及唯一命中断言；待索引数清零的 120 秒门槛；5 次热读和热读中位数；generation 5 / 6 各自 120 秒等待后的清空 / 250 本分类 / 500 本 active 断言。最终 `FTS_EVIDENCE` 和 `LIBRARY_SEARCH ... clearAndCategory=true` 未产生。v1 不能据此确认两万页下旧 generation 取消、新鲜性、主线程响应或热索引收敛。

### backup-cancel：未进入部分文件写入，取消清理断言未触达

用例 `cancellingWrittenSnapshotRemovesPartialAndPreservesAuthorLibrary` 保持 160 × 100 KB 合成编码来源行。先完成一份参照 snapshot 和完整 inspect，再独立启动需被 FileObserver 中途取消的第二份 snapshot。“baseline” 在本日志中指同一测试内的参照备份，**不是 Git 旧基线性能结果**。

| 参照操作阶段 | 相对参照探针毫秒 | 相邻关键阶段区间 |
| --- | ---: | --- |
| `snapshot-start` | 395.536 | — |
| `BEFORE_ISOLATED_COPY` | 2565.023 | — |
| `AFTER_ISOLATED_COPY` | 6529.003 | 隔离复制 3963.980 ms |
| `BEFORE_SNAPSHOT_VALIDATE` | 9007.834 | — |
| `AFTER_SNAPSHOT_VALIDATE` | 50317.223 | 校验 41309.388 ms |
| `BEFORE_SNAPSHOT_WRITE` | 50325.426 | — |
| `BEFORE_SNAPSHOT_SYNC` | 58717.281 | 写入开始至 sync 前 8391.856 ms |
| `snapshot-complete-inspect-start` | 59070.191 | 从 snapshot-start 到此 58674.655 ms |
| `inspect-complete` | 141843.618 | 完整 inspect 区间 82773.427 ms |

参照 snapshot / inspect 成功返回、具备可用文件长度与 canonical 值。这一阶段不在随后 15 秒取消操作窗口内；inspect 的 82.773 秒包括读取 / 导入 / 验证，v1 没有将其内部验证单独计时。

取消操作重置计时：start 0.072 ms，复制前 1727.908 ms，复制后 6747.330 ms（复制区间 5019.422 ms），校验前 8919.709 ms。**15000 ms** 的 `generation.await()` 预算到期，outcome 在 15144.627 ms 记录 `TimeoutCancellationException`、`partialObserved=false`、`cancelled=false`、`lastPhase=BEFORE_SNAPSHOT_VALIDATE`。没有校验完成、开始写文件或 `partial-observed` 事件。首个“必须因文件中途取消而结束、不得正常结束或超时”的断言失败。

因此已定位为写文件前校验未在预算内结束，而非证明 FileObserver 漏报、生产取消失效或取消后泄漏。`cancelled=false` 记录在清理 finally 显式取消之前，外层 await 超时本身也不等同于 observer 已发出取消。

尚未达到：确认部分字节数严格介于 0 与完整文件大小之间、generation 被 observer 取消、partial 已删除、scratch 为空、作者 canonical 不变，以及关闭 / 重开 Room 后重新 snapshot + inspect 仍等于参照 canonical。finally 的测试自有目录清理不能替代这些正式断言。参照校验的 41.309 秒支持先减少私有 stage 的逐 DAO 调度，但不能单凭这份日志精确分摊 DAO、解码和磁盘成本。

### large-frame：密集帧在原生构形期间超时

用例 `completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker` 保持 1201 笔、76802 点原密集夹具。夹具区间 31.840 秒；后续编辑窗口读取区间 45.318 秒；两者均在完整帧 **30000 ms** 预算之外。已断言编辑窗口不完整且不含最终红色笔迹；窗口为 128 笔 / 8192 点。

完整帧在探针 83.960 秒启动。超时日志 `frameElapsedMs=30446.222191`；只记录到首批 256 行 / 16384 点（帧起点后 11564.581 ms；freeze 1194.862 ms，decode 1497.879 ms）。此时 0 改变回调、`pending=true`、`frameReady=false`、`publishedStats=null`。worker 栈落在 `StrokeNative.createWithBrushAndInputs` → `InkBrushes.stroke` → `StreamedInkRaster.draw`；主线程样本处于消息循环。

该栈是单次超时采样，支持检查原生 shape 构造，但不是 CPU 时间剖析或死锁证明。首批回调并不证明随后完全没有继续处理。资源快照为 total / peak tracked 1848576 bytes、budget 134217728 bytes、inFlightJobs 1；没有预算耗尽证据。v1 源码在每个相交 dirty tile 内构造同一非 pencil 笔迹的原生 shape，因此存在重复构造路径，但 v1 未测原生构造次数。

尚未达到：最后红色目标像素、全部 1201 行跨批完整读取、单批上限、clear 后 5 秒内 worker 归零和 geometry reservation 恢复的正式断言。finally 有 best-effort 清理，但不是这些断言通过的证据。小帧已通过不取消此失败。

### shelf-rename：新建后进入编辑器未完成，尚未开始改名

用例 `shelfRenameCancelBlankAndPersistedIdentity` 在测试自有 app 状态准备后执行。失败位置明确为 `create()` → `singlePageEditor()` 第一个 **15000 ms** 等待：寻找 `ink-surface` 或 `continuous-pages` 语义节点。诊断记录 `stage=single-page-editor elapsedMs=56226` 是整个 create helper 已用时间，**不是单个 wait 的 56 秒预算**；没有记录该 wait 的独立起点。

超时后的只读状态采样成功：笔记 / 首页面存在，noteCount=1、pageCount=1；当前 / 已选笔记、当前 / 已选首页面均匹配合成夹具；loading / readFailed / pageLoading / pageError / createPending / createBusy / workspaceError 均为 false；lifecycle=RESUMED，`navigationReady=false`。主线程瞬时栈在 `InkCanvasView.requestDocument` 的 pre-draw 路径。

这能排除“诊断时笔记完全没写入 / 没选中”的描述；不能仅凭一个采样判定 readiness 哪个子条件未满足、Compose 节点缺失的具体原因、持续主线程阻塞或 API35 软件模拟器就是唯一原因。v1 尚无细分 Ink / authoring / 连续页 readiness 和实际 canvas 状态事件。

尚未达到：初始封面 forest、返回资料库后的菜单可见、空白名称禁止确认、取消保持原标题、改名保存后的身份 / 标题读取、搜索命中新名称，以及后续封面排列截图。后续 ready / 保存 / 隐藏键盘 / dialog 关闭 / 搜索门槛各有原 10 秒预算，但本次均未执行。不能将本例称为“改名逻辑失败”或“改名通过”。

## v1 之后已写入、等待重新验证的改动

这些描述的是源码动作和回归目标，**不表示 v2 编译、instrumentation 或性能通过**。

| 改动 | 与 v1 的区别 | 仍需证据 |
| --- | --- | --- |
| History 根插入小批量 | 私有 TEMP 根表一次最多 128 个 identity / 256 个参数，`INSERT OR IGNORE` 以原主键去重；仍逐条解码 / 检查取消，保持同一读快照和所有根来源。20000 个单根输入理论上由 20000 次插入降为 157 次；此为代码推导，非实测性能 | 原 30 秒根门槛、作者提交 500 ms 断言及后续取消 / 留存全部通过；新增跨整批 / 尾批重复根、已刷出整批后遇损坏 payload 必须中止且不删除；GC 扫描次数、总时间、保存 p50 / p95 / max 重测 |
| Backup 私有 stage 验证事务 | 只在 snapshot 的 isolated 副本与 inspect 的私有 stage 调用点包 `withTransaction`，复用 Room 事务上下文。`validate(stage)` 本身不一概强制事务，避免扩大 ShadowReplica 对 live DB 的调用范围；原有验证不删除 | 原 15 秒内实际观察部分文件并取消，以及全部清理 / canonical / 重开断言；新增“私有验证暂停时作者仍可提交”回归；snapshot / inspect 阶段重新计时 |
| Raster 每笔原生 shape 复用 | 同一笔非 pencil 笔迹遍历多个 dirty tile 时只构造一次不可变 AndroidX Ink `Stroke`，局部保留到该笔 tiles 完成；增加 `nativeStrokeBuilds` 计数。pencil、cuts、坐标矩阵和绘制顺序路径保持 | 原密集夹具 30 秒、完整输出和释放通过；native builds / decode / rebuild 计数及多 tile 输出一致性重新实测 |
| Search 首批细化诊断 | 增加 waiting-writer、writer-acquired、first-source-read、tokenizing / tokenized、first-fts-written、batch-prepared / committed 事件 | 这是定位工具，不是已确认的首批性能修复；需保留 10 秒门槛再运行并定位首个未完成阶段 |
| Shelf readiness 细化诊断 | 增加 Ink、page objects、authoring、连续页及 canvas 自有状态的失败时采样 | 这是定位工具，不代表导航或改名已修复 |
| 工厂已有 4096 / INCREMENTAL 库回归 | 新增显式旧格式夹具，检查重开保持 page size、auto-vacuum、version、WAL 和作者 BLOB | 不属于 initial-v1 的 5 例，需要本轮后续真实 Android 执行 |

本评估没有启动新的构建、ADB 或测试，没有更改主仓源码、原夹具规模、超时或断言。后续验收应在最终冻结源码及匹配 APK 下逐项补足未触达断言，并重新采集同环境前后性能；本目录 v1 数据不提供最终搜索并发保存 p50 / p95 / max、完整 GC 总成本或最终稀疏重建性能结论。
