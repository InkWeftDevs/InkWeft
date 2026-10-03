# 链接阅读与反向引用：本地设备报告（2026-10-03）

本报告接续本批云端交付，区分固定基线、临时诊断、普通应用检查与最终 local-ui 候选。最终UI02原7项辅助真机测试全部PASS，普通应用375dp／1.6字号／120字标题实际滚动至正文通过；原83本4379行及原偏好已核对保持。固定基线UI为3通过／4失败，中间UI01为4通过／3失败；独立Room11项无辅助基线通过。PR11历史通过、云端编译和本批局部证据不转记为本批候选完整通过。

## 构件和设备身份

|字段|固定基线 B|最终UI02候选 C|
|---|---|---|
|源码身份|commit `b466ad776424e219796f84dfdd6ff6381c230872`，tree `3a041d4d2579ec516ec5e8204747058d233980bb`|同一基线＋未提交两文件补丁 `263179b829613a0c66e9ca2361f17565fa4b81930dadd39985c7b13d9b081854`；不将基线tree冒充新tree|
|实际构建输入|771个tracked输入在基线构建前后及最终核验一致|771项在assemble／lint前后稳定；manifest SHA-256 `92102ae67cf66df76d4e05b00f905654337f1d53a61a48f1b0607af7d792f43f`；文档收尾在构建后进行|
|主包|`org.inkweft.app.a0.workspace`|同包名，最终设备安装SHA-256 `ba4fcedb31dc057db4fc1325836eec031141bcd033606582af542daa7102405a`|
|主包版本|68 / `0.0.68-cloud-candidate`|68 / `0.0.68-cloud-candidate`，以APK和源码补丁身份区分|
|app-test|`org.inkweft.app.a0.workspace.test`，`androidx.test.runner.AndroidJUnitRunner`|复用基线同字节runner；targetPackage为`org.inkweft.app.a0.workspace`；原7方法、断言及timeout不改|
|Room runner|`org.inkweft.data.test`，`androidx.test.runner.AndroidJUnitRunner`|复用同字节self-target runner，targetPackage为`org.inkweft.data.test`；沿用基线独立11项结果，未在最终主包上重跑|
|runner版本|两份测试APK回执版本字段为空|仍为空，不套用主包版本|
|设备系统|真机Android16 / API36|同一真机；型号未在所引用身份回执登记；原尺寸2512×3840、density480、fontScale1.0；375dp／1.6字号为明确记录的测试配置|
|构建与签名|三包原证书及安装摘要一致|仅重建主包；assemble与app lint均PASS（0 Error／0 Fatal／137 Warning，改动文件无issue），活动DEX无临时probe，原证书核验通过|

最终构件：本地 `build-local-ui02/artifacts/InkWeft-link-ui-b466ad77-263179b8.apk`。构建及输入回执 `build-local-ui02/checks/final-verification.json`、`build-local-ui02/inputs/local-source-receipt.json`；安装回执 `physical-local-ui02/installation.json`；仓库脱敏汇总见 [local-checkpoint.json](local-checkpoint.json)。

基线三包完整SHA-256：

|包|SHA-256|
|---|---|
|app|`0a128900e739867588e2d9c5c1fd9c9ad173432c9edeed9d9641dc56fd45eb15`|
|app-test|`e276e95f58ad64a2e45f3bb24f3cd65abc0e6e2b7ab851e7a1becb7019cb09c9`|
|Room runner|`1ffb8681569672581189a0a8439e541d24e0487ef51ce80bcf8cbe6129559a34`|

三包证书SHA-256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。身份回执：本地归档 `build-baseline01/checks/{app,app-test,room-test}/receipt.json`、`build-baseline01/checks/final-verification.json`、`physical-baseline/installation.json`。源码／打包dex精确范围为UI7方法、Room11方法。

## 固定基线实际运行

|运行类别|实际结果|计数及边界|
|---|---|---|
|Room，PHYSICAL_UNASSISTED|11 PASS / 0 FAIL / 0 NOT_RUN|独立UUID合成数据库；无前台启动辅助|
|UI，首次前台启动辅助|3 PASS / 4 FAIL / 0 NOT_RUN|7个不同方法；每次仅初始MainActivity前台辅助，正文与重建阶段无第二次辅助|
|UI，无辅助控制一次|NOT_RUN，ENVIRONMENT_BLOCKED_KERNEL_FREEZE|有START无完成，约5.985秒出现kernel freeze；不并入上行7项分母，不作为产品断言FAIL|
|普通应用中文IME局部检查|输入与筛选完成；取消遮挡FAIL|无instrumentation、ADB实际触控屏幕键／候选栏，未提交关联；不是完整候选验收|

无辅助控制只停止本任务instrumentation，没有解冻进程、改变冻结／后台限制或清除用户数据。Room回执 `physical-baseline/room11/results.json`；控制回执 `physical-baseline/ui-unassisted-control/results.json`、`physical-baseline/ui-unassisted-control/bounded-stop-receipt.json`。

以下类均为 `org.inkweft.app.KnowledgeTextLinksUiTest`；这是基线逐方法结果，后续候选或诊断不能覆盖这些记录。

|方法|基线结果|秒|本地回执|
|---|---|---:|---|
|`inlineTitleAndAliasOpenConfirmedTargetWhileDuplicateNamesRequireChoice`|FAIL|23.062|`physical-baseline/ui4-assisted/results.json`|
|`reopenedLivePreviewReadsCurrentBodyWhilePinnedPreviewKeepsExactHistory`|FAIL|22.843|`physical-baseline/ui3-assisted/01/runtime/results.json`|
|`pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting`|PASS|9.703|`physical-baseline/ui3-assisted/02/runtime/results.json`|
|`replacedAndRemovedLinkAreRevalidatedBeforeOpenWithoutSilentlyChangingTarget`|PASS|9.656|`physical-baseline/ui3-assisted/03/runtime/results.json`|
|`backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites`|PASS|10.969|`physical-baseline/ui6-assisted/results.json`|
|`pageAndNotebookBacklinksKeepDistinctScopeAndReadOnlyPreviewIdentity`|FAIL|25.547|`physical-baseline/ui6-assisted/results.json`|
|`narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable`|FAIL|23.297|`physical-baseline/narrow-display/01/runtime/results.json`|

已查看的正文／别名及PAGE／NOTE失败截图在15秒等待结束后仍显示预览加载、没有正文且打开按钮禁用。大字方法在首个预览失败，后续120字标题及控件检查没有执行。基线失败不等于已确认普通应用同路径必现，也不能仅凭失败截图定位协程、Room或调度根因。

## 临时诊断：单列，不计正式验收

临时诊断包的inline方法一次PASS，回执 `preview-trace-diagnosis/runtime-once/results.json` 标记 `diagnostic=true`、`formalAcceptanceEligible=false`。原断言未改不意味着诊断与基线执行条件相同：trace额外读取父State可能改变重组依赖。该结果不能覆盖基线inline FAIL，不能定位此前4项FAIL，也不并入最终候选7方法计数。最终UI02活动DEX的trace清除与实际字节核验已由 `build-local-ui02/checks/final-verification.json` 确认。

第二阶段诊断 `preview-stage2-diagnosis/runtime-once/results.json` 同一原inline方法PASS，11.438秒，仍为 `diagnostic=true`、`formalAcceptanceEligible=false`。新probe未增加父State读取，但仍未捕获失败边界；四次effect从main开始，collect／state.assigned记录位于worker9、正常取消，只说明本次实际调度，不确认具体丢更新竞态。两次诊断PASS均不定位此前失败根因，也不替代正式候选回归。

## 普通应用视觉检查与三项修复

本节记录无instrumentation的普通应用路径；已执行的屏幕输入通过ADB真实触控完成，不称为真人验收。

|问题／LD用例|基线事实|已实施的最小修复|已记录的普通应用结果／版本|
|---|---|---|---|
|重复目标入口／LD-02|同册初始卡片的“初始对象”和实际卡片入口均checked；同一TargetRef重复呈现|NOTE和CARD入口按完整TargetRef排除initialFocus，保留PAGE与NOTE区别及同名不同ID|UI01同册CARD去重通过；其他组合以各自自动化／记录为准|
|来源预览宽度／LD-05|横屏3840×2512、density480，侧栏1200px=400dp符合实现；来源预览约3686px=1229dp，超过640dp|来源预览先widthIn(max640dp)后fillMaxWidth(.96f)，宽屏约614dp仍满足上限|UI01实测614.33dp；UI02窄窗长标题与按钮另验通过|
|选择器中文IME遮挡／LD-06|实际拼音屏幕键tongce→候选“同”→“册”，字段“同册”、筛选命中、linkSubmitted=false；取消文字底端1545，越过键盘上边界约1514；完整按钮范围更大|选择器根safeDrawingPadding().imePadding()，DialogProperties(decorFitsSystemWindows=false)，保留原滚动、筛选及提交语义|UI01真实中文IME输入及取消通过；UI02未重复此组合|

三项基线问题均有普通应用截图与边界证据。原IME截图包含受保护旧资料，仅本地保密归档；本报告不嵌入或复制该图。IME局部记录见 `reports/manual-ime-baseline.json`。`reports/baseline-results.json` 的 `ordinaryManualStatus=NOT_RUN_YET` 是较早检查点，不能据此否定之后已执行的普通应用步骤。

## 中间候选 local-ui01：4 PASS / 3 FAIL

此轮是正式测试资格的中间UI候选，**不是最终交付候选**。七个原方法实际完成，合计 **4 PASS / 3 FAIL / 0 NOT_RUN**；正常窗口六项为4P2F，大字窄窗一项FAIL。基线3P4F原样保留，以下结果不移填到后文最终候选栏。

身份为基线commit／tree加本地补丁SHA-256 `620396566a0632cf39e5262faf6099f1bf8eb05b9c9f0f9ba0f7ca5f35f7448e`；771项构建输入manifest SHA-256 `c8c02f17c69a84b548d6be9b6096270af69b49edec2f3fa7285077f9061fdee4`。只修改两个源码文件的四行UI；测试源码、断言、timeout及data-local输入未改。app APK SHA-256 `852b55419ff045af1d5c870d4b19e9d8533150df949b60c13c5afeeca019b27f`，app-test与基线同字节，Room APK沿用基线且本轮未重建／复跑。主包原位覆盖安装，原app-test与Room包保持同字节并核对安装哈希；未卸载或清数据；签名及安装摘要见 `physical-local-ui01/installation.json`。

构建核验见 `build-local-ui01/inputs/local-source-receipt.json`、`build-local-ui01/checks/final-verification.json`：构建／lint前后771项输入一致，trace标记缺席，app lint最终PASS（137条Warning，两处改动文件无issue）；首次离线lint缺依赖的失败记录仍保留。这些构建结论不代替下列运行结果。

|方法|UI01结果|秒|本地回执|
|---|---|---:|---|
|`inlineTitleAndAliasOpenConfirmedTargetWhileDuplicateNamesRequireChoice`|FAIL|23.562|`physical-local-ui01/ui6/results.json`|
|`backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites`|PASS|11.218|`physical-local-ui01/ui6/results.json`|
|`pageAndNotebookBacklinksKeepDistinctScopeAndReadOnlyPreviewIdentity`|PASS|12.688|`physical-local-ui01/ui6/results.json`|
|`narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable`|FAIL|25.297|`physical-local-ui01/narrow/01/runtime/results.json`|
|`reopenedLivePreviewReadsCurrentBodyWhilePinnedPreviewKeepsExactHistory`|FAIL|23.578|`physical-local-ui01/ui6/results.json`|
|`pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting`|PASS|10.718|`physical-local-ui01/ui6/results.json`|
|`replacedAndRemovedLinkAreRevalidatedBeforeOpenWithoutSilentlyChangingTarget`|PASS|9.563|`physical-local-ui01/ui6/results.json`|

失败阶段按同一基线测试源码核对：

- inline在326行失败：标题入口首个预览通过，返回后第二次别名预览等待正文超时；后续同名选择尚未执行。
- live/pinned在365行失败：首个实时预览通过，第二个固定预览等待正文超时；367行之后修改正文及再次比较尚未执行。
- narrow在636行失败：625行首个实时预览、626–627行按钮可达性，以及随后同名选择器检查已完成；635行选择同名目标后，第二个预览等待正文超时。641行之后120字标题未执行。

三者均在 `assertPreview` 等待 `card-link-preview-body` 时触发 `ComposeTimeoutException`，只定位失败阶段，不确认根因。narrow的这部分instrumentation证据不能代替普通应用完整大字、系统分屏或真实中文IME验收。

七方法各一次初始前台辅助，共7次；正文及重建阶段无第二次辅助，此轮没有无辅助重试。`physical-local-ui01/narrow/01/settings-receipt.json` 及 `physical-local-ui01/narrow/display-results.json` 记录前后10项设置一致；其中测试状态FAIL不表示设置恢复失败。该轮为中间记录；最终UI02的独立身份及实际结果见后文。

### UI01普通应用路径：已完成的视觉片段

以下全部属于上述已安装UI01主包SHA-256 `852b55419ff045af1d5c870d4b19e9d8533150df949b60c13c5afeeca019b27f`；没有instrumentation，不是两次诊断包或最终候选。操作方式为ADB真实屏幕触控。已独立查看01、02、03、05、06、07、08、09、10、11截图，并核对相关XML；04未纳入本次独立复核。原始图与XML保持本地，本报告仅记录量化汇总。

1. **卡片入口和目标预览：已完成所述片段。** `visual-local-ui01/01-card-entry.png` 显示原“保持原卡”及反向引用入口；`visual-local-ui01/02-target-preview.png` 显示目标“条件概率”、当前正文LIVE-V2以及完整的返回／打开按钮。对应 `physical-ui/local-ui-card-entry.xml`、`physical-ui/local-ui-inline-preview.xml`。这不等于所有别名、固定修订和重复打开路径通过。
2. **作用域入口去重：本次同册CARD场景通过。** `visual-local-ui01/03-backlinks-deduplicated.png` 与 `physical-ui/local-ui-backlinks-portrait.xml` 的作用域行范围为 `[48,842][1260,986]`；4个chip中仅“初始对象” `checked=true`，其余3个为false。此计数只针对作用域行，不是整棵UI树的checked总数；其他TargetRef组合仍按LD-02分别记录。
3. **横屏来源预览宽度：本次尺寸检查通过。** `visual-local-ui01/05-source-preview-landscape.png` 与 `physical-ui/local-ui-source-preview-landscape.xml` 的白色预览容器为 `[998,886][2841,1740]`，宽1843px；density480即3px/dp，得到 **614.33dp≤640dp**。返回关联及打开引用来源按钮完整位于容器内。测量对象是白色容器，不是3840px宽的Dialog窗口。
4. **横屏真实中文IME筛选与取消：已完成。** 执行记录为实际屏幕键 `tongceyinyong`，再选候选“同册”“引用”，字段形成“同册引用”；`visual-local-ui01/06-chinese-ime-clear-controls.png`、`physical-ui/local-ui-ime-committed.xml` 确认该字段及两条本批合成筛选结果。取消整按钮 `[2280,1269][2454,1413]`，高144px=48dp，IME上沿y=1513，底部净距 **100px=33.33dp**，按钮可用且可点击。ROOT记录保持IME显示时实际点按取消成功；末态 `physical-ui/local-ui-ime-cancelled.xml` 中选择器和IME均消失。
5. **OEM系统分屏及拖动比例：已完成两档窗口。** `visual-local-ui01/07-system-split-preview.png` 显示墨织与计算器并列、系统分屏divider及可读来源预览；`physical-ui/local-ui-system-split-preview.xml` 的预览容器为 `[38,886][1873,1740]`。拖动divider后的 `visual-local-ui01/08-system-split-resized.png`、`physical-ui/local-ui-system-split-resized.xml` 保留来源内容和两个完整按钮；左侧应用宽1305px= **435dp**，白色预览容器 `[26,886][1279,1740]` 宽1253px= **417.67dp**。这是实际系统分屏，不能把435dp应用窗或417.67dp预览宽度记成375dp用例。
6. **窄分屏＋真实IME取消：已完成。** `visual-local-ui01/09-system-split-ime.png`、`physical-ui/local-ui-system-split-ime.xml` 显示字段“同册引用”和两条筛选结果。取消整按钮 `[886,1298][1060,1442]`，高144px=48dp；IME上沿y=1513，底部净距 **71px=23.67dp**。ROOT记录实际触控取消成功；最终 `physical-ui/local-ui-split-cancelled.xml` 中选择器／IME消失、系统分屏仍在。中途 `physical-ui/local-ui-split-ime-cancel-hit.xml` 仍显示选择器／IME，不能单独用作成功取消回执。
7. **返回原卡并显式打开目标：已完成所述路径。** `visual-local-ui01/10-system-split-returned-card.png`、`physical-ui/local-ui-system-split-returned-card.xml` 显示返回“保持原卡”。随后ROOT记录点正文“条件概率”并选择打开目标；`visual-local-ui01/11-system-split-target-card.png`、`physical-ui/local-ui-split-target-opened.xml` 显示“概率笔记 baeb40”中的完整“条件概率”卡片及LIVE-V2正文。静态末态确认所属本和可见卡内容；不单凭这些截图推定全部节点／视口状态或原作者记录均未改变。
8. **退出分屏及显示恢复：已确认所记录范围。** 拖动divider退出后，`physical-ui/local-ui-system-split-exited.xml` 的应用窗口恢复 `[0,0][3840,2512]`，无IME／分屏divider。`local-ui-manual-display-session.json` 为 `restorationPending=false`；其记录的 `user_rotation=0`、`accelerometer_rotation=1`、`font_scale=1.0` 三项前后一致。此手工会话回执只覆盖这三项，不与前述自动化10项恢复记录混称。

拼音按键／候选的操作顺序及真实取消动作来自ROOT执行记录；独立截图／XML复核确认字段、边界和关闭末态。没有用静态证据代替取消后的数据库作者写入检查。以上UI01片段不改变原自动化4P3F；其中文IME／系统分屏结果保持UI01归属。最终UI02的375dp／1.6字号／120字标题自动化与实际滚动证据在后文独立记录。

## 最终UI02：原7项辅助真机全部通过

最终两文件补丁保留三项视觉修复，移除两层probe，并沿用已有MapPortalPanel的逐次 `Main.immediate` 状态发布／UI回调模式。原collector／effect上下文、Flow(IO)、Room事务及身份检查保持，原7测试源码、断言和timeout不改。该调度边界修复在无probe正式候选上通过原失败路径回归；**精确底层snapshot竞态并未捕获**，不能据此认定Compose跨线程State更新非法或宣称已完全定位根因。

固定主包与同字节runner实际完成 **7 PASS / 0 FAIL / 0 NOT_RUN**。每方法仅一次初始MainActivity前台辅助，正文和重建阶段不追加辅助；不拼接基线、UI01或两次诊断结果。下表辅助时点为该方法启动后秒数。

|方法|UI02结果|耗时秒|初始辅助秒|本地回执|
|---|---|---:|---:|---|
|`inlineTitleAndAliasOpenConfirmedTargetWhileDuplicateNamesRequireChoice`|PASS|11.5|2.766|`physical-local-ui02/first-two/results.json`|
|`reopenedLivePreviewReadsCurrentBodyWhilePinnedPreviewKeepsExactHistory`|PASS|11.75|3.062|`physical-local-ui02/first-two/results.json`|
|`narrow375LargeTextKeepsInlinePickerAndPreviewControlsReachable`|PASS|19.234|2.734|`physical-local-ui02/narrow/01/runtime/results.json`|
|`backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites`|PASS|12.859|2.766|`physical-local-ui02/remaining-four/results.json`|
|`pageAndNotebookBacklinksKeepDistinctScopeAndReadOnlyPreviewIdentity`|PASS|13.032|2.844|`physical-local-ui02/remaining-four/results.json`|
|`pickerAndPreviewRecreationReturnToOriginalCardNodeAndViewportWithoutWriting`|PASS|10.656|2.875|`physical-local-ui02/remaining-four/results.json`|
|`replacedAndRemovedLinkAreRevalidatedBeforeOpenWithoutSilentlyChangingTarget`|PASS|10.609|2.812|`physical-local-ui02/remaining-four/results.json`|

最终无辅助UI为 **NOT_RUN**：未重复基线已出现kernel freeze的环境控制尝试。取得的有效freeze样本均为0；narrow有一次 `cgroup.events` 的 `ADB_READ_TIMEOUT`，属于辅助监测缺口。该方法START／END、`OK (1 test)`及日志哈希完整，结果仍为PASS；不能表述为全程零监测warning或完全连续freeze采样。外层窄屏运行设置已恢复，回执 `physical-local-ui02/narrow/01/settings-receipt.json`。

Room仍采用 `physical-baseline/room11/results.json` 的 **11 PASS / 0 FAIL / 0 NOT_RUN**：每方法独立UUID数据库、无Activity和前台辅助；data源码、测试源码与self-target runner均未改变。最终主包不参与该独立runner执行，不将这11项写成UI02重新执行。

### UI02普通应用：120字标题实际滚动到正文

1. **375dp／font1.6的原自动化通过。** 自动化截图中的120字标题占据滚动区，正文位于下方；`assertPreview`验证正文语义节点存在，不能把该截图写成长标题与正文同屏完整。选择器顶部部分条目是已滚动后的视口，不据此判定固定标题截断。
2. **无instrumentation实际触控滚动通过。** `visual-local-ui02/01-375-large-title-before-scroll.png` 中正文尚不可见；实际滚动后的 `visual-local-ui02/02-375-large-title-body-visible.png` 和 `physical-ui/final-manual-narrow-preview-scrolled.xml` 显示LIVE-V1正文，bounds为 `[63,1073][466,1145]`。前后固定操作按钮完整可见、可用，确认正文实际可达。
3. **实际关闭返回原卡通过。** 点击返回关联，再返回摘要，`physical-ui/final-manual-narrow-returned-source.xml` 显示原“保持原卡”。输入／滚动方式为普通应用ADB真实触控，不记作真人笔感或完整人工旅程。
4. **本次五项设置恢复通过。** `final-manual-narrow-display-session.json` 记录 `result=PASS`、`restorationPending=false`，字号、user_rotation、accelerometer_rotation、尺寸、密度五项前后一致。最终11项设备设置审计另列，不将两种范围混计。

|普通应用／布局范围|实际版本与结论|限制|
|---|---|---|
|卡片入口、目标预览、原卡返回及打开目标|UI01所述路径通过；UI02长标题关闭返回原卡通过|其余人工组合未单独执行；原自动化结果另列|
|同册CARD入口去重|UI01作用域行4项仅1项checked；UI02 PAGE／NOTE原自动化通过|不同TargetRef的全人工组合未逐个执行|
|横屏来源预览宽度|UI01实测614.33dp，符合640dp上限|UI02未重复横屏量尺；不能转记为同一次测量|
|真实中文IME与系统分屏|UI01普通横屏及435dp系统分屏中输入、筛选、取消已完成|UI02未重复这两种实际IME组合|
|375dp／1.6字号／120字标题|UI02原自动化及普通应用实际滚动、关闭返回通过|不声称长标题和正文同屏完整；不是UI01的435dp分屏|
|原资料、偏好及设置保护|最终独立审计通过，详见下节|仅按记录的集合及字段说明，不声称全部应用文件不变|

## 原资料、偏好和工作区的最终审计

历史基线结束检查点仍保留：原4379行missing=0、changed=0，当时总4894行、新增515行归属28个本批合成本；另有4项文件／偏好哈希变化。依据 `private-device-after-baseline/receipt.json`、`private-device-after-baseline/new-row-ownership.json`，该检查点没有被最终结果覆盖。

最终检查点按同一原集合核对：

|范围|最终实际结果|本地证据|
|---|---|---|
|原笔记及作者记录|原83本、4379行全部保留，missing=0、changed=0；当前6074行|`private-device-final/receipt.json`|
|本批新增资料|1695新增行全部归属92个本轮合成本；这些合成本保留可审计，没有代替或删除原83本|`private-device-final/new-row-ownership.json`|
|原外部文件|原48文件SHA-256全部一致；不表述为没有新增文件|`final-external-files-audit.json`|
|原偏好|先保留恢复前tar备份，再实际恢复3个变化文件；全部35个原偏好文件字节匹配|`final-preferences-restoration/receipt.json`|
|设备设置|原11项显示／IME／悬浮窗设置全部匹配|`final-settings-audit.json`|
|旧工作区|三旧目录HEAD／Git状态不变，原162个未提交文件逐字节保持|`private-protected-worktrees-final.json`|

偏好恢复没有写数据库、clear或卸载应用。最终UI02主包已核对安装；恢复后应用保持force-stop，此后没有再次打开，以保留原偏好。原作者记录一致不等于数据库文件字节不变；合成资料增量与文件／偏好变化分别记录。

## 证据、隐私与整理结果

本机归档证据ID：`ARCHIVE-LINK-DISCOVERY-20261003`；本文相对证据标识均归属该归档。原始截图、日志、行清单、设备标识、备份及签名材料保留在本机，仓库只保存脱敏汇总。固定基线、UI01、两次诊断、UI02的原始结果和各版受测包均保留，不以最终通过覆盖早期失败。

设备上134个本任务 `/data/local/tmp/inkweft-link-*.xml` 已逐个与本机归档SHA-256匹配后删除，本机XML证据保留；回执为 `cleanup-receipt.json`。源码、签名、最终与历史受测包、私有恢复副本、有效验证证据及必要工具链分类保留。

清理限制：归档目录 `__pycache__` 的删除被自动审批拒绝，唯一理由为 **“blocked by policy”**；删除未执行，也未换方式重试，该缓存保留。不能将本次整理表述为全部临时文件已清空。

后续问题优先在应用“其他设置 → 诊断与导出”标记时间、复现并导出ZIP，再补日志不能表达的必要操作、视觉或笔感证据。基础诊断不等于完整系统日志、全部输入采样或笔尖延迟测量。
