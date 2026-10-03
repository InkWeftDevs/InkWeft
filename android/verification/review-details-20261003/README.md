# 本轮题目与结果明细 · 云端功能切片

基点：`19394e2cfb9f665845e666dd22d85ed0b0d82e83`（PR #7）。对应既有V65后续规划中的“明确结果、跳过记录和再次练习范围”，补足结果页只有数量而看不到具体题目的缺口。

## 已实现

- 结束页打开独立结果明细，列出本轮固定版本的问题原文及四种手工结果：本次已理解、仍需复习、普通跳过、未提交后跳过。
- “全部／仍需复习／跳过”复用当前轮次领域筛选；仅改变可见列表，不改作者状态、不启动新轮次，不使用最新问题来替代原题。
- 明细只显示问题和本轮结果，不显示卡片标题、答案、原页或图中线索。全部固定历史完整读出才显示题目；缺失或读取失败保留结果、明确提示，可关闭或重试。
- 关闭回到原结果，重新打开保留筛选；Activity重建保留已打开明细和筛选，通过原引用重新读取题目，不将正文塞进SavedState。
- 全屏独立阅读区使用LazyColumn承载长题；筛选可换行，返回结果固定在顶部，触控区至少48dp。大字体／窄屏实际效果仍待设备运行确认。

只增加独立明细UI与原结果页入口；数据库、原回执、固定版本合同、再练逻辑、版本号、签名和依赖均未改变。

## 定向验证

- 复用 `BranchReviewRoundUiTest#mixedExactResultsRetryOnlyTheConfirmedQuestionAndNeverMixANewSibling`，补充四种结果、长题滚动、原题与外部改题区分、筛选／重建／关闭恢复和零作者写入检查。
- 新增 `missingFrozenResultDetailsKeepTheLedgerAndRetryWithoutCurrentQuestionSubstitution`，缺失历史时不显示部分题目，不退回当前题；恢复历史后原明细重试成功，空筛选和关闭仍保留原轮次。
- 2026-10-03 同次串行执行 `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:lintDebug` 全部PASS，退出0，耗时5分37秒（11任务执行，66任务复用）。JDK17／项目固定Gradle9.4.1／SDK36，单worker；未构建APK或执行设备测试。
- lint XML实际为0 Error、0 Fatal、133 Warning，没有指向新明细UI或本次轮次测试的lint项。独立静态复核发现的关闭重开筛选丢失及短窗滚动风险已在该次编译前修正。
- 上述两个方法以及375dp／1.65字体、长题完整阅读、快速关闭／重开／系统返回等真实交互均 **NOT_RUN**。静态和编译不等于Android运行通过；不重复运行未改变的核心／Room全套，也不为纯格式变化重复编译／lint。

PR #6 的12项Room PASS仍只属于固定V68源码 `15f167ed1d1270617e51e0aacd21b5b7caa9bb18`；其UI冻结来源尚未确定。本批不转记旧结果为新版本验收，不操作用户本地设备。
