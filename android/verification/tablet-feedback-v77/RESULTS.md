# V77 实际验证记录 · 2026-10-08

设备 iPA2673 / Android API36。生产源码、APK 及证书摘要见 [交付报告](../../TABLET-FEEDBACK-V77.md)。日常 insertion 包只做普通首开、原笔记查看和入口检查；所有仪器方法在独立 `org.inkweft.app.a0.feedback` 运行。

## 构建与第一批修复

- 旧版回归 `AsyncRasterTest#eraseKeepsUnaffectedInkVisibleBeforeReplacementFrameCompletes` 实际失败，复现局部擦除后无关线条消失。
- `447225f8e8617a524f5cdd867336e4a37791eebb` 的 3 项异步像素及 2 项工具界面方法通过。该批构件身份在交付报告单列，未冒充最终公式包运行结果。
- 最终 `789eb67f01b27bec0be0e0dfae564d36538fc13c`：核心 376/376，Lint 0 Error/Fatal、172 Warning、2 Hint；APK 签名、16 KB 对齐、CRC、构建来源和 11 项资源摘要通过。首次公式测试编译类型错误及其修正日志保留。

## 最终反馈构件的原生方法

| 类／方法 | 结果与实际范围 |
|---|---|
| LocalFormulaSamples#collectConsentedSegments | PASS；实际离线模型、7 行样本、私有输入图和解码轨迹，输入位图占用释放；这不是准确率通过 |
| FormulaFlowTest#confirmationCorrectionUndoAndRestoreKeepOriginalInkByteExact | PASS；注入候选、格式错误拒绝、人工修正、确认、撤销／重做、恢复原迹，来源笔迹字节一致 |
| FormulaFlowTest#automaticFormulaNeverCommitsWithoutReviewAndStaleReviewIsRejected | PASS；自动模式只产生候选，新增笔迹后旧候选不能应用 |
| FormulaFlowTest#fractionsGreekAndPowersRenderWithPersistentPartialErase | PASS；分式、平方和希腊字母实际渲染、局部擦除及编码重读；PNG 已检查 |
| FormulaPersistenceTest#formulaCopyExportAndBackupRestoreRetainEditableSourceAndOwnedInk | PASS；复制、整本导出导入、真实备份恢复至另一测试库并关闭重开，来源 ID 重映射和事务重试 |
| FormulaDiagnosticsTest#formulaCaptureNamesActualPipelineAndOnlyIncludesImagesAfterOptIn | PASS；公式专用元数据、实际输入尺寸、私有图像／候选的显式选择 |
| FormulaEditorUiTest#formulaModeAndDirectEditingSurviveReopen | PASS；实际选择公式模式、点按对象编辑、保存、重建 Activity、重开预览和源码；截图无裁切／缺字 |
| BeautySettingsUiTest#clickOpensCardWithoutTogglingAndParametersPersist | PASS；原文字模式开关与参数保存回归 |
| BeautyQualityTest#delayedRecognitionCannotCommitAfterNewInkAndManualReviewIsRecoverable | PASS；原文字模式异步事务与可恢复校对回归 |

首次 FormulaEditorUiTest 在约 8.562 秒观测到内核 `cgroup.freeze=1`，尚无测试断言结果，按环境阻断记 NOT_RUN 并保留现场。重试仅允许一次普通初始 MAIN 启动，随后实际完成。9 个通过方法中 7 个各使用一次初始 MAIN；另两项没有辅助。设备的 12 项显示／输入／电源／AppOp 记录前后恢复一致，没有修改电源或安全策略。所有结果均要求指定方法开始／成功事件、`OK (1 test)` 和正常结束标记。

## 识别质量与资料核验

单个获准开发页：184 条可见笔迹，7 行公式，模型原生耗时 1,659.748 ms。人工对照原迹，忽略空白及末尾标点，第 4、7 行数学内容正确；其余 5 行仍有函数名／符号错误。参考答案未输入模型，合成候选和人工校对不算模型识别正确。原始资料、候选、诊断 ZIP 和截图仅在本机归档。

正式包安装前后，两库各 44 张表的完整行／字段／BLOB 摘要一致，workspace 的 106 个及 insertion 的 14 个外部文件逐份一致，内部和外部副本均完整验证。正式包冷启动 480 ms；查看原笔记及自动美化面板后再读 insertion，44 表及 19 份偏好仍一致。预期更新仅涉及运行时 profileInstalled 标记和诊断日志。未对原笔记应用公式转换，也未在日常应用运行测试代码。

真人持笔闪烁、实际笔感、压感／掌拒及既有 15＋30 分钟持续使用仍待测。步骤、准备条件和补充证据见 [设备清单](../../DEVICE-TEST-CHECKLIST.md)；后续结果使用 [模板](results-template.md)，保留上述历史结果。
