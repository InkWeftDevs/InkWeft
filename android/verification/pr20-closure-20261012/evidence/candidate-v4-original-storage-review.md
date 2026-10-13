# v4 原件存储独立核对

结论：`OriginalStorageRepositoryTest` 在候选 v4 的 Android 原生 instrumentation 中 **11/11 通过、0 失败**。原始日志为 `candidate-v4-validation-original-storage.log`，回执为 `candidate-v4-validation-original-storage.json`；两者测试数与通过状态一致。JUnit 用时 116.802 秒，host 命令总时长 127.330697745 秒。设备为 API35 x86_64 软件模拟器，无 KVM；这些是回归运行时长，不是物理设备性能测量。

## 原失败用例已执行的断言

`laterCorruptionStillReclaimsSuccessfullyMigratedDatabaseBytes` 现通过预先建立的独立合成库进入原有测试路径：

- 在 Room 打开之前，以自有单连接、DELETE journal 执行 `auto_vacuum=2` 和 `VACUUM`，立即断言模式为 2；Room 打开后的原 `auto_vacuum=2` 断言也通过。
- 正常来源迁移 4,194,304 字节，正常来源的旧 SQLite 分块为 0；外置原件总字节与正常来源大小一致，重新打开原件的 SHA-256 与输入一致。
- 后续损坏来源仍返回 `ORIGINAL_CHECKSUM_CHANGED`。损坏来源全部 3 条分块保留，两条来源记录保留；未把错误改为成功，也未跳过损坏保留断言。
- `freelist_count=0`，主数据库由 6,012,928 字节降至 1,806,336 字节，减少 4,206,592 字节；通过原“减少超过正常来源一半大小”的断言。
- `partial-migration-reclamation` 证据输出位于上述断言之后，且整个测试类最终 `OK (11 tests)`。

与保存的 v3 源码逐字比较，夹具建立之后的正常/损坏来源构造、迁移调用、错误校验、留存、空间回收和原件摘要断言完全未变。`legacyVacuum=true` 仍建立 NONE 模式并执行其原先断言。v4 修正只把本用例所需的预存 INCREMENTAL 格式移到 Room 连接池建立之前，不改变生产存储代码或后续验收标准。

其他两条日志证据也属于本次 11/11：旧 NONE 库迁移后主数据库 7,086,080→790,528 字节、迁移 6,291,456 字节、空闲页 0、原页大小保持 4096；去重/备份恢复场景外置原件 8,388,712 字节，document/image chunk rows 均为 0，恢复后原件字节数一致。

## 源码与运行产物绑定

独立只读重算时，当前仓库 **744/744** 个文件与 `candidate-v4-source-sha256.json` 冻结清单吻合，无缺失或变动。`candidate-v4-build.json` 记录构建成功、用时 934.462822667 秒、构建期间 744 份源码未变。

- 测试源码：`android/data-local/src/androidTest/kotlin/org/inkweft/data/OriginalStorageRepositoryTest.kt`
- 测试源码 SHA-256：`2eb17404947b241d35868fe93194ea6f3b63b42ac0f5551bf03710b58f290e78`
- 原始日志 SHA-256：`b7a8c7c2aba3072fb61502f1019b35a894306fee992914ac6ce1c1cc1582fd20`
- 运行回执 SHA-256：`052028294103e837fedc19609f10f7b307e4d8c55cc7499381ed09b12b4ba4a4`

`candidate-v4-artifacts.json` 与 `candidate-v4-apk-installation.json` 的 data-local test APK 哈希一致：`79a7280c9667ef4721b4606ffc17a4bd7c476ceeb661d4d015ec9c19769324a3`。`candidate-v4-signatures.json` 记录该 APK 签名验证通过；安装回执记录成功安装到 `emulator-5554`，完成于 2026-10-12T23:58:48.481083Z。原件测试回执记录随后于 2026-10-13T00:00:03.632463Z 在相同 serial、`org.inkweft.data.test/androidx.test.runner.AndroidJUnitRunner` 下执行指定测试类。

绑定依据是冻结源码、构建连续性、产物哈希、签名、安装及后续运行回执这一证据链；本次只读复核没有重新提取设备中已安装 APK 或进行独立 DEX/源码对应证明。

## 适用边界与历史状态

- 本用例验证的是预先建立的 INCREMENTAL 合成库兼容性与部分迁移后回收，不能当作新建 1024/FULL 库的性能样本。该 partial 记录没有输出页大小，不能从另一条 legacy 记录借用 4096。
- 空间变化指主 SQLite 数据库文件，不是数据库、WAL、外置原件和临时文件合计占用；迁移后仍保留 4,194,304 字节正常外置原件。
- 保留损坏分块的证据是原分块数量与来源记录断言；此用例没有另外计算损坏分块的迁移前后逐字节摘要。
- 本次通过证明修正后的夹具完整触达并通过原断言，不能证明 v2/v3 模式不符的唯一原因是连接池。v2 的 10/11 和 v3 单例失败保持历史失败，不改写为通过。
- 这些结果不关闭搜索快速输入、备份部分写入取消、栅格完整帧等独立门槛，也不是最终 baseline/candidate 性能配对。

本报告只读取已有源码和证据并保存本 Markdown；未修改主仓，未启动 Gradle、ADB、instrumentation、提交或推送。
