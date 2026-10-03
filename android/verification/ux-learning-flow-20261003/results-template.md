# 学习流程实机结果与记录模板

本表服务于新学习流程报告。公开记录只写合成对象别名、方法身份、构件摘要和脱敏证据ID；不填设备序列号、真实标题、用户正文、本机绝对路径或原始日志。历史结果按轮次追加，不能用最新PASS覆盖旧FAIL／NOT_RUN。

## 已完成04证据与04b候选

| 字段 | 当前值 |
| --- | --- |
| 记录日期 | 2026-10-04；回执时区UTC，必要时另标北京时间 |
| 设备 | iQOO实体平板／vivo iPA2673，Android 16／API 36；真人笔具与键盘待填 |
| 版本／包名 | 68／`0.0.68-cloud-candidate`；`org.inkweft.app.a0.workspace` |
| 主包SHA256 | `517364fe37562c8f3d455e56ab39944f75c8b389d9ca353e07cd0e47cb4b7d94` |
| app-test SHA256 | `0421fd0df30be25cbe3c14dd77b31248d5b4ae95558e481d446571af920eb298` |
| Room-test SHA256 | `f3b9ea801f3acc25f654f4ab38005ecb960bbc7102b402ba6b27de24fc4002ca` |
| 证书SHA256 | `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969` |
| 完整已跟踪补丁SHA256 | `302bc4d6972ec6c7ad0b5068597d1847b0a5fed776d995c6ebe3797b684a6796` |
| 完整基点 | `b466ad776424e219796f84dfdd6ff6381c230872`；补丁候选，非干净提交构建 |
| 安装／构建 | 三包覆盖安装；最终构建PASS；Core317 PASS；lint141 Warning、0 Error／Fatal，较03a新增0／移除0 |
| 04 UI／Room | UI26 PASS（逐方法一次初始前台启动辅助）；Room37 PASS（实体无辅助，全新runner，无旧PASS复用） |
| 04b补丁／tracked manifest／freeze | `02b71372e7ef42eb646c47cea1d0e1246f486bd65c1a13ef8a74daf74bb64c74` ／ `80d68e0e9cf844131b64aa3da92972a868df1a0facda87fbad106d3b321a42dd` ／ `426dfd968518fb317207d5ee622fee38651f2414ab13ac3be419346444dc577a` |
| 04b主包／app-test SHA256 | `c12caad57412efe5933f0446d2e56f1aa2ce90ff60c4bc8f9632b6ef34d01079` ／ `092d96935813a5a5692cf9b42d8a061773795a4946a23d59ff06d100fb338f3d` |
| 04b状态 | 主包／app-test已安装，Room复用04；最终构建／lint PASS，141 Warning、0 Error／Fatal；8 UI PASS；Core／Room本轮执行0，父04证据标NOT_RUN_REUSED |
| 文档／交付版本 | 随本报告提交的版本；当前分支 `codex/local/ux-learning-flow-20261003`，PR链接见最终交付回复 |

## 历史轮次摘要

| 轮次 | 模式 | PASS／FAIL／NOT_RUN | 后续处理 |
| --- | --- | --- | --- |
| 01链接控制 | 无辅助instrumentation | 0／0／1 | freezer环境阻塞保留 |
| 01链接 | 每方法一次初始前台启动辅助 | 5／2／0 | inline产品竞态、375dp助手失败保留 |
| 01阅读 | 相同辅助模式 | 4／0／0 | 限4方法 |
| 01 Room | 实体无辅助 | 11／0／0 | 不计入UI |
| 01b修复复验 | 相同UI辅助模式 | 3／0／0 | 最新7项＝01保留4项＋01b复验3项 |
| 02系列最新唯一方法 | 多轮，保留原模式 | 15／0／2 | 02d只针对性执行5项PASS；两项几何NOT_RUN |
| 03a Core | 本机全量JVM | 315／0／0 | lint 141 Warning、0 Error／Fatal |
| 03a Room | 实体无辅助，新Room包 | 26／0／0 | 03b不重跑 |
| 03a UI首项 | 初始前台辅助；OS最近任务后freezer | 0／0／1 | Ctrl／Alt+Tab触发，环境阻塞 |
| 03a剩余UI | 每方法一次初始前台辅助 | 22／2／0 | 两项测试助手适配，03b复验 |
| 03b指定UI | 同辅助；修饰键直接派发可见native map | 4／0／0 | native派发；仅app-test重建 |
| 03最新唯一UI | 03a21项＋03b4项 | 25／0／0 | 29次attempt总计26 PASS＋2 FAIL＋1 NOT_RUN |

03原始构建脚本失败和03a启动前guard拒绝发生在产品测试前，单列为基础设施attempt，不计入上述产品FAIL。原有逐方法证据本机保留，公开报告只接入脱敏身份和结果。

## 04实际执行与逐方法回执

App63／Room52是包装范围；本轮实际UI26项全部PASS、Room37项全部PASS。UI每方法一次初始前台启动辅助；Room实体无辅助且oldPassReused=false。下表证据ID对应本轮公开脱敏汇总的顺序，不包含原始运行日志或本机路径。普通同PDF47–60截图与两段回忆录屏／10张原生帧已接受；61／62保留PARTIAL。04b普通修复和撤销提示已复验；普通修复、normal／rapid录屏、同PDF合成对象核验和恢复清理已通过；独立零动画录像原生帧已接受，结论PASS_FOR_RECORDED_SCOPE，真人硬件NOT_RUN。

### UI实际26项

| 序号 | 方法 | 本稿状态 | 脱敏回执锚点 |
| --- | --- | --- | --- |
| 01 | `org.inkweft.app.StudyRelationsUiTest#actualToggleAndSelectedOccurrenceChangeOnlyTheReadOnlyOverlay` | PASS | `UI04-01` |
| 02 | `org.inkweft.app.StudyRelationsUiTest#nativeSceneReplacementTouchAndZeroScaleKeepFinalGeometryImmediately` | PASS | `UI04-02` |
| 03 | `org.inkweft.app.StudyRelationsUiTest#projectionCountsEachHiddenOrNonCardLinkOnceWithoutCountingVisibleCopies` | PASS | `UI04-03` |
| 04 | `org.inkweft.app.StudyRelationsUiTest#projectionIsEmptyWithoutSelectionAndNeverSynthesizesHierarchyOrDecorations` | PASS | `UI04-04` |
| 05 | `org.inkweft.app.StudyRelationsUiTest#projectionKeepsDirectionTypesAndEveryVisibleOtherOccurrence` | PASS | `UI04-05` |
| 06 | `org.inkweft.app.BranchReviewUiTest#currentCardReviewKeepsItsQuestionsAndReturnsToTheSameScrolledDetailsAfterSourceAndRecreation` | PASS | `UI04-06` |
| 07 | `org.inkweft.app.BranchReviewUiTest#sourceOnlyCardShowsItsSavedExcerptAfterRevealWithoutWritingAResult` | PASS | `UI04-07` |
| 08 | `org.inkweft.app.BookSourceSelectAwaitUiTest#cancelBackRecreateAndOrdinaryPageOverrideCannotPublishAnOldSource` | PASS | `UI04-08` |
| 09 | `org.inkweft.app.BookSourceSelectAwaitUiTest#genuinelyRecycledSourcePageOrBookKeepsDetailsAndSnapshotWithoutFocus` | PASS | `UI04-09` |
| 10 | `org.inkweft.app.BookSourceSelectAwaitUiTest#sourceWaitsForRealDifferentPageSelectionBeforeDismissingAndFocusing` | PASS | `UI04-10` |
| 11 | `org.inkweft.app.BranchReviewRoundUiTest#mixedExactResultsRetryOnlyTheConfirmedQuestionAndNeverMixANewSibling` | PASS | `UI04-11` |
| 12 | `org.inkweft.app.BranchReviewUiTest#bookKnowledgeAndCardPropertiesAncestorsRestoreWholeNotebookRecall` | PASS | `UI04-12` |
| 13 | `org.inkweft.app.BranchReviewUiTest#foldedStructureBranchDeduplicatesCardsButKeepsTheirIndependentQuestions` | PASS | `UI04-13` |
| 14 | `org.inkweft.app.BranchReviewUiTest#frozenQuestionAndAnswerSurviveEditsRecreationAndSourceReturnWithoutRating` | PASS | `UI04-14` |
| 15 | `org.inkweft.app.BranchReviewUiTest#narrow375LargeTextScrollsToEveryActionAndNextQuestionStartsAtTopHidden` | PASS | `UI04-15` |
| 16 | `org.inkweft.app.KnowledgeTextLinksUiTest#backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites` | PASS | `UI04-16` |
| 17 | `org.inkweft.app.KnowledgeTextLinksUiTest#inlineTitleAndAliasOpenConfirmedTargetWhileDuplicateNamesRequireChoice` | PASS | `UI04-17` |
| 18 | `org.inkweft.app.KnowledgeTextLinksUiTest#narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable` | PASS | `UI04-18` |
| 19 | `org.inkweft.app.KnowledgeTextLinksUiTest#pageAndNotebookBacklinksKeepDistinctScopeAndReadOnlyPreviewIdentity` | PASS | `UI04-19` |
| 20 | `org.inkweft.app.KnowledgeTextLinksUiTest#pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting` | PASS | `UI04-20` |
| 21 | `org.inkweft.app.KnowledgeTextLinksUiTest#reopenedLivePreviewReadsCurrentBodyWhilePinnedPreviewKeepsExactHistory` | PASS | `UI04-21` |
| 22 | `org.inkweft.app.KnowledgeTextLinksUiTest#replacedAndRemovedLinkAreRevalidatedBeforeOpenWithoutSilentlyChangingTarget` | PASS | `UI04-22` |
| 23 | `org.inkweft.app.MapInteractionUiTest#narrowLargeTextKeepsActionsDraftAndCanvas` | PASS | `UI04-23` |
| 24 | `org.inkweft.app.RecallMaskUiTest#exitRestoresAncestorRenderingAccessibilityDraftAndOtherBook` | PASS | `UI04-24` |
| 25 | `org.inkweft.app.StudyOrganizationUiTest#layoutPreviewCancelKeepsAuthorStateAndApplyUndoUseMeasuredNodeBounds` | PASS | `UI04-25` |
| 26 | `org.inkweft.app.StudyOrganizationUiTest#subtreeOrderSurvivesReopenAndUndoWhileKeyboardChangesOneSelectedBranch` | PASS | `UI04-26` |

汇总：UI26 PASS／0 FAIL／0 NOT_RUN，模式 `PHYSICAL_WITH_ONE_INITIAL_FOREGROUND_LAUNCH`。每方法均有本轮回执；26方法的设置恢复均等于各自记录基线，任务终态另查。

### Room实际37项

| 序号 | 方法 | 本稿状态 | 脱敏回执锚点 |
| --- | --- | --- | --- |
| 01 | `org.inkweft.data.KnowledgeTextRepositoryTest#onlyConfirmedLinksForExactSourceAndOwnerAppearWithoutAuthorWrites` | PASS | `ROOM04-01` |
| 02 | `org.inkweft.data.KnowledgeTextRepositoryTest#crossNotebookSameNamesAndAliasesKeepEveryConfirmedTargetIdentity` | PASS | `ROOM04-02` |
| 03 | `org.inkweft.data.KnowledgeTextRepositoryTest#liveRenameAndAliasChangesNeverReplacePinnedTitleOrBody` | PASS | `ROOM04-03` |
| 04 | `org.inkweft.data.KnowledgeTextRepositoryTest#missingPinnedRevisionRetainsAssociationWithoutCurrentTitleOrBodyFallback` | PASS | `ROOM04-04` |
| 05 | `org.inkweft.data.KnowledgeTextRepositoryTest#recyclingNotebookEmitsUnavailableButKeepsExactPinnedExcerptAndTarget` | PASS | `ROOM04-05` |
| 06 | `org.inkweft.data.KnowledgeTextRepositoryTest#capturedRevisionRejectsRetargetingRemovalAndWrongSourceWhilePageTargetsStayExplicit` | PASS | `ROOM04-06` |
| 07 | `org.inkweft.data.KnowledgeTextRepositoryTest#incomingCrossNotebookPreviewReadsCurrentSourceRatherThanPinnedTarget` | PASS | `ROOM04-07` |
| 08 | `org.inkweft.data.KnowledgeTextRepositoryTest#incomingRequiresExactTargetKindReferenceRelationAndSourceOwner` | PASS | `ROOM04-08` |
| 09 | `org.inkweft.data.KnowledgeTextRepositoryTest#typedIncomingPreviewRequiresExplicitModeAndKeepsIdentityVersionAndReadOnlyGuards` | PASS | `ROOM04-09` |
| 10 | `org.inkweft.data.KnowledgeTextRepositoryTest#incomingRejectsStaleRemovedAndRetypedRelationshipsWithoutWriting` | PASS | `ROOM04-10` |
| 11 | `org.inkweft.data.KnowledgeTextRepositoryTest#observedPreviewsRefreshBodyOnlyEditsAndSourceAvailabilityWithoutAuthorWrites` | PASS | `ROOM04-11` |
| 12 | `org.inkweft.data.KnowledgeTextRepositoryTest#observedAnchorPreviewWarnsWhenOriginalInkRevisionChanges` | PASS | `ROOM04-12` |
| 13 | `org.inkweft.data.BranchReviewRepositoryTest#currentCardPreparationKeepsOwnQuestionsAndUnplacedScopeWithoutAuthorWrites` | PASS | `ROOM04-13` |
| 14 | `org.inkweft.data.BranchReviewRepositoryTest#currentCardPreparationRejectsForeignStaleAndInvalidContextWithoutFallback` | PASS | `ROOM04-14` |
| 15 | `org.inkweft.data.BranchReviewRepositoryTest#twoMapsRepeatedPositionsAndRemovedNodeKeepIndependentQuestionScope` | PASS | `ROOM04-15` |
| 16 | `org.inkweft.data.BranchReviewRepositoryTest#nativeFortyLevelBranchIncludesDeepQuestionAndAllDistinctCards` | PASS | `ROOM04-16` |
| 17 | `org.inkweft.data.BranchReviewRepositoryTest#notebookIncludesUnplacedManualCardWhileWholeMapKeepsMapScope` | PASS | `ROOM04-17` |
| 18 | `org.inkweft.data.BranchReviewRepositoryTest#frozenLoadKeepsQuestionAndAnswerDuringConcurrentEditsAndNewQuestions` | PASS | `ROOM04-18` |
| 19 | `org.inkweft.data.BranchReviewRepositoryTest#missingHistoricalRevisionFailsInsteadOfUsingCurrentQuestionOrAnswer` | PASS | `ROOM04-19` |
| 20 | `org.inkweft.data.BranchReviewRepositoryTest#removedQuestionKeepsItsFrozenIdentityButDisappearsFromNewPlan` | PASS | `ROOM04-20` |
| 21 | `org.inkweft.data.BranchReviewRepositoryTest#frozenReferencesRejectSwappedCardAndForeignNotebook` | PASS | `ROOM04-21` |
| 22 | `org.inkweft.data.BranchReviewRepositoryTest#missingBranchAndRecycledMapNeverFallBackToNotebookOrMainMap` | PASS | `ROOM04-22` |
| 23 | `org.inkweft.data.BranchReviewRepositoryTest#startedSessionKeepsArchivedAnswerButGuardedMarkRejectsRecycledCard` | PASS | `ROOM04-23` |
| 24 | `org.inkweft.data.BranchReviewRepositoryTest#guardedMarkRejectsChangedAnswerWithoutAdvancingQuestionRevision` | PASS | `ROOM04-24` |
| 25 | `org.inkweft.data.BranchReviewRepositoryTest#committedMarkReplaysReceiptAfterAnswerChangesWithoutSecondRevision` | PASS | `ROOM04-25` |
| 26 | `org.inkweft.data.BranchReviewRepositoryTest#failedMarkRollsBackAndOriginalCommandCanBeRetriedOnce` | PASS | `ROOM04-26` |
| 27 | `org.inkweft.data.BranchReviewRepositoryTest#collectionAndOrUseCardPropertiesAndKeepEveryIndependentQuestionWithoutWrites` | PASS | `ROOM04-27` |
| 28 | `org.inkweft.data.BranchReviewRepositoryTest#collectionDefaultAndRemovedPropertiesUseInboxWhileRecycledCardsStayOut` | PASS | `ROOM04-28` |
| 29 | `org.inkweft.data.BranchReviewRepositoryTest#emptyCollectionAndMatchedCardsWithoutQuestionsNeverFallBackToNotebook` | PASS | `ROOM04-29` |
| 30 | `org.inkweft.data.BranchReviewRepositoryTest#collectionPreparationRejectsForeignMissingRecycledAndChangedIdentitiesWithoutFallback` | PASS | `ROOM04-30` |
| 31 | `org.inkweft.data.BranchReviewRepositoryTest#collectionSessionKeepsFrozenQuestionAndAnswerWhenMembershipAndDefinitionChange` | PASS | `ROOM04-31` |
| 32 | `org.inkweft.data.BranchReviewRepositoryTest#fourEntryRangesFilterQuestionStateAndPreserveIndependentQuestionsAndUnplacedCards` | PASS | `ROOM04-32` |
| 33 | `org.inkweft.data.BranchReviewRepositoryTest#noPendingQuestionsRemainDistinctFromEmptyAndTrulyUnaskedCollections` | PASS | `ROOM04-33` |
| 34 | `org.inkweft.data.BranchReviewRepositoryTest#stateMarksChangeOnlyNextRoundWhileCurrentQuestionsAndAnswerStayFrozen` | PASS | `ROOM04-34` |
| 35 | `org.inkweft.data.BranchReviewRepositoryTest#removedQuestionsDoNotTurnOtherStateOnlyCardsIntoFalseUnaskedCards` | PASS | `ROOM04-35` |
| 36 | `org.inkweft.data.BranchReviewRepositoryTest#reviewOnlyStillRejectsInvalidCollectionMapAndBranchInsteadOfReturningAnEmptyFallback` | PASS | `ROOM04-36` |
| 37 | `org.inkweft.data.BranchReviewRepositoryTest#questionStateAndPinnedRevisionComeFromOneCoherentRoomSnapshot` | PASS | `ROOM04-37` |

范围为KnowledgeTextRepositoryTest当前12项＋BranchReviewRepositoryTest当前25项。汇总：Room37 PASS／0 FAIL／0 NOT_RUN，模式 `PHYSICAL_UNASSISTED`；当前12项已真实重跑，范围为本轮实际执行。

## 04b实际执行8项

UI_ONLY候选仅修改KnowledgeWorkspace、StudyWorkspace和既有StudyRelationsUiTest。父04其余输入保持同字节，包装身份仍为App63／Room52。Core317和Room37只列历史04结果，04b实际执行均为0；Room APK保持原04路径与SHA。主包／app-test已安装，最终构建／lint PASS。构建回执SHA `82ff12c1e88b0e105614568e498535919d60ec8f1fa3fe0cfd95278a463a9abd`，结束UTC 2026-10-03T22:43:27.938596+00:00。

| 序号 | 实际方法 | 当前状态 | 新回执ID |
| --- | --- | --- | --- |
| 01 | `org.inkweft.app.StudyRelationsUiTest#actualToggleAndSelectedOccurrenceChangeOnlyTheReadOnlyOverlay` | PASS | `UI04B-01` |
| 02 | `org.inkweft.app.KnowledgeTextLinksUiTest#backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites` | PASS | `UI04B-02` |
| 03 | `org.inkweft.app.KnowledgeTextLinksUiTest#inlineTitleAndAliasOpenConfirmedTargetWhileDuplicateNamesRequireChoice` | PASS | `UI04B-03` |
| 04 | `org.inkweft.app.KnowledgeTextLinksUiTest#narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable` | PASS | `UI04B-04` |
| 05 | `org.inkweft.app.KnowledgeTextLinksUiTest#pageAndNotebookBacklinksKeepDistinctScopeAndReadOnlyPreviewIdentity` | PASS | `UI04B-05` |
| 06 | `org.inkweft.app.KnowledgeTextLinksUiTest#pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting` | PASS | `UI04B-06` |
| 07 | `org.inkweft.app.KnowledgeTextLinksUiTest#reopenedLivePreviewReadsCurrentBodyWhilePinnedPreviewKeepsExactHistory` | PASS | `UI04B-07` |
| 08 | `org.inkweft.app.KnowledgeTextLinksUiTest#replacedAndRemovedLinkAreRevalidatedBeforeOpenWithoutSilentlyChangingTarget` | PASS | `UI04B-08` |

本轮8 PASS／0 FAIL／0 NOT_RUN；每方法一次初始前台启动辅助，8项设置均恢复各自基线。执行UTC 2026-10-03T22:40:55.140747+00:00 至 2026-10-03T22:43:43.540851+00:00；回执SHA `822789f502c99111720b5a11c3b09b91ecc2983f899300bb71b140d83878a483`。原04同名方法的PASS保留在上表，并独立记录04b新结果。

最新唯一UI26项＝04保留18项＋04b重跑8项；04b实际8项均PASS。最终汇总回执SHA256 `da1a141a0a4811873ac679811152c8d204843cfc857f45246377769e7381f3d0`，已安装三包身份再次核对一致。

## 本轮公开证据身份

| 证据ID | 内容 | 原回执SHA256 |
| --- | --- | --- |
| BUILD04 | 最终构建、三包签名、Core317及lint | `76083877c496e3726675f8b6646986037be5e9ebae151e8b7d748c48e691771b` |
| LINT04 | 与03a141项逐项比较，仅忽略行列，新增0／移除0 | `a802124c9579d6881432a74309b09255f50a10fcffda79462f1a3014d5002028` |
| UI04-01～26 | 本轮26方法状态与设置记录；一次初始前台辅助 | `38933b926bd9f4f3d81a202e7a469405fcf9f042ac97d20d4d7d0ebb960d052f` |
| ROOM04-01～37 | 本轮37方法；实体无辅助 | `02d95aaae48ee7b428636a96c3ebb93a977b4480fa0bbfdc866970fa801cb4bd` |
| SHOTS04 | 6张已接受合成关联截图；375dp／1.6为instrumentation窗口 | `7339b98b41634d2e0fa080c8fe464618ab633d9334c553866061ccbcdac7cc19` |
| ORDINARY04-47～58 | 同PDF普通应用主链，12张原图与同名回执均accepted PASS | 各图身份由公开图映射与最终证据摘要接入 |
| PIXEL04-48-58 | 排除顶部114px系统状态栏，应用像素相同；作者身份另见SAMEPDF04 | `7e8393448fed60be4b98e9da6bfafdd02d9f01b6049616cdc9e2059c3afbd9c2` |
| BUILD04B | UI_ONLY两包新建、原04 Room复用、lint141 Warning／0 Error | `82ff12c1e88b0e105614568e498535919d60ec8f1fa3fe0cfd95278a463a9abd` |
| UI04B-01～08 | 8项全部PASS；逐方法一次初始前台辅助，设置恢复一致 | `822789f502c99111720b5a11c3b09b91ecc2983f899300bb71b140d83878a483` |
| ORDINARY04-59～60 | C卡图像答案与同PDF第2页，accepted PASS | 图59 `b6a05aa70c12d1feafd182dcc90db8245977a8e08e7725c9d4be3e06b83977a9`；图60 `e498ed2a0cb9e7ad8a37157e684b596434e37cdc46ec94f06c64d011f6d966fc` |
| ORDINARY04-61～62 | 首次方向、折叠后102px下移／错误减号，accepted PARTIAL | 图61 `e29cfee3e96452f01719d65c79b6e1da0e9da4d9838812d842d7ea5588d313d8`；图62 `0d2158bc40084d50261763a8629f52c60e2bb03fb5eaf7a065bdcc327677daf5` |
| V-04-SCOPE-SOURCE | 180.469s wall；392解码帧／last PTS164.492s；6张原生帧accepted | 元数据 `42c02f2e7164c4ab1744fbc9117747d82dbf83b24d19e0ba15551e0f53866e99`；帧回执 `5b975aa70349ff38cdb31901e9b0ec2f9983b85880dabbb00f01efd9ec10fc7b` |
| V-04-RETURN | 90.500s wall；114解码帧／last PTS80.021s；4张原生帧accepted | 元数据 `284d8e7105e16782068d1606f2314c9a73bdd6b7dac47dd4837c422e56745e91`；帧回执 `f526dcf1a6de70b3c4c17294721edc93b7d9e1e6880e51981a3a9918ef263421` |
| VIDEOS04B | 正常／快速／后续平移和独立零动画，原生帧均接受 | `1c78346fc973e513873ee7f305c8f0e5ef3686dc88adc269af1f4c035e13fb98` |
| LINT04B | 141既有Warning与04逐项／重复次数一致，新增0移除0 | `639ebc39ac88f7b8ca58e1123ace3093975303c961f2aa24154c2e1ee6b0bec5` |
| SAMEPDF04B | 最终同PDF卡／来源／知识记录保持；C节点仅明确四次revision变化 | `d1ad13dbd1b2078b42b8e52a3f5af72dc1ccc7dd78b2faaa8b0f224bd74d5fb9` |
| ORDINARY04B-63～74 | 首次出向、折叠0px位移／加号、撤销提示清除及重开；普通ADB步骤已接受 | `ad0f760323722721837579dd3babe9108404b2d3d64be63a078a972d4f5560cb` |
| SAMEPDF04 | 3卡／3节点／3来源原值保持；仅B两题rev+1，C题未评rev1 | `eca8d3aa38a0ffda77c522f7effa1c17d6787f062b6b04fe4e3cc3e80f49d3d7` |

## 普通应用与硬件用例

| 稳定编号 | 适用版本／准备条件 | 具体操作 | 预期／所需证据 | 当前结果 |
| --- | --- | --- | --- | --- |
| SR-01 | 04，同一三页合成PDF，已记录包身份 | 摘录→内容卡→整理→折叠→回源→关联→当前卡／分支回忆→原节点／原页 | 对象连续；分段录屏有明确衔接、时间与原状态 | 47–60文本／图像回忆及两段普通录屏接受；B多行中文见51／53，另有大字体／长标题／IME。04b普通修复已复验；动效视频与终态恢复清理已通过 |
| SR-11 | 03／04，合成同级与子树、共享卡 | 上移／下移、拖动、重开、大纲／导出、同级新增 | 子树同行，顺序不由xy／随机ID重排；作者摘要与录屏 | 03已录上移／撤销／重开等步骤和04相关UI PASS；未录下移／导出等普通子步骤保留NOT_RUN |
| SR-12 | 混合结构与卡片、折叠／聚焦状态 | 缩进、提升、移入、自身／后代拒绝、重开 | 身份／内容不变，合法一次提交，拒绝零部分写入 | 03缩进等已录步骤和04相关UI PASS；未录提升／移入等普通子步骤保留NOT_RUN |
| SR-13 | 文本／图片／长中文、手工位置、折叠后代 | 预览→取消；预览→应用→撤销→重开 | 实际尺寸、取消零写、恢复几何与视口；旧提示应清 | 04布局UI PASS；图45历史PARTIAL保留，04b图73撤销提示清除、74重开已PASS |
| SR-14 | 04，REFERENCE／APPLICATION等关系，重复可见位置 | 默认off→开→切选中→范围外预览→返回→关 | 父子线与知识线分开，方向／类型／原Link身份不变，显式mode不污染旧入口 | 04自动方法及47／48默认off与B关系线PASS；61／62历史PARTIAL保留；04b的64首次出向和65–67固定计数行／0px位移已PASS |
| SR-15 | 04，文本卡2题、图表卡1题、文本分支3题 | 当前卡进入→揭答／回源→同题→结束；节点入口；分支／整本回归 | 固定问答版本，范围正确，原详情滚动／视口保留；只读步骤无评分 | 49–60文本与图像答案普通观察、两段回忆录屏及自动方法PASS；同PDF合成对象核验确认只有B两题明确写入，C未评 |
| SR-16-A | 04b，记录系统正常动画比例 | 折叠／展开、关系切换、布局应用 | 短局部反馈且最终状态正确；视频＋作者摘要 | PASS_FOR_RECORDED_STEPS；正常录像235原生帧已接受 |
| SR-16-B | 04b，记录并临时设系统动画为0 | 重复同动作，结束恢复原值 | 即时终态、不等待动画；比例及恢复回执 | PASS_FOR_RECORDED_STEPS；独立零动画录像31原生帧和设置恢复已接受 |
| SR-16-C | 04b，已加载完整图与关系 | 快速连点折叠／展开／开关、切选中 | 最后状态生效，不排队、无旧轮廓残留、无重复写入 | PASS_FOR_RECORDED_STEPS；10次ADB点击1.531s及末态、后续平移录像已接受 |
| SR-16-D | 04b，反馈正在进行 | 新触摸、拖动画布／节点及取消触控 | 旧反馈中断、当前手势接管、不吞事件；清除旧提交提示 | 录制的折叠后平移步骤PASS；精确中断时点和输入延迟NOT_RUN |
| SR-07／LD-08 | 04，375dp／大字／真实IME／横竖屏 | 按场景编辑／预览／回源／返回；系统分屏另记 | 全按钮可达、输入框不遮挡；截图＋实际dp／字号 | 既有横竖屏／OEM分屏／IME证据保留；04图51／53多行中文与UI375dp／大字体方法PASS |
| SR-08／MUI-12／NF39-07 | 备用设备或可丢弃隔离库 | 提交前后／未知回执／ABA旧撤销／OS死亡 | 原operation一次回执，无部分图；真实OS死亡需旧／新PID | 03隔离方法PASS；未执行真机故障NOT_RUN |
| SR-10／NF39-06／T43 | 合成备份及隔离容量目标 | 模板／独立副本／恢复；128活动／256总／200卡边界 | 顺序与身份映射正确，越界无截断／孤立记录 | 03相关Room覆盖；未执行全部组合NOT_RUN |
| SR-HW-01／LD-HW-01 | 真人兼容笔、真实外接键盘 | 原15分钟笔感、压感／倾斜／掌拒／温升；Tab／Shift+Tab／Enter | 真人视频／操作说明，记录实际硬件；记录真人输入方式 | NOT_RUN，留待真人硬件执行 |
| LD-CAP-01 | 原能力边界 | 未识别手写的自动链接发现 | 自动识别与正式关系分开记录 | NOT_IMPLEMENTED |

SR-16-A～D是SR-16子步骤，不另重编号已有稳定用例。每个完成项均保留适用包、真实操作方式和具体覆盖范围。

## 单次结果记录

| 字段 | 填写内容 |
| --- | --- |
| case／method与子步骤 | 待填稳定用例编号及子步骤 |
| 日期／开始结束／时区 | 待填开始／结束时间及时区 |
| 设备与输入方式 | 实体／模拟器明确；真人手工、普通应用ADB、无辅助instrumentation、一次初始前台辅助、native派发分别填写 |
| 包与源码身份 | 主包、runner、证书SHA；基点＋补丁，或最终提交；记录完整SHA和基点／补丁 |
| 准备条件 | 合成对象别名、PDF SHA、选中／折叠／viewport、设置原值；不用真实标题或私人正文 |
| 实际操作与期望 | 待填实际步骤与预期 |
| 实际结果 | PASS／FAIL／NOT_RUN／PARTIAL；ACTIVE不是最终结果，NOT_IMPLEMENTED另记能力状态 |
| 失败归因 | 产品／测试助手／环境／构建前拒绝；写已证实事实，不用猜测取代原状态 |
| 修复与复验 | 原attempt保留；新包和新方法回执单列 |
| 证据 | 已接受合成截图的相对路径、合成视频SHA、脱敏回执ID；不附原始私人日志 |
| 作者资料与设置 | 待填作者资料与设置恢复核对 |
| 终态核对与清理 | 待填本次终态核对与清理 |

## 本批最终结果与待测范围

- 已完成04：Core317、UI26、Room37及lint141既有Warning；普通47–60、两段回忆视频和10张原生帧；同PDF合成对象核验只见B两道显式评分变化
- 04b后终态脱敏审计：原175本6074行原值保持；当前395本13556行，新增220本7482行全归新合成笔记。C节点4次明确布局操作后revision变化，最终id／parent／xy一致；最终恢复后复核一致，同PDF合成对象核验 PASS；383XML＋6MP4临时文件先归档匹配SHA再删除，remaining0
- 04b构建／lint与UI8已PASS；普通64、65–67和73完成方向／折叠布局／旧提示修复复验；61／62／45历史PARTIAL保留
- 普通反馈：正常／快速与后续平移、独立零动画两段录像的原生帧已接受；精确输入延迟／毫秒级中断时点NOT_RUN。图45由04b图73关闭对应问题
- 最终恢复已通过：原6074行保持／13556当前行、新220本7482行全归属；35偏好＋69外部文件逐SHA匹配；14变化项先归档再恢复（5偏好＋9旧证据），新文件删除0；12设置回原值、主应用stopped。原162未提交文件及V68／PR11旧目录保持
- 首次恢复guard发现两张额外LocalMotion旧图，在任何写入前中止；核对源码扩展白名单后独立尝试通过，原失败归档保留
- 同PDF合成对象核验 PASS：3卡／3来源／6知识记录／9历史修订保持；3节点id／card／parent／xy保持，仅C revision5→9，对应四次明确布局操作。回执SHA `d1ad13dbd1b2078b42b8e52a3f5af72dc1ccc7dd78b2faaa8b0f224bd74d5fb9`
- 设备临时383XML＋6MP4先tar归档、SHA匹配后删除，remaining0；本机证据／APK／工具链保留，本任务无__pycache__杂项
- 源码核对PASS：783冻结输入中546非文档输入逐SHA保持，仅7授权文档变化；回执SHA `ce952435c4c60b7c13b7aadae2ab4055a9be727aab5e6d53374c2d404b23bd64`
- Git与交付：随本报告提交的版本；PR和外部发布状态由最终回复提供。公开目录仅源码／合成测试／脱敏报告和已接受合成图

最终统计只汇总同一明确范围和版本；构建、Core、Room、UI、普通观察、真人硬件分别计数。旧link-discovery报告保持历史身份。
