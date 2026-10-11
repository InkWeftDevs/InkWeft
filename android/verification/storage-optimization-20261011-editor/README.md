# 编辑器按需加载与紧凑点体：应用验证

2026-10-11，本批是 V86 `8596a981a76e27c38caf5220659e0e238141d319` 上的未提交候选。与[第三轮存储优化](../storage-optimization-20261011-batch03/README.md)使用同一份 r2 源码；应用源码没有单独分叉。

## 结果

专用 MuMu5（Android 12 / API 32）执行 `StreamedPageLifecycleTest`，**5/5 PASS**，无缺失或额外方法。原始 runner 时间 0.531 秒仅描述这些检查的执行，不能用作打开笔记、手写或翻页的性能指标。

| 方法 | 验证内容 | 结果 |
|---|---|---|
| `enteringEditorDoesNotDecodeUnrelatedHiddenGeometry` | 进入页面跳过无关隐藏几何；继续书写、离屏返回、撤销和重做正常 | PASS |
| `offscreenReloadKeepsPressureUndoRedoAndOtherActiveView` | 多视图引用、离屏恢复、原始点属性与撤销重做保持 | PASS |
| `unknownCommitCannotReleaseDraftAndReceiptRetryReleasesOnlyAfterConfirmation` | 提交结果未知时保留草稿；确认原回执后才允许释放 | PASS |
| `changedStoredRevisionAfterReleaseDoesNotReplayStaleUndo` | 离屏期间版次变化时，不重放旧撤销记录 | PASS |
| `directPdfProviderImportHashesTheStreamAndClosesItsStagingFile` | PDF 导入流摘要正确，临时原件随租约关闭 | PASS |

配套检查为核心 57 类 468 项、存储主机 10 项，以及第三轮 r2 的 86 项 Android 数据库/整合测试。r1 的四项失败与原始构件完整保留；r2 修复了掩码保存校验仍全页解码的遗漏，并修正三项过期或错误的测试断言。掩码恢复超出预算时，版次、载荷和回执均不变。

普通应用 UI、真实密集页性能、物理断电与手持笔体验仍为待测；本结果不是完整 UI 流程或真实笔尖延迟验证。真机继续使用 [SO3-EDITOR-01、SO3-POINT-01、SO3-HARDWARE-01](../../DEVICE-TEST-CHECKLIST.md)及[结果模板](../storage-optimization-20261011-batch03/results-template.md)，保留各设备旧记录。

## 源码与构件

本机归档根：`E:/Inkweft/archives/2026-10-11/InkWeft-Storage-Optimization/`。

- 联合源码以 `batch-03/source-r2/` 和 `batch-03/verification/build-inputs-r2.json` 为准，共 55 个修改输入；manifest SHA-256：`2efbc1062e144bc8119b103b6f31f937a0b5949971c3f0c7cba4c6a0b226c134`。
- 应用：`editor-visible/artifacts/InkWeft-storage-opt-20261011-editor.apk`，227,761,467 bytes，SHA-256 `5384da52412358355362b78f3c2903e6f43c04b715f1d4774ecdc55e238315c4`。
- 测试：`editor-visible/artifacts/InkWeft-storage-opt-20261011-editor-androidTest.apk`，9,586,415 bytes，SHA-256 `48856b438f30a6a729057fa506b926de6525907a0af26a0b507e9acc10de124f`。
- 应用与测试包分别为 `org.inkweft.app.storageopt20261011`、`org.inkweft.app.storageopt20261011.test`。已核验安装身份、instrumentation 目标、签名、对齐及字节摘要；只用于隔离验证，没有覆盖产品应用。
- 构建记录与冻结检查：`editor-visible/verification/app-build.log`、`freeze-app.py`、`apk-manifest.json`。r2 应用构建 33 秒通过。
- 原始执行和清理：`editor-visible/verification/device-attempt01/` 下的 `instrumentation.txt`、`events.jsonl`、`execution-end.json`、`uninstall.json`、`cleanup.json`。
- `editor-visible/source/` 是此前核心测试的编辑快照，最终应用以以上联合 r2 manifest 为准。未安装的首轮应用构建日志由 `app-build.log` 归档为 `app-build-attempt01.log`；其输出由修正版构建替换。

## 范围与重建

数据库/检查点的新点体每采样点 28→24 bytes，原始点轴与时间值保持；规范交换编码和回执摘要不变。编辑入口只解码可见笔迹及恢复当前撤销所需的历史，擦除保存仅解码受影响的可见笔迹。每页 20,000 笔、1,000,000 点门槛仍保留，空间索引和视口分块尚未实现。

本批数据库升级至 Room17，旧安装包不能直接读取新库或新点体。回退应使用升级前备份在独立环境重建；本次没有删除用户资料。

在 `android/` 使用 JDK17 和项目 Android SDK 运行：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 --init-script verification/storage-optimization-20261011-editor/app-identity.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
```

## 清理

两个本轮隔离包均已卸载；MuMu5 已关闭，root 保持关闭，自动旋转/旋转方向仍为 1/0，私有 ADB 55093 已停止。测试数据库由夹具清理，源码、最终 APK、失败复现材料与验证证据保留。主工作区原有 Git 状态逐字节一致；没有提交、推送或合并。
