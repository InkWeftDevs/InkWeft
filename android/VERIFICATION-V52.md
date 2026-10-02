# v52 本轮工程验证

2026-10-01。本轮仅原生APP会话/UI/controller变化；core-domain及data-local生产与历史测试输入逐字节保留。

新原生UI6/6、实际V51→V52→V51合成兼容3/3、受影响UI7/7通过；lint 0错误/0致命/132警告。固定签名一致，独立模拟器最终安装字节与候选一致。没有重跑历史领域266/Room169。

环境：本批新建 `InkWeft-ReadLock-V52`，Android35 x86_64模拟器，独立adb5038/serial emulator-5556；1920×1200、240dpi，窄屏另设置375dp/字号1.6并还原。任何UI注入、故障/升级库均为合成资料，不是人类笔感或真实库验收。未经授权的其它设备、adb5037不处理。

|验收|本轮证据|结果|
|---|---|---|
|RL52-01 同本联动|真实原生墨迹/对象/胶带输入、三视图编辑入口与实际VM新作者submit门禁；SQL作者内容/修订/回执对比|PASS（合成）|
|RL52-02 可读可导航|原生图选择/平移缩放/搜索返回、卡与来源、标题引用和portal往返；原页/图身份及视野、作者SQL不变|PASS（合成）|
|RL52-03 返回书写|明确退出阅读后一次落笔/一次用户保存，原内容保留；开关本身零作者命令|PASS（合成）|
|RL52-04 草稿/未知|真实DOWN未UP与实际美化延迟Job拒切；正文/标题与原选区重建保留、活动光标仍可移动；模拟丢失确认的原StudyCommand按同一ID/回执重试|PASS（合成）|
|RL52-05 生命周期|Activity重建、窗口收起重开/同本与异本会话隔离、375dp/字体1.6入口|PASS（合成）|
|RL52-06 兼容集成|真实V51→V52→V51 APK覆盖合成库；双向完整备份恢复/重复恢复；必要V49/V50/V51路径；固定证书/最终安装字节|PASS（合成）|

阅读导航SQL对比保留作者字段、时间戳、不可变历史及回执，视野/最近访问仅按现有设备偏好处理。完整备份验证保留原迹、对象、Study来源/卡/图位、Knowledge当前/历史/回执（含portal）。V49明确手工mark例外另比较恰一CAS记录，不混入“导航零写入”结论。

主证据 `archive/evidence/instrumentation.json` 汇总最终每个方法与各次运行日志；失败/修复日志保留。`ui-read-lock-verified.txt`为最终候选六项；`ui-compat-v51-seed.txt`、`ui-compat-v52.txt`、`ui-compat-v51-readback.txt`分别在实际51/52/51运行。三段兼容使用首次V52构建，SHA与首轮源码见`compatibility-build.json`、`iteration-source/ui-first`；之后只修来源展开、活动任务检查、标题选区恢复、草稿与窗口/对象选中区分及测试触达，core/data-local输入未变。受影响七项按实际变更分步复验，最终每个方法的最新结果汇总，不声称在最终字节重跑全部历史项。最终APK52取得`installed-artifact.json`。兼容JSON/备份在`evidence/compatibility`；`build-app.txt`、lint XML、`certificate.txt`、`device-environment.json`、guarded整合journal和最终source-manifest随归档保留。

受影响原生回归7项，针对节点选择/标题/菜单、非模态窗口嵌入、V50标题预览/返回、V49固定题/回源及未知手工mark原请求、V51入口返回/Library重建。生产存储和领域引擎没有变化，不重跑已完成的历史260/266、DB157/169等全套，不引用旧PASS冒充本轮。

本轮离线构建使用原工具链和固定签名，候选SHA256 `41b99a38e85211ac9502c57c488e5f4ac0c95ae2165f45eb7a911af492d61818`。首轮/第二轮失败与修复细节见`evidence/repair-notes.json`；原断言保留，合成测试不替代真人笔感。未暂存/提交/远端写入，未清除原工作。归档/final-check.json核对完整构建输入、被修改原文件备份、未暂存状态、全部历史未提交路径保留及临时设备清理。

指定精确ChatGPT对话最新全文仍未取得，本批依据后续明确授权的NEXT-BATCH-SCOPE-V52.md P1实施。真人笔迹、原“多字变我”原迹、真实库/设备升级、笔感、物理分屏及无障碍仍NOT_RUN。未提交/推送/PR/发布、未安装真实资料设备、未上传私人原迹。
