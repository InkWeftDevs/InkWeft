# PR20 三端集中原生执行包装器

用户于2026-10-13 11:54:51 UTC批准已冻结的集中复验，11:55:28 UTC补充后续常规修复/测试可直接继续。本包装器只执行本次固定尝试，不自动重跑或改变采样。授权边界见 `authorization.json`；固定方法、源码与预算原文仍在提交 `6e6391c7de228b69453499ea106ffed7615afb29` 的 `android/verification/pr20-native-readiness-20261013/`，不修改原提案或其SHA清单。

仅新分支 `codex/kvm-pr20-triple-20261013-once`：先提交、推送无活动工作流的准备提交，再把其完整SHA填入新工作流 `event.before` 并单独提交推送。只允许push、created=false、该固定before、attempt=1；无dispatch/schedule/PR触发。标准ubuntu-24.04单job硬限60分钟，首次真实job开始时间来自只读GitHub元数据；所有阶段受26/32/48/55分钟累计截止，外层保留2秒停止窗口，55–60分钟用于双清理与白名单上传。

`run.py`从固定Git对象重建三端，串行构建9个真实debug APK；不共享项目输出，不用构建缓存。执行17项正确性、24项公共比较及3项候选诊断，失败即停止后续方法。每方法保留原始instrumentation及派生记录/XML；JUnit通过不等同性能验收。自然GC暴露不足、旧暖读和性能不确定结果不会被包装器判绿。

`cleanup.py`沿用已执行旧包装器的进程身份/组检查与最小UID-only KVM ACL逐字节恢复，trap与always分别出具回执。`package.py`仅按固定方法/安装组白名单收集文本，不上传APK/数据库/私钥/AVD，不截断原文；总限20MiB，保留3天。容量超限或敏感模式命中时如实失败。源码准备、语法检查和合成打包测试不调用Android设备、不授予KVM权限。

启动前校验：官方actionlint 1.7.12、全部Shell块bash -n、内嵌Python语法、固定前置提交与分支守卫、源码/probe哈希、方法44/安装14及包装器状态/失败解析。上下文位置参照[GitHub官方上下文表](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability)。
