# V56 PDF 原文搜索交付

2026-10-02，**0.0.56-pdf-text-search（56）**。指定精确对话 chatgpt-conversation://6abbd418-4dd8-83ea-8e83-d60cf0b6e100 最新全文仍未取得。本批依据后续明确授权的 PDF 搜索阅读体验，以及“那你看着改，一直往后推进完善这些功能”实施；不声称已读取该对话。用户已授权连续完善，V56 独立交付后继续下一批，无需逐版本确认。

PDF 有原生文字层时按字面关键词搜索；每个当前活动笔记页仅一行，分别显示 PDF 原文（源页）与手写/文本框摘要。同页两类命中不重复成两行；点击优先定位 PDF 第一处命中的原页区域，连续显示切回单页但不解除阅读锁。返回搜索保存查询与稳定页 ID 上下文，改页顺序不改变 PDF 源页身份；回收页不再出现在结果中。处理可暂停、重试或关闭，快速改词不发布旧结果；扫描页清楚说明没有原生文字，可继续使用现有区域摘录。

复用现有 MuPDF 字符层与原页焦点链，完整搜索页文字，不再把超过20k字的尾部搜索截掉；显示摘要与搜索正文分离。按原生字符 quad 联合定位，裁切原点与旋转沿既有纸面变换，连字拆分形成零宽字符时退回所在行区域；同一时刻仅保留当前 PDF 源实例。

原生 PDF 7/7、新界面 6/6、受影响旧界面 5/5 通过；lint 0 错误、0 致命、132 警告。 最终本地 APK、合成模拟器安装与独立交付字节一致，固定签名保持。未变的领域/Room 历史全套未重复运行。

候选 [E:/Inkweft/dist/PdfSearch-V56/InkWeft-v56-workspace-preview.apk](E:/Inkweft/dist/PdfSearch-V56/InkWeft-v56-workspace-preview.apk)，应用 ID `org.inkweft.app.a0.workspace`，151658052 字节。

APK SHA256：`023b99ecb0403e7877800256fa8948e1f42de5669e3b3b567f05dad610c7ab24`。

固定证书 SHA256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。

436 项构建输入中，430 项与 V55 完全相同，修改既有4项、新增2个有行为覆盖的测试文件；生产范围为版本号、DocumentRendering、BookSearchPanel 和 BookInkScreen。原稿、逐次修复、失败与通过日志、测试 APK、5 张成功断言后的未修改原生截图和源码清单分类保存至 `E:/Inkweft/archives/2026-10-02/PdfSearch`；独立 V55 包保持原摘要。工作树继承原未提交修改，HEAD/分支保持，未把历史脏文件算成本批改动。

首次原生执行触发 MuPDF1.28.5 StructuredText.search JNI 崩溃，已改用既有 typed getBlocks/字符几何路径并实际重测。JNI 源中 toArray 调用与注册签名不匹配的依据见 [StructuredText JNI](https://raw.githubusercontent.com/ArtifexSoftware/mupdf/1.28.5/platform/java/jni/structuredtext.c) 与 [native 签名](https://raw.githubusercontent.com/ArtifexSoftware/mupdf/1.28.5/platform/java/mupdf_native.c)。未关闭 CheckJNI 或隐藏失败。一次测试编译及首轮3项界面失败也保留，最终候选18项全部通过，详见 [验证](VERIFICATION-V56.md)。

Room schema12、IWO9、当前 PDF 文件与人工识别记录均保持；未新增 OCR 或索引表。V55 MAP_PORTAL_V2 当前/历史/删除记录仍要求最低 V55，旧 V54/V47 不能读取这些记录，不能因签名相同直接把真实库交给旧包。

仅字面子串、忽略大小写，查询上限256字符；不跨空白/软连字符拼接，不做语义检索或扫描 PDF OCR。复杂 PDF 的原生字符次序可能不同于视觉阅读次序；无可见几何的文字不伪造定位。native调用本身不承诺中途抢占，返回后才响应协程取消并阻止旧结果发布；大文件等待和各厂商 PDF 实际体验尚待实机。

保持 Android 原生、本地优先与固定签名。未读取、启动、安装或升级真实平板，未提交、推送、PR、合并、发布或上传私有原迹。全部运行仅在本批 Android35 独立模拟器与合成资料完成；真人手写、人工读屏、物理分屏、手写笔和真实资料恢复均未验收。 未测项与记录模板见 [实机清单](DEVICE-TEST-CHECKLIST.md)。下一批优先把已有卡片回源入口放入固定操作区，保留源页、阅读锁与导图状态；现有分屏和导图停靠不重复建设。长期复习、多资料组织等差距记录于 [后续范围](DEVELOPMENT-GAPS-V56.md)。
