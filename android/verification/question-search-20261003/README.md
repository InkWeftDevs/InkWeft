# 已保存回忆题查找 · 云端独立切片

基点：`db20d40c82e31bac8b7b94eec56b61d930592fa8`，包含 PR #6 的 V68 真机记录；受测 V68 源码仍为 `15f167ed1d1270617e51e0aacd21b5b7caa9bb18`。本批不修改 V68 候选、版本号、主线或集成分支。

## 范围

- 从 `KnowledgeWorkspace.kt` 移出已有 `CardPropertiesDialog`，表单、原题身份、编辑／软移除回调和 CAS 提交流程保持。
- 在已保存回忆题区按问题原文作字面查找，忽略大小写及搜索首尾空白，显示命中数／本卡总数，支持清空和无匹配提示。
- 查找只影响当前卡片的问题列表，不改变复习范围或作者数据；只读模式仍可查找。
- 查找词与父层标签、别名、新题草稿分别保存；编辑后按新原文重新匹配，不自动清空查询或替换题目身份。

没有新增索引、数据库表、批量作者动作、依赖、到期算法或版本编号。公开内容只有源码、合成测试与此说明。

## 验证范围

- `git diff --check`：PASS。
- 弹窗迁移对比：移除新增搜索代码、归一化换行后，既有弹窗与基点逐字一致。
- 独立静态复核：未发现具体编译／正确性／回归问题；不代表执行通过。
- 新增 `QuestionMaintenanceUiTest#longQuestionSearchKeepsDraftsAndIdentityAcrossRecreationAndEditing`：21题、大小写／首尾空白、重建、清空／无匹配、编辑后退出匹配、准确作者写入边界。
- 既有 `actualReadOnlyEntryShowsSavedQuestionsButCannotEditRemoveOrSaveAuthors` 补查只读搜索及清空；不另建重复场景。
- `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:app:lintDebug`：2026-10-03 云端同次执行 **PASS**，退出码0，冷环境构建耗时12分12秒。JDK17、项目固定Gradle9.4.1、SDK36/build-tools36.0.0，单worker、关闭并行及SDK自动下载；未生成签名或安装包。
- lint XML：0 Error、0 Fatal、133 Warning；没有指向新弹窗或本次题目维护测试的lint项。末尾空行规范化后的同三项增量复核亦PASS（4分47秒）；该纯格式变更实际上只需差异／字节核对，后续不为格式重复编译或lint。
- 两条上述 UI 方法当前均 **NOT_RUN**；没有模拟器／设备执行和新界面截图，不能把编译当行为验收。本批不重跑未改变的核心和Room全套。此前SDK元数据和Java21缺少javac导致的工具链失败已修正，保留原日志，不算源码测试失败。

## V68 真机冻结的下一最小实验

PR #6 的12项 Room PASS仅属于固定源码 `15f167ed1d1270617e51e0aacd21b5b7caa9bb18`，不能算本次搜索代码的新构建通过；16项未完成 UI边界保持。`readiness.json` 是初始未执行快照，`physical-device-report.json` 的内嵌Room产物说明也残留安装前状态；后续实际结果按逐方法原始完成事件与日志核定，本批不改写历史证据。两条冻结方法共用 Compose Activity rule、`@Before` 的 UiAutomation shell读取，以及显示设置／Activity重建。现记录没有阶段点能证明究竟在哪一步冻结；shell读到EOF没有超时属于诊断盲区，不能据此认定根因，手动暂停Compose时钟更晚才发生。

下一次明确授权的设备验收可先用原已安装app runner仅跑：

`org.inkweft.app.RenderResourcesTest#immutableEqualityMemoDoesNotHideNewErasures`

该现有方法只做内存对象／断言，没有 Activity rule、UiAutomation、设置、Room或笔记写入。配合原外部freeze监测和原始方法事件，若同样冻结可将范围缩到Compose启动之前；若PASS，只证明该runner能执行此控制方法，不能排除延迟冻结。之后再区分Activity rule与LocalMotion设置路径。本批未执行任何本地或设备操作。
