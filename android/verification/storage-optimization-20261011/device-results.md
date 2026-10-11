# 存储优化候选 · Android 整合结果

2026-10-11，专用 MuMu5 模拟器 / Android 12 API32，主 ABI x86_64。主调查完成并释放设备后执行；输入均为测试方法生成的隔离合成资料。没有产品主包安装或人工 UI 操作。

- 基点：`8596a981a76e27c38caf5220659e0e238141d319`，加本轮未提交补丁；设备运行期间生产源码与测试源码均未修改。
- APK：`926a57f6ee574b66eef099e75e42cc9981ad0390a1e080824822f9a1bdaafaba`，12,374,960 字节。
- 包名及 target：`org.inkweft.data.storageopt20261011.test`；runner：`androidx.test.runner.AndroidJUnitRunner`。
- 结果：**14/14 PASS，0 失败，0 跳过**；新类 6 项、已有相关 8 项；runner 报告 `Time: 5.036`、`OK (14 tests)`、`INSTRUMENTATION_CODE: -1`。
- 每项恰有一次开始状态 1 和一次成功结束状态 0。下表时间为主机接收到 instrumentation 行的 UTC 时间；不是设备性能测量或方法内部耗时。北京时间为 UTC＋8。

方法均位于 `org.inkweft.data` 包。

| class#method | 开始 UTC | 结束 UTC | 结果 |
|---|---|---|---|
| `StorageHotPathRepositoryTest#movingImagesPreservesCasAndDamagedNewOrStoredPreviewsAreRejected` | 08:21:19.969761 | 08:21:20.155810 | PASS |
| `StorageHotPathRepositoryTest#backupSyncReleasesTheWriterWithoutChangingItsCapturedSnapshot` | 08:21:20.156780 | 08:21:20.360637 | PASS |
| `StorageHotPathRepositoryTest#sourceOwnershipUsesIdsAndStillRejectsForeignOrRepeatedReferences` | 08:21:20.362144 | 08:21:20.468993 | PASS |
| `StorageHotPathRepositoryTest#failedFinalSnapshotSyncDoesNotPublishOrLeaveItsFile` | 08:21:20.472941 | 08:21:20.518898 | PASS |
| `StorageHotPathRepositoryTest#sourceChangedAfterValidationRollsBackTheWholeImportAndReceipt` | 08:21:20.519875 | 08:21:20.653805 | PASS |
| `StorageHotPathRepositoryTest#sharedPdfIsValidatedBeforeTransactionAndRehashedOnceDuringAtomicImport` | 08:21:20.654790 | 08:21:20.699955 | PASS |
| `PageObjectRepositoryTest#originalSaveReplayCopyBackupAndMissingAssetAreAtomic` | 08:21:20.702949 | 08:21:21.164684 | PASS |
| `PageObjectRepositoryTest#backupRestoresObjectsAndReceipts` | 08:21:21.164684 | 08:21:21.296984 | PASS |
| `PageObjectRepositoryTest#unknownCommitCanReplayAndStaleWriterCannotOverwrite` | 08:21:21.297991 | 08:21:21.324708 | PASS |
| `LibraryContentRepositoryTest#importBeforeReceiptRollsBackEveryPage` | 08:21:21.325712 | 08:21:21.358942 | PASS |
| `LibraryContentRepositoryTest#copyRetainsPagesMasksCoverTagsAndValidManualIndexWithNewIdentity` | 08:21:21.359921 | 08:21:21.422118 | PASS |
| `LibraryContentRepositoryTest#importUnknownRetryPreservesSameNewIds` | 08:21:21.422118 | 08:21:21.452930 | PASS |
| `LibraryBackupRepositoryTest#cancellingWrittenSnapshotRemovesPartialAndPreservesAuthorLibrary` | 08:21:21.453938 | 08:21:24.866077 | PASS |
| `LibraryBackupRepositoryTest#roundTripPreservesOriginalIdsHiddenInkMasksHistoryAndMetadata` | 08:21:24.867061 | 08:21:25.002657 | PASS |

## SO1 用例与结论边界

| 编号 | 本次结果 | 仍需执行 |
|---|---|---|
| SO1-SOURCE-01 | 模拟器自动化 PASS：超大损坏载荷不妨碍合法 ID 关联，非法来源拒绝 | 物理设备结果未填 |
| SO1-IMAGE-01 | NOT_RUN | 普通 UI 移动/缩放、撤销重做、重开及视觉体验 |
| SO1-IMAGE-02 | 模拟器自动化 PASS：共享成功校验、CAS、新旧损坏图拒绝、原件/复制/备份 | 物理设备与真实图片负载观察 |
| SO1-IMPORT-01 | 合成两页 PDF 自动化 PASS：事务外一次校验、事务内摘要复验、提交重试 | 真实大 PDF、首中末页呈现及性能对比 |
| SO1-FAULT-01 | 模拟器软件注入 PASS：来源变化整本/回执回滚、最终同步失败清理 | 备用物理设备断电/进程故障 |
| SO1-BACKUP-01 | 模拟器自动化 PASS：同步期新写入、固定快照、恢复、取消与清理 | 真实大库占用/等待、物理设备结果 |

## 清理与证据

安装前确认隔离包不存在；安装后运行一次完整选择集，随后卸载成功并确认包路径为空。模拟器恢复关机，root 全程 false，旋转保持 accelerometer_rotation=1、user_rotation=0；私有 ADB 服务器已结束。没有操作 StarNote 或用户产品包，没有删除已有笔记、压测页或共享媒体。

原始证据保存在本机 `InkWeft-Storage-Optimization/verification/device/`：`before.json`、`device-before.json`、`install.json`、`command.json`、`instrumentation.txt`、`events.jsonl`、`results.json`、`execution-end.json`、`uninstall.json`、`cleanup.json`。设备私有标识和原始环境回执保留本机。

- 原始 instrumentation SHA-256：`fa50a6e5871cae8abca3ccf2147232bafb76661af96f4e163e46f0de29458784`。
- 带主机接收时间的事件 SHA-256：`c7f494ef20376f59c4e6dc8dde6d427b05fb2acd353bf14bb94018aa867a1ef7`。

本次没有运行真实大文件/密集页性能对比，也未验证真实笔感、热量、物理断电或全部存储场景。原建议的已完成与未实施方向见 [说明](README.md#原建议的落实范围)。
