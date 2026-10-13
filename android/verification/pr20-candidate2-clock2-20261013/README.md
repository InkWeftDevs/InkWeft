# 第二候选clock2：C1准备预算超时，57项未执行

[run38433834125](https://github.com/InkWeftDevs/InkWeft/actions/runs/38433834125)，job115422592925，attempt1，failure。准备提交`14b4f48ea3a683b3cff1b4201bd1e2b4cd740d4a`，执行提交`9907a8f96fe0d20061f0a7deba6a69d4416ced50`，隔离分支`codex/kvm-pr20-candidate2-clock2-20261013`。实际job时间2026-10-13 **16:50:55–17:21:15 UTC，30分20秒**。

本轮时钟、源码与SDK准备通过；B和C0构建及后置检查步骤success，分别733秒、589秒。C1步骤在剩余434秒窗口后以 **exit124** 结束，官方日志与外层`setup cutoff - now - 2秒`限时一致。详细Gradle输出位于分离工件内，未取得原文，不能把434秒当作C1完成所需时间，也不能推断没有潜在编译问题。当前明确阻塞是累计准备预算耗尽，不是已观测的原生断言或MuPDF下载失败。

**模拟器/KVM步骤、30项正确性、24项公共比较及3项诊断全部跳过，57项未执行。** 候选45b4861的原生正确性与性能仍未验证；[第四次完整结果](../pr20-triple-results-20261013/README.md)中的observe保存p50回退和其他不确定性保持。

被测B仍为受控原始基线a128bb0；本轮C0=ebb4848、C1=45b4861。三端来源、57次方法、原探针、四seed、24公共调用、720次保存和判定阈值完整沿用[第二候选固定计划](../pr20-candidate2-readiness-20261013/PLAN.md)，不能与第四次的C0=7949011混淆。

前次候选在公共job API遭HTTP403限流后停止，57项均未执行，其[失败记录](../pr20-candidate2-clock-failure-20261013/README.md)保留。本次移除启动时钟步骤的网络请求，没有新增token或permission：必要PR20更新HTTP200响应提供服务器updated_at `2026-10-13T16:49:10Z`，epoch1791910150。收到响应后才冻结控制器、提交并推送/激活。最小服务器回执、时钟合同、授权、预算和workflow分别绑定相同时间和哈希。

实际job启动晚于预算起点105秒，见[时间对照](metadata/anchor-vs-job-start.json)。准备和排队耗时计入预算，截止没有后移：setup17:21:10、正确性17:27:10、公共17:41:10、诊断/工作17:44:10 UTC，job硬上限60分钟。运行器actual_job_started_epoch为空，用于区分预算起点与实际启动；真实开始由终态元数据独立核对。

[官方job日志](job-decoded.log)保存原始解码字节，35289字节，SHA256 `ea473725e383204daae37ac4ad4ae44c6ceeaa843d2914e857dfe8ffe80b87ad`。C1失败trap及always分别在17:21:09.310与17:21:09.746实际输出：模拟器NOT_STARTED、command_groups STOPPED_CONFIRMED、ACL NOT_TOUCHED、errors=[]。这证明日志记录拥有的命令组已停止、模拟器未启动且ACL未触碰；不称为执行过ACL授权并RESTORED_EXACT，也不冒充已核验工件内独立清理文件。成功路径cleanup为skipped，失败trap和always实际运行。

打包日志报告34个payload、1268679字节，omitted=0、truncated=0；上传动作报告36文件。官方[工件11834012592](https://github.com/InkWeftDevs/InkWeft/actions/runs/38433834125/artifacts/11834012592)为345179字节，ZIP摘要`ba2420bb9217f6ed2185bea66dc46451589f0a097e0119ebf86bb3f443577ebb`，保留至2026-10-16T17:21:10Z。原ZIP尚未取得，未独立复算其摘要、manifest、各APK/依赖回执或逐项ledger；不重试先前受拒的下载通道。

官方日志足以确定本次预算退出及清理事实，**预算普通修复不以用户上传该ZIP为前置**。按父侧最新继续授权，下一步依据本轮构建步骤和第四次已完成测试阶段耗时，重新合理分配累计准备/测试预算；仍保留每轮60分钟硬限、原正确性门禁、全部样本与阈值，避免同配置盲目重跑。候选源码、原始失败和既有证据不变。

[control](control/)保存精确控制器、固定输入哈希、10组离线preflight和激活workflow。准备分支0run、激活前0排队/运行任务，仅一次准备SHA绑定push，无dispatch/rerun。独立[启动审阅](metadata/final-activation-review.md)和[终态审阅](metadata/terminal-review.md)区分已证实事实与缺失原文。

[audit-tool](audit-tool/)是本轮原ZIP的离线核验器；独立审阅后27个合成检查通过，涵盖完成/中断状态、raw与host、helper回执和ACL原字节边界。它没有在本轮真实ZIP上运行，合成检查不等于原生通过。搜索和绘制的执行前判读说明位于metadata；其中预期decode计数不是本轮测量。
