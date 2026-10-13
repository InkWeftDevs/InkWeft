# PR20 三端原生复验：辅助测试进程的有界收尾

第三次run38411448143原ZIP和99项载荷已核验；17个原JUnit全PASS，方法17的host FAIL来自一次立即pidof仍见org.inkweft.app.a0.test PID2707。主应用和data.test均已退出。九APK及879条依赖回执一致，双清理和三份ACL字节一致。原始证据与原FAIL在集成提交71435a6永久保留；不能据此证明当时PID的cached状态或自然退出耗时。

本次仅修复控制器processes_after及简短状态日志，三组源码、44方法/14组、9APK、样本、阈值、测试内部时限、串行单worker和32/38/52/55/60分钟截止均不变。每方法最多3秒自然退出观察；初次即三包消失则立即继续。逐次pidof返回码/输出/错误及时间全部记录，错误输出或非法PID立即失败。每条命令受小窗口和原phase/55截止约束。

只有确证的C1 correctness UI精确方法、原JUnit完整PASS/adb0、主应用和data.test已自然消失、仅本job安装的app-test辅助包残留，才允许一次 `am kill --user 0 org.inkweft.app.a0.test`。后续最多2秒窗口包含此命令与三包全部消失的复核；全收尾至多5秒，不延长原phase。命令返回0本身不能通过，残留/异常/超预算继续FAIL或INCOMPLETE。无force-stop、卸载回退或测试重跑。其他方法不允许该清理例外；所有测量方法沿用相同严格自然退出规则，仅增加短观察窗口。

Android官方说明[进程生命周期](https://developer.android.com/guide/components/activities/process-lifecycle)不由Activity结束直接决定；[adb am kill](https://developer.android.com/tools/adb)只请求终止系统认为可安全清理的后台进程。ActivityScenario关闭目标Activity不保证辅助测试APK进程消失。本次仍不猜原PID状态；新结果区分NATURAL_EXIT与AUXILIARY_AM_KILL_CONFIRMED，不把辅助包显式收尾称自然退出，也不修改原JUnit。若辅助进程仍活动而系统不清理，则明确失败。

额外观察与辅助清理在instrumentation返回后进行，不进入保存/绘制原始计时；仍消耗正确性/公共/诊断的原阶段预算，不更改测量样本和顺序。记录收尾次数与耗时，不能把测试收尾算性能优化。

保留71b8488官方POM单次校验、cleanup.py/package.py及budget-revision.json字节不变。单标准ubuntu-24.04 runner60分钟，contents只读，文本白名单20MiB/3天，不扩大ACL或任何权限。新分支codex/kvm-pr20-triple-20261013-process4，两次提交准确绑定before guard，attempt1且不重叠。preflight仅是离线控制器验证；不会关闭旧性能回退。排障优先官方job_logs，原始工件仍按授权可用路径读取，不绕过拒绝。
