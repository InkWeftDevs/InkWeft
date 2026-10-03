# 本笔记新增页纸面 · 云端功能切片

基点：`3e6e3e502cd0a38b31268b94a92149479ad0cfa9`，包含三批20项UI待验交接资料。原普通纸面沿用已经存在，本批只补本机／本册的固定内置纸面偏好，不新增模板系统。

## 交互合同

- 默认不配置，沿用现有行为：插页沿用所选页，连续追加沿用末页。可明确选择固定内置PaperStyle，确认才保存，取消不改偏好或页面，并可恢复沿用。
- 复用本机已有SharedPreferences方式；不借用WorkspaceRow.paper，不修改现有页的纸面、内容、笔迹或身份。
- 插页与连续追加采用同一默认；本次显式选择沿用／内置纸面／安装资源模板始终优先，且不改保存的默认值。
- 默认纸面只在构造原不可变插页命令前解析。旧尾页身份拒绝规则、待核对命令及其重试不因偏好改变而重算。
- 安装模板仍使用原TemplateRef(hash,id)及原资源事务；不把图片／PDF模板简化成PaperStyle，不宣称完成资源模板默认及其失效处理。

## 验证

- 生产范围为4个文件：BookInkScreen、InsertPagesDialog、BookPagesViewModel及小型NewPagePaperPreference；复用inkweft-reading和原纸面组件。偏好观察及提交／失败回滚读共享顺序，处理保存中重建；首读就绪只约束新增页，原编辑／移动／删除门禁不变。
- 复用3个既有测试文件：新增确认／取消／两册隔离、恢复原插页命令、失败保存3个方法，扩展原批量插页、末页追加、资源模板3个方法。资源覆盖检查真实RESOURCE回执hash，保留原页ID和笔迹。
- 失败保存方法为明确隔离的组件／SharedPreferences故障注入，不冒充整应用旅程；其余相关方法走现有MainActivity。所有方法实际运行均NOT_RUN。
- 校核修复了旧测试的全局手写开关与新建笔记默认残留、空资料库假设；只恢复被触及的原偏好键，合成资源用独立身份，不清库或覆盖已有作者记录。
- 最终定向检查一次通过（2026-10-03 UTC，5分52秒）：`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`，单worker、串行、JDK17、SDK36及仓库诊断／unsigned参数。lint XML为0 Error／0 Fatal／135 Warning；新增1条UseKtx建议位于偏好写入，保留原生Editor是为检查commit()的Boolean结果并处理失败，不以无返回值的便捷调用隐藏结果。
- 设备／真实交互、保存中真实进程死亡和低空间I/O仍未验；不构建APK，不重复未改变核心／Room全套，不为纯文案或格式重复编译。
