# PR20第二候选的一次性原生控制器

准备状态：仅有workflow.proposed.yml，无活动工作流。源码/方法冻结于6c72fd8；C1代码45b4861，C0上一候选ebb4848，B原受控a128。此目录不进入用户源分支或main。

沿用既有持续授权，单标准ubuntu-24.04 runner，单KVM临时模拟器，9个真实debug APK、57单方法调用（30正确性/24公共/3诊断）、14安装组。新增13正确性不改变原44调用参数或公共指标。累计32/38/52/55/60分钟截止、20MiB文本/3天、双清理与最小临时UID ACL保持。only-method17辅助包background-safe am kill例外保持，新增测试不扩展例外。

run/package显式绑定最终source-spec哈希。授权清单绑定PLAN/source-spec/method-plan/original-protocol及新budget amendment。完整离线preflight检查真实函数与合成输入，不能视为Android执行或性能结果。

准备提交先推送并确认0runs，再新增唯一活动workflow，以event.before精确绑定准备提交、created=false、attempt=1及唯一branch/path push只启动一次。workflow.proposed.yml保留零before值，是不可运行的准备模板；活动workflow与它只允许before值不同。执行中不改源码/控制器、不rerun、不缩样本。完成后原始instrumentation、身份、环境、双清理和manifest独立校验，分析器再判性能。
