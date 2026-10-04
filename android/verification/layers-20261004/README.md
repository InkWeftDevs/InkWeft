# 第四批图层、留白与绑定笔迹：增量证据

## 数据与核心检查点（2026-10-04）

- 以 `378dace` 的原图与三态基线接续，保留 Room14 来源修订、来源集合、卡片变换三表
- 新增 Room15 `canvas_authoring` / `authoring_receipts`，34 表完整备份，显式接受 V14 的 32 表；只对 V13 及更早备份提升旧来源集合，避免重复插入 V14 来源版本
- 本页/本画布局部最多 32 层、32 个留白；批注最多 512 笔、60,000 点或 1,900,000 字节（任一先到），超限拒绝；不缩减 3 层（隐藏/锁定）与 2 留白的贯穿样本
- 原 `InkStroke.visible` 和 `PageObject.hidden` 不作用户层开关。配置 revision 与普通笔迹归属补记分开；普通新笔的归属随 ink/object 事务写入，连续排队不因自身新笔产生配置冲突
- 层操作核对配置、笔迹和对象 heads；书写 scope 在落笔时固定，并随队列、单笔/跨页检查点和回执重试保留。先重放旧回执，再校验当前层门禁；空 scope 只兼容旧默认单层
- `.iwpage` v8（含 authoring 载荷）及整本副本保留隐藏层、留白、绑定关系并重新标识独立副本；默认无扩展内容仍使用旧页格式。完整资料库备份不按显隐过滤
- 绑定原始点与局部变换分开，目标移动/缩放只在显示时投影一次；解绑保存冻结变换，不把极细/极粗显示笔宽强行改写为原始笔宽

### 已实际执行

- `:core-domain:test --tests '*PageAuthoringTest'`：11 项，0 失败，0 跳过
- `:data-local:kspDebugKotlin`、`:data-local:compileDebugKotlin`：通过；Room15 schema 已生成
- `:app:compileDebugKotlin`：页面图层/留白/批注工作稿编译通过。首轮类型冲突及 PDF 关闭方式错误已修；保留失败与重跑事实
- `:data-local:compileDebugAndroidTestKotlin`：通过，含 7 个新增图层/故障/回执/备份/检查点用例的源码

### 不得视为已运行

- 新增 Room 用例仅编译，Android 运行 0 次
- 新增原生 UI、像素、真实笔输入、设备容量/性能、真实使用：NOT_RUN
- 页面 UI 尚在独立接线阶段；图上的出现位置批注、返回 viewport hook 和组合回归未在此检查点宣称完成
- 没有重启软件模拟器，没有触及用户电脑、真实资料、签名或发布

## 正文与卡旁接线候选（未继承早检 PASS）

- 正文工具条明确切换“原页视图／含留白展开视图”。展开视图已置于正文区域而非设置预览，保留外层资料导航；按稳定页 ID 保存阅读模式与展开列表位置。返回原页是显式切换，直接离开资料后重开保留展开模式
- 稳定出现位置有独立 `AnnotationRegion` 尺寸、折叠与参考宽度，authoring codec v3 保持 v1/v2 可读。缩小仅裁剪，折叠不删除溢出笔迹；页/图、重开、副本和可见渲染共用裁剪规则。共享卡片文本注释仍独立
- 手写 scope 从 ACTION_DOWN 到队列/单笔及跨页检查点保持，只有默认单层允许旧空 scope。普通新增成员不改变配置 revision；显隐/删除/转层撤销不兼容画面，不能复活旧高清帧中的答案
- 逐可见非空层复用既有 `AsyncInkRaster`。单视窗合计 20,000,000 缓存像素是明确显示预算；超出时显示“未完成”并保留全部原数据，不默默降采样。固定 1000 笔/100,000 点、三层（隐藏/锁定）、两留白的测试未缩小，当前只新增测试源码，实际帧率/输入延迟 NOT_RUN
- 用户层查找使用不可变索引，列表顺序仍为持久顺序。JVM 新增 1000 项、百万次查找样本，不将它当平板原生帧率证据
- 作者 pending 按 scope 写入有校验的 AtomicFile，冻结操作 ID、前后状态、heads 与原编码字节（上限约 4 MB），不进入 SavedState Bundle。IO 线程日志确认前不写数据库；pending 保持未保存状态且禁止下一笔/切离。首次日志 fsync 前仍是未确认窗口，不承诺断电零损失。重开重试同一操作；成功后读取最新 heads，存在新依赖时不开放旧撤销。未知/损坏恢复材料不自动丢弃，放弃须二次确认
- 可见 PDF/PNG 是渲染分享：PDF 背景最长边 2048 像素，图片使用最长边至多 1024 的保存预览；PNG 最长边 4096、8MP。明确标示非原始矢量／原图保真输出，不嵌入隐藏数据或图片元数据。原始 PDF 与原图字节仍由可编辑副本和完整备份保留，未回退为 JPEG 唯一数据
- B3 同卡重复打开请求、详情内回跳、单页/连续页视野恢复已接候选；连续视图在现有 1–3 倍范围内实测几何匹配后才消费请求，无法精确恢复时提示切换单页并保留请求
- B5 原文门禁 continuation 只在同一 session/plan 且仍挂载时切换读写模式；未确认的提示事务不提前离开回忆。旧 manual 空 gate 保留原路径
- 新增两个原生延迟帧/原图隐藏测试、两个日志恢复/损坏保护测试和区域 codec 测试；当前增量待组合编译/执行，Android 运行、像素与真人验收仍 NOT_RUN

## 2026-10-04 10:32 UTC 组合源码终态

源码冻结：`10eacce → 5dccff7 → 39ebee0 → 81ec1a3`。后两项读回/搜索增量已重新验证，不继承旧 PASS。父主线的 Room16、后续 B5 UI 与 B6 截图补丁不在本轮源码内。

- `:core-domain:test`：35 suites / **350 项，0 失败、0 跳过**；其中 PageAuthoringTest 16 项。逐 suite 结果、时间戳与原日志 SHA256 见 `compiled-evidence.json`
- `:data-local:kspDebugKotlin`、`:data-local:compileDebugKotlin`、`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`:data-local:compileDebugAndroidTestKotlin`：全部 PASS
- 组合首轮 15 项中极端绑定往返坐标精确相等失败：差异约 2e-12；改为 1e-8 坐标容差，仍断言原始笔迹对象不变。后续发现的跨模块 smart cast、显式 import 和导出代码括号错误均修复重跑；没有删用例或缩小样本
- 最新全量轮 `b4-final-compile.log`：BUILD SUCCESSFUL in 1m27s；前一扩大接口轮 `b4-ui-joint-build4.log`：2m47s
- Android 新增源码共 16 方法：PageAuthoringRepositoryTest 10、AuthoringPendingStoreTest 2、LayerAuthoringNativeTest 2、StudySourceScopeRestorationTest 1、LayerSnapshotPreviewTest 1。**本 worker 实际执行 0 方法，全部 Android 运行仍 NOT_RUN**；没有把 Kotlin 编译当事务/像素/设备体验通过

### 本轮额外封闭的读取竞态

- 摘录的 `StudySourceDraft.authoringRevision` 在选区/截图冻结，进入新摘要 digest 与 StudySession pending 恢复；null 保留旧 digest 字节且只允许 legacy 作者状态。来源事务先核旧回执，再校验作者 revision 与可见成员；隐藏前的迟到截图/文本不能成为新的摘录。隐藏对象文本也不进入新摘录摘要
- 页面搜索 `saveSearchText(...,authoringRevision:Long?=null)` 同事务检查作者修订。层变化使旧索引失效，迟到 OCR 或旧空 scope 不会重新发表隐藏前文字；旧默认页仍兼容
- 新的冻结缩略图以 `file.authoring` 过滤及分层合成笔迹、对象和局部批注；不借当前原页配置重投影历史快照。隐藏层内容保留在原文件，像素预览不能显示它
- 被删除的结构出现位置通过已有 MapDefinition 修订证明归属，保留绑定批注和完整备份；不把历史节点伪装成当前可见节点
- 连续页优先使用当前页的已发布作者状态，避免书本观察流滞后一帧复活旧显隐；新复制/导入作者数据验证对象/美化原迹闭包

### B4 各项交付与待验边界

| 条目 | 已接生产路径 | 尚需实际验收 |
| --- | --- | --- |
| B4-01 | 正文原页/展开留白、持久模式与滚动；稳定出现位置批注区独立尺寸/折叠；存储不裁原迹 | 窄屏发现性、重开及溢出显示的 Android/真人验证 |
| B4-02 | 卡旁与对象旁绑定、游离、解绑冻结、单次目标变换、稳定出现身份 | 卡移动/重排/缩放的组合像素与真笔验证 |
| B4-03 | 原笔记笔迹明确归属；B5 门禁接口先持久原文提示再切换；冻结/live 作者元数据接口 | B5 attempt-owned 作答与原位遮罩整体验收由 B5 联合完成 |
| B4-04 | 局部图层新建/名称/顺序/显隐/锁定/删除/转层、当前层停笔、跨锁定整组拒绝 | 实际 UI 流程与窄屏/读写锁交互 |
| B4-05 | Room15/格式/备份、副本身份重建、作者 CAS/回执、IO pending 日志、撤销、可见 PDF/PNG 分享 | 故障、备份恢复及输出像素 Android 测试；首次日志 fsync 前仍未确认 |
| B4-06 | occurrence-ID 绑定与用户层顺序/显隐共同投影；历史目标仍有可证明归属 | 层与卡组合操作、重开和性能固定样本 |

父拥有的 BookInkScreen 两个 StudySourceDraft 构造需传 `selection.authoringRevision`；BookSearchPanel 和 PageSearchDialog 在加载时冻结作者状态并传搜索 scope。它们由父单独提交/联合编译；本 B4 worker 不并写该租约文件，当前默认空 scope 在多层页安全拒绝。

外部设备、真实触笔输入/延迟、真人使用与手动视觉验收仍单列 NOT_RUN。未声称原始 PDF 矢量保真导出；当前输出是明确像素预算内的可见渲染。完整备份保存原始文档/原图及隐藏关系。
