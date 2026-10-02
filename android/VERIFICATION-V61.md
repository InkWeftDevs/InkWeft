# V61 验证记录 · 2026-10-02

最终候选/安装APK SHA-256 `478bfdd5a03c85c6ed6a01b9e3a06b688a2d7780d45bf183bc1d33f15d6e94a9`；测试APK `cd8ce1605245393c755f29eb9c1cc552aa9e2417186b9701e1c950393028e9ad`；444项输入清单SHA `c3f5933b140e31578459f1d125b41cc22cb376809760c74124bed57ef060ce5f`。package、版本61、固定证书和实际设备base.apk摘要一致。专用AVD `InkWeft-ReadingTools-V61`、Android35、ADB5038/emulator-5556；原ADB5037未查询设备或改变服务。

**22个唯一用例通过：新增实际UI4，旧回归18。** lint 0错误/0致命/132警告。没有累加历史全域/数据库套件结果。

|类|用例|结果|原日志|
|---|---|---|---|
|ReadingToolsUiTest|narrowLargeFontRecreationAndFullscreenKeepReachableReadingActionsWithoutReplay|PASS|ui-reading-tools-confirmation-final|
|ReadingToolsUiTest|narrowSplitKeepsReadingAndHeaderActionsReachableWithoutChangingReference|PASS|ui-reading-tools-5|
|ReadingToolsUiTest|continuousReadingUsesExistingExcerptMapAndHeaderNavigationWithoutAuthorWrites|PASS|ui-reading-tools-5|
|ReadingToolsUiTest|wideReadingPreservesCollapsedPenCaseToolbarAndNativePenThenReturnsThroughGate|PASS|ui-reading-tools-5|
|ReadLockUiTest|readNavigationSearchSourceAndPortalReturnKeepAuthorRows|PASS|ui-reading-tools-protection-final2|
|ReadLockUiTest|summaryOutlineAndMapRejectActualAuthorIntents|PASS|ui-reading-tools-protection-final2|
|ReadLockUiTest|sessionSurvivesRecreationMinimizeAndOtherBookWithoutLeaking|PASS|ui-reading-tools-protection-final2|
|ReadLockUiTest|draftPreventsModeChangeAndKeepsExactText|PASS|ui-reading-tools-protection-final2|
|ReadLockUiTest|unknownOriginalCommandRetriesItsReceiptUnderReadLock|PASS|ui-reading-tools-protection-final2|
|ReadLockUiTest|paperNativeInputsAndObjectsStayReadOnlyThenResumeOnce|PASS|ui-reading-tools-protection-final2|
|SourceFocusVisibilityUiTest|originalRightDockAndOriginalMinimizedSurviveTemporarySourcePresentation|PASS|ui-reading-tools-protection-final2|
|SourceFocusVisibilityUiTest|narrow375LargeFontRetainsSourceRegionThroughRecreationAndRestoresItsFocusFrame|PASS|ui-reading-tools-protection-final2|
|SourceFocusVisibilityUiTest|movedResizedFloatingCollectOrganizeAndFocusExposeExactSourceThenRestoreOriginalWindow|PASS|ui-reading-tools-protection-final2|
|SourceFocusVisibilityUiTest|splitPaneRefitsRetainedSourceAcrossBothDirectionsWithoutChangingReferenceOrGraph|PASS|ui-reading-tools-protection-final2|
|BookSourceSelectAwaitUiTest|cancelBackRecreateAndOrdinaryPageOverrideCannotPublishAnOldSource|PASS|ui-reading-tools-protection-final2|
|BookSourceSelectAwaitUiTest|genuinelyRecycledSourcePageOrBookKeepsDetailsAndSnapshotWithoutFocus|PASS|ui-reading-tools-protection-final2|
|BookSourceSelectAwaitUiTest|sourceWaitsForRealDifferentPageSelectionBeforeDismissingAndFocusing|PASS|ui-reading-tools-protection-final2|
|BookPagesSelectAwaitUiTest|recycledBookIsRejectedEvenWhenTheCachedPageDirectoryStillContainsTarget|PASS|ui-reading-tools-protection-final2|
|BookPagesSelectAwaitUiTest|canceledOrSupersededAwaitCannotOverrideImmediateOrdinarySelection|PASS|ui-reading-tools-protection-final2|
|PageInsertionUiTest|beginningBatchKeepsOriginalInkAndPageIdentity|PASS|ui-reading-tools-confirmation-final|
|EditorToolsUiTest|toolbarHidingAndOrderPersistAndAlwaysRemainRecoverable|PASS|ui-reading-tools-protection-final2|
|EditorToolsUiTest|documentActionsReadOnlyFullScreenAndTimerAreOperational|PASS|ui-reading-tools-protection-final2|

新增测试使用真实MainActivity、同一个app Room和原生InkCanvasView/MindMapView，通过实际显示后的触控调用入口。核对27类作者/历史/回执数据及BLOB指纹，另一资料本也保持。宽屏用真实双指缩放/平移；窄屏1.6倍字体覆盖Activity重建、菜单、全屏及返回书写；连续页覆盖摘录、导图、查找、概览和设置；375dp左右分屏核对186.5dp阅读侧各48dp实际触控区域及原生参考画布身份/只读状态。没有模拟repo、独立SQLite连接或以semantics click绕过遮挡。

旧回归重跑ReadLockUiTest6、SourceFocusVisibilityUiTest4、BookSourceSelectAwaitUiTest3、BookPagesSelectAwaitUiTest2、加页保留原迹1、工具栏自定义1、文档操作/只读/全屏/计时器1。V59来源位置及原生导图节点可见、原窗口恢复和双向分屏以及V60真实选页等待/取消仍通过。

五张未修改原生截图在evidence/native-ui，设备SHA和本地文件摘要相同。visual-review.json记录实际图像观察；截图仅为本批合成模拟器证据。

失败与修正完整保留：

- ui-reading-tools-1：完成4，PASS 3 / FAIL 1，crash=False；日志SHA `71c9074e28aaff44c64319cce46fc66e16ff275164d0bb3cf3e6729ddc603bf9`。
- ui-reading-tools-2：完成4，PASS 3 / FAIL 1，crash=False；日志SHA `ca89b9a0184cb0be3d6036a82231cedcdf97a018066b4dff8ba14c4f151b3879`。
- ui-reading-tools-3：完成4，PASS 3 / FAIL 1，crash=False；日志SHA `cfc2c6fc4fdbf1a1e404fe6bc44737491c49bf8fc66fab9ea6d70e91162542d4`。
- ui-reading-tools-4：完成4，PASS 4 / FAIL 0，crash=False；日志SHA `cd0207df5eb9542f298068e5bf7f8508c747bf8f210002b67cac3fd6c5996b81`。
- ui-reading-tools-5：完成4，PASS 4 / FAIL 0，crash=False；日志SHA `d017a6154629e6c4c79fd65ca12e06f4efc36a3e542f5fdc74aef6e83f1eafd9`。
- ui-reading-tools-confirmation-final：完成2，PASS 2 / FAIL 0，crash=False；日志SHA `a5a21012ff094f3a8ff6bb9b40e23a2b227e3ae48ea61cbe14f1221aa97b0ae2`。
- ui-reading-tools-protection-final：完成18，PASS 17 / FAIL 1，crash=False；日志SHA `b0c52f636c728a4b61bf239030f210082250dd240f2142c676820a0ab66bb1df`。
- ui-reading-tools-protection-final2：完成18，PASS 17 / FAIL 1，crash=False；日志SHA `292b63053653247505b86d61e4c81240778d39bccb6fc7b2a19540056f0b8725`。

首次测试包编译失败是夹具对不可变InkStroke调用copy；改为原构造函数并保持全部参数、身份和断言，旧字节和编译日志保存在iteration-source及test-compile-correction.json。首轮新增UI3/4通过，窄分屏在参考标题可见断言失败：原单行头部按钮使标题无可见宽度，原生参考笔迹/身份检查此前已通过。修复NotebookReferencePane窄头换行和页码布局后重建最终候选，全部断言不删减；失败日志、444项输入、测试包、原源码及四张已成功截图保存在failed-ui-first-preimage。

第二轮新增UI3/4通过，窄分屏第二页搜索结果尚未进入LazyColumn可见范围，测试等待语义节点超时。测试补上对真实book-search-results的performScrollToNode和assertIsDisplayed；原生参考/48dp几何/作者断言均保留，生产APK摘要不变。该次完整输入、测试包与日志保存在failed-ui-second-preimage，修正记录narrow-search-test-fix.json，独立测试作者只读确认了列表虚拟化边界。

第三轮新增UI3/4通过，参考标题、阅读和头部48dp区域及真实滚动查找均通过；打开导图后原48dp单行头部挤出关闭按钮。仅把StudyWorkspace紧凑头部改为FlowRow且高度按内容测量，回调、标签和作者状态不改，FloatingStudyWindow几何/拖动/相机/sourceReading折叠48dp逻辑保持。失败证据在failed-ui-third-preimage。

首轮受影响旧回归17/18通过，旧documentActions夹具尝试点击已固定且不生成visibility toggle的finger项，在进入只读断言前失败。仅用EditorToolOrder.fixed过滤自定义夹具列表，保留只读/全屏/计时器/导出/加页/美化的全部原断言；生产APK不变，完整输入和旧测试包/字节在failed-protection-preimage，记录legacy-fixed-tool-fixture-fix.json。修正后新增4通过；旧回归第二轮17/18通过，旧工具已通过，加页原用例在既有手指模式已开启时再次无条件切换，把书写关闭，等待笔迹提交超时。仅改加页夹具在新页载入后按原持久模式决定是否点击启用，保留原迹ID/samples/页序和全套断言；失败证据在failed-protection-second-preimage。最后仅重跑加页和被Android首次全屏教学提示遮挡的窄全屏用例，原17项不因截图再次整套重跑，按最新实际结果合并22个唯一用例。

静态独立检查记录editor-final-review、ux-final-review、reference-final-review和study-header-final-review，区分静态结论与本轮实际测试。原证据保留，未把失败改记为通过。

未测：真实硬件、真人手写/原故障、物理分屏和人工无障碍验收NOT_RUN；真实备份恢复NOT_VERIFIED。全屏查找直接入口及默认只读入口尚未实现。170.5dp来源阅读折叠栏仍沿用原48dp单Row，本轮没有证明其全部按钮可达；展开导图头部真实close已验证。搜索/摘录仍沿用即时包装；Library双事务与选页提交后跨连接回收竞争为既有边界，取消不会回滚已提交浏览元数据。
