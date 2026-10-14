# 空草稿零行 CTE：定向主机检查通过，原生待验证

父提交 `68431ea1db07b105a3d93f6819268d8b29fd24bf`；其搜索生产和分页测试文件与 d66 原生候选逐字节相同。改动仅涉及 `LibrarySearchRepository.kt` 与既有 `LibraryPagingTest.kt` 的一个方法。

依据 [d66 完整原始结果](../pr20-candidate3-results-20261014/README.md)：空草稿准备阶段累计 30.572ms（包含建表，墙钟并非 CPU 因果证据）。没有草稿时使用具名且显式类型的零行 CTE，避免每次快照建立空 TEMP 草稿表；存在草稿时原建表及插入路径保留。快照、取消边界、notes 失效监听、正文新鲜性、筛选、稳定排序、分页和预览上限保持。既有分页测试扩展为空/非空覆盖层各跑全部排序、分类与翻页断言。

最终生产 SHA256 `99cdd6b82f48f3553577ae9fad7473019a77293f618df8f3c1dcb29650ad4571`；测试 SHA256 `b05c4c93f2c0ed36204bb548c20f8d95c0dca5a35238e05361ac3655f6f9a061`。[独立静态审查](code-review.md)未发现阻断；末节复核最终五列 affinity。

## 已执行验证

`python3 android/verify.py data --screen search --gradle /workspace/.setup/tools/gradle-9.4.1/bin/gradle --output <独立证据目录>`，云 Linux、JDK17、Gradle9.4.1，单 worker，offline。

- [最终 result.json](host-final/result.json)：PASS，152.027秒，12项 JVM 测试本轮实际 EXECUTED、零失败/跳过；生产 Kotlin 重新编译成功。
- Android 测试 Kotlin 最终为 UP-TO-DATE；相同最终测试文件已在 [首轮](host/result.json) 编译通过。首轮生产 SQL 尚未显式 CAST，随后仅增加五列类型并重跑最终主机检查；首轮不是最终生产输入，原日志保留。
- 两轮均没有连接模拟器或执行 Room/Android 测试；`nativeCorrectness=NOT_RUN`、`nativePerformance=NOT_RUN`。不将编译/JVM 绿灯作原生收益。

## 后续定向原生筛查

比较 d66 与本次候选，分别只构建一个 data-local Android 测试 APK，原样使用冻结 library 探针、seed4101–4104及各轮30保存/5暖读。候选相关 Room 正确性先行，随后四块平衡交替的八次公共搜索调用。标准 runner、同模拟器、无新任务缓存、预算内收尾、最小当前 UID KVM ACL 与精确恢复；原正式三端协议、样本、门槛和旧失败不变。

此筛查只判断本次最小改动的方向，不关闭相对 B 的 p50/warm 回退、绘制疑点或 GC 实际删除暴露不足。稳定候选之后才执行完整验收。
