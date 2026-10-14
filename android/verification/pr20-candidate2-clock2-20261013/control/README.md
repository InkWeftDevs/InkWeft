# 第二候选clock2：提前固定预算起点

按既有持续授权继续，源码45b4861、三端来源及57方法固定计划6c72fd8保持。先前b63e527/run38431279425在checkout前遭公共job API限流，57项全部未执行，失败原文保留。

本次彻底移除启动clock步骤的网络请求，不新增token或permission。预算起点来自必要PR20更新成功响应的GitHub服务器updated_at：2026-10-13T16:49:10Z，epoch1791910150；收到服务器响应后才冻结此准备提交并推送/激活。clock-server-receipt.json保留最小成功响应，clock-contract.json、授权、预算和workflow分别绑定哈希及同一epoch。PR head是报告d30fc6c，不替代被测45b4861。

累计截止从这个更早起点计算：setup17:21:10、正确性17:27:10、公共17:41:10、诊断/工作17:44:10 UTC；job硬上限仍60分钟。准备/排队时间也扣预算，禁止重设起点。首步拒绝未来或过期时间；checkout后在任何依赖/构建/设备操作前核验clock闭环。实际job started_at保持null，终态再以外部元数据核验anchor不晚于实际启动；不伪称这是实际job开始。

单标准runner、9APK、57调用、原探针/样本/阈值、32/38/52/55累计约束、60分钟硬限、method17唯一辅助包清理、最小临时UID ACL和双清理、20MiB文本/3天均保持。package仅增加clock-contract和clock-server-receipt两个固定文本；checkout前失败也保留最小时钟回执。

当前完整10组离线preflight PASS，包括43时钟case、19进程case、8官方POM case、12实际合成package调用；不等于Android或性能通过。准备提交先确认0run，再新增仅before绑定该准备SHA的活动workflow，只运行一次，无dispatch/rerun。实际活动workflow只允许与proposed相差唯一before值。真实anchor一经冻结不得因准备耗时而后移。
