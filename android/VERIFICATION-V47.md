# v47 原生编辑器与连续书写核验

日期：2026-09-29。保留既有工作树、schema12／IWO9、签名及资料。输入任务包SHA256 `8d04bc304e46313aee17476cf7db92e1b291a2e990aca440c0971c8fb1b26c30`。本机证据根目录 `E:/Inkweft/archives/2026-09-29/NextStageV47/`。此记录区分阶段构件，最终身份另列。

## CI实际结果

精确修复提交970df3dd616f2c9266bc9e0dd1c6161fd4ed54c1的[CI 36567769505](https://github.com/InkWeftDevs/InkWeft/actions/runs/36567769505)通过全部4个job。App241（121+120）、Room157、核心259、选择器19、服务18、同步4；TLS5/5；容器4项全部通过。构件ZIP哈希与GitHub公布值一致，汇总见 verification/v47/ci-970df3d.json。

只读失败根因是封存快照仍采用WAL、没有可写边车条件；仅对独立、关闭后的恢复快照封存为DELETE，服务原库继续WAL。验证只读文件系统EROFS、SQLITE_READONLY、独立8MiB tmpfs ENOSPC／SQLITE_FULL后原密文不变，以及独立新卷恢复身份／回执／密文／明文摘要一致。保留首次失败日志。

这些远端结果覆盖970df3d，不能冒充后续UI候选的远端结果。当前UI采用本机受影响领域验证。

## 原生样板与方向确认

初版P1固定用户构件cb6f67a：原生五状态在1920×1200、750×1600／320dpi／1.6字号、1200×1920均通过数据及交互断言；包含真实软键盘占用高度与截图。MuMu最初IME显示标志与实际屏幕不一致，改用官方API35模拟器并保留失败证据。窄屏操作条实际遮挡邻近节点已修复，未用语义节点存在替代像素查看。

用户已确认方向并提出细节：适宽纸张、标签栏配色、导图重复按钮、末页上拉追加、手写／电容笔模式。方向认可不等于最终所有截图审美通过；最终细节和真实笔感另记。

P1曾在vivo iPA2673覆盖安装，v46→v47；2374条原记录无缺失、无字段变化。物理截图和备份只在本机private-device-*归档，Git不包含私人样本。后续最终覆盖需以新基线重新核对。

## 连续书写新增验证

首轮CanvasNavigation两项、ContinuousPageGesture两项通过；完整NotebookNavigation通过实际页面追加、空内容、沿用纸面、拒绝旧末页请求、单指写入、重开页数与模式保持。

完整编辑器测试最初卡在app getter经ActivityScenario触发隐式waitForIdleSync，线程栈保存在details-regression-03；改用Instrumentation目标Application读取资料，保留相同真实数据库断言后通过。没有降低页面数或保存断言。

受影响原有25项全部通过（details-regression-05）：BrushB1Input、ContinuousWriting、TemplateCreation、BackupResource、LearningWorkbench、EditorVisualContract。后续长笔取消门禁的新增修订需要再执行对应回归。

## 保留的证据边界

- 性能旧v46三轮四条件12/12完成；P95中位数writing7.705ms、map8.600ms、encryption8.056ms、PNG7.475ms，均为该专用模拟器工程输入，非真实笔尖延迟。
- PNG资源任务窗口覆盖24/24笔输入，但真实decode每轮只重叠1/24；这是前台优先调度后的资源任务，并非持续PNG CPU压力。最终候选比较待固定构件。
- NEXT45-26 Android限容／隐私、NEXT45-28同本串行链路使用独立显式Probe，工具存在不等于已执行通过。
- 真实手持笔、15分钟温升／耗电、独立至少3位书写者评测仍待测。用户暂不能提供独立作者样本，现有开发样本不进入独立准确率。

新增／受影响实机步骤见 DEVICE-TEST-CHECKLIST.md 的 VIS-13～17。诊断包覆盖应用事件与状态摘要，不替代系统全日志、真实原文和笔感录像。
