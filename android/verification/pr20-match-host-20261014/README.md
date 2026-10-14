# 最小匹配与绘制候选：host 验证

源码固定为 `cb7e14218ac83aa8cf6b9d7906051a10da1a6547`。本目录只记录本地 JVM、编译和只读审查，**没有该候选的 Android 性能或原生正确性通过结论**。

匹配 helper 只排除首字符必不相等的 ASCII 候选位置，其余仍用原 String 忽略大小写匹配；非 ASCII 查询首字符直接走原实现。两类生产 UDF 及诊断 UDF 使用同一 helper，不改 SQL、快照、FTS、失效、取消或排序。帧内铅笔渲染器改为首次实际 PENCIL 绘制时创建，保留原复用、批尾及异常清理路径。4 个文件共 101 行新增、10 行删除，其中 62 行为 5 项 JVM 测试。

| 检查 | 结果 | 边界 |
|---|---|---|
| 搜索定向 JVM | 17 项实际执行，0 failure/error/skipped；159.591 秒 | matcher 5、observation 11、terms 1；非 Android |
| 应用及应用 Android 测试源码编译 | PASS；305.029 秒 Gradle | 3 task executed，43 up-to-date；core 57 项测试为 UP-TO-DATE，未重执行 |
| 数据层 Android 测试源码编译 | PASS；90.210 秒 | 2 task executed，16 up-to-date；未运行设备测试 |

完整命令、日志和 JVM XML 在 `host/`。总 wall 与单次 Gradle wall 可能有少量包装开销，以各 `result.json` 字段为准。编译警告原样保留。没有新 APK/native 构建或计时结论。

[matcher 审查](review/matcher-review.md)和[铅笔审查](review/raster-lazy-review.md)均未发现确定性阻断。依据来自[此前单端原生因果诊断](../pr20-causal-results-20261014/README.md)，不能将其数值标成此候选的结果。相对上次完整候选 `d66b60d6`，此次源码还包含此前默认关闭的诊断 hooks，后续整体比较不能称为仅改 helper 的独立因果实验。

[scope 合成 host 比较](review/scope/review.md)只显示 500 行约 0.04ms 的小差异，未并入生产。[保存返回边界审查](review/save-return-review.md)未找到可安全删除的明确冗余；其 360 条原序尾段复算和缓存依赖哈希一并保存，完整本地 javap 文本/依赖二进制未复制。该审查不能把跨线程尾段称为提交或锁等待，也不能关闭保存 p50 回退。

原公共样本、四个 seed、阈值、旧失败和未充分暴露结果继续保留。新候选的暖读、保存 p50、旧单层绘制回退、连续变换/稀疏更新、dense 时限以及 GC 等价竞争比较仍须按各自证据范围评估。此 host 目录不激活 CI，不修改 Docker，也不触及真实笔记或用户设备。

`manifest.json` 绑定精确复制的文本及 4 个源码文件；`SHA256SUMS` 覆盖其余所有文件。哈希只用于完整性。

源码及新增文档/脚本的 whitespace 检查通过；因果归档的原官方日志含时间戳后的空格，4 份原 CSV 使用 CRLF，完整 `git diff --check` 因此会报告 trailing whitespace。保留这些原字节及其哈希，不对证据做格式清理；排除这 5 份原文本后的 staged 检查通过。
