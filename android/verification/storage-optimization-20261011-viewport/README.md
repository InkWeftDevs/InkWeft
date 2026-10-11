# 视口笔迹与编辑器回归 · 2026-10-11

**12/12 PASS**，MuMu5 / Android 12 / API32，runner 用时 41.008 秒。应用、测试 APK、签名、16KB 对齐、精确方法开始/结束、最终 runner -1 和宿主退出0均已核对。两隔离包已卸载，模拟器恢复关机，root=false、旋转1/0保持，私有 ADB55121已停止。没有覆盖已有产品资料。

基点为 V86 `8596a981a76e27c38caf5220659e0e238141d319` 加未提交的第四轮补丁，Room18；版本号不变。应用源码清单 `batch-04/verification/build-inputs-r5.json` SHA-256 `d997b486a624291d691bf88ad6f3e273991243f836e4970161eecff3dcf5f83a`。本 APK 实际相关的71个变化输入全部匹配；未变化的测试源另按基点Git对象核对。本轮包含新建数据库4 KiB页修正，既有数据库保留原页大小与内容。

## 构件

| 包名 | bytes | SHA-256 |
|---|---:|---|
| org.inkweft.app.storageopt20261011 | 231920529 | `722263ff96fe745eefe5b819b475535a3aefef2db8061781e2200263e4cf6187` |
| org.inkweft.app.storageopt20261011.test | 9637179 | `52dc70b5d68bf9933dd778497d1037e3679cd64f91722d5855a19f014b20fa95` |

[独立测试应用](E:/Inkweft/archives/2026-10-11/InkWeft-Storage-Optimization/batch-04-app-r2/artifacts/InkWeft-storage-opt-20261011-viewport.apk)。这是用于新建合成资料的隔离候选。常规包中未检出 LeakCanary/Shark 类；Requery SQLite 的四个 ABI 与许可文件均已打包，两个64位库的 ELF LOAD 段满足16KB对齐。

## 已执行范围

`StreamedPageLifecycleTest` 的6项包含旧版次不重放陈旧撤销、跳过无关隐藏几何、PDF提供者流式导入、双显示/离屏恢复、密集页继续写与快速移出后立即返回、未知回执恢复。`StreamedInkRasterTest` 确认最终帧包含编辑窗口之外的末尾笔迹并释放工作资源。自动美化检查确认视口补读旧笔不会误触发识别。大页整合检查覆盖20,000笔移动/擦除/继续写、撤销、重开、交换导出和备份恢复。

真实 MainActivity 的3个界面方法覆盖连续页接缝跨页书写、快笔排队和跨本返回非页顶视野。测试自身生成的 `v20-seam.png` 已提取并目视检查，未见接缝空隙、工具遮挡或持续载入提示；不为截图重复测试。上述均为模拟器合成输入，人工视觉、真实触笔及物理设备性能仍待测。

| 精确方法 | 开始 UTC | 完成 UTC | 结果 |
|---|---|---|---|
| `StreamedPageLifecycleTest#changedStoredRevisionAfterReleaseDoesNotReplayStaleUndo` | 2026-10-11T12:29:28.656811+00:00 | 2026-10-11T12:29:28.909636+00:00 | PASS |
| `StreamedPageLifecycleTest#enteringEditorDoesNotDecodeUnrelatedHiddenGeometry` | 2026-10-11T12:29:28.909636+00:00 | 2026-10-11T12:29:29.046916+00:00 | PASS |
| `StreamedPageLifecycleTest#directPdfProviderImportHashesTheStreamAndClosesItsStagingFile` | 2026-10-11T12:29:29.048423+00:00 | 2026-10-11T12:29:29.349861+00:00 | PASS |
| `StreamedPageLifecycleTest#offscreenReloadKeepsPressureUndoRedoAndOtherActiveView` | 2026-10-11T12:29:29.351369+00:00 | 2026-10-11T12:29:29.461386+00:00 | PASS |
| `StreamedPageLifecycleTest#denseViewportStaysWritableAndPanningReleasesGeometryWithoutLosingUndo` | 2026-10-11T12:29:29.462369+00:00 | 2026-10-11T12:29:30.748861+00:00 | PASS |
| `StreamedPageLifecycleTest#unknownCommitCannotReleaseDraftAndReceiptRetryReleasesOnlyAfterConfirmation` | 2026-10-11T12:29:30.749846+00:00 | 2026-10-11T12:29:30.805684+00:00 | PASS |
| `StreamedInkRasterTest#completeFrameIncludesGeometryBeyondTheEditorWindowAndReleasesItsWorker` | 2026-10-11T12:29:30.806660+00:00 | 2026-10-11T12:29:32.162193+00:00 | PASS |
| `AutomaticBeautyRegressionTest#viewportHydrationDoesNotRecognizeOldInkAndIncompleteWindowsPauseAutomaticRecognition` | 2026-10-11T12:29:32.165663+00:00 | 2026-10-11T12:29:34.799618+00:00 | PASS |
| `TabletFeedbackRegressionTest#largePageMoveEraseWriteUndoAndReopenRetainOriginalBytes` | 2026-10-11T12:29:34.801149+00:00 | 2026-10-11T12:30:00.215022+00:00 | PASS |
| `ContinuousWritingUiTest#rapidSuccessiveStrokesRemainQueuedUntilAllAreSaved` | 2026-10-11T12:30:00.217004+00:00 | 2026-10-11T12:30:02.746605+00:00 | PASS |
| `ContinuousWritingUiTest#continuousSeamHasNoGapAndOneGesturePersistsOnBothSides` | 2026-10-11T12:30:02.749119+00:00 | 2026-10-11T12:30:05.882704+00:00 | PASS |
| `NotebookNavigationUiTest#continuousCrossBookReturnRestoresNonTopViewport` | 2026-10-11T12:30:05.882704+00:00 | 2026-10-11T12:30:09.661870+00:00 | PASS |

## 构建与证据

核心469/469、data-local主机10/10与99个不同存储设备方法另见[存储结果](../storage-optimization-20261011-batch04/device-results.md)。本轮 `:app:lintDebug` 通过，0 error/fatal，176 warning、2 hint保留在报告中；没有将建议级提示当作已修复。

在 `android` 目录使用项目 JDK17、SDK36：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' --init-script verification/storage-optimization-20261011-editor/app-identity.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

本机证据目录 `E:/Inkweft/archives/2026-10-11/InkWeft-Storage-Optimization/batch-04-app-r2/` 包含APK、源码绑定、构建/lint记录、逐方法事件、截图、结果校验器及设备清理回执。APK在两个assemble任务完成后冻结；最终Gradle与lint均已成功。此前源码r3的首轮12项通过记录和构件保留在 `batch-04-app/`，未覆盖。

## 当前边界与实机记录

取消的是整页笔迹活动/保留笔数、采样点数及图层成员门槛。编辑窗口最多65,536候选点，另保留撤销所需几何；完整帧分批绘制。单笔、单次编辑和交换文件预算仍在，密集区域的选择/擦除需先放大到完整窗口。PNG/PDF/交换导出仍有整结果内存占用，不能据此声明硬件容量无限或所有操作恒定内存。

按[真机清单](../../DEVICE-TEST-CHECKLIST.md)执行 SO4-WINDOW-01、SO4-AUTO-01、SO4-EXPORT-01、SO4-HARDWARE-01，并填写[新设备结果模板](results-template.md)。升级前保存备份；旧安装包不能读Room18/新PAGE头/紧凑点检查点，降级须在独立环境从升级前备份重建。
