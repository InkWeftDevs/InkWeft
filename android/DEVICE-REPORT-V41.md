# v41 平板测试记录

2026-09-28，vivo iPA2673 / Android 16。使用 VERIFICATION-V41.md 的最终同包，输入为 USB 合成触摸与真实安装的中文输入法。只操作新导入的“学习笔记 · 导图交互验收”，保留 Beauty-v40 及所有原笔记。

## 准备及资料保护

用户允许接手后重新备份，纳入用户刚写入的 Beauty-v40 内容。通过应用的完整备份恢复入口导入 29,326 字节合成资料：主图 24 个主题、长标题、24 段正文、来源及第二张共享图。第一候选上的备份恢复通过；随后覆盖最终包。一次文件选择误点了列表中的其他文件，校验拒绝后重新选定 MUI-v41.iwbackup，无恢复写入。

最终已安装 APK SHA-256 与交付包一致。升级前后 28 张表的 2,209 条原记录逐主键一致；没有卸载或清空设备。只新增合成测试本。原自动旋转、旋转方向、字号已恢复；快捷栏显隐测试后恢复，美化字体参数未修改。保存快捷栏时原缺省的 pen/map 被写为显式顺序，显示顺序等价。

## 分项结果

|编号|本轮状态及实际操作|证据（本地 MapUiRepair/device）|
|---|---|---|
|MUI-01|PARTIAL：知识节点单击只选中、出现四图标；结构主题物理操作待测，自动化通过|frozen-unselected、frozen-selected|
|MUI-02|PARTIAL：点击修改标题打开局部输入，图和笔记仍可见；双击／空白保稿由自动化覆盖|frozen-title-keyboard|
|MUI-03|PARTIAL：屏幕键盘输入 fuxi，候选栏选择“复习”，点完成成功保存；本输入法组合阶段留在自身候选栏，字段仍显示原选区，不能据此声称 Compose 收到了 composition；旋转组合未在物理设备执行|frozen-ime-composing、frozen-ime-selected、frozen-ime-saved|
|MUI-04|PARTIAL：边缘来源按钮不再遮标题，独立打开来源；真实笔／双指待测|frozen-selected、frozen-source-scrolled|
|MUI-05|PARTIAL：改共享标题后原 24 段正文仍在、来源仍可打开；跨图与结构完整断言见自动化|frozen-long-body-last、frozen-source-scrolled|
|MUI-06|PARTIAL：点添加子主题直接出现空标题草稿，完成在空值下禁用，取消回到原图；最终库仍为 24 张合成卡，没有取消草稿遗留；确认创建／同级由自动化覆盖|frozen-child-input、frozen-child-cancel|
|MUI-07|PARTIAL：节点更多→查看内容，滚动至第 24 段，再返回原节点；来源单独展开并能看到原迹。引用位置全部跳转待测|frozen-long-body、frozen-long-body-last、frozen-return-node|
|MUI-08|PARTIAL：节点短菜单和图级菜单范围分开；图级菜单保留画布，三组切换在模拟器实际点击验证|frozen-node-menu、frozen-map-menu|
|MUI-09|PARTIAL：最终包实际边缘节点标题无按钮遮挡；各标题长度、页内布局由自动化覆盖|frozen-selected；frozen-ui/mui-title-layout|
|MUI-10|PARTIAL：最终包平板键盘和局部标题框可同时操作；375dp／1.5字号是模拟器通过，非平板窄分屏通过|frozen-title-keyboard；frozen-ui 窄屏图|
|MUI-11|NOT_RUN（本轮物理完整组合）：最终同包原生拖放两项自动化通过；先前整合候选查找／页内视图通过|frozen-capture、final-ui|
|MUI-12|PARTIAL：安装重开、原记录保留与合成完整备份恢复通过；故障只在隔离模拟器注入|final-preservation.json、fixture-validated、fixture-restored|
|BF40-01 回归|PASS（列表可达）：临时在自定义快捷栏显示“实时字迹调整”，更多中打开美化卡及三个字体列表，随后恢复显隐。未重新手写转换|frozen-beauty-actual、frozen-font-list|

首次使用者任务观察和真实手持笔体验均 NOT_RUN。没有将 USB 点击、语义节点或 CI 通过当作此两项通过。

## 记录方式

后续按 DEVICE-TEST-CHECKLIST.md 的 MUI-01～12 记录版本、输入方式、步骤、预期与实际结果。出错时在应用内标记时间、复现、导出诊断 ZIP，再附截图／视频；基础诊断包不能证明完整系统日志或笔尖延迟。
