# V83 本地交接

云端：源码、核心回放、核心JUnit、Android源代码/测试编译及lint。
本地：完整原生依赖构建、安装、运行Android测试和用户操作采样。当前5方法都是NOT_RUN，旧V80数据不认证V83。

## 构建与精确测试

在仓库android目录（PowerShell）：

```powershell
.\gradlew.bat :core-domain:test :app:assembleDebug -PinkweftInsertionPreview=true
.\gradlew.bat :app:connectedDebugAndroidTest -PinkweftInsertionPreview=true "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.app.ExcerptHistoryStateTest"
.\gradlew.bat :data-local:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.data.StudyAndSelectionRepositoryTest#excerptUndoRedoRestoresFrozenBytesAndReceiptsWithoutNewSnapshotPayload,org.inkweft.data.StudyAndSelectionRepositoryTest#successfulReadTimingObserverCannotChangeRepositoryOutcome"
```

保留app/data-local build/outputs/androidTest-results及测试APK/安装包SHA、实际Git SHA、是否脏工作区。测试在一次性测试资料上运行；另复测V81/V82 pending，避免把原始V80的PASS当新候选PASS。

## 加载与算法采集

- 成功读取的INK_FREEZE/INK_DECODE、AUTHORING_FREEZE/AUTHORING_DECODE，count微秒，auxiliary同一进程内配对序号；最近摘要含条目数。没有页ID，导出前清诊断并只操作一个待测页可便于关联。可能来自外层写事务；失败读取没有阶段回执。
- 分开保留导航触发、ViewModel全部数据ready和首个可读帧时间；截图扫描开销不能当渲染耗时。测试驱动从支持的Compose测试线程调用UI查询，保留超时及WrongThread分母、栈和方法身份。前V80根因仍UNKNOWN。
- 私有识别诊断须用户明确启用，保持3尝试/8MiB限制；公式新增formula-raster PNG与formula-tensor SHA。检查新实际PNG通过Float32规范重建的SHA是否相同，再定位裁剪、灰度、缩放和候选差异。
- 当前文字29有真值中27分组匹配；重新跑REC-02两种文字pass才可报告模型变化，不从预期字符生成候选。“二/三”独立短横仍由校对处理。
- 摘录/注释按钮是在分别命名的范围和CardPresentation单步历史上重放。复测小窗口换行、点击两次、关闭重开、活动重建、冲突、阅读模式、撤销后导出/备份/再导入。历史依赖SavedState与不可变来源，不保证force-stop后未保存编辑恢复。
- 笔压/倾斜/光学延迟与真实手写另采；本轮没有以模拟真值调模型或笔感参数。

## 可重复云端探针

公式探针安装Pillow、numpy及onnxruntime==1.30.0后：

```sh
python formula_replay.py --capture CAPTURE_ROOT --assets REPO/android/app/src/main/assets/formula --output formula-replay.json
```

分行探针先运行core-domain:classes，准备Kotlin2.3.10编译器/标准库与依赖JAR目录：

```sh
python run_lines.py --repo REPO --capture CAPTURE_ROOT --output PRIVATE_OUTPUT --kotlin-tools KOTLIN_JARS --java JAVA_EXECUTABLE
```

默认基线V82 a778add；运行仓库当前HandwritingLines与不可变V82源码各一次。捕获目录中模型分析的sourceMapping关联实际笔迹和人工行真值；原始source.inkweft只作为数据解码，采集驱动不执行。PRIVATE_OUTPUT/line-input.tsv含私有文件路径和来源ID，不能直接公开。
