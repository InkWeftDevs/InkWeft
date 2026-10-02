# V62 验证记录 · 2026-10-02

最终候选/安装APK SHA-256 `c68eea53228148e080c7ec420350aa874d91308d7315e171d1ce0cae35ae5a07`；测试APK `2a4df5bf6751c1e36623d1d2ebb1891c03508513195e1eb37ee76f6af086af76`；445项输入清单SHA `ff928249c093bb4bae6bf3f68c3f7847972ba6c7940c2c8e8a8a4c1010653a30`。package、版本62、固定证书及实际base.apk摘要一致。专用AVD `InkWeft-ReadingAccess-V62`、Android35、ADB5038/emulator-5556；原ADB5037未查询设备或改变服务。

**19个唯一用例通过：新增实际UI4、受影响旧回归15。** lint 0错误/0致命/132警告。不累加失败重试、诊断单项或历史全域/数据库套件结果。

|类|用例|结果|原日志|
|---|---|---|---|
|ReadingAccessUiTest|defaultSettingsEntryPreservesHiddenWritingToolsPreferencesAndRecreation|PASS|ui-reading-access-2|
|ReadingAccessUiTest|openSettingsLiveGateRejectsQueuedOriginalReceiptHeldNativeInputAndRealPreviewDraft|PASS|ui-reading-access-2|
|ReadingAccessUiTest|narrowSplitFullscreenSearchAndOverviewRemainReachableAndRetainLockReferenceAndPage|PASS|ui-reading-access-2|
|ReadingAccessUiTest|fullscreenSearchCloseKeepsActualSourceBoundsSnapshotAndGraphContextVisible|PASS|ui-reading-access-2|
|ReadLockUiTest|readNavigationSearchSourceAndPortalReturnKeepAuthorRows|PASS|ui-reading-access-protection-final|
|ReadLockUiTest|summaryOutlineAndMapRejectActualAuthorIntents|PASS|ui-reading-access-protection-final|
|ReadLockUiTest|sessionSurvivesRecreationMinimizeAndOtherBookWithoutLeaking|PASS|ui-reading-access-protection-final|
|ReadLockUiTest|draftPreventsModeChangeAndKeepsExactText|PASS|ui-reading-access-protection-final|
|ReadLockUiTest|unknownOriginalCommandRetriesItsReceiptUnderReadLock|PASS|ui-reading-access-protection-final|
|ReadLockUiTest|paperNativeInputsAndObjectsStayReadOnlyThenResumeOnce|PASS|ui-reading-access-protection-final|
|ReadingToolsUiTest|narrowLargeFontRecreationAndFullscreenKeepReachableReadingActionsWithoutReplay|PASS|ui-reading-access-protection-final|
|ReadingToolsUiTest|narrowSplitKeepsReadingAndHeaderActionsReachableWithoutChangingReference|PASS|ui-reading-access-protection-final|
|ReadingToolsUiTest|continuousReadingUsesExistingExcerptMapAndHeaderNavigationWithoutAuthorWrites|PASS|ui-reading-access-protection-final|
|ReadingToolsUiTest|wideReadingPreservesCollapsedPenCaseToolbarAndNativePenThenReturnsThroughGate|PASS|ui-reading-access-protection-final|
|DocumentPanelsUiTest|overviewSwitchesPagesWithoutClosingAndSettingsActionsReachTheRealFlows|PASS|ui-reading-access-protection-final|
|EditorToolsUiTest|toolbarHidingAndOrderPersistAndAlwaysRemainRecoverable|PASS|ui-reading-access-protection-final|
|EditorToolsUiTest|documentActionsReadOnlyFullScreenAndTimerAreOperational|PASS|ui-reading-access-protection-final|
|PdfTextSearchUiTest|narrowLargeFontUsesRealTouchesAndPdfRegionCanStillBeExcerpted|PASS|ui-reading-access-protection-final|
|PdfTextSearchUiTest|pdfAndManualHitsMergeByPageAndOpenTheOriginalRegionInReadMode|PASS|ui-reading-access-protection-final|

新增测试使用真实MainActivity、同一个app Room、原生InkCanvasView/MindMapView及SelectionOverlayView；实际显示后触控设置Switch/菜单，核对27类作者/历史/回执及BLOB指纹、另一参考本、写入偏好及Undo/Redo。默认readonly仍隐藏，未用自定义打开旧入口；旋转/重建保持真实阅读锁。Switch拒绝仍可点击，以显示实际原因，既不提前禁用也不通过私有回调绕过。

门禁用例先真实prepareGroup排队并在同一Main立即按Switch的屏幕坐标投递DOWN/UP，证明不是仅靠上次SideEffect就绪快照。原作者命令实际提交一次后建立指纹基线；Unknown只合成丢失UI回执，不声称真实存储故障。原retry经同app Room WriterGate排队，释放并join后比对全部指纹，原回执不重复；门禁同时覆盖已打开设置中的真实短笔DOWN/MOVE后CANCEL、原生摘录预览及真实取消。返回书写保留用户摘录工具，其SelectionOverlay恢复输入，不强制切笔或更改手指偏好。

窄屏为375dp、字体1.6、左右分屏186.5dp编辑侧；新增全屏查找/概览两项逐项检查实际48dp触控区域和Popup内完整边界。查找先真实滚动LazyColumn使目标可见再触控，关闭/重新打开保留query；查找选页、概览选页/关闭、Activity重建保持全屏/阅读、实际原生参考本身份/笔迹/只读。来源用例核对真实来源区域在画布内且不与浮窗相交、实际fit相机、快照摘要、导图选择与框架偏好；全屏搜索关闭前后保持。

旧回归重跑ReadLockUiTest6、ReadingToolsUiTest4、文档概览/设置1、工具栏自定义1、文档操作/阅读/全屏/计时器1、PDF及人工查找选原区域1、PDF窄大字并摘录1。V61工具栏、默认偏好恢复、已有写入门禁与原PDF行为仍通过。本轮没有重跑历史全域、核心或数据库全套。

四张未修改原生截图在evidence/native-ui，设备SHA与本地文件摘要相同；visual-review.json绑定实际图像观察、候选和最终输入清单。空白AVD预先确认Android首次全屏教学提示并读回，记录system-fixture.json，未更改真实设备设置。

失败与修正完整保留：

- ui-reading-access-1：完成4，PASS 0 / FAIL 4，crash=False；日志SHA `24e1a69ca44ffcbb46636dc7b57467ae4643a1f1473828dca010cb97b85560e0`。
- ui-reading-access-2：完成4，PASS 4 / FAIL 0，crash=False；日志SHA `876aa1e3bc1758245431fd0893a3c0a1293f01643292b98c14ef78728d10a419`。
- ui-reading-access-diag-default：完成1，PASS 0 / FAIL 1，crash=False；日志SHA `105fc4f306aef2758619f10682c20af22437288813f949a522821188e9975f29`。
- ui-reading-access-protection-final：完成15，PASS 15 / FAIL 0，crash=False；日志SHA `4c107b00788f44acbef1f477cb94123e34cb84cfa1a241cf3102c5f842a3fc79`。

首轮新增0/4通过，共同停在设置Switch后等待reading-toolbar；门禁用例此前的排队/原回执/原生手势/草稿拒绝断言均走完。失败日志、输入、原测试包及源码在failed-ui-first-preimage。仅加入读取真实锁/InkUi/ObjectsUi的失败诊断，单项仍失败：真实点击出现拒绝提示，锁false而guards为空、navigation=true、queue0/blockednull/processingfalse且无对象操作。原生失败图和日志完整保留。独立读取已编译FunctionReferenceImpl确认捕获navigationReady/externalEnabled的局部函数引用equals只比较函数元数据，不比较这些字段；rememberUpdatedState默认结构相等可忽略新闭包并留在初次loading状态。仅换为普通捕获lambda更新回调，原changeReadOnly及所有功能断言不变，重新构建/验证。修正记录callback-freshness-fix.json和独立复核记录绑定源码/class摘要；实际最终结果见上表，失败未改记PASS。

未测：真实硬件、真人手写/原故障、物理分屏和人工无障碍验收NOT_RUN；真实备份恢复NOT_VERIFIED。170.5dp来源阅读折叠栏仍沿用原48dp单Row，本轮没有证明其全部按钮可达；V61展开导图头部close已验证。搜索/摘录仍沿用即时包装，Library双事务与选页提交后跨连接回收竞争为既有边界，取消不会回滚已提交浏览元数据。
