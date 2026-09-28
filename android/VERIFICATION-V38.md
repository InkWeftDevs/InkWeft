# v38 构件与验证记录

## 上一候选安装身份（窗口恢复修复后将更新）

- versionCode：`38`
- versionName：`0.0.38-note-first`
- package：`org.inkweft.app.a0.workspace`
- sourceCommit：`21c82f9986b6ff5f315d8410c051c9ecc3c1ea18`
- buildCommit：`21c82f9986b6ff5f315d8410c051c9ecc3c1ea18`
- sha256：`7932302b0765a496fad3c76ee825659ea62dec925ab128e15e2a31965c29d2c4`
- certificateSha256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`
- 构建时工作树干净；APK 146,711,957 字节。
- 安装包：`E:/Inkweft/dist/NoteFirst/InkWeft-v38-workspace-preview.apk`。
- 模拟器实际安装 base.apk 的 SHA-256 与交付包一致；后续文档提交不改变该构件。

窗口恢复补验发现旧关闭状态覆盖新摘录，修复及最终同包复验进行中；以下身份属于21c82f9候选，不代表后续修复已交付。

## 已执行检查

|范围|结果|证据|
|---|---|---|
|核心单元|248通过，0失败／错误／跳过|core-domain JUnit XML|
|相关数据库|53通过|NoteFirstRepositoryTest 2、StudyAndSelectionRepositoryTest 20、KnowledgeRepositoryTest 19、LibraryBackupRepositoryTest 12|
|相关界面|31通过|修复后的候选回归；旧摘录／套索／分屏／回源断言保留|
|最终同包界面复验|15通过|NoteFirst 11、IntegratedMap 3、大纲折叠聚焦1；包含实际窗口树输入与可见性检查|
|静态检查|0错误，112警告|本地lint；含既有绘制分配、窗口尺寸API与SDK提示，未关闭检查|
|GitHub整合回归|运行中|[运行36406057226](https://github.com/InkWeftDevs/InkWeft/actions/runs/36406057226)，源码21c82f9|

各组覆盖有重叠，不将重复次数相加。最终15项在上述交付APK上执行。核心和数据库实现未在后续界面收尾中改变。

## 同包视觉复核

使用含中文正文、公式、勾选项和双色笔迹的合成概率论资料；导图包含实际主题或可编辑模板结构。检查正常书写、常用参数、导图浮窗、拖入虚线、保存后节点、375dp／1.5倍字号及800／1180／1366dp布局。入口实际点按并校验命中范围，未只检查语义节点存在。

发现并修正：常用卡底部半截自定义颜色入口（归入高级）；适配时左侧标题被裁（保留可读缩放并完整显示起始主题）；拖放重复边缘文案；新子主题与父主题重叠；撤销行过度占用画布；大纲测试只滚出部分行即点击。最终截图保留原文主体、完整参数控件和可平移的导图画布。窄屏优先可读与触控，未用缩小字号凑遮挡比例。

最终截图及摘要：`E:/Inkweft/dist/NoteFirst/VISUAL-REVIEW.md`、`screenshots.json`。原始最终测试日志／截图：`E:/Inkweft/archives/2026-09-28/NoteFirst/final-ui-reviewed/`。早期失败与修复过程保留在同目录下 `development/`，不混入最终同包证据。

## 物理设备与边界

vivo iPA2673 的 USB 调试可连接，但本轮复核仍显示锁屏，设备仍为v37。未卸载、未清空、未写入用户笔记。本次未完成物理设备安装与复验；NF38-01—NF38-09保持NOT_RUN，真实压感、倾斜、掌拒、温升和密集页性能不能以模拟器或ADB代替。

按独立验收开启的跨标签拖放、跨图入口和两图对照未开放；同级插入、追加来源、边缘滚图、全部参数＋IME组合和强停后视图恢复未完成。详见 [验收矩阵](ACCEPTANCE-UI-NF1.md) 及 [实机清单](DEVICE-TEST-CHECKLIST.md)。私人材料不上传GitHub。
