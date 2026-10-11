# 第二轮存储优化 · Android 整合结果

2026-10-11，专用 MuMu5 模拟器 / Android 12 API32，主 ABI x86_64；8 项新增及 15 项相关检查 **23/23 PASS，0 失败、0 跳过**。本轮只使用独立测试包中的合成资料。

- 基点：V86 `8596a981a76e27c38caf5220659e0e238141d319`＋前两轮存储补丁；生产与测试源码在设备运行期间保持冻结。
- APK SHA-256：`4943d49541b3c2c7bcf50d9ee3fce941b6f6dca0be2c9b129cbe477d244d43b5`；15,383,237 字节。
- package/target：`org.inkweft.data.storageopt20261011b2.test`；runner：`androidx.test.runner.AndroidJUnitRunner`。
- APK v2 签名及 ZIP 对齐 PASS；证书 SHA-256：`345a1e67b72623000c611b0a17e70a8fdf1438768ec79a1144c407c28c14d2f2`。
- 原始结尾：`Time: 286.179`、`OK (23 tests)`、`INSTRUMENTATION_CODE: -1`。每项恰有一次开始状态 1 与一次成功结束状态 0。

下表方法均在 `org.inkweft.data` 包；UTC 为主机接收日志时间，北京时间为 UTC＋8，不作为设备性能测量。

| class#method | 开始 UTC | 结束 UTC | 结果 |
|---|---|---|---|
| `TransferReadPathRepositoryTest#changedOriginalRollsBackCopyAndCommittedReplaySkipsUnavailableSource` | 08:52:20.729525 | 08:52:21.088818 | PASS |
| `TransferReadPathRepositoryTest#newUnvalidatedOriginalAfterCopyPreparationCannotBypassFormatChecks` | 08:52:21.088818 | 08:52:21.157733 | PASS |
| `TransferReadPathRepositoryTest#snapshotRevisionAndVisibilityStayConsistentWithConcurrentEdits` | 08:52:21.158717 | 08:52:21.458297 | PASS |
| `TransferReadPathRepositoryTest#relevantDamagedMasksAndForeignTargetsStillFail` | 08:52:21.459817 | 08:52:21.558261 | PASS |
| `TransferReadPathRepositoryTest#visibleAndSelectedSnapshotsSkipUnrelatedBlobsButKeepEffectiveMasks` | 08:52:21.558261 | 08:52:21.763872 | PASS |
| `TransferReadPathRepositoryTest#selectedSourceCaptureKeepsFixedRevisionAndRejectsMissingOrChangedInk` | 08:52:21.764974 | 08:52:21.870857 | PASS |
| `TransferReadPathRepositoryTest#copyPreparationReleasesWriterAndCommitIncludesCurrentSourceAndNewOriginal` | 08:52:21.870857 | 08:52:21.975862 | PASS |
| `TransferReadPathRepositoryTest#copiesAndVisibleExportsKeepMasksAxesAndCurrentManualIndexWithoutHiddenHistory` | 08:52:21.975862 | 08:52:22.076704 | PASS |
| `LibraryContentRepositoryTest#boardExportAndCopyRemainWorldWithEffectiveMasks` | 08:52:22.077686 | 08:52:22.111505 | PASS |
| `LibraryContentRepositoryTest#copyUnknownThenSourceChangesReplaysOriginalCopy` | 08:52:22.112491 | 08:52:22.150646 | PASS |
| `LibraryContentRepositoryTest#copyBeforeReceiptFaultLeavesNoPartialBook` | 08:52:22.150646 | 08:52:22.176890 | PASS |
| `LibraryContentRepositoryTest#copyRetainsPagesMasksCoverTagsAndValidManualIndexWithNewIdentity` | 08:52:22.176890 | 08:52:22.218865 | PASS |
| `PageEditingRepositoryTest#copyDoesNotReviveHiddenStrokesOrStaleIndex` | 08:52:22.218865 | 08:52:22.263577 | PASS |
| `PageEditingRepositoryTest#copyHasIndependentIdsAndPreservesEffectiveCuts` | 08:52:22.263577 | 08:52:22.306234 | PASS |
| `PageEditingRepositoryTest#staleOrderAndSourceHeadRefuseWithoutChanges` | 08:52:22.307739 | 08:52:22.357559 | PASS |
| `StudyAndSelectionRepositoryTest#excerptSnapshotIsImmutableAfterSourceEraseAndPageMove` | 08:52:22.358566 | 08:52:22.395696 | PASS |
| `StudyAndSelectionRepositoryTest#staleSourceRejectsWholeCardTransaction` | 08:52:22.395696 | 08:52:22.421498 | PASS |
| `KnowledgeRepositoryTest#regionLinkNeedsNoCardAndKeepsOriginalInk` | 08:52:22.422478 | 08:52:22.447761 | PASS |
| `PageAuthoringRepositoryTest#sourceCaptureRejectsChangedOrHiddenAuthoringButReplaysConfirmedReceipt` | 08:52:22.448781 | 08:52:22.490568 | PASS |
| `PageAuthoringRepositoryTest#nonemptyLayerDeletionUndoBackupAndEditableCopyKeepHiddenPayloads` | 08:52:22.491573 | 08:52:22.717629 | PASS |
| `StorageHotPathRepositoryTest#sourceChangedAfterValidationRollsBackTheWholeImportAndReceipt` | 08:52:22.718599 | 08:52:22.768888 | PASS |
| `StorageHotPathRepositoryTest#sharedPdfIsValidatedBeforeTransactionAndRehashedOnceDuringAtomicImport` | 08:52:22.769866 | 08:52:22.809922 | PASS |
| `StreamedStorageRepositoryTest#pdf300MBImportDuplicateAndFullBackupRestorePreserveOriginals` | 08:52:22.812891 | 08:57:06.891676 | PASS |

## 大文件验证范围

`pdf300MBImportDuplicateAndFullBackupRestorePreserveOriginals` 实际生成 300,000,000-byte 合成 PDF，经通用导入、整本复制后断言逻辑 PDF 原件总量为 600,000,000 bytes；完成全库快照、检查、恢复与回执重试。恢复后的原件完整流式复制核验摘要及 300,000,000-byte 长度，写缓冲不超过 65,536 bytes。测试的源/目标数据库及生成文件均在 finally 中清理，随后卸载独立测试包。

该 PDF 含不参与页面绘制的填充流；它验证大数据流、Android 平台 PDF 格式检查与存储一致性，不代表真实扫描/复杂课件的渲染、首帧、内存或交互性能。真实密集页与实际 PDF 的性能对比仍待测。

## 清理与证据

独立测试包安装前不存在，运行后卸载成功且包路径为空。MuMu5 恢复关机，root 全程 false；旋转设置保持 1/0，私有 ADB 55089 已结束。没有操作 StarNote、用户产品包、旧笔记、既有压测页或共享媒体。

本机证据位于 `InkWeft-Storage-Optimization/batch-02/verification/device-attempt01/`：环境前后记录、安装/卸载记录、精确选择命令、原始 instrumentation、带时标事件、逐方法结果和清理回执。私有设备标识只在本机保存。

- 原始 instrumentation SHA-256：`1a09d942b09a3dfce44105d58fff279775bdee31eb5303c1d9de7a0cea1d5fc6`。
- 事件 SHA-256：`19ccf40bde7e0d69b28ebb36abe71238aeecb340951e15e8c8fa9432047bce91`。

普通应用 UI、真实笔感、物理设备与物理断电仍为 NOT_RUN。可见/局部读取通过不代表全部保留历史健康；编辑器完整读取与全库备份校验仍保留。
