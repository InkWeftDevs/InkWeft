# V85 本地验收 · 2026-10-10 / Asia/Shanghai

在保留本地未推送修改的测试工作树检出 PR18 最新头；本包不替代实机回执。Room16不迁移表，新布局／归纳记录需要V85读取，先保留完整备份，不建议降级打开新内容。

日常先用 [增量验收入口](FAST-TESTS.md)，将本轮新增与关联回归限制为13个设备方法；V84与V85无需各跑一次完整套件。历版待测在最终候选合并／发布前集中补齐，未执行项目仍为NOT_RUN。

只检查V85新增与修改的11个设备方法时，在 `android` 目录用完整原生依赖执行；任务自动构建所需APK，无需先单独构建、清缓存或重跑已完成的云端核心检查：

```powershell
.\gradlew.bat :data-local:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.data.MapCompletionRepositoryTest"
.\gradlew.bat :app:connectedDebugAndroidTest -PinkweftInsertionPreview=true "-Pandroid.testInstrumentationRunnerArguments.class=org.inkweft.app.MapCompletionUiTest,org.inkweft.app.NoteFirstUiTest#personalTemplateIsAnonymousAndClosingWindowKeepsDraft"
```

新增5个真实Room事务方法及5个Android主线程／SavedState／图形／模板界面方法，云端仅编译，全部NOT_RUN；另重编译旧自定义模板复用方法，修复固定索引造成的错选，需一起复测。手工继续检查 `device-pending.json` 中的旋转、选择器取消、PDF实际内容、字号与读／写门禁。V81–V83既有待测继续；原V80回执不认证V85。

快速操作：新建导图→搜索或分类→选模板→结构预览→创建；导图管理→整理→自动布局预览→选右向／双侧／左向／组织图→取消或应用→撤销／重做。局部布局沿用当前图布局。

括号归纳：大纲多选或导图框选2–128个连续同级主题→括号归纳→填写标题→保存。根主题、重叠组、非连续或跨父成员拒绝。导图管理→整理→括号归纳管理可编辑、解除和单步撤销／重做。拆开成员、删除成员或删除带活动归纳的图会被拒绝，先解除归纳。极大分组排版若只能退化成会包住其他分支的紧凑网格，则拒绝该预览，保留原图。

底部百分比按钮恢复100%，同时有缩小／放大、全图、可读大小、适配所选。导图管理→输出→PNG/PDF→当前展开内容或全图。输出包括可见主题文字摘要、卡片配色和归纳；正文显示截断规则沿用导图，原迹缩略图、手写批注、知识关系和跨图入口不进入独立输出。PDF是可放大单页，大图会缩小；完整编辑内容使用资料库备份。

采集请保留源码SHA、APK SHA、设备/字体/屏幕条件、方法名、成功与失败尝试；不得把编译PASS写成设备PASS。
