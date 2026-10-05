# IW-B2-06 / IW-B2-07 内容转换与来源版本

基点：e23eb449766b2a50914743d22e8846be4b1fe054。独立工作树 `inkweft-transforms`，不触碰用户设备，不发布或合并。

## 已实现合同

- `SUMMARY` 扩展既有 `KnowledgeData.Link`。多张原卡指向用户填写正文的总结卡，父子层级不改。
- 合并/拆分新建内容身份，所有原卡、出现位置、局部布局、引用、原题和进度保留。旧身份显式列出目标，拆分不会默认选首张。目标不自动建题。MERGE/SPLIT不自动加入导图；SUMMARY在确认的主图或所选图原子添加稳定出现位置，同父可解析时继承共同父级，旧节点不移动。
- 合并正文按选择顺序以两个换行连接；每段原文字节语义保留。共享注释完整连接、双色继承首卡。超过现有单卡容量明确拒绝，不截断。
- 拆分至少两张；当前独立UI提供两张，按用户确认的字符边界逐字分配正文。默认两张均保留全部注释和来源，确认前可分别选择仅第一/第二张。
- 只读预览列出正文、共享注释、来源版本、出现位置、引用和题目。取消不写作者数据，也不写待提交意图。
- 单Room事务、幂等operationId、持久操作回执与受完整版本/依赖约束的原子inverse。写入后失联查询原回执；未知重试沿用相同ID。后继转换尚在使用新身份时拒绝先撤销前一步；后继已撤回后可继续逆序撤销。确认后的待提交计划用应用私有AtomicFile保存，避免大Bundle并支持重新打开核对。
- `study_source_revisions` 存稳定ID和不可变来源修订；`study_card_source_sets` 为每个卡版本保存有序固定引用。合并与独立副本只新增引用，不复制大BLOB到Knowledge。
- 保留 `study_sources` 单源兼容缓存（存在当前快照的存储开销）；容量按不可变来源历史及未升级legacy来源核算。RECROP新增来源版本，满额时不以丢弃旧版本腾空间。
- `MapEmbedRepository` 的独立副本保留全部固定来源，并复制共享注释/双色初值到新cardId；题目与进度不复制。
- Room13→14、生成schema14、完整备份、V6–V13归档promotion及校验同批。旧body逐字保留；已被旧版本覆盖的历史裁剪显式不可恢复，绝不拿当前裁剪冒充。
- `FrozenBranchReviewQuestion.sources` 按原cardRevision读取，旧题固定答案不读取新裁剪。同一次复习/变换读取按sourceRef共享不可变sourceRow/bytes，避免多卡共源复制BLOB；没有全局缓存。

## 接线合同

新独立组件在 `CardTransformDialog.kt`，由StudyWorkspace所有者接入，不由本提交改其生产入口。

- `app.study.transforms()` 返回稳定repository
- `CardTransformDialog(repository, notebookId, initialCardIds, kind, onDismiss, onCommitted, embedded, authorAllowed, onPendingChanged)`
- `CardTransformResolutionPanel(repository, cardId, onOpenCard, authorAllowed, onPendingChanged)`：放在原卡详情，也覆盖由旧链接到达的原卡
- `app.study.sourceSummaries(book)`：仅元数据Flow，`cardId/count/complete`，不加载快照
- `app.study.sources(cardId, revision?)`：固定来源集合；单源旧适配方法不会悄取多源首项
- `FrozenCardSources(sources, cardId, onSourceSelected, enabled)`：单源自动选择，多源显式选择，未知版本回调null；回忆中须在揭示许可之后挂载
- 对dialog和resolution panel使用不同pending guard key；`authorAllowed`不能包含自身pending，否则未知重试会被自己锁死
- 新目标回调是列表，必须提供选择/位置入口，不取first充当完整结果

## 精确验证层级

- JVM：`CardTransformTest` 7、`KnowledgeTest` 15、`CardPresentationTest` 4，此前共25项通过；SUMMARY出现位置补丁定向CardTransformTest 7项通过
- `data-local`、`app` 主Kotlin及两模块AndroidTest Kotlin编译通过；最新检查点命令使用单worker、in-process、Xmx3g、MaxMetaspaceSize1g
- host SQLite：`python3 android/verification/card-transforms-20261004/verify-source-migration.py` 通过。执行实际迁移SQL，比较生成Room14 DDL语义，核对29张旧表全部原行不变、旧body/快照/原图BLOB不改、早期未知来源明确不可用
- 定义并编译：`CardTransformRepositoryTest` 12项、`CardTransformUiTest` 4项；修订既有来源容量与迁移期望
- Android Room/UI实跑、像素/真实输入/真实平板体验：NOT_RUN。当前软件AVD此前启动ANR且已停机，不将编译或host SQLite当作设备通过
- 本提交未跑lint、未生成交付APK、未签名

首次及增量日志位于执行环境 `toolchain/logs/card-transforms-build.log`、`card-transforms-final-build.log`、`card-transforms-recovery-build.log`、`card-transforms-source-sharing-build.log`。最后一轮仅数据层主/测试源码增量编译通过，UI由入口集成者联合复验。它们是本工作树验证，集成后的最终UI入口仍需重新编译和运行适用测试。

## 总结实际节点补洞

SUMMARY的计划V2额外冻结nodeId、mapId、parentId、坐标和graph CAS。新出现位置使用现StudyRepository.REUSE，在同一事务/回执中创建；undo先用REMOVE_NODE撤回新位置再回收总结卡，预算/未知写入/图版本冲突一并保护。V1计划字节仍原样可读。新增命名图同父、备份后撤销及过期图/128节点预算回滚用例，源码编译通过，设备仍NOT_RUN。CardTransformDialog新增末尾mapId可选参数；生产入口须传当前图，未传时预览明确主图。日志toolchain/logs/summary-node-build.log：Core7、两模块主及AndroidTest源码编译通过。
