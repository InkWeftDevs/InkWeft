# SiYuan 搜索请求版本门禁移植

- 上游：siyuan-note/siyuan，固定提交 `572abc5bcc2a447791001e110181eb56edb2bd0c`
- 直接移植的范围：`app/src/search/request.ts` 的递增请求版本、`isCurrent` 与销毁后拒绝旧响应规则；不是声称复制整套搜索或思源内核
- 原源码：https://github.com/siyuan-note/siyuan/blob/572abc5bcc2a447791001e110181eb56edb2bd0c/app/src/search/request.ts
- 原测试：https://github.com/siyuan-note/siyuan/blob/572abc5bcc2a447791001e110181eb56edb2bd0c/app/src/search/request.test.ts
- 许可：该 TS 文件无另列宽松许可，按上游根 AGPL v3 处理，原文随本目录保留。Kotlin 移植明确标为派生代码；不把它称独立实现，不将其归为 Apache/MIT
- 项目原有 LICENSE 为 AGPL v3 文本，本次没有更换项目许可证、追加外部服务或引入 Go/TS 运行时。对应源代码和构建材料仍随 InkWeft 分发；此记录不是法律保证

## 实际缺口与适配

`BookSearchPanel.openResult` 原先只比较请求时与当前查询字符串；A→B→A 会让旧 A 的异步页面核验误认为仍有效。PDF 搜索也有查询文字改动与 Compose 协程重启之间的短窗口。现从上游移植版本门禁并实际接入 PDF 结果/错误/进度与打开结果路径，输入事件立即失效旧版本，作用域销毁后拒绝返回结果。

平台调度继续使用已存在的 Compose LaunchedEffect、协程取消与 DocumentRendering Mutex，因此不搬上游 WeakMap、DOM、计时器或 HTTP 控制器。当前完整词组检索语义未变。测试覆盖旧响应、A→B→A、关闭后排队结果、独立面板和只读令牌；其中 ABA/独立面板是 InkWeft 增补。

未采用：searchHighlight.ts 的 HTML 输出（本项目原生 AnnotatedString，不解析HTML）；backlinkRefresh.ts 全套 DOM 面板状态（已有稳定 Compose 状态，仍继续核验对应边界）。删除引用安全的 Go 规则单独评估，不冒称本提交已引入。
