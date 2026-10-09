# V78 验证记录 · 2026-10-09

V78已同包名、原签名覆盖安装到日常insertion应用；15项不同的定向实机方法与379项核心测试通过，资料保留核对完成。真人用例保持待测。

旧故障页共1000笔、841笔可见、159笔隐藏历史。旧容量逻辑把历史计入当前额度，移动也重复占用；三个相关Core用例在旧逻辑失败，修复后通过。当前每页1万可见笔/50万采样点，当前与历史合计另有2万笔/100万采样点边界。保留原始数据和撤销，不执行历史清理。Room16及数据库结构不变；超过旧1000笔/10万点的页面副本须使用V78或更新版本读取。

完整Core379项通过，0失败、错误或跳过。R4整体lint通过，171 Warning、2 Hint、0 Error/Fatal；R5仅增加authoringUi.state这一项Effect key，精确源码差异已经核对，以编译和原先失败的实际拖动用例复验，不重跑整套lint或Core。没有将旧检查标为本轮新执行。

实机为iPA2673 / API36。全部仪器方法只使用独立feedback包或测试自有临时数据库；两份真实资料库没有运行创建或重置库的仪器方法。PASS要求准确class#method的1→0事件、OK(1 test)、最终-1状态、实际断言与包身份核对。每项最多一次有条件的初始MAIN，实际次数保留在原始结果；结束时核对并恢复显示/输入设置。

|实际方法|结果|本机尝试目录|
|---|---|---|
|`AsyncRasterTest#editedFallbackPreservesOverlappingPencilAndHighlighterPixels`|PASS|`native-v78-remaining-tests`|
|`AsyncRasterTest#eraseKeepsUnaffectedInkVisibleBeforeReplacementFrameCompletes`|PASS|`native-v78-remaining-tests`|
|`AsyncRasterTest#backgroundRasterPreservesPixelsAndRejectsStaleWork`|PASS|`native-v78-remaining-tests`|
|`TabletFeedbackRegressionTest#largePageMoveEraseWriteUndoAndReopenRetainOriginalBytes`|PASS|`native-v78-remaining-tests`|
|`TabletFeedbackRegressionTest#manualBeautyKeepsBoundaryNeighbourAfterSaveAndReopen`|PASS|`native-v78-remaining-tests`|
|`TabletFeedbackRegressionTest#multipleImagesCommitTogetherUndoTogetherAndRejectBadBatch`|PASS|`native-v78-remaining-tests`|
|`TabletFeedbackRegressionTest#convertedTextStaysAboveLaterImageAndHitTestingMatches`|PASS|`native-v78-remaining-tests`|
|`TabletFeedbackRegressionTest#fingerPansWhileStylusSelectsAndWholeErasePreviewsBeforeUp`|PASS|`final-v78-ui-tests`|
|`TabletFeedbackRegressionTest#incrementalWholeEraseMatchesPaintedMaskAndRecordsCost`|PASS|`native-v78-remaining-tests`|
|`BeautyReviewUiTest#reviewUsesPaperCoordinatesPreservesNeighboursAndOffersOriginalComparison`|PASS|`final-selection-v78-tests`|
|`TabletFeedbackUiTest#selectionRuleAndShapeAreIndependentAndEraserShowsSize`|PASS|`final-v78-ui-tests`|
|`TabletFeedbackUiTest#heldStylusKeepsActualToolbarPixelsWhileDisablingTools`|PASS|`final-v78-ui-tail-tests`|
|`FormulaFlowTest#confirmationCorrectionUndoAndRestoreKeepOriginalInkByteExact`|PASS|`final-selection-v78-tests`|
|`AsyncRasterTest#panPaintsNewlyExposedInkBeforeWorkerCanPublish`|PASS|`repaired-v78-tests`|
|`TabletFeedbackUiTest#smoothingKeepsSelectedInkAndStylusDragDoesNotWrite`|PASS|`final-selection-v78-tests`|

合计15个不同方法、20次PASS执行。来源和APK不是用次数推断：每次都绑定准确签名APK、测试APK、源码文件摘要与完整freeze，复用未变的核心、存储和测试代码。逐项构件、时长、初始前台辅助和原始日志摘要在本机runtime-pass-summary.json中保存。

容量用例覆盖1万笔、移动、擦除1001笔的SQLite分块、继续写入、撤销、关闭数据库重开、页面副本和真实整库备份/恢复，原迹字节保留。平移用例在同一个UI回合执行3次MOVE，工作线程不能中途发布，有限与无界的全帧像素都与直接绘制一致。润色用例实际持笔拖动结果，保存后保留原迹/润色/移动三个版本，没有写出新笔迹。工具栏用例在实际MainActivity持笔三次，禁用语义和整个工具栏像素稳定同时成立。图片用例验证多选Intent、两张原件、单次事务/撤销/重做与坏批次不部分保存。

整笔擦除合成样本为240笔×100点、橡皮300点：旧算法763.872ms，新逻辑累计30.198ms，单块最高4.255ms，命中14笔且与旧绘制遮罩结果一致。数据是该热缓存样本，不代表真人笔感或持续书写延迟。原始JSON本机保存。

早期平移像素失败与润色选区失败均保留。润色替换更新图层成员，但作者版本号可不变；最终修复订阅成员状态，以同一个失败用例确认恢复。空ComponentActivity工具栏宿主被OEM内核冻结，未进入断言，记为环境阻断；实际MainActivity工具栏方法已通过。没有修改安全/电源策略。

本机源码位于 `E:/Inkweft-tablet-feedback-20261008`；本轮测试包构建时使用未提交工作树，随后按授权提交并推送源码。基线 `ab00622086f34fafaecbb68767962a911c535b62` 只代表起点；构件SOURCE_COMMIT/BUILD_COMMIT为local-unknown，实际修复身份由完整文件freeze、补丁摘要与34份变更源码归档确定。

|身份|SHA-256|
|---|---|
|最终源输入freeze|`83f2c1a8d63b70bf8854818b87e55b27d2051173086880e38caafdc75a297c43`|
|源补丁（新增文件另由freeze/源码包覆盖）|`613234223d625bada017224e5e7d654e704a7656ffc4273eac63f114db8b05cf`|
|34份变更源码ZIP|`4529da6c0713f45bc1046dcc455927cf2aa1fa2a990cffc58890c6e523db0baa`|
|最终隔离feedback主APK|`b3476d35fb74d08bc808c39c120dafd7d967399cfffd678da408f47c6d1813da`|
|已安装insertion APK|`d22efce60d974912ee8cc8edf7386c7824e32fdef1499fd6f18eccc95f8513f1`|
|未变的R3测试APK|`4e8ca9dc647923684f04964124eb2b1a06d36d15cac2e6dddadee1975684a535`|

两份真实资料库各44表，已取得完整内部/外部只读稳定备份，SQLite完整性及外键检查通过。诊断开始到最终升级前，仅insertion一页centerY视野字段变化；作者内容、原件、偏好与外部文件一致。安装前38个数据库/作者文件/偏好再次与最终备份核对。覆盖安装PASS；安装结束、首次启动前38份原作者/数据库/偏好文件逐字节一致。普通冷启动531ms，重新打开“1.1函数”2/2，841笔可见笔迹、12对象进入SAVED状态；没有在真实页注入测试笔迹。升级后两个库各44表，按主键及字段/BLOB逐项比对：insertion9625行、workspace16715行，作者内容与所有计数/结构保留，22+53份偏好及14+106份外部文件保持一致。

严格全字段比较的初次结果保留为REVIEW_DIFFERENCES；差异全部核清：insertion仅原页面centerY减少0.602466626471255，与新增1dp快捷栏分隔引起的resizedFrom调整值-1/(2×zoom)在1e-9内相等，其他列完全一致；另仅files/profileInstalled的24字节运行时缓存中安装更新时间字段变化，与系统包安装时间匹配，其schema、编译状态、profile大小不变。[AndroidX ProfileVerifier源码](https://github.com/androidx/androidx/blob/androidx-main/profileinstaller/profileinstaller/src/main/java/androidx/profileinstaller/ProfileVerifier.java)说明该文件用于安装profile状态缓存。workspace全部44表字节规范指纹不变。两处变化没有当作作者内容变更，也没有掩盖初次差异。

原始尝试、冻结输入、签名构件、完整备份与PNG本机归档于 `E:/Inkweft/archives/2026-10-09/TabletFeedbackV77`，私人笔迹、完整备份与设备标识不上传。实机步骤见[清单](../../DEVICE-TEST-CHECKLIST.md)，使用[结果模板](results-template.md)逐设备、版本和尝试记录。真人笔感、长期闪烁、实际邻字识别内容、硬件观察与备用设备故障仍待测；基础诊断ZIP不含完整系统日志，仅补充日志无法表达的录像与笔感。

交付安装包：[V78 APK](E:/Inkweft/dist/TabletFeedback-V78/InkWeft-v78-insertion-app.apk)。同目录保留完整source-freeze、source.patch、34份源码ZIP和签名构建回执。验证完成后的源码与文档按授权提交、推送；已交付APK保持原字节与local-unknown构建标记，其构建来源仍以上述freeze和源码包为准，不能用后来的Git提交号冒充包内构建身份。临时材料与最终证据的收尾结果见本机cleanup-final.json。
