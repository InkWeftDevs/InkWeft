# 第三次三端原生复验：官方依赖有界预检

2026-10-13 14:13:02 UTC 创建 [run38411448143](https://github.com/InkWeftDevs/InkWeft/actions/runs/38411448143)，attempt1。执行提交 `71b84885bd4ecb3fe8a4406788ffa14abc189497`，准备提交 `ebd793e965a76f85b6254c2e8d7e2a2a8cd16f45`，隔离分支 `codex/kvm-pr20-triple-20261013-dependency3`。此文件记录启动状态，尚无本次原生通过或性能结果；后续以原始工件核验报告为准。

第二轮原始工件已核验并记录在[第二次恢复报告](../pr20-triple-budget2-recovery-20261013/README.md)，根因是官方 MuPDF 1.28.5 POM 连接超时，非源码编译错误或已记录的HTTP策略拒绝；44项NOT_RUN、双清理正确。此前失败和旧候选观察保存p50回归、四项不确定结果保留，不被本次启动或静态绿灯覆盖。

[控制器](https://github.com/InkWeftDevs/InkWeft/tree/71b84885bd4ecb3fe8a4406788ffa14abc189497/ci/pr20-kvm-triple)只在setup增加单次相同官方POM检查：curl禁用curlrc，仅HTTPS，默认TLS校验，无跳转/重试/代理身份仓库改动；10秒连接、23秒请求、25秒外层上限且受原setup/55分钟截止。须HTTP200、662字节及首轮成功依赖回执的固定SHA一致。POM不注入Gradle缓存、不上传；后续仍需真实Gradle、9 APK、签名和四ABI ELF完整核验。此检查只提前发现不可达，不能保证或修复之后的依赖访问。

三端、44方法/14组、样本、阈值、单runner及32/38/52/55/60分钟截止不变。预检最多额外占用25秒setup，原假设余量相应减少；C1完整构建及新诊断的耗时仍未知。无快速模式或删减样本。cleanup.py和旧budget文件字节不变，白名单文本20MiB/3天和既有权限范围不变。

实际离线8组检查通过：原函数8种预检成功/拒绝/跳转/超时/TLS/错误hash/错误长度/外层截止；44方法计划；24份历史原日志与11种无效解析；原方法错误路径；累计时钟/环境/授权一致；actionlint及bash/Python语法；12种实际打包状态并确认POM排除；38项输入只读哈希一致。检查器SHA与输入摘要见preflight.json；全是离线静态/合成检查，不是Android运行。最终8项执行文件哈希另核验，活动workflow准确绑定准备提交，SHA256 `ac1c1eb8b3a72870ffae9eeb05ae7230e728348005dc2a0ce96fc99fe72907e6`，actionlint及bash检查通过。

准备分支推送后官方API确认0个run，全仓最近30项没有活动run；随后只有活动workflow文件提交，官方API确认唯一run、准确HEAD、attempt1。没有修改源分支、main、生产代码或Docker目录，没有重试原受拒绝的工件下载通道。
