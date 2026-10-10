# V86 原件与按页加载 · 2026-10-10

PDF／图片原件使用逐块流和可重建文件缓存；PDF 直接导入工程边界为 512,000,000 字节、500 页，移除 PDF 全库 80 MB 和图片全库 32 MB 字节门槛。连续阅读按可见范围及相邻页读取正文，全本仅保留轻量图层权限；安全离屏页释放笔迹并保留可重载的撤销记录。设置可查看原件占用、剩余空间并清理缓存。

本地全库备份 v2 上限 8 GiB，读取兼容旧 v1；新备份及超过旧额度的资料需要 V86+。Room16 沿用。单图 20 MiB、单本 500 页、旧交换格式与其他记录边界仍存在；加密云备份沿用 500 MiB 协议。云端规模验证不代替安卓大 PDF 或实机性能验收。

见 [实现与证据](verification/streamed-storage-v86/RESULTS.html)、[验证回执](verification/streamed-storage-v86/validation.json)、[本地精简清单](verification/streamed-storage-v86/LOCAL-TESTS.txt)。

---

# 最新本地候选 V65 — 2026-10-02

整本、收藏集合、整图与分支复习可明确选择“全部问题”或“仅待复习”。后者仅包含题目自身标为“待复习”的活动问题，集合的卡片属性条件继续独立使用；新问题原本默认“待复习”。

默认仍为全部问题。筛选不写资料、不改题目顺序；本轮保存固定问题/答案版次及筛选计数，当前轮标记不重新筛题，下一轮使用最新题目状态。范围显示总题数、本轮题数、真正未设题卡片及仅有其他状态题的卡片，已理解题不冒充未设题。切换笔记、图、分支、集合、页面或状态选择时取消准备，旧请求不能迟到打开，旧 finally 不能清掉新请求。

74 个唯一 class/method PASS：核心 14、Room 23、Android 界面 37（新增 5）。 lint 0 错误 / 0 致命 / 132 警告。候选 `E:/Inkweft/dist/ReviewState-V65/InkWeft-v65-workspace-preview.apk`；SHA-256 `2d059c3a8f86aa520f281119c041ec0259e27071d78309b57ccb9533f72c46ba`。

原签名与 Room12 / IWO9 保持；MAP_PORTAL_V2 当前、历史及墓碑最低支持仍为 V55。原有未提交改动和 V56–V64 交付包保留。

本批仅用新建专属空白模拟器的合成资料。真人手写、真实故障原迹、笔感、真实平板分屏、人工无障碍走查均 NOT_RUN；真实备份恢复 NOT_VERIFIED；完整 OS 进程死亡 NOT_RUN。Activity/SavedState/VM 重建只代表各自已执行边界。未安装或升级真实设备，未读取用户真实资料，未上传私有资料，未暂存、提交、推送、PR、合并或发布。指定对话最新全文仍为 NOT_RETRIEVED；本批依据后续明确持续自主开发授权。

见 [交付](DELIVERY-V65.md)、[验证](VERIFICATION-V65.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[缺口](DEVELOPMENT-GAPS-V65.md)。

---

# 最新本地候选 V64 — 2026-10-02

摘要卡原有“属性与回忆”入口现在列出已保存的独立回忆题，可按实际题目 ID 和修订编辑原文，或确认后仅软移除选中的一道题。题目的手工学习状态、原题历史、旧操作回执、同卡其他题目与原摘要答案保留。

题目子编辑保留父层标签、别名、新题草稿，成功只关闭匹配操作与题目 ID 的子编辑。并发版本变化走原 CAS 拒绝；结果未知仍核对原 operationId，不创建替代操作。明确成功与明确拒绝身份保存至界面消费，恢复不再依据“没有 pending”猜测失败。阅读模式可查看原题，普通作者写入仍由原书级门控拒绝。

Android 定向 28 个唯一 class/method PASS（新增 8：7 项原生界面、1 项真实 ViewModel 状态恢复；相关旧回归 20）；lint 0 错误 / 0 致命 / 132 警告。候选 `E:/Inkweft/dist/QuestionMaintenance-V64/InkWeft-v64-workspace-preview.apk`；SHA-256 `0f74ba0ecf084553fe6d8257a57385d6a64f74655cfe2de9cf9be83f3ba9632b`。

原签名、Room12 / IWO9、原笔迹与作者数据合同保持；MAP_PORTAL_V2 当前/历史/墓碑最低版本仍为 V55。原有未提交改动与 V56–V63 交付保留。

本轮只使用新建空白模拟器的合成资料。真人手写、真实故障原迹、笔感、实机分屏与人工无障碍走查 NOT_RUN；真实备份恢复 NOT_VERIFIED；完整操作系统进程死亡 NOT_RUN。真实 ViewModel 的 SavedStateHandle 重建不等同于完整进程死亡验收。未安装/升级真实设备、未上传私有资料、未暂存/提交/推送/PR/合并/发布。指定对话最新全文仍 NOT_RETRIEVED，功能批次来自父对话后续明确的持续开发授权。

见 [交付](DELIVERY-V64.md)、[验证](VERIFICATION-V64.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[缺口](DEVELOPMENT-GAPS-V64.md)。

---

# 最新本地候选 V63 · 2026-10-02

按已保存的同册智能集合直接回忆：核对集合版次并按原标签/卡片手工状态规则筛卡，显示集合名、真实卡片/问题/未设题计数，再固定本轮问答版本。结束、退出或重建保留原集合和搜索词。失效集合明确提示，准备取消不晚开另一轮。

集合状态是卡片属性；问题“已理解/待复习”是独立标记，本批仍保留匹配卡上的全部活动独立问题。搜索词只查卡片，不改变“复习此集合”的保存条件。原整本、图和分支回忆语义及提示/回源/幂等标记路径复用。

核心 8、真实 Room 17、原生 UI 17（新增 5），共 42 个唯一用例 PASS；lint 0 错误 / 0 致命 / 132 警告。APK `E:/Inkweft/dist/CollectionReview-V63/InkWeft-v63-workspace-preview.apk`，SHA-256 `4528a76cb76129f53014c2f69da0bb19542b74c0694a02b008e11df480a293f8`。

Room12 / IWO9、原笔迹/数据/签名保持；MAP_PORTAL_V2 当前/历史/删除记录最低 V55。原有未提交改动和 V56–V62 交付保留。

仅操作本任务空白合成模拟器。真实平板、真人手写/原故障、物理分屏、人工读屏 NOT_RUN，真实备份恢复 NOT_VERIFIED；合成原迹不代表真人笔感验收。未安装/升级真实设备、未上传私有资料、未暂存/提交/推送/PR/合并/发布。指定对话最新全文仍 NOT_RETRIEVED，本批依据后续持续自主完善授权。

见 [交付](DELIVERY-V63.md)、[验证](VERIFICATION-V63.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V63.md)。

---

# 最新本地候选 V62 · 2026-10-02

**0.0.62-reading-access（62）**：现有“其他设置→阅读与操作”增加默认可用的“只读浏览”开关，不用先自定义工具栏。开关复用当前原生书写页的实时门禁；成功后收起设置并关闭键盘，拒绝时保留设置、原模式和草稿并说明原因。全屏阅读“更多”增加“查找笔记”和“文档概览”，沿用原功能，不增加常驻按钮。

新增4项实际原生UI回归、15项受影响旧回归，共19个唯一用例通过；lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/ReadingAccess-V62/InkWeft-v62-workspace-preview.apk`，SHA-256 `c68eea53228148e080c7ec420350aa874d91308d7315e171d1ce0cae35ae5a07`。见[交付](DELIVERY-V62.md)、[验证](VERIFICATION-V62.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V62.md)。

原笔参数、手指设置、工具栏隐藏/顺序、摘录偏好及作者记录保持。搜索关闭后保留全屏、只读、原来源页及导图上下文；概览沿用原来源退出规则。Room12/IWO9、固定签名与原数据格式保持；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。既有未提交工作和V56–V61候选保留，未暂存/提交/推送/PR/合并/发布。

只操作本轮空白合成模拟器。真实平板、真人手写/原故障、物理分屏和人工读屏NOT_RUN，真实备份恢复NOT_VERIFIED。指定对话最新全文仍NOT_RETRIEVED，本批依据用户后续持续完善授权。

---

# 最新本地候选 V61 · 2026-10-02

**0.0.61-reading-tools（61）**：只读浏览使用紧凑阅读工具栏，直接打开已有导图和本笔记摘录，并一键返回书写。全屏、导出和计时器复用原入口；书写工具、加页入口及作者专用操作提示在阅读时隐藏。返回书写保留原笔类型、颜色、宽度、手指设置及工具栏自定义，不重放旧的展开请求。

375dp窄屏、1.6倍字体及左右分屏实际186.5dp编辑侧使用可换行布局；参考侧标题单独显示，操作及页码可换行。原生画布和参考本身份保持，阅读模式不新增作者记录。正常宽布局沿用原排列。

新增4项实际原生UI回归、18项受影响旧回归，共22个唯一用例通过；lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/ReadingTools-V61/InkWeft-v61-workspace-preview.apk`，SHA-256 `478bfdd5a03c85c6ed6a01b9e3a06b688a2d7780d45bf183bc1d33f15d6e94a9`。见[交付](DELIVERY-V61.md)、[验证](VERIFICATION-V61.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V61.md)。

Room12/IWO9、原迹/PDF字节及数据格式保持，固定签名不变；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。既有未提交工作与V56–V60候选保留；未暂存/提交/推送/PR/合并/发布。仅操作本轮空白合成模拟器。真实平板、真人手写/原故障、物理分屏及人工读屏NOT_RUN，真实备份恢复NOT_VERIFIED。指定对话最新全文仍NOT_RETRIEVED；本批依据用户后续持续完善授权。

---

# 最新本地候选 V60 · 2026-10-02

**0.0.60-select-await（60）**：阅读浮窗“回原文”等待真实选页事务成功后才关闭详情、切页并聚焦。失败保留卡片详情与原迹；返回、重建或更新普通选页使旧请求失效，旧结果不再回跳。阅读锁仍允许浏览。普通选页保持即时响应，并清除之前加页/编辑留下的延迟UI目标，防止后续目录更新覆盖新选择。

新增5项实际UI/VM回归、14项受影响旧回归，共19个唯一用例通过；lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/SelectAwait-V60/InkWeft-v60-workspace-preview.apk`，SHA-256 `35121d4d48d2e7046d5a3a515d4d9c7a5fe784a4ab3d0f0a154cd45bc3edb60e`。见[交付](DELIVERY-V60.md)、[验证](VERIFICATION-V60.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续](DEVELOPMENT-GAPS-V60.md)。V59来源避让、真实节点可见、原窗口/相机恢复和双向分屏保留均已重跑。

Room12/IWO9、原迹/PDF字节与数据格式不变，固定签名保持；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。继承未提交工作与旧候选保留，未暂存/提交/推送/PR/合并/发布。只操作本轮空白合成模拟器；真实平板、真人手写/原故障、物理分屏和人工读屏NOT_RUN，真实备份恢复NOT_VERIFIED。指定线程最新全文仍NOT_RETRIEVED；本批依据用户后续持续完善授权。

取消不补偿已经提交的浏览元数据；选页事务后的其他连接回收仍有极窄竞争。Library来源检查与选页仍为两事务，其旧回归通过；摘录/搜索等原即时包装未在本批改为await。被详情遮挡的浮窗close/min/dock/mode取消路径仅静态核对，未声称真实手指覆盖。

---

# 最新本地候选 V59 · 2026-10-02

**0.0.59-source-focus（59）**：阅读页回原文后，宽屏临时将导图停靠右侧并让出纸面；窄屏或原最小化窗口保留顶部48dp入口，纸面让出56dp。一级“恢复窗口”返回用户原浮动/停靠/最小化和窗口模式，原位置、大小、图节点、折叠与相机保持。既有左右/上下分屏按编辑半屏宽度处理；旋转和切换分屏方向后仍按当前画布尺寸聚焦源区域。拖动纸面后再次点同一来源也会重新定位。

新增UI 4/4、受影响旧UI 12/12，共16个唯一用例通过。lint 0错误/0致命/132警告。候选 `E:/Inkweft/dist/SourceFocus-V59/InkWeft-v59-workspace-preview.apk`；SHA-256 `addf7a02275a029b9adc8b4b3610f1946c78cc2c0b409a0ecb02b348732a3bf5`。见[交付](DELIVERY-V59.md)、[验证](VERIFICATION-V59.md)、[实机待测](DEVICE-TEST-CHECKLIST.md)、[后续范围](DEVELOPMENT-GAPS-V59.md)。

Room12/IWO9、原笔迹/PDF和作者数据格式不变，固定签名保持；MAP_PORTAL_V2当前/历史/删除记录仍最低V55。保留继承未提交工作，未暂存/提交/推送/PR/合并/发布。执行只限本轮独有空白Android35模拟器及合成资料，未读取/启动/安装/升级真实平板，未上传用户数据。真实平板、真人笔迹/原故障、物理分屏、人工读屏仍NOT_RUN，真实备份恢复NOT_VERIFIED。

指定线程最新全文仍NOT_RETRIEVED；本批依据用户后续持续本地完善授权，未把旧材料冒充指定最新讨论。阅读浮窗来源回调仍即时选择已加载页、后续I/O异步保存；本批不声称解决该await一致性。Library来源有效性检查与选页仍是原两个事务，取消不回滚已提交的浏览元数据。

---

# 最新本地候选 V58 · 2026-10-02

**0.0.58-library-source-nav（58）**：资料库学习页“回原文”现在等待真实页面选择成功，随后打开同一本、同一源页和摘录区域；连续阅读切回单页但保持阅读锁。失效来源保留当前卡片和旧原迹快照，不提前关闭学习页。等待时禁用重复回源，返回原节点/关闭仍可点；详情退出同步取消当前请求，关闭学习窗口、换卡及旋转后旧请求不向当前界面发布导航或旧错误。

新增真实UI 3/3、受影响旧UI 12/12，共15个唯一用例通过；lint 0错误、0致命、132警告。候选 `E:/Inkweft/dist/LibrarySource-V58/InkWeft-v58-workspace-preview.apk`；SHA-256 `75155e873e696a4c6ee82ba08caf5d23cd548eb02f1a13bf9749a65130a4c657`。见[交付](DELIVERY-V58.md)、[验证](VERIFICATION-V58.md)、[实机待测](DEVICE-TEST-CHECKLIST.md)、[后续范围](DEVELOPMENT-GAPS-V58.md)。

精确指定来源 [分支 · 分支 · 调研iPad笔记软件开发](chatgpt-conversation://6abbd418-4dd8-83ea-8e83-d60cf0b6e100) 最新全文仍 **NOT_RETRIEVED**。本批根据后续“那你看着改，一直往后推进完善这些功能”的连续本地开发授权实施；历史阶段的单批停止约束已被后续授权替代。未将旧讨论冒充最新需求。所有执行限本轮空白 Android35 模拟器与合成资料；未读取、启动、安装或升级真实平板，未提交、推送、PR、合并、发布或上传私有原迹。

Room12/IWO9与原笔迹、PDF、作者数据格式不变，固定签名保持。MAP_PORTAL_V2当前/历史/删除记录仍最低V55，旧V54/V47不能读取，不能据同签名推定真实旧库升级/恢复已通过。真实平板、真人笔迹/原故障、物理分屏、人工读屏、真实备份恢复均未验收。有效性检查与选页仍是两个原有事务，存在极窄的跨连接整本回收竞争；取消不能撤销已提交的浏览页选择。旧阅读页浮窗回调沿用已加载页面检查和异步选页，不声称改为等待I/O完成。浮动导图遮挡焦点的自动避让本批未实现。

---

# V57 卡片固定回源 · 2026-10-02

**0.0.57-card-source-nav（57）**：卡片“回原文”移入固定操作区，来源默认收起、长正文滚动后均一级可达；完整标题放入现有正文滚动区，固定header简短，按钮至少48dp且窄屏可换行。来源按当前cardId校验，成功回跳清详情/来源查看模式，保留原图选中节点、视野、折叠及阅读锁；失败保留详情、旧原迹快照与原页，不跳替代页。来源错误限当前卡，下一卡不沿用旧提示。

新增原生UI 3/3、受影响旧UI 13/13通过；lint 0错误、0致命、132警告。 候选 `E:/Inkweft/dist/SourceNavigation-V57/InkWeft-v57-workspace-preview.apk`。见[交付](DELIVERY-V57.md)、[验证](VERIFICATION-V57.md)、[实机待测](DEVICE-TEST-CHECKLIST.md)、[后续范围](DEVELOPMENT-GAPS-V57.md)。

Room12、IWO9、PDF与作者数据格式不变；V55 MAP_PORTAL_V2当前/历史/删除记录仍最低V55，旧V54/V47不能读取；原固定签名相同不代表真实旧库升级/恢复已验收。 精确指定对话最新全文仍NOT_RETRIEVED；本批依据后续“那你看着改，一直往后推进完善这些功能”连续授权实施，不声称读到旧线程最新讨论。V56独立交付后继续V57，随后优先修复已发现的资料库学习页异步回源。 历史“本批即停”是当时约束，最新连续授权优先。本批精确页/区域与失败保留验证针对阅读页浮窗回调。资料库学习页旧回调仍在异步导航后立即返回成功，可能提前关闭且没传来源区域；此缺陷列下一批，不宣称本批已修复所有入口。浮动导图仍可能遮住部分原页焦点，现有移动/缩放/右停靠保持；未实现自动避让。来源变化标记沿用既有墨迹修订比较，不承诺覆盖所有PDF/对象变化。

所有运行仅在本批独立Android35模拟器和合成资料。未读取、启动、安装或升级真实平板；未提交、推送、PR、合并、发布或上传私有原迹。真人手写/原故障原迹、真实笔感、物理分屏、人工读屏与真实资料恢复均未验收。

---

# V56 PDF原文搜索 · 2026-10-02

**0.0.56-pdf-text-search（56）**：PDF 有原生文字层时按字面关键词搜索；每个当前活动笔记页仅一行，分别显示 PDF 原文（源页）与手写/文本框摘要。同页两类命中不重复成两行；点击优先定位 PDF 第一处命中的原页区域，连续显示切回单页但不解除阅读锁。返回搜索保存查询与稳定页 ID 上下文，改页顺序不改变 PDF 源页身份；回收页不再出现在结果中。处理可暂停、重试或关闭，快速改词不发布旧结果；扫描页清楚说明没有原生文字，可继续使用现有区域摘录。

原生 PDF 7/7、新界面 6/6、受影响旧界面 5/5 通过；lint 0 错误、0 致命、132 警告。 候选 `E:/Inkweft/dist/PdfSearch-V56/InkWeft-v56-workspace-preview.apk`，固定签名与实际安装摘要一致。见[交付](DELIVERY-V56.md)、[验证](VERIFICATION-V56.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续范围](DEVELOPMENT-GAPS-V56.md)。

Room schema12、IWO9、当前 PDF 文件与人工识别记录均保持；未新增 OCR 或索引表。V55 MAP_PORTAL_V2 当前/历史/删除记录仍要求最低 V55，旧 V54/V47 不能读取这些记录，不能因签名相同直接把真实库交给旧包。

指定精确对话 chatgpt-conversation://6abbd418-4dd8-83ea-8e83-d60cf0b6e100 最新全文仍未取得。本批依据后续明确授权的 PDF 搜索阅读体验，以及“那你看着改，一直往后推进完善这些功能”实施；不声称已读取该对话。用户已授权连续完善，V56 独立交付后继续下一批，无需逐版本确认。 历史各批“完成即停止”保留作历史记录，不代表最新连续开发授权。保持 Android 原生、本地优先与固定签名。未读取、启动、安装或升级真实平板，未提交、推送、PR、合并、发布或上传私有原迹。全部运行仅在本批 Android35 独立模拟器与合成资料完成；真人手写、人工读屏、物理分屏、手写笔和真实资料恢复均未验收。

---

# V55 同笔记跨图分支入口 · 2026-10-01

**0.0.55-branch-portals（55）**：跨图入口可明确选择整图或指定分支；分支打开后只显示该根与后代，沿用同一导图窗口与有限面包屑返回，不改变原笔记页。目标身份按图与节点 UUID 校验，改名不改指；删除/移图/同名替代均不自动退回整图。打开后失效及嵌套返回失效保留原身份、显示不可用，只有明确“全部主题”才展开其他主题。

领域 24/24、Room 34/34、原生 UI 24/24 通过；lint 0 错误、0 致命、132 警告。 最终候选、模拟器实际安装与交付 APK 字节一致，沿用原固定签名。候选 `E:/Inkweft/dist/BranchPortal-V55/InkWeft-v55-workspace-preview.apk`；见 [交付](DELIVERY-V55.md)、[验证](VERIFICATION-V55.md)、[实机清单](DEVICE-TEST-CHECKLIST.md) 和 [剩余范围](DEVELOPMENT-GAPS-V55.md)。

Room schema12 和 IWO9 保持。整图入口继续写原 MAP_PORTAL_V1；指定分支写 MAP_PORTAL_V2（目标图 ID + 目标位置/结构节点 UUID）。含 V2 当前记录、历史或删除记录的资料库及完整备份要求 V55，旧 V54/V47 无法读取这些记录；移除入口不消除历史中的 V2。

指定精确 ChatGPT 对话 `chatgpt-conversation://6abbd418-4dd8-83ea-8e83-d60cf0b6e100` 最新全文仍未取得。本批依据用户后续明确要求继续墨织、父任务释放资源，以及已读取的历史 UI 合同 §6.2 与 V51 指定分支缺口实施，不声称已读到最新讨论。所有运行使用本批独立 Android35 模拟器和合成资料。真实平板安装/启动/升级/降级、真实资料备份恢复、真人手写、手写笔体验、原“多字变我”故障原迹、物理分屏与人工读屏均 NOT_RUN；不能用这些合成结果代替真人验收。未提交、推送、PR、合并或发布，未上传私人原迹。

本批完成后停止，不新增下一阶段。跨笔记/跨标签入口、独立复制身份映射、留白产品语义、服务商用范围及其他新功能不在本批。

---

# V54 UI完善 · 2026-10-01

2026-10-01 **0.0.54-ui-polish（54）**，完成本批准UI批次并停止。UI新增2/2、V53遮挡保护7/7、受影响旧UI15/15通过；lint 0错误/0致命/132警告；最终安装APK与交付字节一致，原固定签名一致。

候选 `E:/Inkweft/dist/UiPolish-V54/InkWeft-v54-workspace-preview.apk`；[交付](DELIVERY-V54.md)、[验证](VERIFICATION-V54.md)、[范围](NEXT-BATCH-SCOPE-V54.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)。

真人手写、物理平板/手写笔体验、原“多字变我”故障原迹、真实资料升级和人工读屏均NOT_RUN。所有运行使用本批独立Android35模拟器与合成库。未安装用户真机/真实资料库，未上传私人原迹，未提交/推送/PR/合并/发布。本批准批次完成即停止；AniMemo未启动。

---

# V53 本批交付入口

2026-10-01，**0.0.53-recall-mask（53）**，本题多视图遮挡/主动提示完成，本批准批次交付即停。新原生UI7/7、实际V52→V53→V52合成兼容3/3、受影响UI9/9通过；lint 0错误/0致命/132警告。最终候选安装字节一致，沿用原固定签名。未重跑未变的历史领域266/Room169全套。

Room schema12、IWO9与V51知识payload均未变。完整备份保存作者原文/墨迹/对象/关系/历史/回执/PDF，不保存会话遮挡。V51 portal最低解码版本限制保留，含portal资料仍不能交给V50旧解码器。

指定精确ChatGPT对话最新全文仍未取得，本批依据用户后续明确选择的多视图遮挡与主动提示实施。真人笔迹、原“多字变我”原迹、真实资料库/设备升级、真实笔感、物理分屏/多窗口与人工读屏验收全部NOT_RUN。未提交/推送/PR/合并/发布，未安装真实资料设备或上传私人原迹。

候选 `E:/Inkweft/dist/RecallMask-V53/InkWeft-v53-workspace-preview.apk`；见[交付](DELIVERY-V53.md)、[验证](VERIFICATION-V53.md)、[范围](NEXT-BATCH-SCOPE-V53.md)、[缺口](DEVELOPMENT-GAPS-V53.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)。

---

# v52 本批交付入口

同本原页与摘要/大纲/导图阅读锁P1完成。候选、范围、验证证据和真实NOT_RUN记录见[DELIVERY-V52](DELIVERY-V52.md)、[VERIFICATION-V52](VERIFICATION-V52.md)、[DEVELOPMENT-GAPS-V52](DEVELOPMENT-GAPS-V52.md)、[DEVICE-TEST-CHECKLIST](DEVICE-TEST-CHECKLIST.md)。P2多视图遮答与留白未实施，本批已停止。schema12/IWO9不变，V51 portal最低解码版本不变；真实安装/远端提交未执行。

---

# 墨织 Android

当前测试版：**同本整图跨图入口 0.0.51（51）**。同本整图跨图入口：绑定源图和源节点位置，明确选择目标整图，保存、只读预览、同一浮窗跳转、面包屑恢复原图选择/视野/折叠/聚焦、明确移除，仅完整资料库备份恢复入口关系。目标改名保留ID，回收禁开；旧捕获修订变更拒绝误跳。 保留V48美化、V49分支回忆与V50摘要标题链接。固定签名候选及实证见 [交付](DELIVERY-V51.md)、[验证](VERIFICATION-V51.md)、[范围/缺口](DEVELOPMENT-GAPS-V51.md)、[当前状态](CURRENT-STATUS.md) 和 [实机清单](DEVICE-TEST-CHECKLIST.md)。整体尚未完成，真人与真实设备仍待测。

v50历史交付保留：[交付](DELIVERY-V50.md)、[验证](VERIFICATION-V50.md)。

历史 **书写工作台测试版**（`0.0.15-writing-workspace`）：圆珠笔、钢笔、毛笔、马克笔和荧光笔；多笔记标签页；上下连续翻页；阅读设置、文档标签与工具栏位置。保留 24 种纸面、自定义封面、选区编辑、摘要卡和脑图。连续模式用手指滚动、触控笔书写，精确缩放和套索使用单页模式。页内图片、拍照、文本框、胶带和图层尚未实现，下一组按统一页面对象推进。范围见 [功能对照与路线](FEATURE-ROADMAP.md)、[修改日志](CHANGELOG.md)、[待实现内容](TODO.md)。诊断反馈的实际边界见 [版本 14 用户反馈](DEVICE-REPORT-20260926.md)。

以下内容保留各早期阶段的历史记录；其中“未完成”和测试数字描述的是当时状态，不代表 UI-R2 的现状。

实机验收请使用 [真机测试清单与反馈模板](DEVICE-TEST-CHECKLIST.md)，按用例操作并记录设备、版本与结果。

[竞品实际体验记录](COMPETITOR-HANDS-ON.md) 包含 vivo 平板上的 StarNote 与模拟器上的 Goodnotes 等：分别标记实际完成的操作、仅查看的入口和受阻项目，作为下一组页面对象编辑的依据。

## A0 — 原生基础切片（历史）

这是安卓主工程的第一笔实现，不是把网页放进 WebView，也**不是完整 A0.1 书写版**。旧 `src/`、`index.html` 和 Web 自检均保持原样，原计划37条应用验收仍为 NOT_RUN。

## 本批范围

- 三模块：纯 Kotlin 草稿/命令模型、Room 本地数据、Compose 原生资料库与文字页。
- 新建、选笔记、文字输入、显式保存、独立明文文本导出。
- 草稿按 noteId/baseRevision 保存于 ViewModel：失焦不清空，切卡不串写，合法空正文保持空。
- 内容、不可变修订和幂等回执放在同一 Room 事务；失败不显示“已保存”，结果未知保留原命令重试，陈旧版本拒绝覆盖。
- 显式丢弃草稿后的异步读取，只能替换当时的同一个草稿实例；期间编辑、再次保存或其他读取均使旧请求失效，不以结构相等绕过检查。
- 无 INTERNET、麦克风、相机、全文件权限；声明排除系统备份与设备迁移。构建依赖下载不等于运行时联网。
- 保留现有数据库：无 destructive migration；自定义 open helper 在腐败回调时拒绝默认删除，并明确关闭 allowDataLossOnRecovery。此行为仍需真实故障验证。

## 明确未完成

手写/Ink、PDF、页面缩略图、卡片轴心、脑图、标题链接、留白、FSRS、插件、云盘、完整备份/恢复、长期草稿存储和正式发行尚未接入。界面没有这些假按钮。本批只验证原生工程、UI与文字数据链路，后续继续接入 AndroidX Ink，而不是自研笔引擎。

未点击保存的草稿仅在当前 ViewModel 中；旋转保留，进程终止可能丢失。没有声明 fsync/掉电零丢失或 Pencil3 适配。`PRAGMA synchronous=FULL` 是配置，不是耐久性测试证据。单页文字导出不含历史/回执，不是完整备份；云端目标必须由用户主动选择。

`org.inkweft.app.a0` 使用独立实验库，不迁移、不删除 Web 样品内容。不要将真实笔记的唯一副本放在此版本。

## 构建

本次构建例外：最初声明API37，但实际CI（run 35875551637）在官方SDK源返回 `Failed to find package platforms;android-37`。仅此A0文字基础切片显式改用compile/target36，min31不变；不是静默退回，也不表示API37验证通过。后续原生主线按实际SDK可用性再评审升至37。SDK工具显式安装，不依赖runner预置PATH。

固定：JDK 17、Gradle 9.4.1、AGP 9.2.1、Kotlin/Compose plugin 2.3.10、compile/target 36、min 31。直接依赖版本见 `gradle/libs.versions.toml`。AGP 9 使用 built-in Kotlin；Android模块不再重复应用 kotlin-android。

**标准 wrapper 已入库**：`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 与 `gradle-wrapper.properties` 已提交。JAR 由官方 Gradle 9.4.1 的 `wrapper` 任务生成，本地重建后 SHA-256 与下列官方校验值逐字节一致（不是手写或用下载器伪造的 JAR）：

```powershell
Set-Location android
.\gradlew.bat :core-domain:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest
```

标准wrapper生成任务固定官方分发SHA-256：
`2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`
来源：Gradle官方9.4.1 release中的gradle-9.4.1-bin.zip资产digest。
CI还在执行生成的wrapper前验证官方JAR SHA-256：
`55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c`
来源：https://gradle.org/release-checksums/

Room schema v1已从成功CI run35876852580的artifact10758881048取回并核对字段后提交至 `data-local/schemas`；未改数据库版本或结构。CI重建后检查已提交Schema没有漂移。传递依赖锁/校验材料仍需补齐，不能将直接版本固定等同整个供应链已完成。

## 证据

CI针对指向main的PR和main的相关push分别验证。先前只触发开发分支的配置已移除，合并之后仍检查实际main构件。

CI分别编译 Debug APK、运行纯Kotlin JUnit、lint，并编译（不执行）instrumentation test APK。实际结果以对应提交的 Actions 为准。CI输出APK SHA-256、签名指纹和构建信息。没有模拟器/设备任务就保持安装、Room instrumented tests、Compose操作、热/笔延迟为 NOT_RUN；不能将 `assembleDebugAndroidTest` 当作测试通过。

测试：
- core-domain：空文本、原编辑基线、未知结果同命令重试、冲突保留、字段边界和摘要。
- DraftReloadTest：明确读取、迟到结果、新输入、保存中、结果未知、相等但不同的草稿实例、重复结果与跨笔记读取；定义不等于已执行，以本次CI XML为准。
- data-local/androidTest：幂等、陈旧版本、重开、旧回执固定版本。仅定义/编译不等于运行。

调试APK使用当前构建环境debug key，不是正式签名；不同环境证书不保证兼容覆盖安装，禁止脚本自动卸载清库。没有自动合并、Release或上传商店。

## A3 局部橡皮 / 多页 / 封面改名（本轮实测）

本节的数字来自本地实际执行并保留原始日志与报告，不是计划值，也不是把定义中的用例算作通过。

- 编译：`:core-domain:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :data-local:assembleDebugAndroidTest` 全部成功（153 个任务）。
- JVM 单元测试：**104 项通过，0 失败 / 0 错误 / 0 跳过**（CanvasViewport20、DiagnosticLog14、DraftReload8、InkEditing12、InkModels26、NotebookAppearance12、NoteDraft12）。本地与 CI 两次独立执行数字一致。
- lint：本地 0 错误/40 警告，CI 0 错误/49 警告（规则集合差异）；未关闭规则、未用 `continue-on-error`。
- Room schema4 由真实 KSP 生成并提交，`identityHash=43aaa681e66f431ce083dc510f59cbe8`。用宿主 SQLite 独立复核：3→4 迁移后的结构与 Room 期望的 schema4 逐表逐索引一致，2→3→4 链式升级结果同样一致；原笔迹 `payload` 字节在迁移前后完全相同，第一页保留原 UUID 身份。CI 生成的 4.json 与本地生成逐字节相同。
- CI（run 36144792720，head `62e5e4e`）整体 success，实际 JUnit XML 统计：**app 仪器测试 18/18 通过，data-local 仪器测试 31/31 通过，0 失败 / 0 错误 / 0 跳过**。其中包含 `migration3To4PreservesAllOriginalInkBytesAndReferences`。
- 本地另用 MuMu Android 12（API 32，x86_64）独立复跑：data-local 31 项全部通过；app 侧 17/18 通过，两项抽屉用例需要窄于 840dp 的宽度，而该 VM 的虚拟显示被锁定为横屏，提高密度后这两项亦通过。该 VM 的限制属于本地模拟器环境，不代表用例有问题——CI 的 API35 模拟器上 18 项全部通过。
- 构件：`org.inkweft.app.a0.workspace`，versionCode 5 / `0.0.5-a3-editing`，minSdk 31 / target 36，四种 ABI。
  - CI 构件 APK SHA-256 `125e390b7b3ef4192d5fac0a6bf9d695f42c9a2e1479877d83856b36de9398ca`（35,248,119 bytes），签名 v2 通过，证书 SHA-256 `ea2d4ed2ddfda316f001195d379d129ef47b3a07df05723462611b912f4b082c`。
  - 本地同源构建 APK SHA-256 `CBDC7B6FD92E157B78559D45B39DFB39141018794AFB8994B6BAFE6403D25BA0`，证书 SHA-256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。两者都是 diagnostic 变体（applicationId `org.inkweft.app.a0.workspace`，应用名“墨织工作台预览”），字节不同只因为调试签名证书不同；版本、包名、schema 与内容一致。本轮未单独产出非 diagnostic 的 `org.inkweft.app.a0` 包。
- 仍然 NOT_RUN：真机 iQOO/Pencil3、真实掌拒与笔身按钮、光学延迟、温升/掉电、系统分享目标、16KiB 设备、自动手写识别（当前仅为人工转录索引）。上面通过的是模拟器与宿主检查，不等于这些项目已验收。

## 后续最短路径

1. 提交完整标准wrapper与传递依赖材料；开发基础版当前有明确bootstrap方式。
2. 原生Ink画布、笔盒、事务化笔迹保存、取消/长笔分段、完整导出恢复。
3. 真机安装、旋转/进程终止/低空间；再接PDF与一张共享知识卡。
4. 本批未修改网页残留问题，它们仍需独立小修，不以安卓实现宣布网页已修复。

## 官方实现依据

https://developer.android.com/build/releases/agp-9-2-0-release-notes
https://developer.android.com/build/migrate-to-built-in-kotlin
https://developer.android.com/jetpack/androidx/releases/room
https://developer.android.com/reference/kotlin/androidx/sqlite/db/SupportSQLiteOpenHelper.Configuration.Builder
https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-setup

自有新增代码：AGPL-3.0-or-later；仓库根 LICENSE 保持原文。第三方 AndroidX/Kotlin/Gradle 依其自身许可；没有复制竞品素材、字体或代码。
