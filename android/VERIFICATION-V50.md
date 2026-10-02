# v50 验证记录

2026-10-01；源码基线 `8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238` 加V48/V49既有未提交修改和本批9个源码／测试路径。逐项核对V49全部415项构建输入后再合并，修改的原文件保存备份；无git锁、staged变更或远端写入。

本轮只验证本批及受影响入口，以下为实际执行结果：

|检查|实际结果|证据|
|---|---|---|
|新增字面标题／别名匹配|8/8 PASS；左侧首次、同位置最长、同名候选、ASCII词边界、UTF-16、预算和不改原文|`evidence/TEST-org.inkweft.core.KnowledgeTextLinksTest.xml`|
|新增Room只读稳定关联|6/6 PASS；精确source/owner、同名跨本、当前／历史、固定缺失、回收和旧关联修订拒绝；作者修订／回执前后相同|`evidence/room-title-links.txt`|
|新增实际MainActivity交互|5/5最终PASS；真实字形触碰、明确目标选择／稳定ID导航、固定历史、关闭／重建原卡图位、换目标／移除拒绝、375dp字体1.6|`evidence/instrumentation.json`及`ui-title-links*.txt`|
|受影响原生图／卡片与回忆入口|5/5 PASS；保留具体方法和日志，未重跑全部旧App|`evidence/ui-affected.txt`|
|主APK与测试APK编译、lint|PASS；0Error/0Fatal/132Warning|`evidence/build*.txt`、`lint-results-debug.xml`|
|原固定签名／实际安装构件|PASS；签名与V49相同，APK50实际安装字节SHA256与最终输出相同|`evidence/certificate.txt`、`evidence/installed-artifact.json`|

首轮生产编译缺少正确主题色引用，改为现有主题色；测试编译缺Compose语义扩展导入，已补齐，失败日志保留。本批新增的窗口尺寸／可变集合lint提示分别改用实际窗口信息和只读List状态，没有抑制规则。

原生初始／复现共记录 8 个失败测试结果，全部保留。最终具体修复及各轮结果以 `evidence/instrumentation.json`、`evidence/ui-debug-resolution.md` 为准；不把此前失败隐藏或把不同轮次拼作一次整套运行。正文点击、同名选择和有效Open均有实际断言，未用合成callback绕过UI。截图只展示合成资料，真实字形触碰也不能代替真人手写准确率或笔感验收。

APK：`e2d6efcdd0c602f2f2242ffe4dd4a0ec80489b5f3f162cab8308d0fac55dbb44`（150613740字节），schema12/IWO9对应既有实现未改。V49完整核心266/Room169、v48→v49升级29表21条仅为历史证据，本次不重跑且不外推真实库。

未测：用户设备覆盖安装／真实库升级、真实物理分屏与TalkBack、真人笔／手指体验、私有故障原迹、多作者识别、物理低资源与系统出口。TL50及旧ABF/BR49清单保留NOT_RUN；普通笔记全文／PDF字层链接、统一读写忆／留白／portal等不在本批实现范围。

完整证据归档 `E:/Inkweft/archives/2026-10-01/TitleLinks`，交付 `E:/Inkweft/dist/TitleLinks-V50`。没有提交／推送／PR／发布或真实设备安装。
