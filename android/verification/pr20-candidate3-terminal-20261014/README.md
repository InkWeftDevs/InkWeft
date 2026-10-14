# Candidate3原生任务终态：原始ZIP待核

[run38498392929](https://github.com/InkWeftDevs/InkWeft/actions/runs/38498392929) 已成功完成，实际job **39分02秒**。官方完成日志的 **58条compact回执均PASS：31正确性、24公共比较、3诊断**，逐项匹配冻结身份/顺序/选择。工作流步骤仍显示“30项正确性”，只是旧标签，实际冻结计划及日志为31项。

执行提交 `d20b37a00d084ddea8a59698b926d94ce2f1a633`，prep `b3bcbcc9ca4e4134f0d2e4806bcd7bcf55ab0897`；B=`a128bb07`、C0=`45b4861a`、C1=`d66b60d6`。来源和方法哈希见 [独立终态核对](terminal-review.json)。本次未打断或重启任务；正式样本、阈值、预算与清理规则未变。

| 实际阶段 | 用时 |
|---|---:|
| 源码/SDK准备 | 23秒 |
| B / C0 / C1构建 | 9分45秒 / 8分24秒 / 7分37秒 |
| 模拟器准备 | 32秒 |
| 31项正确性 | 44秒 |
| 24项公共比较 | 10分13秒 |
| 3项诊断 | 56秒 |
| 两次清理 | 10秒 / 8秒 |
| 打包 / 上传 | 各1秒 |

构建合计25分46秒，占实际job约66%；时间来自官方步骤墙钟，不能当作单次原生性能样本。两次清理均报告进程停止、ACL精确恢复、errors为空。方法17是唯一报告辅助清理的调用，其余57条为自然退出。摘录保留原日志行号与完整原件SHA：[终态日志摘录](terminal-log-excerpts.txt)。

**证据边界：LIMITED_OFFICIAL_LOG_RECEIPTS_ONLY_FULL_ZIP_AUDIT_PENDING。** 尚未取得本轮原始ZIP；无法复算保存p50/p95/max、绘制decode/rebuild/像素一致性、GC扫描次数与实际DELETE暴露，无法逐字节核对ACL或复核helper17完整前置观察。APK/native/依赖也仍待文本回执审核，不能把构建步骤绿灯当作已独立检查APK二进制。上一轮保存p50回退、暖读变慢、绘制不确定与GC暴露不足仍保留，不能由58条compact PASS宣称关闭。

所需新原件为 `pr20-kvm-triple-38498392929.zip`，artifact **11866466104**，官方大小 **772054字节**，官方SHA256 **79c53a73e262a22205519f5a994e10e6f2c75ca396a39bbd251d5bbd6b17993c**，有效期到 `2026-10-17T02:35:26Z`。这些是服务器所报值，并非已对本地ZIP哈希。已知下载存储通道被拒绝，本轮没有重试或绕过；后续需实际提供该新原件。上一轮57项ZIP已完整审查，无须再次提供。

终态原始metadata及decoded日志在云scratch保持原字节，发布目录仅含非敏感派生核对与摘录。完整方法与来源冻结见 [candidate3准备材料](../pr20-candidate3-readiness-20261014/README.md)，上一轮完整结果见 [candidate2结果](../pr20-candidate2-results-20261014/README.md)。日常轮次成本优化见 [周转记录](../pr20-turnaround-20261014/README.md)。
