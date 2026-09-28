# v36 实机记录

日期：2026-09-28。设备：vivo iPA2673、Android 16，通过USB ADB。先观察StarNote的摘要笔、备注侧栏与笔记列表菜单，再在墨织专用测试本验证。ADB生成的直线与点按不等同真实手写笔体验。

原资料保护核对：升级前24本笔记、44页、558条笔迹、9张摘要卡、17份对象记录；测试后25本、45页、560条笔迹、11张摘要卡、17份对象记录。逐行比对上述表的原ID，原行无变化。测试本名InkWeft-v36。

|用例|实际结果|证据／尚待验证|
|---|---|---|
|EX36-01|通过ADB点按|首次只选中、再次出现参数卡；`tablet-lasso-selected`、`tablet-lasso-settings`、`tablet-final-excerpt-settings`|
|EX36-02|部分通过|顶部、左侧笔盒参数卡无暗幕且位置正确，`tablet-final-eraser`；右侧笔盒和横竖屏组合仍待实机测试|
|EX36-03|部分通过|设置行开关原位，`tablet-customize`与`tablet-customize-hidden`；固定短栏见`tablet-compact-editor`。本轮未重做拖动排序实机测试|
|EX36-04|部分通过|框选手写区域、备注USB-v36、覆盖安装后重开保留、删除摘录不删原笔迹；`tablet-final-excerpt-reopened`、`tablet-excerpt-deleted-source-retained`。图片＋胶带组合待实机测试|
|EX36-05|真机待测|最终APK在模拟器通过PDF文字层选区及彩色图形快照检查；没有用其替代真机结果|
|EX36-06|部分通过|20个标签滚动时头部固定，独立笔记框与省略号菜单存在；`tablet-notebook-list`、`tablet-notebook-list-bottom`、`tablet-final-split-menu`。未批量关闭用户原标签|
|EX36-07|部分通过|同一本笔记两种分屏、旋转分屏布局及结束分屏可用；`tablet-final-split-horizontal`、`tablet-final-split-vertical`。阅读区翻页后切换编辑的目标页由最终APK自动化验证，尚待人工手写复验|
|EX36-08|真机待测|隔离Room测试验证区域图片、备注与完整备份恢复；未清空用户平板验证恢复|

最终源码为cfe68d9；早期图像证据来自同轮dca54ac／454ccd6，最后仅补目标页定位、窄长区域比例与过大手写选区提示。最终包再次覆盖安装，安装身份与摘要见DELIVERY-V36.md；最终截图名称以verified开头，具体证据索引保存在本地档案中。

真实压感、倾斜、掌拒、持续15分钟手写及横竖屏边界仍为待测。未改动系统安全设置；测试保持唤醒开关结束后恢复原值0。
