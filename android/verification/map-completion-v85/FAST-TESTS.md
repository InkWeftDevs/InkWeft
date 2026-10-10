# V84 / V85 增量验收

日常迭代先测本轮改动，合并或发布前在最终候选上集中补齐完整回归。下面是缩小执行范围的入口，不是完整验收，也没有新的设备 PASS。

## 现在该跑什么

- 正在测 V84：保留已经完成的回执；若下一步直接用 V85，之后在 V85 复测相关功能即可，不必把 V84 整套重跑完再把 V85 整套重跑一遍。V84 回执仍只证明 V84。
- V84 的八个新增核心方法已在云端通过。该次 433 项核心测试的 XML 执行时间合计 2.560 秒，最终编译加 lint 的 Gradle 日志为 4 分 5 秒；均不包含本地构建、设备启动、截图和手工操作，不能据此承诺本地总耗时。
- V85 日常先跑下面 **13 个设备方法**：10 个新增、1 个修改过的个人模板方法，以及布局预览和事务指纹的 2 个关联回归。第一次下载完整依赖、构建或启动设备另算；当前没有实测这组总耗时。

## V85 快速入口

在专用测试设备／模拟器、测试资料和 `android` 目录执行，使用本地完整原生依赖。无需先单独 `assembleDebug`；下面任务会构建需要的 APK。保留 Gradle 缓存和增量构建，不加 `clean`、`--rerun-tasks` 或重复三轮诊断。

```powershell
.\gradlew.bat :data-local:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.data.MapCompletionRepositoryTest,org.inkweft.data.StudyOrganizationRepositoryTest#measuredLayoutUsesFrozenCoordinatesAndRelevantContentFingerprint"
.\gradlew.bat :app:connectedDebugAndroidTest -PinkweftInsertionPreview=true "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.app.MapCompletionUiTest,org.inkweft.app.NoteFirstUiTest#personalTemplateIsAnonymousAndClosingWindowKeepsDraft,org.inkweft.app.StudyOrganizationUiTest#layoutPreviewCancelKeepsAuthorStateAndApplyUndoUseMeasuredNodeBounds"
```

这里只给出了待执行命令。新测试、关联回归和未选择方法的设备状态保持原有 NOT_RUN，直到收到本候选的实际回执。云端已通过的核心测试和 lint 无需每次在本地重复；本地有代码修改时按修改范围重新验证。

## 手工先走一条完整路径

创建“项目拆解”模板 → 改标题 → 切换布局并取消／应用／撤销 → 给连续兄弟主题创建括号归纳 → 关闭重开 → 缩放适配 → 分别导出 PNG、PDF 并打开确认。确认标题、成员、布局和原笔记保留。先覆盖这一条，字号／横竖屏矩阵、1024 节点极限、选择器重建与性能录屏放到后面的扩展验收。

## 扩展与失败处理

- 合并／发布前在最终候选上集中完成 [设备待测清单](device-pending.json) 和 V81–V83 未完成项，重点包括容量、保存恢复、完整备份与新记录往返、冲突回滚及旋转。缩小日常范围不会把这些项目标成通过。
- 同一源码、同一 APK、同一设备条件下，保留已完成结果；失败先保存方法名、异常栈、超时记录和原始输出，只单独重跑该 `类名#方法名`。修复后补测受影响的关联项目，避免直接从头重跑全部。每次失败仍计入记录。
- 等待明显停滞时，先记录当前 Gradle 任务／设备方法和耗时，区分依赖下载、编译、安装、设备等待与用例超时；先处理该环节，不靠增加超时或整套重试掩盖问题。未完成标为未完成。
- 保留源码 SHA、脏工作区状态、APK SHA 和设备条件。不要清除真笔记数据来加速测试；CI 的逐方法清数据只适用于它自己的临时模拟器。
