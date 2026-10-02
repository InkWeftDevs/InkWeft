# V64 题目维护候选交付 — 2026-10-02

版本 `0.0.64-question-maintenance`（64），应用 `org.inkweft.app.a0.workspace`。

摘要卡原有“属性与回忆”入口现在列出已保存的独立回忆题，可按实际题目 ID 和修订编辑原文，或确认后仅软移除选中的一道题。题目的手工学习状态、原题历史、旧操作回执、同卡其他题目与原摘要答案保留。

题目子编辑保留父层标签、别名、新题草稿，成功只关闭匹配操作与题目 ID 的子编辑。并发版本变化走原 CAS 拒绝；结果未知仍核对原 operationId，不创建替代操作。明确成功与明确拒绝身份保存至界面消费，恢复不再依据“没有 pending”猜测失败。阅读模式可查看原题，普通作者写入仍由原书级门控拒绝。

Android 定向 28 个唯一 class/method PASS（新增 8：7 项原生界面、1 项真实 ViewModel 状态恢复；相关旧回归 20）；lint 0 错误 / 0 致命 / 132 警告。候选 `E:/Inkweft/dist/QuestionMaintenance-V64/InkWeft-v64-workspace-preview.apk`；SHA-256 `0f74ba0ecf084553fe6d8257a57385d6a64f74655cfe2de9cf9be83f3ba9632b`。

原签名、Room12 / IWO9、原笔迹与作者数据合同保持；MAP_PORTAL_V2 当前/历史/墓碑最低版本仍为 V55。原有未提交改动与 V56–V63 交付保留。

本轮只使用新建空白模拟器的合成资料。真人手写、真实故障原迹、笔感、实机分屏与人工无障碍走查 NOT_RUN；真实备份恢复 NOT_VERIFIED；完整操作系统进程死亡 NOT_RUN。真实 ViewModel 的 SavedStateHandle 重建不等同于完整进程死亡验收。未安装/升级真实设备、未上传私有资料、未暂存/提交/推送/PR/合并/发布。指定对话最新全文仍 NOT_RETRIEVED，功能批次来自父对话后续明确的持续开发授权。

原始回归、源文件与 runner 摘要、原生截图、独立审查、历史原像和清理记录：`E:/Inkweft/archives/2026-10-02/QuestionMaintenance`。

新增功能沿用原 KnowledgeRepository CAS、不可变版本与幂等回执；未增加存储表、同步器、到期算法或依赖。

见 [验证](VERIFICATION-V64.md)、[实机清单](DEVICE-TEST-CHECKLIST.md)、[后续缺口](DEVELOPMENT-GAPS-V64.md)。
