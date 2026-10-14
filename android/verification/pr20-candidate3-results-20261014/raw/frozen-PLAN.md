# PR20 SQL边界取消候选：58次调用，单标准runner最多60分钟

状态：源码/方法冻结；最终输入本地31项JVM、Android测试源码编译及lint已通过，原生尚未执行。沿用用户持续授权完成普通优化修正及必要有界验证，根节点独占最终审查/激活。原run38441089594及所有历史失败/分析保持，不同run不能混池。

三端固定身份：B=a128bb07e34cb82f00eeafc8e62a47bf7b95b128，仅原common writer factory和公共探针；C0=45b4861ae23ac19eda3246fcd34cf3d2132dc1ee，上一run的C1；C1=d66b60d6cf9587f646acb76dd8a00910b73a6d70。source-spec和三个manifest绑定Git/tree/overlays/实际android与ci输入；排除verification文档。新C0不是旧ebb。报告同时列slot和真实commit。

改动假设：SQL阶段边界检查取消，使已取消读取不再继续进入后续临时表/索引/查询/目录组装阶段，保护中断恢复、新鲜读取与保存语义。边界检查不表示execSQL本身可被抢占，也不保证保存延迟改善。上一run57项原生PASS仍未关闭observe保存p50回退；所有REGRESSION_SIGNAL、INCONCLUSIVE和GC暴露不足如实保留。

原1–30正确性不变；新增31为LibraryPagingTest#cancelledSetupStopsBeforeNextStageAndNextReadSeesCommittedAuthorState，一次调用覆盖六门控取消与后续读取/保存状态。原31–57只ordinal+1，变公共32–55、候选诊断56–58。共31正确性/24公共/3诊断、14安装组、9真实APK，预计36个不同selection。每次严格原class/method、单OK(1)、terminal及adb/host状态核验。正确性全部通过才能执行公共，公共全部通过才能诊断；失败立即停，不删样本/修改断言或重试方法。

方法17唯一辅助test包例外不变：原JUnit PASS+adb0且app/data均gone、仅helper残留时，原有natural观察后单次后台安全am kill并严格确认三包消失；无force-stop或fallback，其他方法自然退出。实际natural3s窗口保留0.25s末端查询保护，不虚称每次完整等待3s。

公共两probe及original-protocol.json逐字节不变：seed4101/4102/4103/4104；访问顺序B-C0-C1、C1-B-C0、C0-C1-B、B-C1-C0。C1相对B/C0前后各2次，B对C0为3/1旁证。24调用、720原序保存样本、18指标/54比较，四区组是分析单位，不将720次保存合池为独立样本。30保存nearest-rank14/28/29不变；raster10%/5ms；保存p50 10%/1ms、p95 15%/5ms、max25%/20ms；冷读/索引10%/500ms。至少3/4变慢且配对中位相对/绝对均超带为REGRESSION_SIGNAL，任一超带但不足为INCONCLUSIVE；全部带内且门禁齐全才WITHIN_PROPOSED_BAND。warm无新增阈值，不以改善抵消其它回退。

末3诊断不变：同Room/另一Room各30个新库自然GC；覆盖before-batch-commit才计明确暴露，少于20不给子集p50/p95；候选诊断不代替B/C0对照。libraryStageDiagnostics=true单次仅解释阶段，不能混公共值或作跨版本CPU/锁等待推断。

预算沿用已审预算3：从一次冻结的更早PR20服务器updated_at累计setup38、correctness41、common53、diagnostics/work55分钟，actual job60硬限。第一clock步无网络；未来/已过setup锚点立即拒绝，禁止后移或rebase；actual_job_started_epoch保持null直到外部终态metadata。早完成即推进，每命令受phase和global截止；原方法内部时限不变，新增门控耗时无预先保证。上一run完整job40分58秒仅参考，不保证本轮完成。

单标准ubuntu-24.04、单API35 AOSP ATD x86_64 KVM、SwiftShader/2vCPU/2048MiB、1920×1200/density240/font1.0及原动画。三独立源码树serial单worker/no-build-cache各3debugAPK；核验源码前后manifest、嵌入source/build身份、APK签名/hash、官方MuPDF四ABI完整ELF、依赖hash。官方源拒绝即停，不使用Java-only或代理/身份/镜像绕过。只本run合成包，不触用户设备/笔记/AniMemo。

KVM有权限则不动，否则只既有临时UID ACL且不扩大mask；记录原设备/ACL，trap+always各停止本job进程并逐字节恢复。原cleanup45+45、package45、upload120含grace名义261秒及39秒步骤裕量保持；总job60仍硬限，时间预算不代替恢复证据。无chmod666/改组/udev/模块或额外权限。

根节点独占一次性隔离执行：先无活动workflow的准备提交确认0runs，再以唯一branch/path、event.before精确prep、created=false、attempt1激活；无dispatch/schedule/PR事件、无并行或自动rerun。所有模板/源独立review后才必要PR写入取fresh anchor并一次冻结。

工件只完整白名单非敏感文本≤20MiB/3天：原日志、JSON/派生JUnit、原序样本、source/overlay/APK/依赖回执、环境和双cleanup/ACL、manifest。禁止APK/AAR/DB/keys/全env/full logcat；不截断慢样本。原生执行和性能接受分开；回执一致不等于对缺失APK本体重新验签。任何未执行/中断及原失败保持。
