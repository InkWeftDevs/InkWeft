# V58 验证记录 · 2026-10-02

候选 0.0.58-library-source-nav（58），SHA-256 `75155e873e696a4c6ee82ba08caf5d23cd548eb02f1a13bf9749a65130a4c657`，测试APK `2bbdedb80d7fb15a749e87d24908e5789a2573193ae9e0d22666eb126750e94d`。固定证书、版本/applicationId、设备实际安装base.apk摘要均一致。独有AVD `InkWeft-LibrarySource-V58`，Android35，独立ADB5038/emulator-5556；1920×1200/240dpi，窄屏用例375dp/字号1.6。保留既有ADB5037，无真实设备执行。

**最终15个唯一用例：新增3/3，受影响旧UI12/12。** lint 0错误/0致命/132警告，不将警告描述为清零。不重跑未修改的全量domain/Room、原生PDF和历史恢复探针；历史通过数量不计入本批。

|类|方法|结果|原始日志|
|---|---|---|---|
|LibrarySourceNavigationUiTest|learningSourceAwaitsRealPageSelectionThenFocusesOriginalBoundsWithReadLock|PASS|ui-library-source-2|
|LibrarySourceNavigationUiTest|closingOrRecreatingPendingLibrarySourceCannotJumpOrCloseAnotherCard|PASS|ui-library-source-2|
|LibrarySourceNavigationUiTest|recycledLibrarySourceRetainsCurrentCardSnapshotAndMapWithoutNavigating|PASS|ui-library-source-2|
|CardSourceNavigationUiTest|missingAndRecycledSourcesStayScopedWhileStaleSnapshotReturnsToCurrentPaper|PASS|ui-library-source-protection-final|
|CardSourceNavigationUiTest|collapsedSourceFooterReturnsToExactPaperAndKeepsReadOnlyMapContext|PASS|ui-library-source-protection-final|
|CardSourceNavigationUiTest|narrowLargeFontKeepsFooterReachableAfterLongBodyScrollAndRecreation|PASS|ui-library-source-protection-final|
|SelectionStudyUiTest|excerptCreatesSharedCardAndReturnsToSource|PASS|ui-library-source-protection-final|
|ReadLockUiTest|readNavigationSearchSourceAndPortalReturnKeepAuthorRows|PASS|ui-library-source-protection-final|
|RecallMaskUiTest|allRecallAncestorsHideCluesFromInteractiveWindows|PASS|ui-library-source-protection-final|
|RecallMaskUiTest|exitRestoresAncestorRenderingAccessibilityDraftAndOtherBook|PASS|ui-library-source-protection-final|
|LearningWorkbenchTest|learningOpensMapWithoutOpeningNoteAndKeepsHostConfiguration|PASS|ui-library-source-protection-final|
|KnowledgeTextLinksUiTest|pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting|PASS|ui-library-source-protection-final|
|KnowledgeTextLinksUiTest|narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable|PASS|ui-library-source-protection-final|
|MapInteractionUiTest|branchBadgeAndSourceAreSeparateTargets|PASS|ui-library-source-protection-final|
|PdfTextSearchUiTest|narrowLargeFontUsesRealTouchesAndPdfRegionCanStillBeExcerpted|PASS|ui-library-source-protection-final|

新增用例从实际资料库学习工作台进入：已有BookPagesViewModel先在第1页连续阅读预热，然后回源至第2/3页，核对原页身份、CanvasViewport中心/缩放/可见区域、临时anchor消费、读锁、图/节点/折叠/视野；回库再开同图保持上下文。真实repo回收源页后仍显示详情/快照并提示不可用，不打开其他页。真实app Room事务队列等待门分别覆盖返回原节点并换卡、Activity重建、整学习窗关闭；释放门后旧请求没有发布跳页，新来源触控仍成功。

27类作者/历史/回执SQL快照，对测试本及另一本比较前后，包括墨迹/切割/PDF/对象、派生搜索、摘要卡/来源/图节点/知识记录及其回执。选页和视野属于正常浏览元数据，未纳入“作者零改动”结论。事务fence只排空此前已入队Room任务，不作为整个旧导航Job完成的证明；取消安全还由caller Job同步cancel和发布前ensureActive静态独立复核支持。

**首次尝试原样保留：1 PASS，2 SQLITE_BUSY FAIL。** 门原先取消外层Job后关闭独立DB，Room executor内事务未保证已结束；最终测试门复用app真实DB、正常释放并等待事务返回，不关闭共享DB。门异常转为可等待失败，不重试吞SQLite异常、不用fake导航回调。旧日志缺少phase，无法唯一判每一次锁发生的位置；不把旧FAIL改记PASS。首次截图/候选身份、两轮源清单、构建与安装记录和原始测试日志均归档。

三张成功断言后的未修改原生截图见 evidence/native-ui，设备/本地SHA一致，视觉复核见visual-review.json。它们来自合成资料，不证明真人手写效果、真实旧库升级或现场笔感。真实平板/物理分屏/人工读屏/真实故障原迹为NOT_RUN，真实备份恢复NOT_VERIFIED；全程未触真实设备或远端写入。

已知边界：整本有效性校验与选页之间未增加新事务；跨连接整本回收仍有极窄竞争。取消不回滚已提交浏览选页。阅读页浮窗仍可能遮住焦点，自动避让另批；旧阅读页同步回调的后续选页I/O失败不在本批await契约内。
