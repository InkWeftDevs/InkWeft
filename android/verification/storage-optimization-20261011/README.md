# 存储优化候选：首批 A/B 与部分 C

基点：V86 `8596a981a76e27c38caf5220659e0e238141d319`。本目录对应独立工作树内未提交补丁；没有提交、推送、PR 或产品安装。隔离测试包已在专用模拟器完成 14 项整合检查并卸载。数据库继续 Room16，原件、笔迹和备份格式不变，容量上限不变。

## 已实现范围

- 来源校验复用 `strokeIds()`，保留同页身份与重复引用检查，不为身份检查读取整页笔迹 BLOB。
- 页内图片缩略图的成功格式/像素校验按内容 SHA-256 保存最多 64 个内存结果；同一数据库的普通保存、临时仓库批量编辑和内部导入共享结果，位置、大小或对象 ID 改变不重复完整解码。只缓存成功结果，首次/变更/损坏内容仍完整验证。缓存不保留位图或缩略图内容，淘汰只导致重新验证。CAS、图层、几何边界和原件引用检查继续在事务中执行。页级对象序列化仍存在，未宣称消除全部 Base64 编解码。
- `LibraryContentRepository.import` 的 PDF/图片原件格式校验移到主库写事务前，按不可变摘要、长度及 PDF 页数绑定本次校验结果。原件入库继续通过 `storeOriginal` 全流检查长度与 SHA-256；同一事务发布来源、页面、对象及回执，来源变化、取消或失败均不能留下半本。预校验前查询已有回执，提交事务内再次查询；已提交重试无需重新读取暂存原件。
- 全库备份的文件最终 `fsync` 放到数据库快照事务完成后。快照读取、语义验证和归档序列化仍在同一个一致事务中；文件同步完成前不会返回可交付 Snapshot，失败仍删除本次暂存文件。归档写入器自身的流 `flush` 保持原行为。

## 有意保留

整本复制必须冻结源本的页面、作者状态、几何和原件引用，当前仍使用既有事务。把全库语义验证或归档序列化提前/拆出需要可独立持有的一致快照，而非先读若干 ID 后再读取变化中的行。本批没有引入 SQLite 原始文件复制、另一个全库副本或跨事务失效重试协议；这些需要另外的空间/并发设计。

`NotebookPages.importBook`、单页/资源模板内部导入等其他调用方继续使用原件 attach 的原验证路径；只向本次预校验的通用内容导入传递私有构造的校验结果。完整备份恢复、CAS、引用闭包与 durable receipt 不放宽。

## 原建议的落实范围

下面对应《墨织 V86 与 StarNote 5704：存储实现对比与优化建议》的原编号。本批完成四处具体优化，但第一批全部建议和后续方案尚未全部落实。

| 原建议 | 本批状态 | 当前实现或剩余内容 |
|---|---|---|
| 第一批 A：来源只查询笔迹 ID | 已完成 | 复用 `strokeIds()`，保留同页与重复引用检查 |
| 第一批 B：相同图片内容避免重复完整解码 | 已完成 | 数据库级 64 项成功摘要缓存；首次、变更、失败及淘汰后仍验证 |
| 第一批 C：缩短导入、复制、备份长事务 | 部分完成 | 通用内容导入的原件格式预校验及备份最终同步已移出事务；整本复制、备份全库验证/序列化和其他内部导入路径保持原状 |
| 第一批 D：复制只读取可见笔迹 | 未实施 | 复制仍经完整历史读取后生成可见副本；需要在一致事务中组合可见快照与版次元数据 |
| 第二批 A：可见几何与历史分层 | 未实施 | 编辑页未改为按 ID 延迟加载历史；撤销和固定版次来源仍使用原路径 |
| 第二批 B：逐对象存储、独立缩略图及图层身份关系 | 未实施 | 页对象大包和页级图层身份载荷仍会重新编码；本批只复用内容校验结果 |
| 第二批 C：包围框、状态/版次索引 | 未实施 | 没有新增索引或画布视口空间查询；A 项仅复用已有 ID 查询 |
| 第三批 A/B：不可变原件文件及完整原件模块 | 未实施 | 原件继续数据库分块＋可重建文件缓存；没有新增持久文件发布、引用提交、孤儿回收和备份固定引用协议 |
| 第三批 C：单本流式归档 | 未实施 | 大本仍使用现有全库流式备份；没有新增选定一本及资源清单的流式导入/导出 |
| 第三批 D：空间核算与回收 | 未实施 | 没有新增按本区分活动/历史/共享原件/数据库开销的核算或永久回收 |
| 笔迹点体 28→24 bytes | 未实施 | 点格式与 64 位时间字段保持，压力、倾角、方向及时间精度不变 |
| 容量值 | 保持 V86 原值 | 本批未提高笔迹、页对象、原件或归档容量上限 |

本轮整合检查证明所选行为与事务断言通过；真实密集页、329 MB PDF 的冷暖首帧、保存等待、PSS 和临时空间峰值对比尚未执行，不能由这些检查推算性能收益。

## 验证入口

主机、编译、lint 与独立测试 APK（执行情况以本轮最终验证记录为准；在 `android/` 运行）：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 --init-script verification/storage-optimization-20261011/test-application-id.init.gradle :core-domain:test :data-local:testDebugUnitTest :data-local:lintDebug :data-local:assembleDebugAndroidTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

新主机检查 `StorageValidationTest` 覆盖几何变化与内容变化、失败不缓存、64 项淘汰、并发保存复用成功校验、以及原件变化必须被实际分块写入器拒绝。它不执行 Android 图像解码或 Room。

新设备类 `org.inkweft.data.StorageHotPathRepositoryTest` 覆盖来源 ID 投影、损坏缩略图与 CAS、PDF 事务外验证、校验后来源变化回滚、备份同步期间的新写入、一致快照和同步失败清理。2026-10-11 在主调查释放专用模拟器后，新 6 项及相关 8 项共 **14/14 PASS**；见[逐方法结果](device-results.md)。

测试 APK 输出为 `data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk`。本次 init script 仅把 library-test 包隔离到 `org.inkweft.data.storageopt20261011.test`；它以自身为 instrumentation target，内含 Room、平台 PDF/图片检查及核心库，不需要安装产品主包。产品 applicationId 不变。每个方法仅使用本包的合成临时数据库。

以后重跑时先取得独占测试设备，以该设备序列号替换 `<SERIAL>`，执行下列入口。安装前确认此隔离包不存在，记录 APK 摘要、证书和设备身份；仅卸载本轮确认新安装的包。本轮使用私有 ADB 端口，准确命令与原始回执在本机归档：

```powershell
$adb = 'E:/Android/android-sdk/platform-tools/adb.exe'
$serial = '<SERIAL>'
$apk = 'data-local/build/outputs/apk/androidTest/debug/data-local-debug-androidTest.apk'
& $adb -s $serial install -r $apk
$classes = @(
    'org.inkweft.data.StorageHotPathRepositoryTest'
    'org.inkweft.data.PageObjectRepositoryTest#originalSaveReplayCopyBackupAndMissingAssetAreAtomic'
    'org.inkweft.data.PageObjectRepositoryTest#unknownCommitCanReplayAndStaleWriterCannotOverwrite'
    'org.inkweft.data.PageObjectRepositoryTest#backupRestoresObjectsAndReceipts'
    'org.inkweft.data.LibraryContentRepositoryTest#importBeforeReceiptRollsBackEveryPage'
    'org.inkweft.data.LibraryContentRepositoryTest#importUnknownRetryPreservesSameNewIds'
    'org.inkweft.data.LibraryContentRepositoryTest#copyRetainsPagesMasksCoverTagsAndValidManualIndexWithNewIdentity'
    'org.inkweft.data.LibraryBackupRepositoryTest#roundTripPreservesOriginalIdsHiddenInkMasksHistoryAndMetadata'
    'org.inkweft.data.LibraryBackupRepositoryTest#cancellingWrittenSnapshotRemovesPartialAndPreservesAuthorLibrary'
) -join ','
& $adb -s $serial shell am instrument -w -r -e class $classes org.inkweft.data.storageopt20261011.test/androidx.test.runner.AndroidJUnitRunner
```

上述入口选中新增 6 项和既有相关 8 项，共 14 个方法。完整逐方法开始/结束、断言结果和清理证据另存本机；模拟器通过不能替代真实设备的普通 UI、硬件笔感或物理断电观察。

需要人工与既有整合方法的准确步骤见 `android/DEVICE-TEST-CHECKLIST.md` 中 SO1-* 编号；每次执行单独填写 [结果模板](results-template.md)。不把原 V86 或其他候选的通过记录移写到本次候选。

## 本轮实际结果

- 核心主机 56 类 / 458 项、data-local 主机 2 类 / 10 项通过，无失败、错误或跳过。核心代码未改，本轮首轮实际执行通过后最终构建复用 `UP-TO-DATE`；data-local 最新候选在最终构建重新执行通过，其中新增 `StorageValidationTest` 5 项。
- 最新 data-local 主代码和 AndroidTest、app 主代码和 AndroidTest 编译通过；data-local AndroidTest APK 打包通过。最终联合构建耗时 3 分 09 秒，110 项 Gradle task 中 38 项执行、7 项缓存、65 项最新。
- 完整 `:data-local:lintDebug` 通过，0 Error / Fatal、9 Warning。第一次新备份写法触发 UseKtx 的 UAST 内部崩溃；精确 HEAD 文件对照通过，随后将一致归档事务提成私有 `writeSnapshot()` 修复，未屏蔽检测器。失败、对照、恢复摘要和最终成功日志均保留在本轮本机证据归档。
- 独立测试 APK：12,374,960 字节；SHA-256 `926a57f6ee574b66eef099e75e42cc9981ad0390a1e080824822f9a1bdaafaba`。包名及 instrumentation target 均为 `org.inkweft.data.storageopt20261011.test`，runner 为 `androidx.test.runner.AndroidJUnitRunner`，minSdk 31 / targetSdk 36，无显式产品版本号。APK v2 签名和 ZIP 对齐通过，debug 证书 SHA-256 `345a1e67b72623000c611b0a17e70a8fdf1438768ec79a1144c407c28c14d2f2`。
- 专用 MuMu5 / Android 12 API32 的整合检查 **14/14 PASS**，runner 报告 5.036 秒；14 项均有开始及成功结束事件，没有失败或跳过。Room 事务、Android 平台 PDF/图片校验实际执行；测试耗时不当成产品性能收益。普通用户界面、真实硬件、真实大文件性能对比与物理断电检查仍 **NOT_RUN**。
- 隔离测试包安装前确认不存在，测试后已卸载；专用模拟器恢复关机，root 全程 false，旋转设置保持 1/0，私有 ADB 已结束。没有启动或操作 StarNote 和用户产品包，没有删除已有压测页或旧笔记。清理回执与逐项结果见本机 `verification/device/` 证据。
- 工作树保持基点上的未提交补丁；未提交、推送、建 PR、合并或发布。源码、可重现构建入口、测试 APK 与最终验证证据保留；本轮没有待清理的零散临时文件，lint 对照材料作为复现证据分类保留。
