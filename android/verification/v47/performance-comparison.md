# 411ccf9 编辑器性能对照

旧 v46 与候选各 12 场（4 条件 × 3 轮）均完成，功能和 trace 完整性检查通过。以下为有限样本观测，不作为 SLO、稳定回退或优化幅度结论。

| 条件 | 旧 P95 中位数 ms | 新 P95 中位数 ms | 变化 | 旧/新超 deadline 帧数 |
| --- | ---: | ---: | ---: | ---: |
| 书写 | 7.705 | 7.761 | +0.73% | 2/2 |
| 64 节点图窗 | 8.600 | 8.256 | -4.00% | 2/2 |
| 16 MiB 加密 | 8.056 | 6.800 | -15.59% | 14/1 |
| 合法 PNG 任务 | 7.475 | 7.548 | +0.97% | 2/8 |

P95 中位数指三轮各自 P95 的中位数；deadline 计数为各自三轮实测帧的总和，分母与逐轮值保存在 JSON。

候选书写和 PNG 中位数略升，图窗和加密下降。PNG 首轮 P95 从 7.475 升至 9.873 ms（该轮 7/219 帧超 16.667 ms，旧轮 2/221），需要保留为尾部恶化观测。候选另两轮为 7.530、7.548 ms。

PNG 最慢帧为 31.460 ms，FrameMetrics draw 21.868 ms；对应 trace 的 Record View#draw() 21.833 ms，主线程在该帧 Running 24.263 ms，并与 concurrent GC 和 1.207 ms ClassLinker lock 竞争重叠。最后一次实际 PNG decode 已在约 2.515 秒前完成；该轮全部 7 个超 deadline 帧均未与真实 decode 重叠。现有证据不足以定位生产代码热点，也不能把并发 GC 墙钟时间当成暂停时间。

两版全部 24 场均为相同测试 APK（081f5b494322…）、相同设备设置、作者 fixture/seed hash 和视口；运行时源码分别为 74b5a3dd90f… 与 411ccf9a9ee…。每场保留 600 笔种子、完成 24 笔并确认 624 笔持久化；trace 每场 1 个窗口、192 个 input、24 个 save；无 error/data_loss，无 FrameMetrics 报告丢失。

每轮 PNG 整体任务与 24/24 次输入重叠，但只有 2 次 decode 调用跨入测量窗、实际 decode 仅与 1/24 次输入重叠。其余时间包含生产前台让步等待和幂等安装，不能称为持续 PNG 解码压力。加密调用则与每轮 24/24 次输入重叠。

模拟器 GPU_DURATION 全部为异常负值，已排除 GPU 耗时解读；SurfaceFlinger FrameTimeline 在先前 smoke 中有生产者时钟问题，因此正式 trace 采用 FrameMetrics VSYNC 与应用标记绑定。CPU 百分比为线程调度时间/窗口时间，多工作线程可超过 100%；加密含 crypto、分配与文件 I/O，不能只凭工作线程总量归因。输入至保存确认含 7×16 ms 注入节拍和数据库轮询，不代表真实笔延迟。

已停止独立 .performance 进程；12 个自有 trace 会话均已停止，远端配置、trace 和报告已清理；本地原始证据和独立包的合成 fixture 保留。普通工作台与平板资料未触及。

- 机器摘要：[机器摘要](performance-comparison.json)
- 基线：`performance-baseline-formal-01/analysis.json`
- 候选：`performance-candidate-411ccf9-01/analysis.json`
- PNG 定点查询：`performance-candidate-411ccf9-01/candidate-1-png-6be06835/png-outlier.sql` 与 `.csv`
- 清理记录：`performance-candidate-411ccf9-01/cleanup.json`

原始路径均相对于本机归档 `E:/Inkweft/archives/2026-09-29/NextStageV47/`，不包含私人笔记。
