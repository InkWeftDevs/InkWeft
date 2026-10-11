# 第三轮存储优化：对象分行、预览去重与编辑快照

基点 V86 `8596a981a76e27c38caf5220659e0e238141d319`，叠加前两批补丁及点格式、编辑快照改动。当前是未提交候选，产品 versionCode/versionName 未变，没有产品安装或发布。前两批源码、APK 和执行证据保持冻结。

## 存储与读取

- Room17 将 `page_objects` 改为页级 revision；对象按 `page_object_records` 独立保存 ID、顺序、元数据及预览引用，图片预览在 `object_previews` 按 SHA-256 共享。移动对象只写改变的记录；删除最后引用时才回收该预览。图片原件继续使用原件分块存储。
- 对象写入在自己的主事务前固定规范编码、准备元数据与预览摘要。页 CAS、图层权限、原件引用、对象增删/顺序、搜索失效及 durable receipt 仍在同一个作者事务内提交；批量外层事务保留其锁与原子性。
- 完整对象读取在同一事务内冻结头、顺序、记录和预览字节，退出自身事务后解码并核对预览摘要及原容量。作者图层读取使用对象 ID 投影；图片/对象显示观察提交后的页 revision。未变化的旧预览引用只查存在，完整读取和备份检查实际字节。
- 编辑入口只加载可见原笔迹及有效掩码；恢复离屏历史时按其 ID 读取必要隐藏笔迹/掩码，其他保留历史通过 ID、点数和引用元数据维持容量、去重与冲突检查。完整笔迹读取和备份仍验证保留载荷。
- 数据库与持久检查点的新笔迹点体以 32 位非负时间字段无损保存，点体由 28 降至 24 bytes。压力、倾角、方向和时间语义保持；旧行双读、不批量重写。规范 `encode`、交换文件、来源快照和回执摘要继续使用既有编码。

## 格式影响

Room16→17 迁移保留原对象身份、顺序、版次与回执；损坏对象阻止迁移并保留原库。既有已支持备份可在隔离 staging 中提升对象结构。新备份覆盖全部对象头、记录和所需预览，恢复到已有库时只按内容合并相同预览，其他身份冲突仍拒绝。

旧安装包不能读取 Room17 数据库、新点体或新对象备份。需要继续使用当前候选或后续支持版本；若要回到旧安装包，应使用升级前备份在独立环境重建，不能直接覆盖降级后的数据库。本轮没有删除用户资料。单页对象数、总编码字节、笔迹笔数/点数等容量门槛保持；按可见区域分块和取消固定每页门槛属于后续工作。

## 验证

核心 57 类 468 项通过：原 458＋点格式 4＋编辑快照 4＋对象 codec 2。核心证据归档于 `editor-visible/verification`；本批不重复运行未修改的核心结果。

主代码、AndroidTest、应用整合编译通过，data-local 2 类 10 项主机测试通过，lint 为 0 Fatal/Error、9 Warning。首轮 85 项已实际运行，81 项通过、4 项失败；原始栈、源文件、APK 和清理记录保留在 `device-attempt01` 对应证据中。修复包括作者状态按编码字节比较、旧源卡测试使用当前表结构、过期备份断言跟随既有 256 MB 常量，以及掩码保存只验证目标笔迹的实际读取遗漏。

修正版隔离包 `org.inkweft.data.storageopt20261011b3r2.test` 在 MuMu5/API32 实际运行 **86/86 PASS**，包括对象新测试 8 项、编辑快照 6 项（新增掩码恢复逐笔预算拒绝）、点格式持久化/检查点及对象/备份/复制/图层回归。runner 145.233 秒，包含现行 256 MB 快照预算的超出 1 byte 拒绝、精确边界恢复与重放；逐方法 UTC 和首轮修复记录见[设备结果](device-results.md)。普通应用 UI 和物理设备仍待测。

最终 APK 为 12,689,842 字节，SHA-256 `de318d6c209f184166cd6f2bd46eabf11670714d81c2422ad5a52b02d58030f5`，签名及对齐通过；修正版构建耗时 3 分 30 秒。55 个冻结构建输入清单 `build-inputs-r2.json` 的 SHA-256 为 `2efbc1062e144bc8119b103b6f31f937a0b5949971c3f0c7cba4c6a0b226c134`，对应 `source-r2` 副本。

首轮 APK 为 15,694,931 字节，SHA-256 `f877d0383af896f3bfa75167eb7476db7aedf0b41410b0a585a1911f6f8a7879`，签名及对齐验证通过。该轮 55 个源文件/生成 schema/测试入口的冻结清单 SHA-256 为 `0146f0e32d881900203a33cf5bf1f5f0a8a6a301a6588f23d5427dd4da9baee4`；修正版另用 `source-r2` 和 `build-inputs-r2.json`。精确副本、构建日志、主机 XML 与构件归档于本机 `InkWeft-Storage-Optimization/batch-03`。

在 `android/` 运行：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 --init-script verification/storage-optimization-20261011-batch03/test-application-id.init.gradle :data-local:testDebugUnitTest :data-local:lintDebug :data-local:assembleDebugAndroidTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

普通应用 UI、物理设备、真实密集页延迟与内存效果仍待测。模拟器的软件故障注入不替代物理断电，测试耗时不作为用户性能收益。应用内问题优先标记时间、复现并通过“诊断与导出”保存 ZIP；基础诊断不含完整系统日志，视觉/笔感另留录像。按[结果模板](results-template.md)追加不同版本和设备结果。

本批结束时已卸载独立测试包、关闭 MuMu5，root=false、旋转 1/0 保持，私有 ADB 55095 结束；设备随后交回主任务运行独立 app 编辑器检查。源码、APK、最终验证证据及首轮失败复现材料分类归档，没有零散临时文件；必要构建缓存保留。未提交、推送、合并或安装产品包。

主任务随后使用相同 55 个冻结输入构建独立 app，`StreamedPageLifecycleTest` **5/5 PASS**，覆盖双显示/离屏释放、未知结果重试、版次变化与可见编辑入口；另含 PDF provider 暂存流关闭。两个隔离身份已卸载，MuMu5/root/旋转保持且 private ADB 55093 结束，见[编辑器验证记录](../storage-optimization-20261011-editor/README.md)。这 5 项是应用生命周期断言，普通 UI、真实视觉与物理手写仍待测。
