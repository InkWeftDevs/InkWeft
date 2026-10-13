# 第四次三端原生复验：有界辅助测试进程收尾

2026-10-13 15:05:19 UTC 创建 [run38419007471](https://github.com/InkWeftDevs/InkWeft/actions/runs/38419007471)，attempt1。准备提交 `7052c22afdd6e349020b6e48af1b038d87e5e1cf`，执行提交 `30acef2e082eb1cd2d70471737824da32fbe9c24`，隔离分支 `codex/kvm-pr20-triple-20261013-process4`。本文记录启动；尚无本次原生通过或性能结果，最终以原始证据核验为准。

[第三次完整工件报告](../pr20-triple-attempt3-recovery-20261013/README.md)保留17份原JUnit PASS、方法17 host FAIL、27项NOT_RUN。根因是该UI方法返回后的一次立即进程检查仍见辅助测试APK；主应用和data.test已消失。单次观察不能证明原PID是否cached或会自然退出。旧观察保存p50回退、四项不确定结果和GC持续删除竞争缺口仍未关闭。

本次[控制器](https://github.com/InkWeftDevs/InkWeft/tree/30acef2e082eb1cd2d70471737824da32fbe9c24/ci/pr20-kvm-triple)只修改进程收尾检查和结束状态日志。各方法先最多3秒自然观察，初次三包消失则立即继续；仅对C1正确性阶段指定UI方法，原JUnit PASS/adb0且只有固定辅助测试包残留时，允许一次 `am kill --user 0 org.inkweft.app.a0.test`，后续最多2秒含命令及三包消失复核。命令返回0不足以通过，任何残留/异常继续FAIL；没有force-stop、卸载回退、测试重跑或原JUnit改写。公共性能方法不允许此例外。所有命令及观察仍受原阶段截止约束，收尾不进入方法内测量值，但消耗原总预算。

三源B/C0/C1、9APK、44方法/14组、样本、阈值、单runner与32/38/52/55/60分钟截止逐项不变。cleanup.py、package.py、budget-revision.json与第三次字节相同，保留官方POM单次预检、精确ACL恢复、双清理和文本20MiB/3天限制。源码计划仍固定于 `6e6391c7de228b69453499ea106ffed7615afb29`；本次没有业务或测试源码修改。

离线9组检查通过：新增19种实际进程检查函数的合成路径，其余包括官方POM检查、冻结计划、24份历史解析/11种无效变体、方法失败路径、原累计截止、actionlint与脚本语法、12种打包状态、38项输入只读摘要。独立静态审阅未发现阻断项。以上不是Android验证；实际辅助包清理路径仍待本次运行确认。

冻结摘要：run.py `5334abc5cb8391f9f6f83366f0a7463646d420da8e8ac78fb27fd9544414c3cc`；preflight.json `d501563421b5a1a47e1264b50305f3313ba6c6e9c4ef1498b0de95ee27598a46`；EXECUTION_SHA256SUMS `084c43aaf6addb322bd5ddc730183f9c5dbb8fb65ccbae3843953c79e3923a5c`；准确绑定准备提交的活动workflow `40234f780c26e93a87fbaf4c871e410e6ee55a803c4ffd05b2332db2a984c7a0`。八项执行文件逐项核验，actionlint与12段bash语法通过。

准备分支推送后官方API确认0个run，仓库最近100个run无活动项；随后只推活动workflow文件，确认新分支唯一run、准确HEAD、attempt1。没有合并、部署、强推或访问用户设备。排障优先官方job日志；不会绕过此前工件存储下载拒绝。
