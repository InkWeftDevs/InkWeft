# 第三轮存储优化：实际设备结果

**最终结果：86/86 PASS，0 失败、缺失或跳过。** 新对象存储 8 项、编辑快照 6 项、点格式和相关对象/备份/图层/迁移整合均已实际运行。普通产品 UI、物理设备和真实手写性能仍待测。

## 构件与环境

- 基点：V86 `8596a981a76e27c38caf5220659e0e238141d319` 加本轮未提交补丁；Room17。
- 独立 package/targetPackage：`org.inkweft.data.storageopt20261011b3r2.test`；runner：`androidx.test.runner.AndroidJUnitRunner`。
- APK：12,689,842 字节，SHA-256 `de318d6c209f184166cd6f2bd46eabf11670714d81c2422ad5a52b02d58030f5`；签名和对齐通过。
- 55 个冻结构建输入清单：`build-inputs-r2.json`，SHA-256 `2efbc1062e144bc8119b103b6f31f937a0b5949971c3f0c7cba4c6a0b226c134`。
- MuMu 实例 5 `InkWeft-Test`，Android 12 / API32，主 ABI x86_64；模拟器上报的 Samsung/SM-S9260 不是物理设备型号证据。root 始终为 false，shell uid=2000。
- 只安装空白独立测试包，各方法创建并删除自己的临时数据库；没有产品包、旧笔记或既有压测页操作。
- 执行区间：2026-10-11T09:40:41.167159+00:00 至 2026-10-11T09:43:06.382804+00:00；runner 145.233 秒。时间是测试执行记录，不是性能收益。

## 准确范围

| 类/选择 | 方法数 | 结果 |
|---|---:|---|
| `PageObjectStorageTest` | 8 | PASS |
| `PageObjectRepositoryTest` | 6 | PASS |
| `InkRepositoryTest` | 11 | PASS |
| `InkCheckpointTest` | 2 | PASS |
| `InkEditingSnapshotTest` | 6 | PASS |
| `StorageHotPathRepositoryTest` | 6 | PASS |
| `LibraryBackupRepositoryTest` | 19 | PASS |
| `PageAuthoringRepositoryTest` | 10 | PASS |
| `AuthoringProjectionRegressionTest` | 3 | PASS |
| `SearchIndexFreshnessTest` | 2 | PASS |
| `PageEditingRepositoryTest` | 5 | PASS |
| `StudyAndSelectionRepositoryTest` | 2 | PASS |
| `CardTransformRepositoryTest` | 2 | PASS |
| `RecallStudyRepositoryTest` | 1 | PASS |
| `LibraryContentRepositoryTest` | 3 | PASS |

备份边界方法使用既有 `StudyCapacity.MAX_SNAPSHOT_BYTES=256,000,000`：128 MB＋128 MB＋1 byte 的合并恢复拒绝，减少 1 byte 到精确边界后恢复成功，重复确认保持相同内容。没有修改任何产品容量常量。

## 首轮失败与修复

首轮 APK `f877d0383af896f3bfa75167eb7476db7aedf0b41410b0a585a1911f6f8a7879` 实际执行 85 项，81 通过、4 失败。`device-attempt01`、首轮源码/构建输入和 APK 均保留，未用修正版证据覆盖。

| 失败 | 原因与修复 | 最终核对 |
|---|---|---|
| 对象身份投影 | 测试将不实现值相等的 `PageAuthoring` 对象直接比较；改为编码字节和版次比较 | 原对象与预览坏载荷仍跳过，完整读取仍拒绝，PASS |
| 恢复后重做掩码 | 保存 `Cut` / `CutVisibility` 时仍完整读取无关隐藏笔迹 | 改为目标可见笔迹校验；新增逐笔掩码预算拒绝且不发布回执，6 个编辑快照方法 PASS |
| 备份合并预算 | 旧测试仍把 32 MB 当作当前上限，HEAD 的实际常量已是 256 MB | 测试输入跟随当前常量，精确边界/超出 1 byte/重放 PASS |
| schema13 源卡备份 | 测试在空对象表上仍查询已移除的整页 payload 列 | 明确核对空对象表；非空旧对象由真实 schema16 库/归档提升方法覆盖，两者 PASS |

## 清理与边界

本批于 2026-10-11T09:43:46.409448+00:00 完成清理：独立测试包已卸载，MuMu5 已关闭，root=false，旋转设置仍为 1/0，private ADB 55095 已结束。首轮也已单独清理。之后设备交回主任务执行应用层编辑器验证。

主任务随后以相同 55 个冻结输入完成应用层 `StreamedPageLifecycleTest` **5/5 PASS**，并于 2026-10-11T09:45:41.934727+00:00 确认两个隔离身份卸载、MuMu5 关闭/root=false/旋转保持、private ADB 55093 停止。其构件与逐方法结果见[编辑器验证](../storage-optimization-20261011-editor/README.md)。本页的 86 项仍单独计为 Room/Android 平台集成；应用生命周期断言也不替代普通 UI/物理设备。真实图像、密集页首帧/内存、手写输入与物理断电仍按 SO3-* 清单待测。

## 原始证据

本机归档：`E:/Inkweft/archives/2026-10-11/InkWeft-Storage-Optimization/batch-03/verification/device-attempt02/`。包含安装前身份、包不存在核对、安装、实际命令、原始 instrumentation、逐行 UTC 事件、执行结束、解析断言、卸载与清理回执。

- `instrumentation.txt` SHA-256：`72de98eac2b2bb696dc7e0dea113e7ebd5b688c178e21bc1f6c9448dd21b0268`。
- `events.jsonl` SHA-256：`ef1b21a965a484d0a615641d8380dfd98f03855f200dd1a7c29ec4c0cbcf7445`。
- parser 要求所有 86 个方法各有一次开始和 code=0 结束，集合与选择完全一致，runner 为 `OK (86 tests)`，最终 code=-1，host exit=0。

## 逐方法 UTC 记录

开始/结束时间由主机接收 runner 行时记录，仅用于审计。

| 方法 | 开始 UTC | 结束 UTC | 结果 |
|---|---|---|
| `PageObjectStorageTest#authoringIdentityProjectionSkipsCorruptObjectAndPreviewPayloadsButFullReadsReject` | 2026-10-11T09:40:41.167159+00:00 | 2026-10-11T09:40:41.366090+00:00 | PASS |
| `PageObjectStorageTest#corruptSchema16ObjectAbortsMigrationAndKeepsTheOriginalDatabaseIntact` | 2026-10-11T09:40:41.366090+00:00 | 2026-10-11T09:40:42.018797+00:00 | PASS |
| `PageObjectStorageTest#schema16AndItsArchivePromoteObjectsWithOrderIdentityAndReceiptUnchanged` | 2026-10-11T09:40:42.019778+00:00 | 2026-10-11T09:40:42.390939+00:00 | PASS |
| `PageObjectStorageTest#concurrentSnapshotsAndObserversKeepRevisionOrderMetadataAndPreviewTogether` | 2026-10-11T09:40:42.391921+00:00 | 2026-10-11T09:40:42.937008+00:00 | PASS |
| `PageObjectStorageTest#movingOneObjectWritesOneRecordAndSharedPreviewLivesUntilItsLastReference` | 2026-10-11T09:40:42.937997+00:00 | 2026-10-11T09:40:42.998915+00:00 | PASS |
| `PageObjectStorageTest#sharedPreviewRestoreMergesByContentAndAnInjectedFailureRollsBackAllNewRows` | 2026-10-11T09:40:42.999901+00:00 | 2026-10-11T09:40:43.274857+00:00 | PASS |
| `PageObjectStorageTest#deduplicatedImagesStillRespectTheExistingAggregatePageBudget` | 2026-10-11T09:40:43.275840+00:00 | 2026-10-11T09:40:43.303161+00:00 | PASS |
| `PageObjectStorageTest#receiptFailureRollsBackHeadChangedRowsNewPreviewAndAuthoringTogether` | 2026-10-11T09:40:43.305119+00:00 | 2026-10-11T09:40:43.339285+00:00 | PASS |
| `PageObjectRepositoryTest#originalSaveReplayCopyBackupAndMissingAssetAreAtomic` | 2026-10-11T09:40:43.339285+00:00 | 2026-10-11T09:40:43.768922+00:00 | PASS |
| `PageObjectRepositoryTest#schema12UpgradesWithoutRewritingExistingAuthorRows` | 2026-10-11T09:40:43.768922+00:00 | 2026-10-11T09:40:43.883221+00:00 | PASS |
| `PageObjectRepositoryTest#backupRestoresObjectsAndReceipts` | 2026-10-11T09:40:43.883221+00:00 | 2026-10-11T09:40:44.025207+00:00 | PASS |
| `PageObjectRepositoryTest#invalidBoundsAndRecycledPageCannotBeWritten` | 2026-10-11T09:40:44.025207+00:00 | 2026-10-11T09:40:44.053082+00:00 | PASS |
| `PageObjectRepositoryTest#unknownCommitCanReplayAndStaleWriterCannotOverwrite` | 2026-10-11T09:40:44.053082+00:00 | 2026-10-11T09:40:44.083925+00:00 | PASS |
| `PageObjectRepositoryTest#duplicateAndContentImportCarryIndependentObjects` | 2026-10-11T09:40:44.083925+00:00 | 2026-10-11T09:40:44.125971+00:00 | PASS |
| `InkRepositoryTest#sameCommandReplaysAndDifferentPayloadRejected` | 2026-10-11T09:40:44.126973+00:00 | 2026-10-11T09:40:44.157972+00:00 | PASS |
| `InkRepositoryTest#strokePersistsAcrossActualDatabaseReopen` | 2026-10-11T09:40:44.157972+00:00 | 2026-10-11T09:40:44.185745+00:00 | PASS |
| `InkRepositoryTest#v1DatabaseMigratesWithoutLosingOriginalTextAndReceipts` | 2026-10-11T09:40:44.187250+00:00 | 2026-10-11T09:40:44.253052+00:00 | PASS |
| `InkRepositoryTest#mixedStoredFormatsReopenAndReplayWithUnchangedCanonicalReceipts` | 2026-10-11T09:40:44.253052+00:00 | 2026-10-11T09:40:44.296267+00:00 | PASS |
| `InkRepositoryTest#eraseAndUndoRetainExactlyOriginalBytes` | 2026-10-11T09:40:44.297247+00:00 | 2026-10-11T09:40:44.328421+00:00 | PASS |
| `InkRepositoryTest#twoDatabaseInstancesDoNotOverwriteSameBase` | 2026-10-11T09:40:44.328421+00:00 | 2026-10-11T09:40:44.355865+00:00 | PASS |
| `InkRepositoryTest#importedPageIsNewIdentityAndDoesNotOverwriteText` | 2026-10-11T09:40:44.356842+00:00 | 2026-10-11T09:40:44.380081+00:00 | PASS |
| `InkRepositoryTest#commitThenLostReceiptReconcilesByOriginalCommand` | 2026-10-11T09:40:44.383062+00:00 | 2026-10-11T09:40:44.407986+00:00 | PASS |
| `InkRepositoryTest#previewSkipsHiddenPayloadWhileFullReadStillValidatesHistory` | 2026-10-11T09:40:44.408962+00:00 | 2026-10-11T09:40:44.441248+00:00 | PASS |
| `InkRepositoryTest#visiblePreviewMatchesFullReadAfterCutsReplacementAndUndo` | 2026-10-11T09:40:44.442217+00:00 | 2026-10-11T09:40:44.489191+00:00 | PASS |
| `InkRepositoryTest#failureBeforeReceiptRollsBackInkAndPageRevision` | 2026-10-11T09:40:44.489191+00:00 | 2026-10-11T09:40:44.511080+00:00 | PASS |
| `InkCheckpointTest#crossPageStorageSliceKeepsPageIdentityAndPersistentCancellation` | 2026-10-11T09:40:44.512065+00:00 | 2026-10-11T09:40:44.563269+00:00 | PASS |
| `InkCheckpointTest#checkpointCrashCutsKeepOneIdentityAndSealDeduplicates` | 2026-10-11T09:40:44.564252+00:00 | 2026-10-11T09:40:44.605838+00:00 | PASS |
| `InkEditingSnapshotTest#resumeLoadsOnlyHistoryIdsAndPreservesReplacementMaskUndoRedo` | 2026-10-11T09:40:44.606849+00:00 | 2026-10-11T09:40:44.657034+00:00 | PASS |
| `InkEditingSnapshotTest#enteringPageSkipsUnrelatedHiddenInkAndMasksButPreservesTheirBudgets` | 2026-10-11T09:40:44.658009+00:00 | 2026-10-11T09:40:44.696830+00:00 | PASS |
| `InkEditingSnapshotTest#selectedGeometryQueriesCrossSqliteBindChunksWithoutReordering` | 2026-10-11T09:40:44.696830+00:00 | 2026-10-11T09:40:44.801084+00:00 | PASS |
| `InkEditingSnapshotTest#restoringMaskRejectsPerStrokeOverflowWithoutPublishingRevisionOrReceipt` | 2026-10-11T09:40:44.801084+00:00 | 2026-10-11T09:40:44.842204+00:00 | PASS |
| `InkEditingSnapshotTest#invalidHiddenMetadataAndBrokenMaskReferencesPreventEditingSnapshot` | 2026-10-11T09:40:44.842204+00:00 | 2026-10-11T09:40:44.867125+00:00 | PASS |
| `InkEditingSnapshotTest#changedRevisionDoesNotReadOrRestoreStaleHistoryGeometry` | 2026-10-11T09:40:44.868106+00:00 | 2026-10-11T09:40:44.892882+00:00 | PASS |
| `StorageHotPathRepositoryTest#movingImagesPreservesCasAndDamagedNewOrStoredPreviewsAreRejected` | 2026-10-11T09:40:44.893857+00:00 | 2026-10-11T09:40:44.933526+00:00 | PASS |
| `StorageHotPathRepositoryTest#backupSyncReleasesTheWriterWithoutChangingItsCapturedSnapshot` | 2026-10-11T09:40:44.937077+00:00 | 2026-10-11T09:40:45.015234+00:00 | PASS |
| `StorageHotPathRepositoryTest#sourceOwnershipUsesIdsAndStillRejectsForeignOrRepeatedReferences` | 2026-10-11T09:40:45.016219+00:00 | 2026-10-11T09:40:45.079131+00:00 | PASS |
| `StorageHotPathRepositoryTest#failedFinalSnapshotSyncDoesNotPublishOrLeaveItsFile` | 2026-10-11T09:40:45.080116+00:00 | 2026-10-11T09:40:45.113815+00:00 | PASS |
| `StorageHotPathRepositoryTest#sourceChangedAfterValidationRollsBackTheWholeImportAndReceipt` | 2026-10-11T09:40:45.113815+00:00 | 2026-10-11T09:40:45.239886+00:00 | PASS |
| `StorageHotPathRepositoryTest#sharedPdfIsValidatedBeforeTransactionAndRehashedOnceDuringAtomicImport` | 2026-10-11T09:40:45.240863+00:00 | 2026-10-11T09:40:45.283969+00:00 | PASS |
| `LibraryBackupRepositoryTest#trashAndStaleIndexArePreservedButStaleTextIsNotSearchable` | 2026-10-11T09:40:45.284940+00:00 | 2026-10-11T09:40:45.449481+00:00 | PASS |
| `LibraryBackupRepositoryTest#cancellingWrittenSnapshotRemovesPartialAndPreservesAuthorLibrary` | 2026-10-11T09:40:45.450985+00:00 | 2026-10-11T09:40:48.890240+00:00 | PASS |
| `LibraryBackupRepositoryTest#streamReadFailureOnlyAffectsIsolatedStage` | 2026-10-11T09:40:48.892229+00:00 | 2026-10-11T09:40:48.993232+00:00 | PASS |
| `LibraryBackupRepositoryTest#allSupportedArchiveSchemasStillStageIntoTheCurrentRoomContract` | 2026-10-11T09:40:48.994209+00:00 | 2026-10-11T09:40:49.541430+00:00 | PASS |
| `LibraryBackupRepositoryTest#reorderedCompositePrimaryKeyCannotChangeCanonicalBackupOrder` | 2026-10-11T09:40:49.541430+00:00 | 2026-10-11T09:40:49.559141+00:00 | PASS |
| `LibraryBackupRepositoryTest#restoredRecordsRemainAfterRealCloseAndReopen` | 2026-10-11T09:40:49.560124+00:00 | 2026-10-11T09:40:49.692496+00:00 | PASS |
| `LibraryBackupRepositoryTest#preCommitFailureRollsBackAllLiveTables` | 2026-10-11T09:40:49.692989+00:00 | 2026-10-11T09:40:49.841685+00:00 | PASS |
| `LibraryBackupRepositoryTest#lostAcknowledgementReusesOriginalIdentities` | 2026-10-11T09:40:49.842190+00:00 | 2026-10-11T09:40:50.004785+00:00 | PASS |
| `LibraryBackupRepositoryTest#legalSnapshotArchivesRejectMergedOverflowAndReplayAtExactCapacity` | 2026-10-11T09:40:50.005758+00:00 | 2026-10-11T09:43:00.709836+00:00 | PASS |
| `LibraryBackupRepositoryTest#disjointExistingLibraryIsRetainedAndRepeatedRestoreIsNoOp` | 2026-10-11T09:43:00.715770+00:00 | 2026-10-11T09:43:00.897254+00:00 | PASS |
| `LibraryBackupRepositoryTest#compatibleTextAffinitySpellingIsStillAccepted` | 2026-10-11T09:43:00.897254+00:00 | 2026-10-11T09:43:00.930941+00:00 | PASS |
| `LibraryBackupRepositoryTest#sameNamedColumnsWithDifferentNullabilityCannotBeBackedUp` | 2026-10-11T09:43:00.931923+00:00 | 2026-10-11T09:43:00.953778+00:00 | PASS |
| `LibraryBackupRepositoryTest#unknownTablesCannotBeSilentlyOmitted` | 2026-10-11T09:43:00.954785+00:00 | 2026-10-11T09:43:00.990967+00:00 | PASS |
| `LibraryBackupRepositoryTest#sameNamedColumnsWithDifferentAffinityCannotBeCoercedIntoBackup` | 2026-10-11T09:43:00.990967+00:00 | 2026-10-11T09:43:01.015215+00:00 | PASS |
| `LibraryBackupRepositoryTest#changedIdentityStopsEntireRestoreWithoutOverwritingNewWork` | 2026-10-11T09:43:01.016200+00:00 | 2026-10-11T09:43:01.175170+00:00 | PASS |
| `LibraryBackupRepositoryTest#roundTripPreservesOriginalIdsHiddenInkMasksHistoryAndMetadata` | 2026-10-11T09:43:01.177170+00:00 | 2026-10-11T09:43:01.324201+00:00 | PASS |
| `LibraryBackupRepositoryTest#orphanHistoricalReferencesPreventExport` | 2026-10-11T09:43:01.325171+00:00 | 2026-10-11T09:43:01.367842+00:00 | PASS |
| `LibraryBackupRepositoryTest#damagedFooterNeverTouchesLiveLibrary` | 2026-10-11T09:43:01.370809+00:00 | 2026-10-11T09:43:01.472849+00:00 | PASS |
| `LibraryBackupRepositoryTest#emptyBackupDoesNotEraseExistingData` | 2026-10-11T09:43:01.473825+00:00 | 2026-10-11T09:43:01.586002+00:00 | PASS |
| `PageAuthoringRepositoryTest#scopedCheckpointRoundTripPreservesLayerAcrossReopen` | 2026-10-11T09:43:01.588033+00:00 | 2026-10-11T09:43:01.638949+00:00 | PASS |
| `PageAuthoringRepositoryTest#capturedScopeRejectsStaleOrMissingLayerButConsecutivePensDoNotConflict` | 2026-10-11T09:43:01.639935+00:00 | 2026-10-11T09:43:01.679089+00:00 | PASS |
| `PageAuthoringRepositoryTest#sourceCaptureRejectsChangedOrHiddenAuthoringButReplaysConfirmedReceipt` | 2026-10-11T09:43:01.679089+00:00 | 2026-10-11T09:43:01.719972+00:00 | PASS |
| `PageAuthoringRepositoryTest#oldReceiptReplaysAfterLayerLockAndStaleMetadataCannotEraseNewMembership` | 2026-10-11T09:43:01.719972+00:00 | 2026-10-11T09:43:01.755607+00:00 | PASS |
| `PageAuthoringRepositoryTest#removedStructuralOccurrenceKeepsAnnotationOwnerThroughCompleteBackup` | 2026-10-11T09:43:01.756283+00:00 | 2026-10-11T09:43:01.930937+00:00 | PASS |
| `PageAuthoringRepositoryTest#source14ArchiveIsNotPromotedTwiceAfterAddingLayerTables` | 2026-10-11T09:43:01.931927+00:00 | 2026-10-11T09:43:02.071982+00:00 | PASS |
| `PageAuthoringRepositoryTest#nonemptyLayerDeletionUndoBackupAndEditableCopyKeepHiddenPayloads` | 2026-10-11T09:43:02.073936+00:00 | 2026-10-11T09:43:02.255853+00:00 | PASS |
| `PageAuthoringRepositoryTest#hiddenLockedMembershipPersistsAndWholeMixedEditIsRejected` | 2026-10-11T09:43:02.255853+00:00 | 2026-10-11T09:43:02.296949+00:00 | PASS |
| `PageAuthoringRepositoryTest#rollbackAndUnknownReceiptKeepLayerAndInkAtomic` | 2026-10-11T09:43:02.298455+00:00 | 2026-10-11T09:43:02.340777+00:00 | PASS |
| `PageAuthoringRepositoryTest#lateSearchIndexCannotRepublishWordsFromAHiddenLayer` | 2026-10-11T09:43:02.340777+00:00 | 2026-10-11T09:43:02.372208+00:00 | PASS |
| `AuthoringProjectionRegressionTest#projectedIdsIncludeHistoryButFullInkAndObjectReadsStillValidatePayloads` | 2026-10-11T09:43:02.373199+00:00 | 2026-10-11T09:43:02.403911+00:00 | PASS |
| `AuthoringProjectionRegressionTest#firstStrokePast22000RetainedRowsCommitsAndKeepsHiddenAuthorBytes` | 2026-10-11T09:43:02.407860+00:00 | 2026-10-11T09:43:04.468216+00:00 | PASS |
| `AuthoringProjectionRegressionTest#bulkReplacementInheritsEachSourceLayerAndLockedBatchRemainsAtomic` | 2026-10-11T09:43:04.469179+00:00 | 2026-10-11T09:43:05.091116+00:00 | PASS |
| `SearchIndexFreshnessTest#freshnessFollowsInkObjectLayerAndPageChanges` | 2026-10-11T09:43:05.092101+00:00 | 2026-10-11T09:43:05.138888+00:00 | PASS |
| `SearchIndexFreshnessTest#checkingIndexNeverDecodesPayloadWhileFullObjectReadStillValidates` | 2026-10-11T09:43:05.139874+00:00 | 2026-10-11T09:43:05.164198+00:00 | PASS |
| `PageEditingRepositoryTest#copyDoesNotReviveHiddenStrokesOrStaleIndex` | 2026-10-11T09:43:05.165177+00:00 | 2026-10-11T09:43:05.210797+00:00 | PASS |
| `PageEditingRepositoryTest#schemaSixMigrationPreservesOriginalInkAndPages` | 2026-10-11T09:43:05.211779+00:00 | 2026-10-11T09:43:05.311796+00:00 | PASS |
| `PageEditingRepositoryTest#copyHasIndependentIdsAndPreservesEffectiveCuts` | 2026-10-11T09:43:05.312778+00:00 | 2026-10-11T09:43:05.353977+00:00 | PASS |
| `PageEditingRepositoryTest#schemaSixArchiveIsPromotedWithoutChangingOriginalIdentity` | 2026-10-11T09:43:05.354982+00:00 | 2026-10-11T09:43:05.482849+00:00 | PASS |
| `PageEditingRepositoryTest#backupRestoresRecycledPagesAndActionReceipts` | 2026-10-11T09:43:05.484969+00:00 | 2026-10-11T09:43:05.631130+00:00 | PASS |
| `StudyAndSelectionRepositoryTest#migrationSevenToEightRetainsOriginalInkAndPageIdentity` | 2026-10-11T09:43:05.635130+00:00 | 2026-10-11T09:43:05.729770+00:00 | PASS |
| `StudyAndSelectionRepositoryTest#missingAndForeignInkTargetsConflictWithoutChangingInkOrLayers` | 2026-10-11T09:43:05.731286+00:00 | 2026-10-11T09:43:05.786128+00:00 | PASS |
| `CardTransformRepositoryTest#schema13BackupPromotionAndCorruptSourceReferenceAreCheckedBeforeLiveWrites` | 2026-10-11T09:43:05.787134+00:00 | 2026-10-11T09:43:05.936884+00:00 | PASS |
| `CardTransformRepositoryTest#schema13MigrationPreservesLegacyBodyAndOnlyFreezesSurvivingSource` | 2026-10-11T09:43:05.938919+00:00 | 2026-10-11T09:43:06.100096+00:00 | PASS |
| `RecallStudyRepositoryTest#schema15MigrationAddsRecallWithoutRewritingAuthorInkQuestionOrAnnotation` | 2026-10-11T09:43:06.101079+00:00 | 2026-10-11T09:43:06.272047+00:00 | PASS |
| `LibraryContentRepositoryTest#copyUnknownThenSourceChangesReplaysOriginalCopy` | 2026-10-11T09:43:06.273037+00:00 | 2026-10-11T09:43:06.303908+00:00 | PASS |
| `LibraryContentRepositoryTest#copyBeforeReceiptFaultLeavesNoPartialBook` | 2026-10-11T09:43:06.304885+00:00 | 2026-10-11T09:43:06.336803+00:00 | PASS |
| `LibraryContentRepositoryTest#copyRetainsPagesMasksCoverTagsAndValidManualIndexWithNewIdentity` | 2026-10-11T09:43:06.336803+00:00 | 2026-10-11T09:43:06.382804+00:00 | PASS |
