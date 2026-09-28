# v38 构件与验证记录

版本：0.0.38-note-first（38）；包名：org.inkweft.app.a0.workspace。沿用用户已有工作台预览签名，证书 SHA-256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。

## 当前已执行

- 核心单元测试248项、相关数据库测试53项通过。数据库范围为 NoteFirstRepositoryTest、StudyAndSelectionTest、KnowledgeRepositoryTest、LibraryBackupTest，以本机日志中的实际类名与数量为准。
- 图窗、模板、摘录拖放及旧交互的界面验证正在对最终生产改动复验；最终候选包身份和截图清单将在构建后写入本记录。
- 新增覆盖：窗口外实际输入、匿名个人模板、关闭窗口保留草稿、标记临时选区、拖放取消、原子回执重放及安全撤销。
- 原失败记录保留：大纲点击此前只滚出部分行，修正测试滚动位置后原断言通过；没有删除聚焦、父子关系或来源保留检查。

## 物理设备

vivo iPA2673 的 USB 调试可连接，但本轮复核仍显示锁屏。未卸载、未清空、未写入用户笔记，也未把模拟器结果计为真机通过。NF38-01—NF38-09 的物理设备状态保持 NOT_RUN，等待解锁后再安装同一交付包。

本机原始记录：`E:/Inkweft/archives/2026-09-28/NoteFirst/`。最终证据与安装包单独归档；私人材料不上传 GitHub。完整范围见 [验收矩阵](ACCEPTANCE-UI-NF1.md)。
