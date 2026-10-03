# 学习页签恢复：本轮交付与实际验证

**隔离 API35 模拟器原30方法跨源码最新：30 PASS / 0 FAIL / 0 NOT_RUN。真机原runner无辅助启动仍为ENVIRONMENT_BLOCKED，原30无辅助方法记NOT_RUN；真机仅首次前台启动辅助模式：28 PASS / 0 FAIL / 2 NOT_RUN。** 三种环境独立列出，辅助下业务PASS不代表原runner启动机制已修复，也不把定向回归累计写成同一候选全量运行。未实际执行的项目保持NOT_RUN。

本次APK构建源码：`41e23e96e36e0bb58c394e941718e049f525ada5` / tree `3bb36b951bd87773a784bb608e2ef4eb9231083d`。以下为当前候选已经完成核验的构建身份。回执快照：2026-10-03T09:20:06.960372+00:00。待完成/失败范围：隔离模拟器：原30方法均已有明确来源的实际PASS；仍不代表同一候选全量运行。 真机首次前台辅助模式：`BranchReviewRoundUiTest#committedButUnverifiedResultStaysLockedAcrossRecreationAndRetriesReadOnlyProof`（NOT_RUN）；`BranchReviewRoundUiTest#missingFrozenResultDetailsKeepTheLedgerAndRetryWithoutCurrentQuestionSubstitution`（NOT_RUN）

## 已核构建与安装边界

已核构建源码为 `41e23e96e36e0bb58c394e941718e049f525ada5` / tree `3bb36b951bd87773a784bb608e2ef4eb9231083d`，版本仍为 `68 / 0.0.68-cloud-candidate`；构建回执 `build-repair-08/build-receipt.json`。版本号不足以区分这些候选。三包来自同一固定输入，原证书 SHA-256 为 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`；两个 runner 均为 `androidx.test.runner.AndroidJUnitRunner`。

|用途|完整文件名|包名 / targetPackage|完整 SHA-256|
|---|---|---|---|
|app|InkWeft-study-tab-restore-41e23e96.apk|`org.inkweft.app.a0.workspace` / `不适用`|`57604ea3bbeb61f38ca08d308544fe3a032e9529cf9b0259f61311909d56979e`|
|app-test|InkWeft-study-tab-restore-41e23e96-androidTest.apk|`org.inkweft.app.a0.workspace.test` / `org.inkweft.app.a0.workspace`|`a888f69420959624b693f6bb7d16db49e8f15edd20f1e2056c10bd4344ed30d1`|
|room-test|InkWeft-study-tab-restore-41e23e96-room-androidTest.apk|`org.inkweft.data.test` / `org.inkweft.data.test`|`aa905b4de50848f12f7ee0054fc9f861b23eca2f86b972201671f3efde6bbff5`|

签名、ZIP/manifest与输入身份按各构建回执核对。签名包完整保留；没有更换包名、卸载清库或换钥绕过。用户已授权安装；此前UserRejected/同版本厂商弹窗等待保留为历史，后来选择重新安装主包，并确认runner风险继续安装，三包实际安装状态与SHA分别核对匹配。真机当前已装源码是 `41e23e96e36e0bb58c394e941718e049f525ada5` / tree `3bb36b951bd87773a784bb608e2ef4eb9231083d`；Room保持同字节已装包，主包/app runner原位更新。回执为 `repair8-physical/installation.json`。

|已装包名|安装后完整SHA-256|核对结果|
|---|---|---|
|`org.inkweft.app.a0.workspace`|`57604ea3bbeb61f38ca08d308544fe3a032e9529cf9b0259f61311909d56979e`|updated in place|
|`org.inkweft.app.a0.workspace.test`|`a888f69420959624b693f6bb7d16db49e8f15edd20f1e2056c10bd4344ed30d1`|updated in place|
|`org.inkweft.data.test`|`aa905b4de50848f12f7ee0054fc9f861b23eca2f86b972201671f3efde6bbff5`|same byte-identical Room package retained|

## 原30方法逐项实际结果

下表每行绑定真正运行的 commit/tree。候选自身是否运行、所有旧失败与 START-only 未完成记录保留在 `latest-30-executions.json`，逐源码矩阵保留在 `method-results-summary.json`；新源码未开始的 NOT_RUN 不覆盖旧源码实测 PASS。

|准确方法|最新实际结果|实际 sourceCommit / tree / 环境|真机无辅助|回执|
|---|---|---|---|---|
|`org.inkweft.app.LocalMotionUiTest#cardSectionsEnterOnceAndDisposeCollapsedSourceAtTheNextRealFrame`|PASS|`3fe6b7b1d9c3f62bf14e88b091abd08d47450e7d` / `f1dbedb95ac340602af7216613f8c007d3adff2c` / API35模拟器|NOT_RUN|`repair5-emulator/targeted5/results.json`|
|`org.inkweft.app.LocalMotionUiTest#answerEnterCannotRetainPreviousQuestionCluesAcrossSkipHideAndExit`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / API35模拟器|NOT_RUN|`repair3-emulator/targeted6/results.json`|
|`org.inkweft.app.LocalMotionUiTest#latestPassiveSourceHighlightKeepsExactPaperAndNativeCaptureAtSystemZeroScale`|PASS|`afd74669351d50ecf99a1f6a8b6bc4fc09a0e67c` / `edbe6490fae5788e7ea37674adac6be056097a66` / API35模拟器|NOT_RUN|`repair4-emulator/targeted4/results.json`|
|`org.inkweft.app.CardSourceNavigationUiTest#collapsedSourceFooterReturnsToExactPaperAndKeepsReadOnlyMapContext`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / API35模拟器|NOT_RUN|`repair3-emulator/targeted4/results.json`|
|`org.inkweft.app.SourceFocusVisibilityUiTest#movedResizedFloatingCollectOrganizeAndFocusExposeExactSourceThenRestoreOriginalWindow`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / API35模拟器|NOT_RUN|`repair3-emulator/targeted4/results.json`|
|`org.inkweft.app.SourceFocusVisibilityUiTest#narrow375LargeFontRetainsSourceRegionThroughRecreationAndRestoresItsFocusFrame`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / API35模拟器|NOT_RUN|`repair3-emulator/targeted4/results.json`|
|`org.inkweft.app.SourceFocusVisibilityUiTest#splitPaneRefitsRetainedSourceAcrossBothDirectionsWithoutChangingReferenceOrGraph`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / API35模拟器|NOT_RUN|`repair3-emulator/targeted4/results.json`|
|`org.inkweft.app.RecallMaskUiTest#narrowRotationSourceReturnAndNextQuestionResetPermissions`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.RecallMaskUiTest#explicitKnownAndUnknownRatingRetainOriginalCasAndReceiptIdentity`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.RecallMaskUiTest#mapHintGatesNativeTopologyTitlesBodiesAndFoldedDescendants`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.ReadLockUiTest#summaryOutlineAndMapRejectActualAuthorIntents`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#mixedExactResultsRetryOnlyTheConfirmedQuestionAndNeverMixANewSibling`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#pureSkipWithIdenticalRefsStartsAtFirstQuestionWithNoOldPermissionAndRestoresItsOwnRound`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#unknownOriginalOperationSurvivesActivityAndWriterRecreationBeforeCountingExactlyOnce`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#committedButUnverifiedResultStaysLockedAcrossRecreationAndRetriesReadOnlyProof`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#completedRoundRecoveryConsumesOnlyItsOwnOperationBeforeRetryAndClose`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.QuestionMaintenanceUiTest#longQuestionSearchKeepsDraftsAndIdentityAcrossRecreationAndEditing`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.QuestionMaintenanceUiTest#actualReadOnlyEntryShowsSavedQuestionsButCannotEditRemoveOrSaveAuthors`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#missingFrozenResultDetailsKeepTheLedgerAndRetryWithoutCurrentQuestionSubstitution`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.OverviewTabsUiTest#outlineFoldsFollowStableRowsThroughSearchRestoreAndStructureChanges`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.PageInsertionUiTest#newPagePaperRequiresSaveRestoresAndStaysNotebookLocal`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.PageInsertionUiTest#restoredPendingInsertionKeepsCapturedPaperAndReceiptAfterDefaultChanges`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.PageInsertionUiTest#failedDefaultSaveRollsBackAndKeepsDialogOpen`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.PageInsertionUiTest#beginningBatchKeepsOriginalInkAndPageIdentity`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.NotebookNavigationUiTest#realEditorAppendPersistsOneBlankPageAndRejectsStaleTail`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.TemplateCreationUiTest#installedTemplatesAreUsableInExistingCreationFlows`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/independent22/results.json`|
|`org.inkweft.app.MapInteractionUiTest#outlineTitlesKeepSharedContentAndContinueAtTheSameParent`|PASS|`41e23e96e36e0bb58c394e941718e049f525ada5` / `3bb36b951bd87773a784bb608e2ef4eb9231083d` / API35模拟器|NOT_RUN|`repair8-emulator/targeted3/results.json`|
|`org.inkweft.app.MapInteractionUiTest#outlineEnterUnknownReceiptRetriesOneCommandBeforeOpeningOneSiblingDraft`|PASS|`41e23e96e36e0bb58c394e941718e049f525ada5` / `3bb36b951bd87773a784bb608e2ef4eb9231083d` / API35模拟器|NOT_RUN|`repair8-emulator/targeted3/results.json`|
|`org.inkweft.app.SelectionStudyUiTest#outlineFoldFocusAndQuickAddShareOneGraph`|PASS|`3fe6b7b1d9c3f62bf14e88b091abd08d47450e7d` / `f1dbedb95ac340602af7216613f8c007d3adff2c` / API35模拟器|NOT_RUN|`repair5-emulator/targeted3/results.json`|
|`org.inkweft.app.ReadLockUiTest#studyTabSavedStateRestorationSurvivesCompactInitialization`|PASS|`bdf20da9e0a6fc135fc5072ba38592164b52719b` / `6e8046f288a9b28625cb8361dce8dedf95245dcc` / API35模拟器|NOT_RUN|`repair2-emulator/last2/results.json`|

### 真机首次前台启动辅助下的正式业务断言

执行模式严格记为 `PHYSICAL_WITH_ONE_INITIAL_FOREGROUND_LAUNCH`，原始 `initialUnassistedRunnerStatus=ENVIRONMENT_BLOCKED` 保留。每方法只在初次ActivityScenarioRule启动时按已核对规则补一次真实MainActivity的MAIN+LAUNCHER、flags `0x10008000`；正文、recreate和恢复操作不补第二次启动。每条辅助的组件、相对时间、flags和限制原文保留在JSON，不能把这些PASS写成无条件真机PASS。诊断11的ComponentActivity控制 `formalAcceptanceEligible=false` 已排除，未凑进原30。

|准确方法|业务实际结果|实际 sourceCommit / tree|唯一首次辅助|显示设置恢复|回执|
|---|---|---|---|---|---|
|`org.inkweft.app.LocalMotionUiTest#cardSectionsEnterOnceAndDisposeCollapsedSourceAtTheNextRealFrame`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.296s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/01/settings-receipt.json`|`repair7-physical/display7-assisted/01/runtime/results.json`|
|`org.inkweft.app.LocalMotionUiTest#answerEnterCannotRetainPreviousQuestionCluesAcrossSkipHideAndExit`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.281s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/02/settings-receipt.json`|`repair7-physical/display7-assisted/02/runtime/results.json`|
|`org.inkweft.app.LocalMotionUiTest#latestPassiveSourceHighlightKeepsExactPaperAndNativeCaptureAtSystemZeroScale`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.313s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/03/settings-receipt.json`|`repair7-physical/display7-assisted/03/runtime/results.json`|
|`org.inkweft.app.CardSourceNavigationUiTest#collapsedSourceFooterReturnsToExactPaperAndKeepsReadOnlyMapContext`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.281s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.SourceFocusVisibilityUiTest#movedResizedFloatingCollectOrganizeAndFocusExposeExactSourceThenRestoreOriginalWindow`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.313s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/04/settings-receipt.json`|`repair7-physical/display7-assisted/04/runtime/results.json`|
|`org.inkweft.app.SourceFocusVisibilityUiTest#narrow375LargeFontRetainsSourceRegionThroughRecreationAndRestoresItsFocusFrame`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.422s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/05/settings-receipt.json`|`repair7-physical/display7-assisted/05/runtime/results.json`|
|`org.inkweft.app.SourceFocusVisibilityUiTest#splitPaneRefitsRetainedSourceAcrossBothDirectionsWithoutChangingReferenceOrGraph`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.375s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/06/settings-receipt.json`|`repair7-physical/display7-assisted/06/runtime/results.json`|
|`org.inkweft.app.RecallMaskUiTest#narrowRotationSourceReturnAndNextQuestionResetPermissions`|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.344s；flags `0x10008000`|PASS，before=after；`repair7-physical/display7-assisted/07/settings-receipt.json`|`repair7-physical/display7-assisted/07/runtime/results.json`|
|`org.inkweft.app.RecallMaskUiTest#explicitKnownAndUnknownRatingRetainOriginalCasAndReceiptIdentity`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.407s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.RecallMaskUiTest#mapHintGatesNativeTopologyTitlesBodiesAndFoldedDescendants`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.422s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.ReadLockUiTest#summaryOutlineAndMapRejectActualAuthorIntents`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.516s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/first-assisted-method/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#mixedExactResultsRetryOnlyTheConfirmedQuestionAndNeverMixANewSibling`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.562s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#pureSkipWithIdenticalRefsStartsAtFirstQuestionWithNoOldPermissionAndRestoresItsOwnRound`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.656s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#unknownOriginalOperationSurvivesActivityAndWriterRecreationBeforeCountingExactlyOnce`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.687s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#committedButUnverifiedResultStaysLockedAcrossRecreationAndRetriesReadOnlyProof`|NOT_RUN|未实际执行|无完成运行证据|不适用/无该类回执|无|
|`org.inkweft.app.BranchReviewRoundUiTest#completedRoundRecoveryConsumesOnlyItsOwnOperationBeforeRetryAndClose`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.641s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.QuestionMaintenanceUiTest#longQuestionSearchKeepsDraftsAndIdentityAcrossRecreationAndEditing`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.516s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.QuestionMaintenanceUiTest#actualReadOnlyEntryShowsSavedQuestionsButCannotEditRemoveOrSaveAuthors`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.547s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.BranchReviewRoundUiTest#missingFrozenResultDetailsKeepTheLedgerAndRetryWithoutCurrentQuestionSubstitution`|NOT_RUN|未实际执行|无完成运行证据|不适用/无该类回执|无|
|`org.inkweft.app.OverviewTabsUiTest#outlineFoldsFollowStableRowsThroughSearchRestoreAndStructureChanges`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.672s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.PageInsertionUiTest#newPagePaperRequiresSaveRestoresAndStaysNotebookLocal`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.672s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.PageInsertionUiTest#restoredPendingInsertionKeepsCapturedPaperAndReceiptAfterDefaultChanges`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.656s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.PageInsertionUiTest#failedDefaultSaveRollsBackAndKeepsDialogOpen`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.734s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.PageInsertionUiTest#beginningBatchKeepsOriginalInkAndPageIdentity`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.703s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.NotebookNavigationUiTest#realEditorAppendPersistsOneBlankPageAndRejectsStaleTail`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.594s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.TemplateCreationUiTest#installedTemplatesAreUsableInExistingCreationFlows`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.782s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.MapInteractionUiTest#outlineTitlesKeepSharedContentAndContinueAtTheSameParent`|PASS|`41e23e96e36e0bb58c394e941718e049f525ada5` / `3bb36b951bd87773a784bb608e2ef4eb9231083d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.344s；flags `0x10008000`|不适用/无该类回执|`repair8-physical/targeted3/results.json`|
|`org.inkweft.app.MapInteractionUiTest#outlineEnterUnknownReceiptRetriesOneCommandBeforeOpeningOneSiblingDraft`|PASS|`41e23e96e36e0bb58c394e941718e049f525ada5` / `3bb36b951bd87773a784bb608e2ef4eb9231083d`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.297s；flags `0x10008000`|不适用/无该类回执|`repair8-physical/targeted3/results.json`|
|`org.inkweft.app.SelectionStudyUiTest#outlineFoldFocusAndQuickAddShareOneGraph`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.359s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|
|`org.inkweft.app.ReadLockUiTest#studyTabSavedStateRestorationSurvivesCompactInitialization`|PASS|`f28a9e23ce320de7b50e5b07699b765620930e56` / `077d2860789d5f1b3b8937460558e9439ce0ba47`|`org.inkweft.app.a0.workspace/org.inkweft.app.MainActivity`；t+2.328s；flags `0x10008000`|不适用/无该类回执|`repair6-physical/independent19-assisted/results.json`|

两个删除修订故障方法仍只在隔离模拟器执行，主真机保持NOT_RUN。七个显示配置方法使用用户已有临时设置授权，由外部wrapper逐项锁竖屏、让原测试改wm/font/animator，并在finally按快照恢复与核对；当前已有7项完整设置回执确认before=after。逐项原值、准备值与恢复值保留在JSON，正文结果与设置恢复分别核对。没有修改设备冻结策略、后台耗电设置或重启。

## Room 14、新增回归5、PDF/备份UI4

Room 主12与PDF/备份补充2均为本轮全新实际执行，`oldPassReused=false`。已装 runner 经完整SHA核对与当前构建Room产物同字节；Room模块未被这些UI修复改动，完整摘要为 `aa905b4de50848f12f7ee0054fc9f861b23eca2f86b972201671f3efde6bbff5`。这不是继承V68历史PASS，补充2不重复算入主12，也不算入app原30。

|准确方法|结果|实际 sourceCommit / tree / 环境|首次辅助边界|回执|
|---|---|---|---|---|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#aMissingSelectedHistoryRejectsTheEntireRetryWithoutDroppingThatQuestion`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#causalRevisionMustExistAndMatchOnlyTheRequestedStateChange`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#completedReviewLedgerStillRequiresItsOriginalReceiptForRetry`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#exactReceiptsCountIndependentSameCardQuestionsOnceWithZeroReadSideWrites`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#laterQuestionCardEditsAndRecyclingNeverRebaseAnAlreadyCommittedResult`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#missingWrongOperationBookDigestResultOrRequestedStateNeverConfirms`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#noCandidateAndRecycledBookCannotStartANewRoundButOldReceiptStillCounts`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#originalQuestionAndCardHistoryAreRequiredRatherThanCurrentRows`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#rejectedSkipRetriesOriginalRefsButNeverPretendsToHaveAReceipt`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#skippedUnderstoodQuestionRetainsAllFourRefsAndDoesNotUseGlobalStateFiltering`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#unknownRollbackCannotCountOrSkipUntilTheSameOperationActuallyCommits`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.BranchReviewRoundRepositoryTest#wrongFrozenNotebookCannotBorrowAValidReceiptOrAnswer`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/runtime/results.json`|
|`org.inkweft.data.LibraryBackupRepositoryTest#roundTripPreservesOriginalIdsHiddenInkMasksHistoryAndMetadata`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/pdf-backup/results.json`|
|`org.inkweft.data.ReadingHandwritingRepositoryTest#pdfCopyExportBackupAndRestoreRetainOwnedBytes`|PASS|`810ba729a4caa3c22a857454b4838776456ccf61` / `65d3bd082cfeb28b958f01333e3524b8fa0e730f` / PHYSICAL|无已记录外部辅助|`current-physical-room/pdf-backup/results.json`|

新增恢复模型2、浮窗几何2、容器重挂载UI1是独立回归，不计原30；每方法按下表实际执行源码列出。registry/remount不等于OS进程终止，Geometry2也不是OEM、字体、分屏或旋转组合全扫。

|准确方法|结果|实际 sourceCommit / tree / 环境|首次辅助边界|回执|
|---|---|---|---|---|
|`org.inkweft.app.StudyProcessRestorationUiTest#remountedNotebookAndStudyContainersRestoreInlineTitleWithoutCreatingNode`|PASS|`41e23e96e36e0bb58c394e941718e049f525ada5` / `3bb36b951bd87773a784bb608e2ef4eb9231083d` / PHYSICAL_WITH_ONE_INITIAL_FOREGROUND_LAUNCH|`org.inkweft.app.a0.workspace/androidx.activity.ComponentActivity`；initial ActivityScenarioRule only; no second launch or assistance inside method body|`repair8-physical/remount-component/results.json`|
|`org.inkweft.app.StudyProcessRestorationUiTest#restoredUnknownStudyCommandWaitsForExplicitRetryOfTheSameOperation`|PASS|`f67ace09d8e0f5693fe50895a3d355d7287c6640` / `1532822c0faf0456d024087f88123cca4642e9f1` / EMULATOR|无已记录外部辅助|`repair1-emulator/model-restoration/results.json`|
|`org.inkweft.app.StudyProcessRestorationUiTest#savedRegistryRestoresSelectedBookPanelAndEveryStudyTab`|PASS|`f67ace09d8e0f5693fe50895a3d355d7287c6640` / `1532822c0faf0456d024087f88123cca4642e9f1` / EMULATOR|无已记录外部辅助|`repair1-emulator/model-restoration/results.json`|
|`org.inkweft.app.StudyWindowGeometryUiTest#restoringBottomRightWindowKeepsExpandedFrameInsideEveryFrame`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / EMULATOR|无已记录外部辅助|`repair3-emulator/targeted9/results.json`|
|`org.inkweft.app.StudyWindowGeometryUiTest#returningFromSourceToFocusKeepsCloseTargetInsideEveryFrame`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / EMULATOR|无已记录外部辅助|`repair3-emulator/targeted9/results.json`|

新增remount使用createComposeRule，其真实宿主是ComponentActivity。首次辅助组件必须与该宿主匹配，不能套用原30的MainActivity配置；组件不匹配的START-only尝试作为harness环境NOT_RUN保留，不算产品FAIL或业务PASS。正确组件的独立重跑只按实际完成回执计数，正文中不补第二次启动。

PDF/导出/备份补充UI均在893a983的隔离API35模拟器执行。可渲染合成PDF经过实际导入、缩放/重开；导出和备份使用真实应用UI。独立恢复目标前27作者表均0行，确认恢复后1本1页、integrity=ok、FK违规0；两份截图已视觉检查。原设备没有作为恢复目标，不扩大为真实平板或任意用户资料规模通过。

|准确方法|结果|实际 sourceCommit / tree / 环境|首次辅助边界|回执|
|---|---|---|---|---|
|`org.inkweft.app.LibraryBackupUiTest#backupRequiresConsentThenSavesActualLibraryArchive`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / EMULATOR|无已记录外部辅助|`repair3-emulator/pdf-export-backup/results.json`|
|`org.inkweft.app.LibraryBackupUiTest#isolatedPreviewRequiresConfirmationAndRestoresOnDevice`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / EMULATOR|无已记录外部辅助|`repair3-empty-restore-target/runtime/results.json`|
|`org.inkweft.app.LibraryTransfersUiTest#exportStartsAtShelfAndWritesTheSamePreparedSnapshot`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / EMULATOR|无已记录外部辅助|`repair3-emulator/pdf-export-backup/results.json`|
|`org.inkweft.app.ReadingHandwritingUiTest#pdfImportOwnsSourceAndKeepsBackgroundAcrossZoomAndReopen`|PASS|`893a983d1f4fcf9d782d77e9e81c693a28a7c81c` / `d07daa86dd269a1811721448a0e7b5ddb04a8b47` / EMULATOR|无已记录外部辅助|`repair3-emulator/pdf-export-backup/results.json`|

## OS进程死亡、修复与历史

f67ace普通应用在隔离API35上完成两次HOME→带savedState的STOPPED→am kill→同一保留任务冷启动。旧进程消失、新进程创建：原本/学习区大纲/ASCII未提交标题自动恢复PASS；确认标题后第二次恢复PASS，29表349行前后一致，无重放/重复写入。证据在 `repair1-emulator/actual-os-death/result.json` 和 `confirmed-result.json`。这不是Activity重建替代，也不覆盖force-stop、真实中文IME、所有草稿或真机。

普通应用真机 OS kill 的 draft、confirmed 两切点均有独立 PASS 回执。两次均为3fbc普通应用、非instrumentation：HOME后核对保留任务已保存且STOPPED，再OS am kill、同一任务冷启动。只覆盖新增合成笔记的大纲结构标题草稿与确认后状态；不与instrumentation首次启动辅助模式混用，也不能推广到全部编辑路径、中文IME、笔输入或force-stop。

|切点|结果|实际 sourceCommit / tree|逻辑快照范围|原回执scope|回执|
|---|---|---|---|---|---|
|draft|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|29表 / 3868行（仅本切点前后）|New synthetic notebook; outline structure title only. ASCII ADB input is not Chinese IME or stylus evidence. Confirmed title is checked only in confirmed stage. No force-stop, permission change, process unfreeze or second recovery assistance.|`repair7-physical/manual-os-death/draft/result.json`|
|confirmed|PASS|`3fbc5f15c607e85c29aec4402f92d3a22cad3d27` / `9cb1ba919979930ca4072935fa4c88887f364a4d`|29表 / 3870行（仅本切点前后）|New synthetic notebook; outline structure title only. ASCII ADB input is not Chinese IME or stylus evidence. Confirmed title is checked only in confirmed stage. No force-stop, permission change, process unfreeze or second recovery assistance.|`repair7-physical/manual-os-death/confirmed/result.json`|

各切点前后的29张逻辑表各自未变；draft为3868行、confirmed为3870行。这两个检查点之间存在用户确认写入，后续又新增测试夹具，不能把它们与后续3995行或设备当前全表行数直接相等比较。

公开基点 `bb0f2b15f2c02e956c0ea4256e9f64378f41de0e` 顺序应用两补丁，得到本地精确基线 `810ba729a4caa3c22a857454b4838776456ccf61` / tree `65d3bd082cfeb28b958f01333e3524b8fa0e730f`。基线的2 PASS/1 FAIL/27 NOT_RUN、两次自动回资料库失败及后续各版本失败均保留；原V68章节不重写。后续生产修复包括恢复路由、浮窗动画边界及原位标题定位requestScrollToItem；对应实际验证以逐方法表为准。

测试修正单列：暂停时钟前准备滚动、OS窗口检索标志、caption自身屏幕像素采样、真实window根Recomposer与native子上下文倍率对应、明确选择节点后打开来源。Map原业务断言保留，并增加unmerged tree和对应outline-row祖先检查；这不意味着所有测试覆盖等价。LocalMotion保留长正文窄屏重建后的可达性／正文／作者不变检查，但首次恢复像素段改走同一卡片source-only入口，该入口强制showSource=true并省略正文；取样也只含caption，不再包含整个来源组。因此该像素段不能独立证明showSource保存恢复、长正文底部整个来源区域的恢复首帧像素，或异步缩略图的同帧状态。准确审查见 `test-scope-review/FINAL-REPAIR-REVIEW.md`。

3fe6卡片方法31.984秒PASS，未知回执18.578秒PASS，容器重挂载14.375秒PASS，Selection22.078秒PASS。3fe6长标题方法17.438秒FAIL发生在输入后立即recreate；f28a9e增加输入同步前置断言后两处recreate已通过，但Enter阶段仍FAIL。3fbc只修测试同步，该包在API35长流程30.688秒PASS、真机首次辅助长流程在line143的首次空白续写等待FAIL，两种结果分别保留。

后续failure-only证据确认已保存节点但旧anchor编辑器仍锁定；完成阶段trace则在software Next后第二次空白等待失败：fresh-nodes IO读取返回后，完成交接在DefaultDispatcher worker继续，clearFocus抛CalledFromWrongThreadException，catch也在worker。那次trace内硬件Enter步骤已完成，不计整个方法正式PASS。Compose 1.10.5测试默认effectContext派生UnconfinedTestDispatcher，不保证IO返回后仍在主线程；此证据不能推出普通应用必现线上故障，普通应用OS kill两切点的PASS仍独立成立。

正式修复 `41e23e96e36e0bb58c394e941718e049f525ada5` / tree `3bb36b951bd87773a784bb608e2ef4eb9231083d` 只将整个标题完成LaunchedEffect主体固定到Dispatchers.Main.immediate，保留内部IO读取、取消重抛、unknown门控、同命令回执与token校验；7处早退指向Main block，成功与catch收尾同在Main。归档补丁 `repair7-physical/title-completion-trace-diagnosis/minimal-fix.patch`；可分享的诊断摘要 `delivery-evidence-sanitized/title-completion-thread-summary.json`。诊断包不计正式通过。build08的标题长流程、未知回执、容器重挂载三项，在API35模拟器及真机首次启动辅助模式下各3 PASS，逐项回执分别见repair8-emulator/formal-run-summary.json和repair8-physical/formal-run-summary.json。该长流程在当前候选API35模拟器实际复跑PASS，耗时31.422秒，回执 `repair8-emulator/targeted3/results.json`；真机结果仍按独立表记录。

旧V68纯内存/原生View/普通Activity与独立UiAutomation控制只用于诊断。诊断07证明等待在startActivitySync而非waitForIdleSync。用户后来批准SAW单次对照：诊断09因监控旧进程退出race，原始方法事件为空，立即finally恢复default，结论INCONCLUSIVE；不能说PASS或排除原因。诊断10有效持有SAW许可时仍START-only、约6秒freeze，结果NOT_RUN/ENVIRONMENT_BLOCKED，finally已恢复default并结束该次冻结instrumentation；普通MainActivity冷启动成功321ms，不代表原runner已修复。诊断11用精确shell MAIN+LAUNCHER/flags启动同一ComponentActivity一次，诊断PASS，但formalAcceptanceEligible=false，不入原30。随后业务方法采用上述独立辅助模式。没有改变冻结策略/后台耗电/重启；主机与设备时钟不一致，以host时间、同次方法事件与相对耗时关联，未调整时钟。

## 真机、隐私与收尾

SR-01～10、SR-HW-01保持稳定编号，真人手工用例仍NOT_RUN；相关真机自动化只按前台辅助模式记录，不合并为真人/无辅助通过。隔离证据另列。真人15分钟必须用户持笔，按0/5/10/15分钟记录中英/公式、轻重压、倾斜、掌触、缩放、卡顿和温升。ADB/回放/模拟器/CI不能替代。真实中文组词、真人完整旅程、物理分屏和实际笔感继续待验。

`private-device-final/receipt.json` 确认原有2565行未丢失、未修改；该归档共4379行，新增1814行。`private-device-final/new-row-ownership.json` 确认新增行全部归属50个新增测试笔记，归属原有或未知笔记的新增行0。这是该检查点的原资料保留与归属结论；另有22项应用文件哈希变化，包含新增测试证据；原始偏好文件差异为0。 原始行保留与新增夹具归属不等于后续全库行数固定。两个受保护旧目录HEAD/status及162个原dirty文件未变、设置恢复等结论分别以各自保护回执检查点为准，不扩大为所有后续时点。原始笔记/数据库/备份/截图/系统日志及签名秘密仅留本机，本报告只放计数、scope及脱敏结果；不公开设备序列号、具体进程标识、原用户内容或行明细。应用“诊断与导出”用于标记时间、复现和导出ZIP，基础包不等于完整系统日志。

|检查点|结果|原有行数|原有行差异|该点总行数|新增行/归属新笔记|回执|
|---|---|---|---|---|---|---|
|private-device-after-physical-tests-01|PASS|2565|缺失0 / 修改0|3995|1430行 / 46本|`private-device-after-physical-tests-01/receipt.json` / `private-device-after-physical-tests-01/new-row-ownership.json`|
|private-device-final|PASS|2565|缺失0 / 修改0|4379|1814行 / 50本|`private-device-final/receipt.json` / `private-device-final/new-row-ownership.json`|

最终五项应用偏好open-tabs／reading／editor／study-window／learning已按原字节哈希恢复，恢复前已备份当时偏好，未写数据库或清空数据；回执 `final-preferences-restoration/receipt.json`。 最终十项display／IME／SAW设置与七项显示测试前baseline一致；回执 `final-settings-audit.json`。 最终完整保护回执存在时优先采用，较早检查点保留为历史；不存在时只报告已完成的中间检查点。

最终收尾：`protected-worktrees-after.json`确认两个受保护旧目录HEAD/status及162个原dirty文件哈希保持。40个本轮设备XML/PNG临时文件先归档核对哈希后删除，见`cleanup/device-ui-temporaries/receipt.json`；独占测试AVD已停止且数据保留，停止回执单列。实机普通MainActivity最后一次COLD启动成功328ms，不替代测试验收。

清理限制仍保留：15个已验证重复-built.apk共859,594,231字节，Remove-Item在CreateProcess前被自动审批拒绝，命令未执行；8个临时Edge profile共56,517,876字节也保留原拒绝记录，没有绕过。不能声称所有临时产物都已删除。完整签名包、源码、原始验证证据、备份和必要工具缓存均保持；准确路径见cleanup回执。最终仓库文档提交与APK构建提交分开；文档整合不改变上述APK身份。
