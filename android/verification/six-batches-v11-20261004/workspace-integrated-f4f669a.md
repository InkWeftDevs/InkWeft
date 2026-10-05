# 结构、内容变换与固定来源的联合源码检查

固定源码：`f4f669a31c420e99b0a9f38792a25e39285fc387`。

- 依赖包：`af8fdd1` → `a5bf967` → `01a4b14`（内容变换/Room14/固定来源与共源缓存），`dcd5908`、`f0f5b38`、`dac1d2f`（归纳总结关系、关系显示属性及编辑预览）
- 本任务接线：`c4597b0`（生产卡片操作、全部新目标、旧身份、多来源显式选择与固定版本快照）；`825acf5`（未决撤销锁住详情内离开入口）；`be9a535`（实/虚线、正反箭头、短注释，不混父子线）；`f4f669a`（图例断言）
- 前序B1/B2检查及提交见 [6600066记录](workspace-outline-6600066.md)；`d7d20ba`、`f2ba7a3` 已纳入本次联合源码检查

## 实际执行

`BUILD SUCCESSFUL in 4m 51s`；单worker、Kotlin in-process，JVM 3GiB heap / 1GiB metaspace。

| 检查 | 结果 |
|---|---|
| `StudyOrganizationTest` | 10通过 |
| `StudyOutlineTest` | 4通过 |
| `CardTransformTest` | 6通过 |
| `KnowledgeTest` | 16通过 |
| 定向JVM合计 | 36通过，0失败/错误/跳过 |
| app主Kotlin源码 | 通过 |
| app AndroidTest Kotlin源码 | 通过 |
| data-local AndroidTest Kotlin源码 | 通过 |

XML见 [workspace-integrated-evidence](workspace-integrated-evidence/)。命令包含上述四类的`:core-domain:test`、`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`及`:data-local:compileDebugAndroidTestKotlin`。

新增受控用例已编译但尚未运行：生产大纲勾选→合并取消/确认→旧身份目标选择→第二固定来源→真实页导航；原生离屏关系实/虚线与双向箭头/长注释绘制。它们不代替原生可读性、触控或真人体验验收。

## 后加与交接

`69979d0` 是验证后的10行复用入口追加，依赖 `78e669f`/`60572cf`；不在本次编译范围，应由后续B3/B4组合编译覆盖。入口为卡片更多动作中的“跨笔记复用 · 引用或独立副本”，保留原卡详情及位置，原卡不可用时提供返回重新读取。

已停止写入并交回：BookInkScreen、StudySession、StudyWorkspace、MindMapView、StudyOrganizationUi、BranchReviewDialog、MapSourcePreview、MapScenePainter、ExcerptCollection、RecallContextPanel及之前已释放的InkScreen/ReadingToolbar。后续B4作者层需要把RecallContextPanel原页读入改为相同authoring可见性投影；B5应继承consultedOriginal曝光事实及暂停恢复，不清掉已查看资料的提示记录。

Android UI/Room运行、屏幕像素、无动画实际交互、真人笔/掌触/无障碍/热状态均 **NOT_RUN**。未运行本轮lint，未制作最终签名APK，未访问用户电脑或真人资料。子项仍为源码进展与待原生验收，不能据此宣布B1–B3或六批全部完成。
