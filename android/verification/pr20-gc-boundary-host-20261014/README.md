# GC 删除与保存边界：测试实现及 host 编译

新增独立 [HistoryDeletionBoundaryDiagnosticsTest.kt](../../data-local/src/androidTest/kotlin/org/inkweft/data/HistoryDeletionBoundaryDiagnosticsTest.kt)，只启用现有默认关闭的观测接口，未改 GC 生产策略或原自然/条件到达探针。**本归档没有新增 Android/原生执行，也不关闭 GC 竞争延迟门。**

该文件 SHA-256 为 `98b69ea1d09e0bce21646d79f50f9b7dd9396c9e73bb1a9baea647de7cdf6b72`。两个方法 `sameRoomNaturalArrivalBoundaries`、`separateRoomNaturalArrivalBoundaries` 仅在 `historyGcBoundaryDiagnostics=true` 时执行。每模式仍固定30个夹具、1281来源/1280垃圾、一次真实 rename；保留原 after-scan 非阻塞通知、5秒保存 watchdog 及至少20条暴露样本门槛，没有 pause、扩大夹具、重试补样或新外层事务。核心夹具构造与原自然探针逐字节相同。

测试复用 `HistoryGcBatchEvent` 记录 guard 与真实 DELETE 循环前后；复用 `SaveBoundaryDiagnostics` 为 GC、作者及收尾分别标记 context token，记录原 Room executor 排队、Room/SQLite BEGIN/END。作者走原 rename，不改成 save；选取跨 rename receipt 的主 SQLite 事务，排除 trigger-sync 和嵌套 DAO 事务。11个相邻 wall-time 区间覆盖请求到返回，CPU只作同线程差值。DELETE观测窗口包含回调开销，BEGIN调用耗时也不能命名为纯锁等待；`pureSqliteLockWaitMs` 始终为 null。

原提交前时点暴露、DELETE窗口交叠和保存开始于DELETE窗口分别输出。任何暴露子集少于20条，其竞争p50/p95保持null；完整功能结果允许0/30暴露。保存后收尾的扫描、耗时和删除另记，不并入并发成本，也不与历史条件到达结果合池。

记录上限为每数据库512条边界、每夹具64条GC事件和32条phase；越界或回调异常明确失败。计时期间不输出JSON/文件。所有生产协程结束或cancel/join之后，等待每个writer任务真正退出再封存、关闭并删除合成数据库；未退出则保留失败状态，不用超时空值宣称完成。边界以每段最多48条输出，失败与提交未知状态保留。

最终离线 `:data-local:compileDebugAndroidTestKotlin` 于 2026-10-14 09:23:47 UTC 退出0，用时 **107.791秒**（2任务执行、16缓存）。[起始绑定](compile-start.json)与[结果](compile-result.json)记录九个输入哈希全部不变，含保存guard的独立UUID修复 `cd1b0ba9…`；[完整日志](compile.log)为 `c5a35960b31ba7677b5f7c26e69e7b6d48c320ea180e109293ed17ef74a96bc0`。第一轮70.622秒编译及并发输入变更通知保存在 `history/`，不替代最终绑定。未运行新单元测试、设备测试或native benchmark。

[静态自查](static-review.json)绑定该文件；父侧 `/root` 独立读完后报告无阻断源码提交问题，[消息回执](review-receipt.json)明确只是源码审查。新的保存边界原生guard曾因非法UUID在构造命令时失败；此处包含的源修正尚未取得新的原生guard通过结果，因而本GC诊断依赖的hook仍不能视为已完成原生验证。是否运行后续独立诊断由父侧在解决该依赖后统一安排，本任务未创建/修改CI或推送。

本次还独立复核了[已完成run38520196523的官方日志](../pr20-match-execution-20261014/official/job-decoded.log)中方法56/57的原JSON字节、SHA、顺序和footer，重建records并逐字节匹配；[复核结果](gc-log-recheck.json)仍为同Room **0/30**、异Room **1/30** 暴露，后者唯一保存84.060999ms。各模式首轮+收尾各60次根扫描，总清理耗时分别2583.426125/2622.481542ms（30夹具之和）。竞争p50/p95为空，未记录精确DELETE窗口或纯锁等待，没有重跑该62方法运行。历史17→1扫描及1656.414→238.557ms纯GC配对保持原证据边界。

源码、旧探针及完整最终host输入均可由绑定文件和 `SHA256SUMS` 复核。没有访问用户设备/真实笔记/AniMemo，没有改动或重测Docker，没有提交或推送。当前没有足够GC数据支持新的生产修复。
