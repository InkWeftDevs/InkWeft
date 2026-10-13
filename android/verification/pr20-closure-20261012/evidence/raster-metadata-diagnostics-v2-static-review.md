# 绘制诊断 v2 静态更正复核

v1 实际编译失败：`InkRepository.kt:385:60` 的 `onBatch` 初始化被推断为 `Any`，不满足 suspend 函数类型；构建回执 exit 1、66.596 秒。**此前静态审阅漏掉了空 if 分支被解析为 Unit 的错误。** 保留 v1 静态记录、补丁和失败回执，不将原静态结论追认为编译通过。

v2 只将两分支明确写成带括号的 lambda：`if(probe==null)({ _ -> })else({batch-> ... })`。独立把候选和基线的这两行改写还原到内存后，均精确得到各自 v1 InkRepository 哈希；没有其它行为变更。此操作只处理内存文本，没有改动文件。

| 绑定对象 | v2 SHA-256 |
| --- | --- |
| 候选 InkRepository.kt | `a3bdf761ad7d768db82f10a8a7f22ea5197dcdd07e0e72cda3e6c3018ecd080d` |
| 候选 TileLocalRedrawTest.kt（未变） | `31325f357e719d4fe61354645f499ce06ecf8079e64fddb471c069f44ad3f5ec` |
| v2 portable patch | `3449bdb9dc5063e4386a9d5f9d0b3061a5bbb8834362dcb897777b05605352ac` |

Tile 文件与 v1 字节相同，原 18 行 assert、3 行 withTimeout 不变；默认关闭时的时钟短路、CPU 线程/非负检查、observer/cancel 边界、原摘要执行顺序及基线缺少 native counter 不补零，均不受此次语法改写影响。

该显式 lambda 写法符合原意，未发现新增静态问题；**本记录不声称 v2 编译通过，也没有原生诊断结论**。父侧正在运行独立 v2 构建，结果须以其新回执为准。未构建、运行设备、网络访问或全树扫描；仅新增本 MD/JSON，没有覆盖 v1 记录或修改源码。
