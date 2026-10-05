> 本文保留原V74交付时的身份和结果。后续已核对616份冻结输入并固定源码为 `050edf0224c61c9958bbd4c10bcbcde9465787fa`；当前V76交付见[收尾报告](../local-v76-20261005/README.md)。旧APK和local-unknown字段不变。

# V74 连续页导航与原区域恢复 · 本地交付报告

**主 workspace 已受控覆盖到 V74：原生首开成功，42 作者表／16,713 行、53 份偏好及106份外部文件保持。** 三包构建／签名／CRC／16KiB 对齐、lint、六个指定真机方法与完整样本定向普通检查已通过。六项方法的前后 APK 身份及设置恢复已核对，最终12项原始设置已恢复，自有远端临时文件清理完成；停机保留的AVD限制见文末。未执行的真人／硬件／故障项目不由上述结果代填。[设备清单](../../DEVICE-TEST-CHECKLIST.md)与[结果模板](results-template.md)使用稳定编号 NAV74；[V69 历史报告](../local-v69-20261005/README.md)保留原版本范围。

## 修复范围

- 连续页概览选择第 12 页／第 1 页时，程序滚动期间保持显式目标；面板开合改变纸宽后仍定位该目标。
- 跨本返回时，等待工具栏实测高度再恢复连续页视口，避免用初始 56dp 高度提前消费恢复请求。
- 手动阅读形成页内偏移后，面板改变纸宽沿用列表的尺寸变化处理，不强制回页顶。只有仍有效的显式跳页锚点会触发重新定位。
- 追加测试通过当前可见菜单入口查找操作，并在执行尾页手势前关闭菜单；保留追加数量、模板持久化、陈旧尾页、输入模式及重建断言。新增面板缩放用例明确断言纸面实际变窄。

相对祖先基线仅四个构建输入变化：`android/app/build.gradle.kts`、`ContinuousPages.kt`、`InkScreen.kt`、`NotebookNavigationUiTest.kt`（后三者分别位于 app 主源码／androidTest 的 `org/inkweft/app`）。Room 格式仍为 16。本轮是本地未提交候选；没有执行 Git commit、push 或 merge。

## 源码与构件身份

| 项目 | 身份／当前结果 |
| --- | --- |
| 版本 | `74 / 0.0.74-local-page-navigation` |
| baseCommit（祖先） | `84fa32e7f59d979bd8faf54c33ca1b2efd27d7ad` |
| workingSourceTreeSha256 | `35db406fa8aa91e3b5a819f622dd59959d50b0ad2c9d338a8f351ef2e609b655` |
| freezeSha256 | `ef34fafb89349ff6a691bd3f4af02e0b0aa95372f20ea5844d22a886ca1dd13e` |
| buildInputPatchSha256 | `d6588bea6042bf0e4c227b74a3e6f7cd3dd96e12788f0beb1be091c7592abb1a` |
| 导航测试源码 SHA-256 | `e8bdb58beba86de9766e85108840c2dab3cb6e382d7bcb721e8342862e1ea7ef` |
| SOURCE_COMMIT / BUILD_COMMIT | 均为 `local-unknown`；baseCommit 仅表示祖先 |
| insertion app SHA-256 | `e5293c5d06070c767551297312dbf4e96a210ebcff2db0cd3061e142328cb9b9` |
| insertion test SHA-256 | `0c5dc3cfdf9c4aeea3e4472571a47700a4b722d7854e1b6afb34fa424bc05e13` |
| 当前 workspace app SHA-256 | `c2107c58f4788af0b7475958132323c8e37fcff12cdbdf91b3712a66fed981d4`；已受控覆盖安装并完成资料保留核对 |
| 三包实际签名证书 SHA-256 | `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969` |
| 升级前主 workspace V69 APK | `fe8821c5383a246b917ca0e3e53c5e300ade984e293541bd6587c5a57486362f` |

insertion 构建回执 SHA-256 为 `22a0ac70982543ffdf72c63660e4e00494456f7069cbedb50ee5738c2c60a592`，workspace 构建回执为 `427033856bfa1e7d907adb587ce0608045debb5545416ea2caa6813255eec131`；冻结输入未变，三个归档 APK 的字节摘要再次核对一致。lint 已实际执行 PASS（Error/Fatal 0、Warning 165、Hint 2），回执 SHA 为 `b1a3b1a846a850dd4f6b2e5620781f5a5555b8c1688f53e9efa42d98e36be4e7`。构建、方法运行及主资料保留分别登记。

## NAV74 自动化结果

六项均使用同一冻结 V74 生产 MainActivity、实际 ViewModel／导航与原生画布；只向隔离 insertion 的合成资料写入。精确清单来自本机 `runtime-inputs/runtime-selection.json`。实际顺序为 **06→04→01→05→02→03**，本组于 2026-10-05 12:09:39～12:11:33 UTC 完成运行及组末核对。

| 稳定编号 | 精确 class#method | 本轮结果 |
| --- | --- | --- |
| NAV74-AUTO-01 | `org.inkweft.app.NotebookNavigationUiTest#continuousOverviewJumpKeepsRequestedPageUntilScrollingSettles` | PASS；1次初始MAIN |
| NAV74-AUTO-02 | `org.inkweft.app.ReadingToolsUiTest#continuousReadingUsesExistingExcerptMapAndHeaderNavigationWithoutAuthorWrites` | PASS；1次初始MAIN |
| NAV74-AUTO-03 | `org.inkweft.app.ContinuousWritingUiTest#continuousSeamHasNoGapAndOneGesturePersistsOnBothSides` | PASS；1次初始MAIN |
| NAV74-AUTO-04 | `org.inkweft.app.NotebookNavigationUiTest#continuousCrossBookReturnRestoresNonTopViewport` | PASS；1次初始MAIN |
| NAV74-AUTO-05 | `org.inkweft.app.NotebookNavigationUiTest#realEditorAppendPersistsOneBlankPageAndRejectsStaleTail` | PASS；1次初始MAIN |
| NAV74-AUTO-06 | `org.inkweft.app.NotebookNavigationUiTest#continuousManualViewportSurvivesOverviewResize` | PASS；1次初始MAIN |

AUTO-04 独立读取原生画布的位置、尺寸、密度及 viewport，换算列表可视中心的世界坐标；固定离开前的 world 为返回基准，中心 X／Y 误差均≤2，zoom 误差≤.001。缓存只用于收敛及独立旁证，非页顶位移须>80作者单位。AUTO-06 先确认页内偏移>200px，实际打开概览且纸面变窄，不选择页面，再关闭；最终边界／尺寸相同且原生画布 Y 误差≤4px。

已独立核对六份原始仪器日志：每项均为准确方法的开始 code1→完成 code0、OK(1)、最终 -1、宿主退出 0；APK 前后身份、受保护 V69 主包和 12 项设置均保持／恢复。各实际一次初始 MainActivity 辅助，位于准确开始事件后 2.062～2.469 秒，没有第二次脚本拉起；方法 body 的进入时刻未独立观测。

70 次监测中，62 次可读 freeze 值均为0、59次可读 frozen 事件值均为0，未观察到冻结；8次 freeze 读取不可用与12条边界读取告警保留，采样不代表连续内核状态保证。组末回执 SHA 为 `3a65984039a40cf0b2b99c973357ed88f0f4e3eef9f483418fe441bfad2bc9bb`，自动化阶段独立摘要 `checks/runtime-evidence-summary.json` SHA 为 `601241f422c20d21a60f2d8bb7c351d4d0f9f5972d52d2f868a898538f9b71b9`。该摘要保留当时状态，后续 lint、普通样本与主升级分别见各自回执。本轮六项通过仅覆盖上述精确方法；历史不利结果继续保留。

## 普通完整样本与主资料

| 编号 | 本轮操作与可支持的结论 | 当前结果 |
| --- | --- | --- |
| NAV74-USER-01 | 同完整样本真实 12→1 跳页、非页顶面板开合／跨本返回、只读图层入口、回忆入口与资料库重开 | PASS_ORDINARY_UI_SMOKE；范围见下文 |
| NAV74-UP-01 | 同包同证书受控覆盖、原生首开、实际主库只读比较、偏好及外部文件保留 | PASS（受控覆盖＋资料保留） |

完整合成样本保持 **2 本／13 页（12 资料页＋伴随知识本 1 页）、96 卡、3 图各 120 活动节点**，使用原固定身份与完整规格。V74 insertion 经普通应用 SAF 确认恢复；实际恢复副本的42作者表／3,203行与固定备份完整比较一致，schema16、integrity正常、FK0，输入字节未改变。恢复比较回执 SHA 为 `bfdd21dafff58aa263f3056afe6fa1778b64fa1dc96d03ae51c39960d3c8fb13`，普通导入回执 SHA 为 `daf0aabbec933e91ef4ee94b0a9f450d77c048715e73e31df3c6f9edbd710b4f`。

随后在该完整样本上通过普通生产界面与 ADB 触控完成：实际缩略图跳12→1；上滑至原文第6行后，开合停靠面板仍保留同区域；经关联打开伴随知识本主题7并点击可见返回，原文仍在同一第6行／页边界；只读图层入口显示基础层且编辑禁用；回忆范围显示48卡／12题，第1／12题的线索和原文保持隐藏；退出回忆、回资料库并重开后保持原阅读位置。没有执行图层修改、作答、提示或评分。

普通检查回执为 `physical-synthetic/final-flow-receipt.json`，SHA `e63ab236ba5ed57625c3ef32b73a592b674a5ddbae6b4d681f6f2c6090592716`。13张原生截图及配套记录保留本机；`02-pressure-page12` 是点到不可点击标签的无操作记录，不计通过或失败；实际第12页证据为 `03-jump12-thumbnail`。通过结论仅覆盖上述定向普通检查，不是完整37项、真人持笔或连续学习时长。

主资料基准为 V69／schema16：**434 本，431 活动、3 回收；42 个作者表合计 16,713 行**。本轮升级前只读复核42表与结构全部一致，实际主库及基准 SHA 均为 `2a30984594ef6ceea771710fba1c8599aa9c02c2d65f277e0b76fd78b9a76750`；保护比较回执 SHA 为 `29aa5892812b4ca41d9e8f331e8faf6de4b8d712b596e889603043cb4f239877`。该事实仅证明升级前主库保持，不是升级通过。

2026-10-05 12:27:42 UTC 的联合回执确认 **PASS_CONTROLLED_MAIN_WORKSPACE_COVER_UPGRADE**：V69→V74同包同证书覆盖，无卸载或清数据；原生冷首开回执报告463ms，资料架可见431活动本，3回收本保留。实际升级后副本逐行核对42作者表全部字段、BLOB、完整主键、列及SQL结构，原／现均16,713行，missing／changed／new均0；两端schema16、integrity正常、FK0，比较输入未改变。53份偏好和106份外部文件SHA逐项一致，缺失／改变／新增均0。

联合回执 `upgrade-results/workspace-upgrade-final.json` SHA 为 `14f95bce964c1bcf812f4693b0fa1492201e0453c684e662277f33b7df01a30e`；42表比较回执 SHA `3d9e0c1a0d4a620e037b3a2bf6ec99e7e0300ac445a849db43ca0fcb23e02750`，偏好／外部文件回执 SHA `ab7dd6d2a39900f8ee27bfd661835d7c14a55248deee89443a686b6e752409a4`。原备份与基准保留，未提交草稿、真人笔感及长时使用不在本结论范围。

## 历史结果及边界

- V69：14 个唯一方法 PASS，分别为 Room4、Main7、Component3；原生 `LayerAuthoringNativeTest#thousandStrokeHiddenLayerCannotReappearAfterDelayedViewportFrame` 受环境冻结阻断，仍为 NOT_RUN_ENVIRONMENT_BLOCKED。
- V69 普通完整样本流程为 PARTIAL_BLOCKED_NAVIGATION：部分阅读／大纲／跨本链路已执行，第 12 页缩略图落至 PDF 第 4 页时停止，其余步骤未做。原恢复与 42 表比较结果保留。
- V70 跳页 RED／GREEN 的真实失败保留。V71 原三个方法 PASS，但随后普通完整样本非页顶跨本返回 FAIL，未升级主 workspace。
- V72 经严格几何核对确认真实返回偏移：worldY `902.2627→907.0398`，差约 `4.7771>2`；相同最终边界／尺寸下原生 Y `-144→-156px`。原失败保持 FAIL。
- V73 五个实际方法全部 PASS（返回、概览、追加、阅读、接缝），12 项设置及受保护主包保持；随后静态审查发现手动阅读后面板变宽会强制回页顶，故未作为交付版本。V74 收窄该条件并新增 AUTO-06；V73 的五项通过不计为 V74 通过。

## 继续待测与交付收尾

15 分钟真人持笔（SB69-HW-01）、另 30 分钟完整学习（SB69-FLOW-30）、性能／热观察（SB69-THERMAL-01）及备用设备故障测试（SB69-FAULT-01）继续待测，保留各版本实际记录。硬件未知记 UNKNOWN，已证实不支持记 NOT_SUPPORTED；未实现功能仅在有源码依据时记 NOT_IMPLEMENTED，不把环境阻断归为功能未实现。

异常优先使用软件内“诊断与导出”：标记时间、复现、导出 ZIP，再补日志不能表达的操作、视觉或笔感证据。基础诊断包不等于完整系统日志。

最终设备设置回执 `final-device-settings.json` 为 PASS_ALL_12_ORIGINAL_SETTINGS_RESTORED，SHA `ce24ae0211e6679fba8e125e13b65f04f9dde03d7e26d27c65477a995170e52c`：本任务临时常亮值2恢复为原值0，全部12项与最初基线一致。主V74再次正常启动并留在前台，合成测试应用已停止。

清理回执 `cleanup-final-device-staging.json` SHA `9fb0f71547d52c8a9fa3e1bfc91da815207670695ba0634d0f21c8279638fc90`：核对本地SHA后删除余下93份自有远端XML，连同此前137份及63份，本任务累计293份；删除下载目录中本任务7.8MB合成备份副本，本机 `emulator/synthetic-library.iwbackup` 原件保留。原备份、原始失败与日志、源码、签名密钥、安装包及必要工具未清除；R74归档未发现零散临时／pyc产物。原工作区既有162文件复核零变化，回执SHA `bac6c9c601fed09da538f1052170ca0362302a277a89eeedf965297dd40fb333`。

本机证据归档根为 `archives/2026-10-05/SixBatchLocalHandoff/local-fix-v74/`。公开文档只含汇总与摘要；私人库值、设备序列号、原始备份和私人截图不进入Git。本轮源码与文档仍为本地未提交改动，Git提交、推送、合并均未执行。


清理限制：自动审批拒绝删除本次AVD及其注册文件，仅返回“策略阻止”，未提供更具体原因。该AVD已停机保留，没有重试删除。
