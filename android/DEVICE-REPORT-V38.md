# v38 实机结果

日期：2026-09-28。vivo iPA2673（DPD2540），Android 16。墨织工作台预览 0.0.38-note-first（38）；包名、构件与 SHA-256 见 [验证记录](VERIFICATION-V38.md)。本轮使用 USB 注入点按／拖动／恒定输入轨迹，未使用真实手写笔。

## 安装与数据保护

覆盖安装前将应用数据库、资料和偏好只读备份到本机；沿用签名安装，没有卸载或清库。平板实际 base.apk 与交付包哈希一致。安装后新增“UI-NF1”合成 PDF，内容为条件概率、公式、例题、复习清单与留白批注区。所有写入均在此测试本。

测试后对全部有主键表逐项比对：原有行零删除、零变化，包含25本笔记、560条笔迹及原有对象／摘录／来源。新增3条合成批注、1个公式摘录、1张键盘编辑卡，以及对应图和来源记录。恢复自动旋转1、方向0、字号1.0；保留新测试本供验收。

## 本轮执行记录

|用例|实际操作与结果|状态／证据|
|---|---|---|
|NF38-01|导入并打开有内容PDF，USB连续3次落笔，实际打开笔参数、摘录、导图|PARTIAL；writing.png、pdf-open.png；全屏与工具排序未在本机重跑|
|NF38-02|打开常用和高级笔参数，关闭后返回原文；卡片白色，无背景调暗|PARTIAL；pen-card.png、pen-advanced.png；本轮未改变原有配方，所有参数持久化组合待测|
|NF38-03|新图输入时转横屏；拖动窗口、放大、最小化恢复；卡片在真实软键盘下输入标题和摘要，隐藏键盘、关闭浮窗重开，草稿一致并成功保存|PARTIAL；map-created.png、window-moved.png、map-resized.png、map-minimized.png、map-restored.png、draft-typed.png、draft-window-restored.png；节点拖动、停靠与全部旋转组合未穷举|
|NF38-04|选择公式区域，拖入“定义与条件”，停留显示目标与虚线，松手生成一个子主题；数据库核对一张摘录卡和一个来源|PARTIAL；excerpt-region.png、drag-preview.png、drag-dropped.png；物理取消／撤销组合未重跑，自动化另有覆盖|
|NF38-05|以矩形原貌摘录PDF公式，点击图中卡片可回到对应原文范围|PARTIAL；node-menu.png；标记／文字模式及旧备注调整未在本机重跑|
|NF38-06|从“知识点梳理”创建独立图，五个结构主题不生成假知识卡；加入真实摘录后核对存储|PARTIAL；template-picker.png、map-created.png、data-preservation.json；其余模板及备用资料库恢复仍待测|
|NF38-07|实际横屏与软键盘组合可输入和保存；转屏保留未保存图名|PARTIAL；landscape-keyboard.png、draft-typed.png；375dp／1.5倍字号等为同包模拟器证据，非本机通过|
|NF38-08|备用设备故障注入|NOT_RUN；未在个人设备破坏测试|
|NF38-09|真实手写笔15分钟、压感、倾斜、掌拒、温升与密集页性能|NOT_RUN；不能由USB输入代替|

证据均位于本机 `E:/Inkweft/archives/2026-09-28/NoteFirst/device/`。原始备份为私人资料，截图中可能出现原有标签名称，不上传GitHub。`data-preservation.json`记录比对结果，`backup-receipt.json`与`after-backup-receipt.json`记录本机备份校验。

步骤、预期结果和结果模板见 [独立实机清单](DEVICE-TEST-CHECKLIST.md#ui-nf1--v38-浮动导图实机清单)。问题复现时记录时间，优先从软件内“诊断与导出”生成ZIP，再补充视觉／笔感证据；基础诊断包不是完整系统日志。本轮未新增导出完整系统日志的承诺。
