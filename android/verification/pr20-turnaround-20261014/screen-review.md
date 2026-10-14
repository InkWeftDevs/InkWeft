# 筛查入口独立审查

结论：当前冻结实现未发现剩余阻断；可作为 `SCREENING_ONLY` 日常筛查入口。不能据此关闭正式三端配对、原生正确性或性能门槛。

审查输入：

- `android/verify.py` SHA256 `c8858920e558c47b097eea19b9e4b366bedd0022cf1ea7bdc24e8562c547a356`。
- 新增 `ci/test_android_screen.py` SHA256 `bd4eefc66353818c93218f9f3978c78ca8caa69725bd8b8681aad33fdece7be0`。
- 离线结果 `screen-unit-review.log` SHA256 `ebbab1ae5bb873c1d3453bda33c5d03dce42f9a67656b90a1c655f0227f86c49`：15/15 PASS，0.112 秒。所有 Gradle/adb 子进程均为模拟对象，未执行构建、Android 或网络操作。

本次发现并由 root 修复的四项问题：

1. 原生输出仅有 `OK (0 tests)` 或少跑/重复方法也可能通过。当前 screen 从实际 Kotlin preset 提取具名 `@Test` 清单，核对非零 OK 数及每个 class/method 的成功状态；模拟零项、少项、重复均拒绝。
2. JVM 缺失、失败或全 skipped XML 可能被计为成功。当前 screen 要求完整选中 suite、正测试数及零 failure/error/skipped，并要求唯一对应 Gradle task 输出。`EXECUTED`、`UP-TO-DATE`、`FROM-CACHE` 分开记录；`SKIPPED` 及无任务证据不能借旧 XML 通过。
3. 清理原先发生在释放工作树锁后，可杀掉下一次调用的进程。当前锁覆盖全部 screen 清理；模拟安装失败时仍逐一清理两个自有 app/test 包，保留原异常，清理错误另记。`package:helper` 残留也使整体失败。
4. 已启动但失败/取消的原生检查原先仍显示 `NOT_RUN`。当前启动前记录 `RUNNING`，异常归为 `FAILED_OR_INCOMPLETE`；已确认原生 PASS 若仅清理失败，则保留原生结果、整体 FAILED，避免混淆两个结论。

兼容性与选择已核对：原 core/data/app/all 任务计划和显式 legacy 设备入口保持；新 screen 必须匹配 scope，拒绝覆盖 preset、物理序列号及无模拟器的 `--performance`。host screen 只选相关 JVM 与 AndroidTest 编译任务，不安装 APK。四套 preset 的类和具名方法均在对应 Kotlin source set 中真实存在。性能探针只追加，不替代正确性选择；`formalAcceptance=false` 始终保留。

15 项离线测试还覆盖：真实非模拟器属性拒绝且无安装/清理、主机成功不声称 native、清理命令失败返回非零、辅助进程残留、调用中断后清理与锁释放、非法 CLI 在执行前拒绝。首两次离线试跑因 root 同步新增可执行文件存在检查及 JVM task 日志门禁，假夹具缺文件/任务行而失败；补齐假夹具后才得到上述最终结果。这不是实际 Gradle/设备失败。

只读核对 root 已保存的 `search-screen-final/result.json`：整体 PASS，1.614 秒，JVM `UP-TO-DATE`，11+1 共12项零失败缓存报告，nativeCorrectness/nativePerformance 均 NOT_RUN。它证明缓存结果复用，不能表述为12项 JVM 本轮重新执行，更不能当作原生提速。最后 native 状态字段补丁仅经离线模拟验证，不冒称已跑设备。

边界：screen 使用当前单源、固定现有夹具和当前选择，不提供四 seed 配对结论；原生 parser/包隔离/清理尚需实际模拟器路径验证。继承的本地命令执行器不提供正式 CI 的60分钟预算或外部强杀后的完整收尾保证。本审查未修改 `verify.py`、正式冻结计划/探针/native clone，也未提交或推送。

另只读查看 cache 两文件：`ci/pr20_build_cache.init.gradle` (`d3e02bb3d9d237530867e04eac9892d526093fb51fdf85fcd2928bdab715e830`) 与 `ci/test_pr20_build_cache.py` (`5523ae7e35ced537d2c22b15ee7046cbd9b641c203d4418ac6a501fe2c72f35b`) 未见实现阻断。12项实际非Android Gradle夹具的既有回执 SHA `f286cf0119e48b5e7a1616afc65d9960b098e367b9852086b4fad7a2cfa8a92e` 已读取，未重跑。源/依赖/完整工具链内容以及每job新建自有目录仍由调用driver核验；helper仅校验声明摘要和实际Gradle/JVM字段。不能将该小夹具表述为Android加速或正式构件等价性验证。
