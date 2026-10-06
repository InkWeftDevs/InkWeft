> 历史范围：本报告及原通过项对应 V69 的精确构件。当前连续页导航、跨本原区域恢复与面板缩放修复见 [V74 报告](../local-v74-20261005/README.md)，实际新结果独立登记，不把旧通过项计入 V74。

> 当前交付：主 workspace 已同包同证书受控覆盖为V74，原生首开、42作者表16,713行、53偏好及106外部文件保留通过；V74六项定向方法和完整样本普通导航检查也已通过，详情按V74精确构件记录。最终12项原始设置已恢复，自有远端临时文件已清理；自动审批阻止AVD删除，已停机保留。V69历史结果保留；真人15／30分钟、硬件及备用设备项目继续待测。

> V69 普通流程后续记录：SB69-FLOW-01 为 **PARTIAL_BLOCKED_NAVIGATION**。同完整样本已完成资料架、PDF／原图阅读、120节点大纲、卡片关联跨本 B 与可见返回 A；第12页缩略图落到 PDF 第4页后停止，其余步骤未做。原证据保留；没有完整37项或30分钟真人通过结论。

# V69 六批本地接手报告

本次已纳入2026-10-05 09:18 UTC／北京时间17:18的主workspace受控升级联合回执及后续完整合成样本真机恢复证据。**真机14个唯一方法PASS：Room4无辅助，Main7和Component3各一次初始准确Activity启动辅助；原生View千笔像素方法仍NOT_RUN_ENVIRONMENT_BLOCKED。主workspace已受控覆盖V69，原生迁移、15,012旧作者行及53份偏好／106份外部文件保留通过。** 原隔离迁移、真实V68备份的隔离生产恢复与副本比较结果保留；大样本真机导入与完整42表比较已通过；普通链路、真人体验及最终环境清理仍待完成。本报告仅更新交付记录，未修改生产代码、测试断言或构建脚本。[设备清单](../../DEVICE-TEST-CHECKLIST.md)与[结果模板](results-template.md)使用相同稳定编号。

## 源码与交接依据

- 本地固定源码：`84fa32e7f59d979bd8faf54c33ca1b2efd27d7ad`；版本`69 / 0.0.69-six-batches-candidate`，Room16。
- 云端同源[run37265155262](https://github.com/InkWeftDevs/InkWeft/actions/runs/37265155262)为completed/success：App三个分片55＋54＋54，共163/163；Room本轮实际新执行202/202；18份核心JUnit XML共157/157；规定lint由分片1执行通过。
- 最新[交接文档提交4a3b2f2](https://github.com/InkWeftDevs/InkWeft/blob/4a3b2f278dbaab610623a06b77ff22fc02fcf977/CLOUD-HANDOFF.md)仅承载交接文字，不是重新验证代码。文档报告40图事后实际审阅无静态阻塞；本地没有重新下载／审阅云端图片。导图选中位置偏下和部分相邻节点在视口外仍为该交接记录的非阻塞问题。
- 仓库[acceptance-current.json](../six-batches-v11-20261004/acceptance-current.json)仍停留旧候选／旧失败检查点，未记本次run；其名称不代表已更新。历史报告保留自己的源提交，不能把旧pending状态或不同源通过拼成本轮结论。

## 分层状态

| 层次 | 已核对结果 | 限定范围／待完成 |
| --- | --- | --- |
| 云端CI＋隔离模拟器 | 同源163 App、202 Room、157 Core及规定lint通过；同一库五阶段PASS、40图有完整回执 | 不是当前平板或本地交付APK的结果；云端调试签名与本地签名分别登记 |
| 本地主机构建 | workspace三包、insertion两包构建／签名／ZIP／zipalign校验通过；本地lint 0 Error/Fatal、165 Warning、2 Hint | 构建成功不等于任何未执行instrumentation通过 |
| 本地专属模拟器 | 仅prepare实际PASS；按原guard生成完整同规模样本，生产备份恢复及重复恢复核验通过 | 没有在本地重跑其余四阶段，不称本地五阶段／40图通过；本地prepare七图已逐图视觉审阅，未见阻塞静态缺陷；仅覆盖七图 |
| 真机独立Room | SB69-AUTO-01～04四项PASS，均0次初始前台辅助 | 合成私有数据库；不能代替原用户库升级或完整生产备份恢复 |
| 原库隔离副本比较 | SB69-MIG-01 = PASS_ISOLATED_COPY_COMPARISON | 比较脚本只读核对隔离副本，不证明主workspace升级；未覆盖外部文件／设置／未提交草稿 |
| 真机生产备份恢复 | 通过普通应用SAF选择／确认恢复，最终UI成功；恢复副本schema16、原15,012行无差异 | 已完成隔离目标生产恢复及内容比较；不证明主workspace升级或逐页视觉／重复恢复通过 |
| 真机App方法／大样本 | Main7＋Component3共10方法PASS，各1次初始准确Activity辅助；View1因OEM冻结NOT_RUN_ENVIRONMENT_BLOCKED | 保留原无辅助冻结与停队列记录；大样本普通完整链路另记 |
| 完整合成样本真机恢复 | SB69-FIX-01 PASS：空insertion普通SAF恢复为2本13页，42作者表／3,203行完整typed摘要与备份一致 | 无子集过滤；普通链路、真人时长及硬件观察不由恢复比较代填 |
| 主用户应用 | SB69-UP-01受控升级PASS；实际主库采集副本Room16，15,012旧行零变化，53偏好／106外部文件逐字节保留 | 未提交草稿不在比较范围；普通使用／真人体验另记，V69 workspace测试包仅构建未装 |
| 真人与硬件 | 15分钟书写、30分钟学习、热状态均NOT_RUN | 不用ADB、合成笔事件、帧截图或模拟器耗时替代 |

## 本地构件身份

五个V69制品都使用原证书SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`，冻结输入摘要为`f4c6b27f02ce2e9f10ddfbcace5a81b262d93f2f137cb6e09377ed71d0911d7c`。构建器回执的deviceOperations和instrumentation执行计数为0；真机执行证据另表登记。

| 构件／包 | SHA-256 | 截点状态 |
| --- | --- | --- |
| V69 workspace主包 `org.inkweft.app.a0.workspace` | `fe8821c5383a246b917ca0e3e53c5e300ade984e293541bd6587c5a57486362f` | 已同包同签名受控覆盖安装；原生首开成功 |
| V69 workspace测试包 `.workspace.test` | `addbd7f6206cdea9ef41213b4bdb0b2f7e6d0152db7c3ffdd59fd7d4f8434be5` | 仅构建、未安装；旧workspace runner保留且未运行；App定向用例使用insertion runner |
| V69 insertion主包 `org.inkweft.app.a0.insertion` | `0635bf0a06af3ccd46c919021e8615e31f63ffb6a33cc71ca35c4b8b7801ffaf` | 已核真机安装身份 |
| V69 insertion测试包 `.insertion.test` | `2e423d29f6f77e9abfd72865c8c5905b97c32b176a7909144e4cff2f10f317f1` | 已核真机安装身份 |
| 独立Room测试包 `org.inkweft.data.test` | `74fd93d9507da3fbc5500410c3dd0bb9461a48b0b0318591e606ca0eeebc697b` | 真机四方法PASS |
| 原用户workspace V68 | `7a49c8f07befc7b8b519d5f3a7a24d4472c4c481bf08720f0e22fcd1c052fa8f` | 全部instrumentation期间受保护；随后按独立回执受控覆盖到上表V69 |

包名、版本号、证书和源码不能取代实际APK摘要。后续交付变化单独追加，insertion断言与workspace安装／原生迁移分别记证据，不互相替代。较新Room16数据不得交给V68试开；出现格式／身份冲突时保留资料和回执，按已确认构件与生产恢复路径处理。

## 真机定向方法的实际结果

公开设备说明为vivo iPA2673、Android16／API36；不记录唯一设备标识。2026-10-05 08:12:32～08:13:10 UTC执行四个不同Room方法，每项均有准确class/test的启动code1、完成code0、OK(1)、最终instrumentation结束码-1及ADB退出0。`physical-runtime/room-unassisted/group-final.json`为PASS，三APK身份及原workspace摘要前后匹配，12项设置恢复到本组开始值，辅助次数均0。

`physical-runtime/summary.json`汇总14个唯一方法PASS、1项环境阻断：Main7每项一次初始MainActivity启动，Component3每项一次初始准确ComponentActivity启动。Component首个无辅助方法实际发生OEM冻结，后两方法当次因队列停止未运行；后续独立探针及两次followup通过，原始尝试完整保留。原生View无辅助方法仍因OEM冻结未完成。辅助仅限已审初始启动，禁止测试过程中抢占或循环拉前台，不能写成无辅助全部通过。

全部执行组设置恢复到组基线，且全部instrumentation期间原V68 workspace受保护；之后的主包升级另有独立证据。组基线含当时临时常亮设置，组恢复通过不代表任务最终设置、临时XML与专属AVD清理完成。

## 自动化方法索引

15项是本地定向计划，4 Room＋11 App；未机械重复云端全量163／202。Room用独立UUID数据库，App只在insertion处理本次合成资料。下表方法的准备、预期与证据要求对应设备清单。

| 稳定编号 | 精确方法 | 覆盖 | 当前结果 |
| --- | --- | --- | --- |
| SB69-AUTO-01 | `org.inkweft.data.PageObjectRepositoryTest#schema12UpgradesWithoutRewritingExistingAuthorRows` | schema12→16迁移 | PASS；真机新执行1次，前台辅助0次 |
| SB69-AUTO-02 | `org.inkweft.data.RecallStudyRepositoryTest#schema15MigrationAddsRecallWithoutRewritingAuthorInkQuestionOrAnnotation` | schema15→16原始字节 | PASS；真机新执行1次，前台辅助0次 |
| SB69-AUTO-03 | `org.inkweft.data.RecallStudyRepositoryTest#saveReopenResumeFullBackupRestoreAndReceiptKeepAnswerBytes` | 回忆答案与完整备份 | PASS；真机新执行1次，前台辅助0次 |
| SB69-AUTO-04 | `org.inkweft.data.PageAuthoringRepositoryTest#nonemptyLayerDeletionUndoBackupAndEditableCopyKeepHiddenPayloads` | 三层两留白与隐藏内容 | PASS；真机新执行1次，前台辅助0次 |
| SB69-AUTO-05 | `org.inkweft.app.SelectionStudyUiTest#excerptCreatesSharedCardAndReturnsToSource` | 摘录与回源 | PASS（1次初始MainActivity辅助） |
| SB69-AUTO-06 | `org.inkweft.app.StudyOrganizationUiTest#workModesPreserveSelectedGraphAndResumeTheSameUnrevealedQuestion` | 读写忆上下文 | PASS（1次初始MainActivity辅助） |
| SB69-AUTO-07 | `org.inkweft.app.StudyOrganizationUiTest#realOutlineHandleMovesWholeBranchAndSupportsUndoRedoAndCancel` | 大纲整支拖动与撤重 | PASS（1次初始MainActivity辅助） |
| SB69-AUTO-08 | `org.inkweft.app.StudyTransformNavigationUiTest#mergeFromSelectedOutlineKeepsOriginalsAndRequiresAnExplicitSource` | 合并及多来源选择 | PASS（1次初始MainActivity辅助） |
| SB69-AUTO-09 | `org.inkweft.app.KnowledgeTextLinksUiTest#backlinkEntryRestoresLiveSourcePreviewAndOriginalCardWithoutAuthorWrites` | 双链预览与返回 | PASS（1次初始MainActivity辅助） |
| SB69-AUTO-10 | `org.inkweft.app.CardReuseDialogUiTest#realReferenceAndCopyActionsKeepSharedIdentityAndIndependentContentAfterReopen` | 跨本同步引用与独立副本 | PASS（1次初始准确ComponentActivity辅助） |
| SB69-AUTO-11 | `org.inkweft.app.BoundAnnotationFlowUiTest#bindMoveReorderZoomDetachAndReopenKeepOneTransformAndProtectedLayerOwnership` | 绑定批注、缩放、层归属 | PASS（1次初始MainActivity辅助） |
| SB69-AUTO-12 | `org.inkweft.app.RecallOriginalSourceUiTest#durableCurrentSourceWaitsForOriginalReceiptAndReturnsToSameAnswerAndWindow` | 原文提示持久化后返回同题 | PASS（1次初始准确ComponentActivity辅助） |
| SB69-AUTO-13 | `org.inkweft.app.RecallOriginalSourceUiTest#sealedComparisonKeepsSixScoresReachableWithinBoundedReadingColumn` | 封存与六级评分触区 | PASS（1次初始准确ComponentActivity辅助） |
| SB69-AUTO-14 | `org.inkweft.app.LayerAuthoringNativeTest#thousandStrokeHiddenLayerCannotReappearAfterDelayedViewportFrame` | 1000笔隐藏层原生帧 | NOT_RUN_ENVIRONMENT_BLOCKED（OEM内核冻结；0次辅助） |
| SB69-AUTO-15 | `org.inkweft.app.LearningWorkspacePolishUiTest#narrowLargeTextWhitespaceAndLayersKeepControlsClearAndPenPreference` | 窄屏大字图层与留白入口 | PASS（1次初始MainActivity辅助） |

SB69-AUTO-09的双链方法不在本次云端163项选择范围，本轮真机已独立PASS（1次初始MainActivity辅助）；它覆盖预览／返回及同本来源，平板完整跨本A→B→可见返回入口→A另由SB69-FLOW-01确认。原生View用例虽以MainActivity为host，会替换内容视图，归为无辅助像素检查，不能冒充产品触区或真人持笔。

## 隔离迁移与生产恢复边界

SB69-MIG-01依据本机`upgrade-results/native-migration-audit.json`：基线schema12、29表（其中27个作者表／15,012行）；候选schema16、44表（42个作者表）。27旧作者表的主键／列结构及15,012条原行相同，缺失和改变均0；新增来源版本／来源集合符合预期，27个历史不完整集合保持不完整标记。完整性与外键检查通过，比较输入字节未改变。

这是隔离SQLite副本的比较回执；比较脚本本身设备命令0、数据库写入0，没有比较外部文件、设置和未提交草稿。本回执自身不证明主workspace升级；主workspace安装、原生首开及实际采集副本的独立证据见下一段。SB69-RS-01已通过普通应用SAF选择真实V68生产备份并确认恢复，最终UI显示已恢复资料库记录；08:43 UTC的`full-backup-restore-audit.json`为PASS_ISOLATED_COPY_COMPARISON，候选schema16、42个作者表齐全，15,012条旧作者行缺失／改变／意外新增均0，完整性与外键检查通过。该回执SHA-256为`580f59fc0cefb24e2cd02dfd9dbf9299f65bf8732db6a1ba0a7ac844abddbb8b`。因此当前确认的是隔离目标的生产恢复及恢复副本内容比较；重复恢复、重开逐页视觉、外部文件／设置／草稿仍各有独立边界。

SB69-UP-01联合回执`upgrade-results/workspace-upgrade-final.json`为PASS_CONTROLLED_MAIN_WORKSPACE_COVER_UPGRADE。同包同签名V69覆盖安装完成，未卸载或清数据；`installations/workspace-app/receipt.json`实际安装后SHA为`fe8821c5383a246b917ca0e3e53c5e300ade984e293541bd6587c5a57486362f`。`private-main-before-upgrade/first-v69-launch.txt`记录原生冷启动Status ok、TotalTime 501ms，`physical-ui/workspace-v69-first-open.xml`资料架显示431份。`private-main-after-upgrade/receipt.json`记录实际主库采集副本Room16；`upgrade-results/workspace-upgrade-row-comparison.json`为PASS_ISOLATED_COPY_COMPARISON，27旧作者表15,012原行缺失／改变0、完整性／外键通过、输入字节不变，该比较回执SHA-256为`d9273424f7842f783b1639e1c9cd69002c7bb2f963bdfc15abfd71a6b3201c11`。`private-main-after-upgrade/preferences-external-check.json`确认53份原偏好与106份外部文件逐字节一致且无增删。安装、原生首开、实际主库采集比较与文件保留证据共同支持本项已执行范围PASS；只读比较脚本本身没有执行设备迁移。未提交草稿不在比较范围，本任务开始已显示保存；普通使用及真人体验另记。

## 同规模合成样本

最新云端artifact没有可下载的`synthetic-library.iwbackup`；完整spec嵌在manifest中。本地使用既有emulator-only prepare入口在专属空模拟器生成样本，不改guard。08:18 UTC的本地导出回执PASS：runId `81f9c035-e470-469b-b8b9-9b79545742a8`，spec SHA-256 `0f262d9433c9a4448ad74e257bde762abed1823dfe875d51cd9af4f0e4d62e80`，合成备份7,823,730字节、SHA-256 `b9fe6adf21f107bf08d65f4cc1c21cb0329181169ee8ab33627d3864952aff37`。真机导入已PASS：前序18份自动测试合成本单独归档并按准确数据库文件清理后，从0份空insertion经原应用SAF校验／确认恢复，界面显示2本13页、0回收及实际恢复成功。`physical-synthetic/canonical-restore-comparison.json`为PASS_FULL_SYNTHETIC_LIBRARY_COPY，实际采集副本全部42作者表／3,203行与备份逐表typed摘要及全库canonical `97c2195b2442fcc04d83f4a5933a25d3103d79ea2c3a444539a676ea3b02aa8b`相同；输入前后字节不变，没有过滤其他本或只比较部分样本。原始采集归档`physical-synthetic/restored-database/database-raw.tar`的SHA-256为`4e82c76ba45298c35b728c3a750b30b08287df9fd21d8116ca35e447fe5b07ee`。普通六批链路仍待独立回执。`emulator/prepare-visual-review.json`记录已逐图查看本地prepare七张PNG，未见阻塞静态缺陷；这不扩展为本地五阶段40图或真机通过。

初始规格保持两本96卡、12资料页＋伴随知识本1页、3图各120活动节点／6层、12跨本引用、12,000字正文／4,000字注释、两份4096×3072原图、压力页1000笔／100000点、三层两留白。prepare生产备份包含24次正式＋1次临时练习，共25次合成作答。云端后续native_recall另加3次练习，其末次重开核对28条；本地仅prepare不能宣称已有后续阶段结果。

云端40图来自同一runId、源码及APK：prepare 7、reopen 1、visual 24、native_recall 7、native_recall_reopen 1。末次重开图只展示资料页，回忆记录一致性依靠独立断言；图片存在或静态审阅不代表所有题态、触区、真人笔感或温升已验收。

## 本机证据索引与后续回填

本机证据根为`E:/Inkweft/archives/2026-10-05/SixBatchLocalHandoff`；不把私有原件复制到本目录或Git/PR。可核对的回执入口如下：

| 入口（相对本机证据根） | 用途 |
| --- | --- |
| `cloud-reference/CLOUD-HANDOFF.md`、`cloud-evidence/evidence-audit-summary.json` | 固定源／文档提交和本轮云端证据范围 |
| `checks/workspace-receipt.json`、`checks/insertion-receipt.json`、`checks/lint-receipt.json` | 本地构建、签名、摘要和lint |
| `physical-runtime/summary.json`及`physical-runtime/room-unassisted/`、`physical-runtime/app-main-initial/` | 14唯一方法汇总；Room4与Main7的方法终态、身份和辅助次数 |
| `physical-runtime/app-component-unassisted/`、`physical-runtime/app-view-unassisted/` | 原Component首方法冻结、后两方法停队列及View冻结，保留原始状态 |
| `physical-runtime/component-initial-probe/`、`physical-runtime/component-followup-01/`、`physical-runtime/component-followup-02/` | Component3各一次初始准确Activity辅助后的实际通过 |
| `upgrade-results/native-migration-audit.json` | 隔离副本迁移比较摘要；原件与原始私有数据只本机 |
| `upgrade-results/full-backup-restore-audit.json`、`physical-ui/backup-restore-final-ui.xml` | 普通生产恢复结果及恢复副本比较；只公开本文汇总，不复制原始私有UI或数据 |
| `upgrade-results/workspace-upgrade-final.json`、`installations/workspace-app/receipt.json` | 主workspace受控升级联合回执与实际安装身份 |
| `private-main-before-upgrade/first-v69-launch.txt`、`physical-ui/workspace-v69-first-open.xml`、`private-main-after-upgrade/receipt.json` | 原生首开与431份资料架、实际主库采集Room16；原始私有UI仅本机 |
| `upgrade-results/workspace-upgrade-row-comparison.json`、`private-main-after-upgrade/preferences-external-check.json` | 15,012旧作者行零变化、53偏好／106外部文件逐字节保留 |
| `emulator/fixture-prepare/prepare-runner.json`、`emulator/archive-transfer-source.json`、`emulator/prepare-visual-review.json` | 本地prepare／合成备份身份与仅七图的视觉审阅 |
| `private-worktree-before/preservation-midtask.json`、`cleanup-private-device-staging.json` | 旧162文件／Git状态保留、已归档私有临时副本和Download临时备份的定向清理 |
| `physical-ui/synthetic-inspection-complete.xml`、`physical-ui/synthetic-restore-running.xml`、`physical-synthetic/canonical-restore-comparison.json` | 完整合成样本的普通生产恢复成功及全部42表typed比较 |
| `physical-synthetic/restored-database/database-raw.tar`、`isolated-test-fixtures-after/owned-synthetic-cleanup.json` | 原始物理采集归档、前序18份合成本的单独归档与定向清理 |
| `physical-plan.json`、`runtime-inputs/group-inputs.json` | 原始定向及普通／真人计划；实际方法状态以summary和追加回执为准 |

后续仅根据新增实际回执更新：完整合成样本的普通链路、View环境阻断的处理、15／30分钟真人及热状态分别登记。真实生产恢复的现有通过记录保留其隔离目标与内容比较范围。每项保留失败与重试，不以较晚通过覆盖不利证据。故障注入只安排备用／合成环境；确认未实现能力时另记NOT_IMPLEMENTED与依据，不混为待测或通过。

异常优先应用内“诊断与导出”标记、复现并导出ZIP，补充日志无法表达的操作／视觉／笔感。基础诊断不是完整系统日志。私人书名、真实备份、原迹、数据库、签名材料和原始诊断仅本机；公开只放已核对的合成内容或统计摘要。

本次文档更新仅涉及本报告、结果模板和设备清单，不构建Android。旧162文件字节及原Git状态中途保留检查PASS；两处私有核对副本目录的六个文件及Download临时备份已确认归档后清理。最终系统设置、临时XML、专属AVD与交付链接收尾仍由集成人完成，不能将已完成的定向清理写成全任务清理通过。
