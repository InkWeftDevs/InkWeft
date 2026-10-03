# 学习页签恢复：本轮设备结果（2026-10-03）

本报告记录精确恢复基线及已经发生的验证，不是最终修复交付结论。基线 commit `810ba729a4caa3c22a857454b4838776456ccf61`、tree `65d3bd082cfeb28b958f01333e3524b8fa0e730f`；后续原位标题测试诊断及外层恢复路由修复正在进行，最终 commit/tree、三包身份与修复回归结果由主任务补入。未运行项目保持 NOT_RUN，不继承旧 V68 结果。

主机与设备日志时钟不一致，本轮以同次方法事件、内部进程标识和相对耗时关联；新 runtime 同时记录 host UTC/device UTC，未调整时钟。公开报告不列具体进程标识。

对应操作步骤见 [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md) 的 SR-01～10、SR-HW-01。下表中的模拟器结果与真机结果分别计数；原始日志、设备唯一标识、进程标识及私人内容不复制到本报告。

|本轮范围|已核结果|限制/下一步|
|---|---|---|
|精确基线三包构建与身份|PASS|构建时源码 tree 为 65d3，不能代表后续修复工作树|
|真机独立 Room 12 唯一方法|12 PASS / 0 FAIL / 0 NOT_RUN|本轮全新执行；不是继承旧 V68 12 PASS|
|真机 app 30 唯一方法|0 PASS / 0 FAIL / 30 NOT_RUN|本候选主包/app runner 尚未在真机安装，等待用户就绪|
|隔离 API35 模拟器 app 30 唯一方法|2 PASS / 1 FAIL / 27 NOT_RUN|两项修订缺失故障通过；原位标题祖先 semantics 断言失败待诊断|
|隔离模拟器完整 OS 进程终止恢复|2 次 FAIL|均自动回资料库；手动重开可找到原位草稿，自动路由仍失败|
|ADB 归档恢复保护验证|限定范围 PASS|41 个内部文件哈希一致、普通启动通过；不是应用内备份恢复验收|
|真实完整学习旅程、真实 IME/窄窗分屏、真实 PDF/应用内导出及完整备份恢复|NOT_RUN|按 SR 清单分别执行|
|真人 15 分钟、压感/倾斜/掌触与温升|NOT_RUN|必须由用户实际持笔，ADB 不能代替|

## 精确构建身份

源码按公开基点 `bb0f2b15f2c02e956c0ea4256e9f64378f41de0e` 顺序应用两份补丁；第一片本地 commit `d899727ad02b085c19e19448eb3511621660b7ec`、tree `58cd0921039e43a33efe81e20471d90545be185a`，第二片得到上述 65d3 基线。恢复回执记录附件哈希与 30 方法源码哈希全部匹配。

构建采用 Temurin 17.0.20.1+1、SDK/build-tools 36/36.0.0、项目 Gradle wrapper 9.4.1，单 worker 串行构建主 APK、app runner 与独立 Room runner。764 个跟踪输入文件构建前后哈希一致，构建时工作树干净；耗时 279.746 秒。`INKWEFT_HEAD_SHA`/`GITHUB_SHA` 均为实际本地提交；主包及 app runner DEX 含该提交。Room 没有内嵌源码 BuildConfig 字段，以同次构建、输入摘要和产物绑定来源。

|产物|包名 / targetPackage|版本|最终 SHA-256|
|---|---|---|---|
|主 APK|`org.inkweft.app.a0.workspace` / 不适用|68 / `0.0.68-cloud-candidate`|`0d679a4d8cff63a3af842a0edecfe2a5b1938da5d78c6e38e3ca77976bcc0157`|
|app runner|`org.inkweft.app.a0.workspace.test` / `org.inkweft.app.a0.workspace`|manifest 无独立版本字段|`b5f14c37486ee5b853b83286fe72f71a63893478f52a91bfdf787827d5278855`|
|Room runner|`org.inkweft.data.test` / `org.inkweft.data.test`|manifest 无独立版本字段|`aa905b4de50848f12f7ee0054fc9f861b23eca2f86b972201671f3efde6bbff5`|

三包证书 SHA-256 均为原证书 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`；两个 runner 均为 `androidx.test.runner.AndroidJUnitRunner`。三包签名、ZIP CRC、16KB zipalign、包名和 targetPackage 已核对。未更改 applicationId、未新建替代密钥；私钥/口令不进入报告。版本仍为 68，不能靠版本号辨认新旧源码。

构建首次失败来自归档 PowerShell 脚本中带点参数未加引号；核查脚本曾预期错误的 runner 文件后缀。两处均仅修归档脚本，失败证据保留；不列为产品断言失败。

## 旧包真机执行阻断

这组控制均属于已装旧 V68 源码 `15f167ed1d1270617e51e0aacd21b5b7caa9bb18`，与本候选方法计数完全分开。

|诊断|实际结果|能得出的结论|
|---|---|---|
|01，同 runner 纯内存 `RenderResourcesTest#immutableEqualityMemoDoesNotHideNewErasures`|PASS，0.859秒，开始/结束事件齐全|instrumentation 的这条纯内存路径可完成|
|02，主线程原生 View `BrushB1InputTest#singlePaperPencilDrawsWhileDownAndPersistsPageCoordinates`|PASS，0.609秒，开始/结束事件齐全|该原生 View 控制可完成；不代表真人笔|
|03，普通 Activity 启动、独立 UiAutomation 读取|PASS，0.718/2.688秒|普通启动与独立读取可完成；不代表 Compose Rule 初始化|
|04，Compose Rule `EditorVisualContractTest#narrowMapActionsAvoidTheNeighbouringBranch`|BLOCKED，6.125秒；仅开始事件，观察到内核冻结|未取得产品断言结果|
|07，同方法冻结前 JDWP 栈|BLOCKED，6.547秒|实际等待位于 `Instrumentation.startActivitySync`，并非此前候选 `waitForIdleSync`|

第 07 次栈显示 `Object.wait → Instrumentation.startActivitySync → InstrumentationActivityInvoker.startActivity → ActivityScenario.launchInternal → ActivityScenarioRule.before`；主线程在消息循环等待。未取得目标 Activity 创建/恢复或方法正文进入证据，不能归咎业务 Room 或控件断言。`SYSTEM_ALERT_WINDOW`（SAW）appop 的记录提供后台启动限制线索，尚无因果证明。

仅针对旧包的一次临时 SAW 许可对照仍在等待用户授权，执行后需恢复原值。已批准的窗口/字体/动画/旋转临时调整不等于批准此项，也不包括冻结策略、后台耗电设置或重启。不循环重试、重启或强制解冻。

## 真机 Room：本轮重新执行

目标为用户指定的 Android 16 / API36 真机，仅使用公开别名，不记录序列号。重装 Room runner 时设备返回 `INSTALL_FAILED_ABORTED: User rejected permissions`；没有卸载、清数据或换包绕过。随后核实当前已装 APK 与本次精确源码构建产物字节相同，SHA-256 均为 `aa905b4d…e6bbff5`，以该明确身份执行全新 12 方法，时间为 2026-10-03 13:41:13～13:41:27（+08:00）。

运行回执 `oldPassReused=false`。每项均有正确 class/test 开始及完成事件、成功码和日志摘要；下面列的是本轮执行结果。同字节产物不是继承历史结果的理由。

|完整类：`org.inkweft.data.BranchReviewRoundRepositoryTest`；方法|本轮真机|耗时（秒）|
|---|---|---|
|`exactReceiptsCountIndependentSameCardQuestionsOnceWithZeroReadSideWrites`|PASS|0.953|
|`unknownRollbackCannotCountOrSkipUntilTheSameOperationActuallyCommits`|PASS|0.953|
|`missingWrongOperationBookDigestResultOrRequestedStateNeverConfirms`|PASS|1.313|
|`causalRevisionMustExistAndMatchOnlyTheRequestedStateChange`|PASS|1.5|
|`originalQuestionAndCardHistoryAreRequiredRatherThanCurrentRows`|PASS|1.344|
|`laterQuestionCardEditsAndRecyclingNeverRebaseAnAlreadyCommittedResult`|PASS|0.937|
|`skippedUnderstoodQuestionRetainsAllFourRefsAndDoesNotUseGlobalStateFiltering`|PASS|0.922|
|`aMissingSelectedHistoryRejectsTheEntireRetryWithoutDroppingThatQuestion`|PASS|0.953|
|`completedReviewLedgerStillRequiresItsOriginalReceiptForRetry`|PASS|0.969|
|`wrongFrozenNotebookCannotBorrowAValidReceiptOrAnswer`|PASS|0.969|
|`rejectedSkipRetriesOriginalRefsButNeverPretendsToHaveAReceipt`|PASS|0.953|
|`noCandidateAndRecycledBookCannotStartANewRoundButOldReceiptStillCounts`|PASS|0.953|

## app：30 个唯一方法按环境分别记录

当前真实平板尚未安装本候选主包及 app runner，30 项全为 NOT_RUN，等待用户就绪。独立 API35 AVD 已还原到本任务空快照，再安装本候选三包，三包 SHA 与构建回执匹配；用于故障测试的资料均为其后新建的合成数据。未把原资料副本留作故障夹具。

|准确方法（类均以 `org.inkweft.app.` 开头）|真机|隔离 API35 模拟器|
|---|---|---|
|`LocalMotionUiTest#cardSectionsEnterOnceAndDisposeCollapsedSourceAtTheNextRealFrame`|NOT_RUN|NOT_RUN|
|`LocalMotionUiTest#answerEnterCannotRetainPreviousQuestionCluesAcrossSkipHideAndExit`|NOT_RUN|NOT_RUN|
|`LocalMotionUiTest#latestPassiveSourceHighlightKeepsExactPaperAndNativeCaptureAtSystemZeroScale`|NOT_RUN|NOT_RUN|
|`CardSourceNavigationUiTest#collapsedSourceFooterReturnsToExactPaperAndKeepsReadOnlyMapContext`|NOT_RUN|NOT_RUN|
|`SourceFocusVisibilityUiTest#movedResizedFloatingCollectOrganizeAndFocusExposeExactSourceThenRestoreOriginalWindow`|NOT_RUN|NOT_RUN|
|`SourceFocusVisibilityUiTest#narrow375LargeFontRetainsSourceRegionThroughRecreationAndRestoresItsFocusFrame`|NOT_RUN|NOT_RUN|
|`SourceFocusVisibilityUiTest#splitPaneRefitsRetainedSourceAcrossBothDirectionsWithoutChangingReferenceOrGraph`|NOT_RUN|NOT_RUN|
|`RecallMaskUiTest#narrowRotationSourceReturnAndNextQuestionResetPermissions`|NOT_RUN|NOT_RUN|
|`RecallMaskUiTest#explicitKnownAndUnknownRatingRetainOriginalCasAndReceiptIdentity`|NOT_RUN|NOT_RUN|
|`RecallMaskUiTest#mapHintGatesNativeTopologyTitlesBodiesAndFoldedDescendants`|NOT_RUN|NOT_RUN|
|`ReadLockUiTest#summaryOutlineAndMapRejectActualAuthorIntents`|NOT_RUN|NOT_RUN|
|`BranchReviewRoundUiTest#mixedExactResultsRetryOnlyTheConfirmedQuestionAndNeverMixANewSibling`|NOT_RUN|NOT_RUN|
|`BranchReviewRoundUiTest#pureSkipWithIdenticalRefsStartsAtFirstQuestionWithNoOldPermissionAndRestoresItsOwnRound`|NOT_RUN|NOT_RUN|
|`BranchReviewRoundUiTest#unknownOriginalOperationSurvivesActivityAndWriterRecreationBeforeCountingExactlyOnce`|NOT_RUN|NOT_RUN|
|`BranchReviewRoundUiTest#committedButUnverifiedResultStaysLockedAcrossRecreationAndRetriesReadOnlyProof`|NOT_RUN|PASS|
|`BranchReviewRoundUiTest#completedRoundRecoveryConsumesOnlyItsOwnOperationBeforeRetryAndClose`|NOT_RUN|NOT_RUN|
|`QuestionMaintenanceUiTest#longQuestionSearchKeepsDraftsAndIdentityAcrossRecreationAndEditing`|NOT_RUN|NOT_RUN|
|`QuestionMaintenanceUiTest#actualReadOnlyEntryShowsSavedQuestionsButCannotEditRemoveOrSaveAuthors`|NOT_RUN|NOT_RUN|
|`BranchReviewRoundUiTest#missingFrozenResultDetailsKeepTheLedgerAndRetryWithoutCurrentQuestionSubstitution`|NOT_RUN|PASS|
|`OverviewTabsUiTest#outlineFoldsFollowStableRowsThroughSearchRestoreAndStructureChanges`|NOT_RUN|NOT_RUN|
|`PageInsertionUiTest#newPagePaperRequiresSaveRestoresAndStaysNotebookLocal`|NOT_RUN|NOT_RUN|
|`PageInsertionUiTest#restoredPendingInsertionKeepsCapturedPaperAndReceiptAfterDefaultChanges`|NOT_RUN|NOT_RUN|
|`PageInsertionUiTest#failedDefaultSaveRollsBackAndKeepsDialogOpen`|NOT_RUN|NOT_RUN|
|`PageInsertionUiTest#beginningBatchKeepsOriginalInkAndPageIdentity`|NOT_RUN|NOT_RUN|
|`NotebookNavigationUiTest#realEditorAppendPersistsOneBlankPageAndRejectsStaleTail`|NOT_RUN|NOT_RUN|
|`TemplateCreationUiTest#installedTemplatesAreUsableInExistingCreationFlows`|NOT_RUN|NOT_RUN|
|`MapInteractionUiTest#outlineTitlesKeepSharedContentAndContinueAtTheSameParent`|NOT_RUN|FAIL|
|`MapInteractionUiTest#outlineEnterUnknownReceiptRetriesOneCommandBeforeOpeningOneSiblingDraft`|NOT_RUN|NOT_RUN|
|`SelectionStudyUiTest#outlineFoldFocusAndQuickAddShareOneGraph`|NOT_RUN|NOT_RUN|
|`ReadLockUiTest#studyTabSavedStateRestorationSurvivesCompactInitialization`|NOT_RUN|NOT_RUN|

两项故障的运行证据分别来自 `current-emulator/isolated-faults/results.json` 与 `current-emulator/isolated-fault-second/results.json`。第一项测试日志已完整结束后，主机 cgroup 监控读取超时退出；依据准确开始/结束、OK 摘要及最终成功码恢复其 PASS，没有重跑，也没有伪称取得 freeze 样本。第二项正常完成；两个唯一方法分别计一次。早先队列中第二方法的 NOT_RUN 已由随后单跑 PASS 补齐，不能用旧队列状态覆盖新结果。

原位标题方法实际在 `MapInteractionUiTest.kt:89` 的 `hasAnyAncestor(hasTestTag(...))` 断言失败，结束代码 -2，属于真实断言失败，不能混成旧真机冻结。当前需要检查合并/未合并 semantics 树及实际行身份，尚未证明是产品显示错误或测试查询错误；诊断/修正后的源码和运行结果另记，基线 FAIL 保留。

## SR-08：真实 OS 进程终止的基线失败

两次均在隔离 API35 AVD 上完成实际进程终止与新进程启动验证，使用后台 Home → `am kill`，不是 `force-stop`、Activity 重建或仅划掉任务。为保护设备与资料，公开报告只记“旧进程消失/新进程已创建”及证据存在，不复制具体进程标识。

|切点|步骤与预期|实际结果|边界|
|---|---|---|---|
|SR-08-A，学习区节点大纲页签|选中合成本，打开学习区和节点大纲；后台终止进程并重启，预期恢复原本/学习区/大纲页签|FAIL：自动回到资料库|未覆盖草稿及已确认内容恢复|
|SR-08-B，原位未提交标题|在合成本节点原位标题输入 ASCII 草稿，不点 Save/Enter；后台终止进程并重启，预期自动回到原编辑上下文|FAIL：没有自动返回草稿|ADB ASCII 仅检查此草稿恢复，不是中文 IME 组词|
|SR-08-B 的诊断性手动重开|失败后手动重开同一本及导图/学习区，检查可编辑字段|找到原合成标题草稿|只说明该草稿仍在，不能抵消自动路由 FAIL，也不等于所有草稿/所有死亡方式均可恢复|

据此优先修复外层笔记/学习区恢复路由。修复进行中，未预填 PASS；其他页签 0/1/2、父层草稿、未知命令、已确认写入、结算结果/再练、force-stop 冷启动仍需按清单逐切点验证。真实平板的完整 OS 死亡项仍 NOT_RUN。

## 资料保护与恢复边界

在独立 API35 AVD 上完成原资料的 ADB 归档恢复保护检查：41 个内部文件逐文件哈希一致，普通启动 PASS，外部归档已复制。这是受控归档能还原的有限证据，不是应用内 LibraryArchive 备份导入/完整恢复验收，也不代表已逐页验收原笔迹、PDF、卡题/来源闭包。

保护检查后已还原本任务空快照，再安装当前三包开展合成故障验收。报告不包含原笔记名称、内容、文件清单、数据库行、截图或原始备份；不以原资料库开展删除修订等破坏性实验。应用内真实 PDF/导出往返及完整备份恢复仍为 NOT_RUN。

## 仍需执行的手工与真人项目

|稳定编号|范围|当前真实平板结果|
|---|---|---|
|SR-01|阅读→书写→摘录/导图→回忆→结果筛选→针对性再练完整旅程|NOT_RUN|
|SR-02|长题搜索、页面大纲折叠与清空搜索状态恢复|NOT_RUN|
|SR-03～04|真实中文组词、Enter 连按、取消、旋转、保存中离开|NOT_RUN；合成自动化断言/诊断不替代|
|SR-05～06|新增页纸面取消/确认/沿用/单次覆盖/跨本隔离；插页与连续追加|NOT_RUN|
|SR-07|375dp、大字体、真实软键盘、应用内及系统分屏|NOT_RUN|
|SR-08|完整 OS 进程终止后页签、草稿及已确认写入恢复|NOT_RUN；模拟器基线失败另列|
|SR-09～10|隔离目标上的真实 PDF、导出及应用内完整备份恢复|NOT_RUN|
|SR-HW-01|真人 15 分钟：中英/公式、轻重压、倾斜、掌触、缩放；0/5/10/15 分钟卡顿/温升|NOT_RUN；必须真人实际持笔|

真人步骤与停止条件沿用清单 SR-HW-01。压感、倾斜、掌拒、热态笔感及温升不能用 ADB、回放、模拟器或 CI 填写通过。温度无法测量时写“未测量”，不填推测值。

## 追加结果模板

```text
case_or_exact_class_method / attempt:
source_commit / source_tree / app_and_runner_SHA256 / certificate:
environment: PHYSICAL | ISOLATED_EMULATOR
device_alias / OS / pen / IME / input_mode:
time_with_timezone / setup / isolated_fixture / backup_reference:
settings_before / tested / restored_and_checked:
actual_steps / expected / actual:
status: NOT_RUN
failure_class: PRODUCT_ASSERTION | ENVIRONMENT_BLOCKED | NONE
capability_gap / remaining_scope:
original_operationId / receipt / relevant_ids_and_revisions:
start_end_events / final_test_code / summary / timeout_or_monitor_warning:
private_evidence_reference / sanitized_evidence_reference / hashes:
```

|真人时点|卡顿/等待及异常时间|轻重压/倾斜|掌触/缩放/丢笔|温度数值和方式或主观温感|电量/热状态|结果/证据|
|---|---|---|---|---|---|---|
|0分钟|待填|待填|待填|未测量|待填|NOT_RUN|
|5分钟|待填|待填|待填|未测量|待填|NOT_RUN|
|10分钟|待填|待填|待填|未测量|待填|NOT_RUN|
|15分钟|待填|待填|待填|未测量|待填|NOT_RUN|

每个设备、版本及 attempt 保留原记录；修复后的 PASS 不覆盖基线 FAIL。发生问题先在应用“诊断与导出”标记时间、复现、导出 ZIP，仅补日志不能表达的操作/视觉/笔感证据；基础诊断不是完整系统日志或端到端笔尖延迟测量。

## 本机证据索引与整理

本轮归档根为 `E:/Inkweft/archives/2026-10-03/StudyRestoreHandoff`。以下仅是证据定位，原始 private 日志/截图及原资料不随本文公开：

|证据代号|本机回执位置|用途|
|---|---|---|
|S01|`source-reconstruction.json`|补丁顺序、实际 commit/tree 与哈希核对|
|A01|`acceptance-810ba729-results.json`|精确源码 30 方法按真机/模拟器分别记录；Room 12 另列|
|B01|`build-current-source/build-receipt.json`|三包来源、签名与摘要|
|D07|`diagnostics-review/diagnostic-07-sanitized-summary.json`|旧包 `startActivitySync` 等待定位|
|R01|`current-physical-room/installation.json`、`runtime/results.json`|同字节已装 runner 核对、本轮 12 方法结果|
|E01|`current-emulator/installation.json`|空快照恢复与当前三包身份|
|E02/E03|`current-emulator/isolated-faults/results.json`、`isolated-fault-second/results.json`|两项隔离故障结果与监控限制|
|E04|`current-emulator/outline-title-baseline/results.json`|基线原位标题断言失败|
|E05/E06|`current-emulator/process-death/result.json`、`inline-draft/os-death-result.json`、`inline-draft/manual-reopen-result.json`|两次 OS 终止及诊断性手动恢复|
|P01|`isolated-restore-check/receipt.json`|限定 ADB 归档恢复验证|

此次文档合并仅改新 clone 的清单和本报告，旧清单原记录完整保留；未操作受保护 recon/旧 V68、ADB、构建或提交。普通 Markdown 本轮仅做结构/diff 检查，不再启动浏览器生成配置。

先前草稿渲染产生的 8 个临时浏览器配置及一份 npm 检查日志，删除操作被自动审批拒绝，仅返回 `blocked by policy`；未绕过，仍按 `delivery-draft/verification/render-results.json` 的 `cleanupPending` 留存。源文件、最终视觉证据、安装包、原始验证材料及依赖缓存均保留。
