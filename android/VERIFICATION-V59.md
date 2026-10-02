# V59 验证记录 · 2026-10-02

候选SHA-256 `addf7a02275a029b9adc8b4b3610f1946c78cc2c0b409a0ecb02b348732a3bf5`；测试APK `48e658b7fee12e007898c769144a98faabd743948b4f81c3e2504e1c6310fe44`。版本、appid、固定证书和设备已安装base.apk摘要与候选一致。独有AVD `InkWeft-SourceFocus-V59`，Android35、ADB5038/emulator-5556；保留原ADB5037，不操作真实设备。

**最终16个唯一用例：新UI4/4、受影响旧UI12/12。** lint 0错误/0致命/132警告。未重新运行未变的数据层或历史全量套件。

|类|方法|结果|原始日志|
|---|---|---|---|
|SourceFocusVisibilityUiTest|originalRightDockAndOriginalMinimizedSurviveTemporarySourcePresentation|PASS|ui-source-focus-4|
|SourceFocusVisibilityUiTest|narrow375LargeFontRetainsSourceRegionThroughRecreationAndRestoresItsFocusFrame|PASS|ui-source-focus-4|
|SourceFocusVisibilityUiTest|movedResizedFloatingCollectOrganizeAndFocusExposeExactSourceThenRestoreOriginalWindow|PASS|ui-source-focus-4|
|SourceFocusVisibilityUiTest|splitPaneRefitsRetainedSourceAcrossBothDirectionsWithoutChangingReferenceOrGraph|PASS|ui-source-focus-4|
|CardSourceNavigationUiTest|missingAndRecycledSourcesStayScopedWhileStaleSnapshotReturnsToCurrentPaper|PASS|ui-source-focus-protection-final|
|CardSourceNavigationUiTest|collapsedSourceFooterReturnsToExactPaperAndKeepsReadOnlyMapContext|PASS|ui-source-focus-protection-final|
|CardSourceNavigationUiTest|narrowLargeFontKeepsFooterReachableAfterLongBodyScrollAndRecreation|PASS|ui-source-focus-protection-final|
|ReadLockUiTest|readNavigationSearchSourceAndPortalReturnKeepAuthorRows|PASS|ui-source-focus-protection-final|
|SelectionStudyUiTest|excerptCreatesSharedCardAndReturnsToSource|PASS|ui-source-focus-protection-final|
|PdfTextSearchUiTest|narrowLargeFontUsesRealTouchesAndPdfRegionCanStillBeExcerpted|PASS|ui-source-focus-protection-final|
|LibrarySourceNavigationUiTest|learningSourceAwaitsRealPageSelectionThenFocusesOriginalBoundsWithReadLock|PASS|ui-source-focus-protection-final|
|LibrarySourceNavigationUiTest|closingOrRecreatingPendingLibrarySourceCannotJumpOrCloseAnotherCard|PASS|ui-source-focus-protection-final|
|LibrarySourceNavigationUiTest|recycledLibrarySourceRetainsCurrentCardSnapshotAndMapWithoutNavigating|PASS|ui-source-focus-protection-final|
|NoteFirstUiTest|floatingFramePreservesPaperAndTemplateHasNoFakeCards|PASS|ui-source-focus-protection-final|
|MapAddendumUiTest|windowModesKeepCanvasAndEmbeddedEditUsesSameWindow|PASS|ui-source-focus-protection-final|
|LearningWorkbenchTest|learningOpensMapWithoutOpeningNoteAndKeepsHostConfiguration|PASS|ui-source-focus-protection-final|

新用例运行真实MainActivity、同一app Room/repository和原生画布。1440dp宽屏实际拖动/缩放窗口并切换COLLECT/ORGANIZE/FOCUS；回源范围换算到实际屏幕Rect，全部包含于native纸面并且不与study panel相交；保持临时停靠时真实拖动画布，再同卡回源重新定位。原右停靠、原最小化入口、实际header查找触控与一级恢复保持原frame/prefs/camera。375dp/字号1.6长文来源经Activity重建仍按新native尺寸聚焦；左右分屏600dp编辑半屏折叠、上下分屏1200dp半屏右停靠，切换后区域重fit且另一真实资料页与原图上下文保持。

27类作者/历史/回执SQL快照对测试本与另一资料本核对，覆盖笔迹、切割、对象、PDF源/块/页、搜索文本、卡片来源/节点/知识记录和回执；作者数据、原快照和历史无变化。选页与视野属于浏览元数据，不含在作者零改动结论内。四张成功断言后的未修改原生截图在evidence/native-ui；本地/设备哈希及视觉复核另存。

每次尝试均保留，不把失败改记通过：

- ui-source-focus-1：完成4，PASS 0 / FAIL 4，crash=False；原日志SHA `c9e3854d3eb924e68a4a8c11f30499f742ff790825d5e22dab87d4fe965a6fca`。
- ui-source-focus-2：完成4，PASS 3 / FAIL 1，crash=False；原日志SHA `e53dca0adac4c85a885c546e88e7442dbd11b2be2fa9181a42f9fe8d0cdf8ec8`。
- ui-source-focus-3：完成4，PASS 3 / FAIL 1，crash=False；原日志SHA `5f2598503384f5afa9b73a5128bc25fe50b60220ddeb729c942bbe2fbdd39493`。
- ui-source-focus-3-native-failure：完成0，PASS 0 / FAIL 0，crash=False；原日志SHA `5debc50eef0545fd47308016c0d7c817302aaa039bd9aeec412e38aac6e6e491`。
- ui-source-focus-4：完成4，PASS 4 / FAIL 0，crash=False；原日志SHA `53a758a9ed5f7e696499dceca24651f944fe29253cec019f83987edd655a0bcb`。
- ui-source-focus-protection-final：完成12，PASS 12 / FAIL 0，crash=False；原日志SHA `d1e20754436286a8cf79566d7b880146dad62a3cb714e3e15d01ee6250d93dbf`。

实际截图发现专注脑图缩到右侧后，原像素平移会使节点留在窗口外；已增加临时选中节点视野，原生节点边界和绘制截图均另作核对。临时相机不写入原视口或portal返回栈，异步原视口恢复只更新待恢复相机，“恢复窗口”还原原相机。独立静态复核发现的宽屏原最小化documentBar覆盖、相同bounds连续回源不重新fit亦已修正；页/请求身份、attach等待和原8dp边距已核对。源码完整preimage、迭代与build-before日志保留。最终源码439项摘要与实际候选/测试APK及lint对应。

合成资料与模拟器触控不等于真人手写、真实平板、实际窗口管理器分屏或人工无障碍验收；这些仍NOT_RUN。真实故障原迹/笔感与真实备份恢复未驗收，恢复NOT_VERIFIED。精确指定对话最新内容仍NOT_RETRIEVED，但本批有后续连续开发授权。没有远端或用户私有数据传输。

尚未覆盖：阅读页旧来源同步回调的异步保存失败；Library校验与选页的跨连接整本回收窄竞争；不能承诺取消回滚已经提交的浏览选页。700dp精确临界尺寸与不同厂商系统窗口应在后续硬件清单记录，不以600/1200dp模拟器结论代替。
