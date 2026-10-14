静态审查结论：`LibraryTextIndex` 将分词预计算移出 Room 写事务的最终增量，未发现确定性数据安全或一致性阻断项。新增 focused suite 为 11 项，未见静态编译或同步问题。**本记录不是编译或原生测试通过证明**；未构建、运行测试或调用 ADB，未修改源码。`final-v3-source-review.md` 保留为历史记录，其“生产冻结”结论不能覆盖本次 v4 增量。

核准的最终文件：

| 文件 | SHA-256 |
| --- | --- |
| `android/data-local/src/main/kotlin/org/inkweft/data/LibraryTextIndex.kt` | `9861462b2eb3328020dcdc0396b1ed380d4b56fa4dd0646b030fb7a1a4f77fdf` |
| `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryTextIndexPreparationTest.kt` | `2b0bf878258086f80e349bf6360b833dd3b4c652aa8f5c7fcf6cce6ec892badb` |

生产逻辑的安全依据：

- `prepare()`（63–86 行）通过普通读取收集至多 64 个 pending 身份及源文本，在写事务外生成词项。保留原 1,000,000 字节 UTF-16 原文预算和首条超额仍单独前进的规则；每条和分词已有检查点响应取消。
- `commit()`（88–114 行）在同一个 Room 写事务内确认 pending 仍存在、当前可空 `(title, body)` 与预计算输入精确相等，之后才读取当前 doc key、更新 FTS 和删除 pending。变化的源保持 pending；缺失源与空正文严格区分。同 ID 删除重建且文本相同的 ABA 可复用词项，因为 FTS 只依赖这些文本，doc key 则重新读取。
- 多个 builder 由写事务串行核对；先完成者清 pending 后，后者跳过且不重复计进度。非空批次全部 stale 时仍等待 10 ms 并重试，只有新一轮 pending 查询为空才结束。源触发器在后续作者写事务中重新入队，搜索仍能从 pending 对应原文得到新鲜结果。
- 取消检查保留在预计算后、提交逐条开始和清 pending 前。**最终顺序为 `step?.invoke("batch-written")`（112 行）之后执行 `context.ensureActive()`（113 行），再从事务体返回**，覆盖诊断回调期间发生取消的情况。诊断 hook 默认 null，原尾部 `onBatch` lambda 兼容；仅实际处理条目增加进度。

11 项 focused 测试覆盖：预计算暂停时真实作者保存；全部 stale 后保持 pending 并由同一 builder 重试；准备后源删除；准备的缺失源被同 ID 空正文重建；card 仅 title/仅 body 改变；同文 ABA 且竞争 builder 更换 doc key；另一 builder 清 pending 后不重复进度；预计算后取消与 FTS 写后取消回滚；64 行边界及超额首条边界。暂停回调在写事务前或明确指定的提交位置，finally 放开 latch 后再取消并等待 worker；未见因清理提前关闭库留下 worker 的路径。末尾 `batch-written` 回调后取消的最终检查目前由静态顺序复核，未单独新增或运行该回调位置的原生用例。

需要保留的边界：1 MB 限制原文，不是全部堆内存；预计算会同时保留词项字符串，并发 builder 可能重复计算和提高峰值内存。正文复核和 FTS 写入仍占用 writer，持续修改也可能使批次反复 stale；因此不能据源码宣称保存等待已消除。当前唯一生产调用位于 `LibrarySearchRepository.observe()` 的 `launch(Dispatchers.IO)` 内；其余调用为 instrumentation 用例，因此此普通预计算路径目前不会在 Main 执行。该结论限定于已核对的调用点，build 本身不新增 dispatcher 切换。

`git diff --check` 通过。后续应先核对包含上述哈希的 APK，再执行 11 项 focused suite、原搜索新鲜性/20k gate，以及同环境索引并发保存配对；保留 p50/p95/max、原序样本、索引进度和暖查询取舍。既有 v2/v3 原生或性能结果只说明旧生产版本，不证明 v4 的正确性或性能。
