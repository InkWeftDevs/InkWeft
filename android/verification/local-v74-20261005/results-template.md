# V74 连续页导航与原区域恢复 · 结果记录模板

对应[交付报告](README.md)与[设备清单](../../DEVICE-TEST-CHECKLIST.md)。每个设备、精确 APK 和尝试独立追加；保留 V69～V73 历史结果。**V74 三包构建／签名／CRC／对齐、lint、六个指定真机方法、完整样本定向普通检查及主资料受控升级已通过；当前主 workspace 为 V74。** 最终12项原始设置与自有远端临时文件清理已核对，AVD停机保留的限制记在收尾栏；真人／硬件／备用设备项目继续待测。

## 本轮身份

- 日期、操作者、设备型号／系统：待记录（序列号仅本机）。
- 窗口、字号、密度、读写模式、实际输入方式：待记录。
- 版本：`74 / 0.0.74-local-page-navigation`，Room16。
- baseCommit：`84fa32e7f59d979bd8faf54c33ca1b2efd27d7ad`，仅标识祖先；SOURCE_COMMIT／BUILD_COMMIT 均为 `local-unknown`。
- workingSourceTreeSha256：`35db406fa8aa91e3b5a819f622dd59959d50b0ad2c9d338a8f351ef2e609b655`。
- freezeSha256：`ef34fafb89349ff6a691bd3f4af02e0b0aa95372f20ea5844d22a886ca1dd13e`。
- buildInputPatchSha256：`d6588bea6042bf0e4c227b74a3e6f7cd3dd96e12788f0beb1be091c7592abb1a`。
- 导航测试源码 SHA-256：`e8bdb58beba86de9766e85108840c2dab3cb6e382d7bcb721e8342862e1ea7ef`。
- insertion app SHA：`e5293c5d06070c767551297312dbf4e96a210ebcff2db0cd3061e142328cb9b9`；test SHA：`0c5dc3cfdf9c4aeea3e4472571a47700a4b722d7854e1b6afb34fa424bc05e13`。
- insertion 构建回执 SHA：`22a0ac70982543ffdf72c63660e4e00494456f7069cbedb50ee5738c2c60a592`；冻结输入未变，两包字节摘要复核一致。
- 当前 workspace APK SHA：`c2107c58f4788af0b7475958132323c8e37fcff12cdbdf91b3712a66fed981d4`；构建回执 SHA：`427033856bfa1e7d907adb587ce0608045debb5545416ea2caa6813255eec131`；已同包同证书受控覆盖，原生首开与资料保留通过。
- 三包实际证书 SHA：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`；签名核验、ZIP CRC、16KiB 对齐均 PASS。
- lint：实际执行 PASS，Error/Fatal 0、Warning 165、Hint 2；回执 SHA `b1a3b1a846a850dd4f6b2e5620781f5a5555b8c1688f53e9efa42d98e36be4e7`，冻结输入未变。
- 自动化期间受保护、升级前原主 workspace V69 SHA：`fe8821c5383a246b917ca0e3e53c5e300ade984e293541bd6587c5a57486362f`。

## 当前结果台账

| 编号 | 精确方法／范围 | 结果 | 回执 SHA／实际覆盖 |
| --- | --- | --- | --- |
| NAV74-AUTO-01 | `org.inkweft.app.NotebookNavigationUiTest#continuousOverviewJumpKeepsRequestedPageUntilScrollingSettles` | PASS | 真实跳12→1及手动滚动；1次初始MAIN |
| NAV74-AUTO-02 | `org.inkweft.app.ReadingToolsUiTest#continuousReadingUsesExistingExcerptMapAndHeaderNavigationWithoutAuthorWrites` | PASS | 真实阅读导航／无作者写入；1次初始MAIN |
| NAV74-AUTO-03 | `org.inkweft.app.ContinuousWritingUiTest#continuousSeamHasNoGapAndOneGesturePersistsOnBothSides` | PASS | 接缝／两侧持久化；1次初始MAIN |
| NAV74-AUTO-04 | `org.inkweft.app.NotebookNavigationUiTest#continuousCrossBookReturnRestoresNonTopViewport` | PASS | 非页顶独立world／缓存严格比较；1次初始MAIN |
| NAV74-AUTO-05 | `org.inkweft.app.NotebookNavigationUiTest#realEditorAppendPersistsOneBlankPageAndRejectsStaleTail` | PASS | 追加／模板／陈旧尾页／模式／重建；1次初始MAIN |
| NAV74-AUTO-06 | `org.inkweft.app.NotebookNavigationUiTest#continuousManualViewportSurvivesOverviewResize` | PASS | 实际纸宽缩小、最终nativeY±4px；1次初始MAIN |
| NAV74-USER-01 | 同完整样本概览、非页顶返回、手动阅读面板开合、只读入口、重开 | PASS_ORDINARY_UI_SMOKE | ADB普通触控；具体覆盖见下文 |
| NAV74-UP-01 | 主 workspace 覆盖与资料保留 | PASS_CONTROLLED_MAIN_WORKSPACE_COVER_UPGRADE | 同包同证书、原生首开、42表16713行及53偏好／106文件保持 |

自动化实际顺序 06→04→01→05→02→03，2026-10-05 12:09:39～12:11:33 UTC 完成运行及组末核对。六份原始日志均严格 code1→code0、OK(1)、最终-1、宿主0；逐项前后APK、受保护主包和12设置及组末恢复已核对。组末回执 SHA `3a65984039a40cf0b2b99c973357ed88f0f4e3eef9f483418fe441bfad2bc9bb`；独立摘要 `checks/runtime-evidence-summary.json` SHA `601241f422c20d21a60f2d8bb7c351d4d0f9f5972d52d2f868a898538f9b71b9`。

各一次初始MAIN，2.062～2.469秒，无第二次脚本拉起；方法body进入时刻未独立观测。可读freeze值62次均0、可读frozen事件59次均0；保留8次freeze不可读与12条边界读取告警，不把采样当作连续监测。V73五项PASS、V69的14个唯一PASS仍属各自版本；V72严格返回FAIL、V71普通返回FAIL、V69部分链路受阻及原生View环境阻断继续保留。

## 每次自动化尝试记录

- 稳定编号、完整 class#method、阶段、实际执行顺序：
- 两 APK SHA、冻结输入／打包 sidecar／安装与前后身份回执 SHA：
- 实际起止和时区；开始 code1、完成 code0、OK(1)、最终 -1、宿主退出 0：
- 初始 MainActivity 辅助实际 0／1 次、时间、开始事件／进程／cgroup.freeze 条件：
- PASS／FAIL／NOT_RUN 的实际原因、断言位置或阻断证据：
- AUTO-04：非页顶位移>80；返回请求消费与返回栈；两端 bounds、原生 window 位置、尺寸、density、native viewport、独立 world、cache；固定目标与中心误差≤2／zoom≤.001：
- AUTO-06：初始偏移>200px；打开概览后的纸宽确实缩小；未选择页面；最终边界／尺寸相等，原生 Y 误差≤4px，页码／页数／墨迹：
- AUTO-01／02／03／05 的页码、上下文、作者数据或追加／模板／模式断言：
- 原始日志、结果文件、前后 APK 身份及受保护主包 SHA：
- 12 项设置基线、逐项恢复和组最终恢复回执：
- 队列停止位置、尚未执行的方法、后续独立尝试：

## NAV74-USER-01 普通完整样本记录

本次已完成：普通 SAF 恢复同一2本13页／96卡／3图各120节点完整样本，42表3,203行及schema与固定备份完整一致，integrity正常、FK0、输入未改。比较回执 SHA `bfdd21dafff58aa263f3056afe6fa1778b64fa1dc96d03ae51c39960d3c8fb13`；原生导入回执 SHA `daf0aabbec933e91ef4ee94b0a9f450d77c048715e73e31df3c6f9edbd710b4f`。

普通生产界面以ADB触控完成真实缩略图12→1；非页顶第6行的停靠面板开合；关联打开伴随本主题7并可见返回后保持同第6行／原页边界；图层只读入口及禁用编辑；回忆48卡／12题范围、第1／12题线索与原文隐藏；退出回忆、返回资料库并重开后保持位置。未做图层修改、作答、提示或评分。

普通检查回执 SHA `e63ab236ba5ed57625c3ef32b73a592b674a5ddbae6b4d681f6f2c6090592716`；13张PNG及配套记录保留本机。步骤02为点到不可点击标签的无操作，不计PASS或FAIL；步骤03实际缩略图点击才是第12页证据。仅这些定向普通检查PASS，不代填完整37项、真人15／30分钟、热观察或故障测试。以下空栏供下一次独立尝试追加。

- 适用 APK／模式／窗口／字号／实际输入方式：
- 同一固定样本身份及恢复回执：2 本、13 页、96 卡、3 图各 120 活动节点；实际起始计数与原规格一致性：
- 真实概览第 12 页及返回第 1 页，目标页和原文证据：
- 离开前的稳定布局、非页顶原文区域；A→关联目标 B→可见返回 A 的实际步骤与返回区域：
- 手动页内滚动后，打开／关闭停靠面板且不选页，前后阅读位置：
- 普通滚动、正常退出重开、其余六批流程的已做／未做步骤：
- 页／卡／墨迹的预期编辑变化与实际变化：
- 异常标记时间、复现、“诊断与导出”ZIP，以及日志不能表达的视觉／笔感证据：
- 结论与未覆盖范围：本检查不代填完整 37 项或真人 15／30 分钟。

## NAV74-UP-01 主资料受控升级记录

升级前保护复核：42作者表／16,713行及结构保持，实际主库与基准SHA均为 `2a30984594ef6ceea771710fba1c8599aa9c02c2d65f277e0b76fd78b9a76750`；回执SHA `29aa5892812b4ca41d9e8f331e8faf6de4b8d712b596e889603043cb4f239877`。

本次UP-01已PASS：V69→V74同包同证书覆盖，无卸载／清数据；原生冷首开报告463ms，可见431活动本，3回收本保留；42作者表原／现16,713行，missing／changed／new均0；全部字段／BLOB／主键／结构已比较，两端schema16、integrity正常、FK0、输入字节未变。53偏好和106外部文件SHA一致，无缺失／改变／新增。

联合回执SHA `14f95bce964c1bcf812f4693b0fa1492201e0453c684e662277f33b7df01a30e`，42表比较SHA `3d9e0c1a0d4a620e037b3a2bf6ec99e7e0300ac445a849db43ca0fcb23e02750`，偏好／外部文件回执SHA `ab7dd6d2a39900f8ee27bfd661835d7c14a55248deee89443a686b6e752409a4`。未提交草稿、真人笔感与长时使用不在该结论范围。以下空栏供后续独立升级记录。

- 升级前 V69 主包、原备份、主库副本、偏好／外部文件清单及摘要：
- 基准摘要：434 本（431 活动／3 回收）、42 作者表／16,713 行；原始值仅本机：
- V74 同包同证书候选 SHA、覆盖安装与正常原生首开回执：
- 实际主库采集 SHA、sidecar／checkpoint 状态：
- 42 表完整字段、BLOB、主键、列／SQL 结构的缺失／新增／改变：
- 两端 schema16、integrity、FK0及输入摘要前后不变：
- 偏好和外部文件计数、差异及独立回执 SHA：
- 安装、首开、数据库、偏好、外部文件分别支持的结论；未提交草稿范围：

## 继续待测及最终收尾

| 既有稳定编号 | 适用／类别 | 当前状态与补录字段 |
| --- | --- | --- |
| SB69-HW-01 | V74 复测；真人 15 分钟持笔 | 待测；真实起止／暂停、笔和设备、笔感／异常证据 |
| SB69-FLOW-30 | V74 复测；另 30 分钟完整学习 | 待测；实际步骤／时长、计数与上下文变化 |
| SB69-THERMAL-01 | V74 复测；硬件能力与热观察 | 待测；开始、15分钟、另30分钟采样及来源，未知记 UNKNOWN |
| SB69-FAULT-01 | V74 复测；备用设备故障测试 | 待测；可恢复备份、具体故障／恢复步骤与实际结果 |

合成手势／ADB 操作不代填上述真人和硬件结果；NOT_SUPPORTED、NOT_IMPLEMENTED 需各自证据，不能从环境阻断推断。

- 最终设备：V74主应用再次正常启动并留前台，合成测试应用停止；常亮2恢复原0，12项全部恢复最初基线。`final-device-settings.json` SHA `ce24ae0211e6679fba8e125e13b65f04f9dde03d7e26d27c65477a995170e52c`。
- 已清理：余下93份自有远端XML逐份核对本地SHA后删除；含此前137＋63份，本任务累计293份。下载目录自有7.8MB样本副本删除，本机备份原件保留。清理回执SHA `9fb0f71547d52c8a9fa3e1bfc91da815207670695ba0634d0f21c8279638fc90`。
- 保留：原备份、原始失败及日志、源码、签名密钥、安装包、必要工具；R74归档无零散临时／pyc产物。原工作区既有162文件零变化，回执SHA `bac6c9c601fed09da538f1052170ca0362302a277a89eeedf965297dd40fb333`。
- 源码与交付文档为本地未提交改动；没有Git commit、push或merge。公开文档不含私人库值、设备序列号、私有备份或私人截图。
- 清理限制：自动审批拒绝删除本次AVD及注册文件，仅给出“策略阻止”；已停机保留，不重试。
