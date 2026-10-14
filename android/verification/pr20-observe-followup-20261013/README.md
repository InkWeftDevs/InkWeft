# PR20 观察流初始化修复与剩余验证范围

本轮从已推送 `cd98df001715c4a5d68f15b5ac8dce931dbd146e` 接续。仅在云环境修改 Android 观察流及合成测试；不访问用户电脑、真实笔记、共挂载 AniMemo，不修改 backup-server、安全设置或 GitHub 工作流。原始配对报告、阈值、逐次样本和历史建议补丁保持原字节。

## 已修复的行为

`runningFold(0L)` 在 Room 的首次事件前发出虚拟版本 0。该事件可能启动 `mapLatest`、设置 previous 并开始 150 ms 延时；Room 随后的首次事件取消它，因为查询已相同，下一次读取跳过延时。

`LibrarySearchObservation` 保存原有组合、取消、异常和请求身份逻辑，供仓库及 JVM 测试使用。唯一调度修正是在累计版本后 `drop(1)`：仅移除虚拟种子，Room 首次真实事件仍为版本 1。真实失效立即刷新；索引完成重读而不增加 sourceVersion；清空查询、改变分类/类型/排序仍立即读取。各次仓库 collection 独立创建该状态。没有加入跨请求缓存或修改 SQL/read。

8 项虚拟时钟测试覆盖首次事件完整 150 ms 延时、Room 未发事件时不读取、快速输入取消旧读并重置延时、清空/分类/类型/排序立即生效、同请求真实刷新及版本递增、索引完成版本不变、真实编辑发生在延时内不被丢弃、collector 取消不发布失败、读失败身份与恢复。测试针对生产调度路径，不能证明 Android 数据库实际调度或保存延迟改善。

另补 `NotebookShelfUiTest.pageSourceEditResetsSecondShelfPageWithoutDirectoryChanges`：65 本合成笔记只在页面原文命中，翻到第二页后替换第一页某项的原文；要求 sourceVersion 增长、自动回第一页、再翻页共 64 个正确身份，且作者笔记、notes、drafts、目录保持相等。这样不会由目录 remember 键变化掩盖 sourceVersion 重置缺失。只覆盖页面源；卡片来源新鲜性仍由既有 Room 用例覆盖。

## 检查记录

现有云环境、JDK 17.0.16+8 / Gradle 9.4.1、单 worker、全程 `--offline`：

```sh
bash /workspace/scratch/run-inkweft-gradle-pan --offline \
  :data-local:testDebugUnitTest \
  :data-local:compileDebugAndroidTestKotlin \
  :app:compileDebugAndroidTestKotlin
```

- 最终 **BUILD SUCCESSFUL**，28 项 data-local JVM 测试全部通过（新增调度测试 8 项），0 failure/error/skipped。应用及 UI 测试源码编译通过。Room 测试源码在首轮实际编译通过，最终检查为 UP-TO-DATE。
- 反向检查仅临时移除 `.drop(1)`，8 项中 7 项按预期失败，包括首次延时和 Room 未发事件时不应读取；Gradle exit 1。脚本 finally 已逐字节恢复修复源码，随后执行上述最终绿灯检查。该失败属于刻意恢复旧故障的回归敏感性检查，不是隐去的候选失败。
- 首轮 data-local 编译/JVM 通过后补了“Room 首事件前索引完成”与“失败结果 sourceVersion=0”两条断言；最终 XML 验证的是包含这两条断言的最终源码。首轮日志仅保留过程记录。
- [精确命令与检查范围](evidence/checks.json)、[最终构建日志](evidence/final-build.log)、[8项调度测试XML](evidence/final-junit/TEST-org.inkweft.data.LibrarySearchObservationTest.xml)、[反向检查记录](evidence/mutation.json)、[最终源码 SHA256](evidence/source-sha256.json) 均已归档；其余20项 JVM XML 和反向失败原文一并保留。编译存在既有弃用/nullability 等 warning；本轮没有重跑 lint 或 core-domain 测试。

新的 Room/UI/原生性能运行均为 **NOT_RUN**；本轮无新的 APK 构建成功声明或安装、GitHub/KVM 任务、设备或安全设置变更。开发分支 `codex/android-perf-integration-20261012` 和 PR20 的 storage 基分支均不匹配现有工作流的 main / six-batches 推送及 PR 过滤条件；单次 KVM 工作流分支保持不动。PR 保持 Draft，不合并、不部署、不强推用户源分支。

## 暖读分析：保留未解决结论

旧四对暖查询中位数增量分别为 69.129、36.050、68.262、62.246 ms，均变慢。这些样本在索引完成且观察协程取消/join 后取得，`drop(1)` 不覆盖这条直接 read 路径。

旧基线复用 `request.notes` 已预载的 10,010,000 个正文字符；当前查询按当前快照核对正文，保护旧请求输入下的检索新鲜性。61 条预览投影只限制返回量，不代表最多搜索 61 份正文。夹具中的正文核对仍可能涉及 501 本。恢复旧正文缓存会违反新鲜性要求。

三个独立 SQL 确实重复构造无正文 shelf，但旧证据无分段计时，不能断言重复构造解释了 36–69 ms 差距。在单次快照中物化并复用元数据是后续候选，也会增加临时写入；本轮不凭猜测叠加修改。后续先用既有 metadata/previews/scan 回调记录阶段时间，在计时外查看查询计划，再决定是否实施。若变更，必须复验正文实时刷新、空正文草稿覆盖、新草稿、Unicode、稳定分页和并发快照。

## GC 删除竞争验证草案（未实施/未运行）

归档八轮首次 live 全部延期、删除 0 条；4096 条删除均在保存完成后的收尾阶段。live 保存实际重叠仅 3–9 次，删除收尾重叠为 0。现有数据不能证明连续实际删除中的保存尾延迟。

正确性用例可复用现有 `HistoryCollectionTest` 的 1281 来源版本夹具及 hook，分别验证同一 Room 和另一 Room 连接：

1. 提前打开连接、读取作者 revision。GC 首次 `before-batch-commit` 已执行真实 DELETE、仍持写事务时暂停，确认前 256 条在事务内已删除。
2. 从独立协调协程发出作者保存，并增加对尚未删除版本 300 的活引用；不得从 GC hook 继承事务上下文。确认请求已提出而未完成。
3. 释放提交前闸门，在 `after-batch`（已释放 writer）再暂停 GC，要求保存 Committed、revision +1、标题及新活引用正确。
4. 释放跨批闸门；必须延期且只删除 256 条。300、1281 保留；新一轮可删除 1023 条，最终保留这两个版本。
5. finally 无条件释放闸门、取消并等待任务，再关闭连接。保留原有超时、恢复和活引用断言。

该用例验证 DELETE 后提交前的 writer 排队及跨批安全，不代表 DELETE 循环内持续并行。人工闸门耗时不能用作性能数据。

自然延迟需另行冻结协议：30 次独立新夹具中，在 `after-scan` 仅发非阻塞保存通知，阶段 hook 只记时间、不暂停；记录保存请求/提交时间、Committed/revision、事务内 `before-batch-commit`、已提交 `after-batch`、首次删除/延期/收尾各区间。保存区间覆盖事务内时间点才计为明确删除事务重叠。作者先提交、GC 零删除延期也是有效样本，全部保留。暴露次数不足则 `INSUFFICIENT_EXPOSURE`，不得挑选重跑、放松阈值或关闭 generation guard 来制造整个 GC 轮次的竞争。

旧 a128 没有对应批次 hook；B/C 配对前需在隔离基线副本补相同位置的纯观测 hook，冻结新协议，不混用两侧不同启动阶段。生产 GC 本轮无变更。

## 仍需独立原生复验

- 新候选的 Room 失效与 Compose 分页用例实际运行。
- 新候选与固定基线、原候选的同环境 observe 首次时序及保存 p50/p95/max；增加读取开始/取消、writer 请求/提交与索引阶段观测，不能从 debounce 单元测试推出 p50 已修好。
- 同数据暖读复测及阶段定位；本轮没有暖读性能修复。
- 上述 GC 删除事务竞争正确性及预先定义暴露要求的自然延迟测量。
- 原四对报告的 1 个 REGRESSION、4 个 INCONCLUSIVE 及其他原生验收缺口继续保留。新设备/GitHub/KVM 执行需另行明确授权，本轮没有启动。

原始结果见 [四对 KVM 配对报告](../pr20-kvm-paired-20261013/README.md)。云端编译和 JVM 绿灯不替代它，也不改变其门槛。
