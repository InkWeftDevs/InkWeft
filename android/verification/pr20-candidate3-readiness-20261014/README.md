# 搜索SQL边界取消候选：冻结与本地验证

新候选 **d66b60d6cf9587f646acb76dd8a00910b73a6d70** 只修改搜索实现及对应Room测试两文件。基于run38441089594原始阶段记录，补齐已取消读取在临时表SQL之间的停止检查，并将snapshot-open回调纳入事务finally保护。保留实时失效、排序、分页、author快照和正文新鲜性；不能中断正在执行的单条execSQL，尚无新的原生性能结论。

最终文件SHA逐项核对后，本地离线、串行单worker、无build-cache执行 `:data-local:testDebugUnitTest :data-local:compileDebugAndroidTestKotlin :data-local:lintDebug`：**31项JVM/0失败，Android测试源码编译通过，lint 0错误/14警告，3分9秒**。[完整回执](evidence/final-check.json)与原日志、XML保留。第一轮编译后曾修正snapshot-open清理位置，首轮日志作为已被替代的输入记录保留，不计作最终源码验证。主应用API未变化；当前未运行设备、Room或性能测试，不挪用45b候选的原生PASS。

[代码独立审查](evidence/code-review.md)无剩余确定性阻断。新增一项测试覆盖六个取消边界，期间完成保存和分类更新，取消后禁止进入下一工作阶段，下一次读取旧request仍返回新正文/revision/分类。闸门只用于正确性，不产生性能样本。

[58项冻结计划](PLAN.md)保留原57项，仅新增第31项；原1–30不变、原31–57只顺延1，31正确性/24公共/3诊断、四seed和全部阈值不变。B=a128，新C0=45b，新C1=d66；三端947/964/964个构建输入实际重建哈希通过，B与新C0分别保持原B和旧C1的canonical字节身份，候选相对45b仅两源文件变化。[清单差异](candidate-source-delta.json)及[源码冻结回执](evidence/source-freeze.json)可复核。source-freeze回执的PLAN哈希是工具冻结阶段原文；其后仅把状态更新为最终本地检查已完成，字节变化另记[状态修订](evidence/plan-status-finalization.json)。

[24项准备检查](evidence/preparation-check.json)和[执行复用审查](evidence/readiness-review.md)仅证明源码/模板准备；完整控制器预检、fresh时钟与一次性激活另行完成。沿用38/41/53/55/60分钟、单标准runner/单worker、原ACL恢复及≤20MiB文本/3天，不扩大权限或付费资源。未开始新的原生job。

[上一候选完整结果](../pr20-candidate2-results-20261014/README.md)仍为COMPLETE_SCREENING_NOT_FINAL_ACCEPTANCE：保存p50回退、暖读变慢、绘制不确定与GC实际删除暴露不足全部保留，不以本次本地检查宣称关闭。
