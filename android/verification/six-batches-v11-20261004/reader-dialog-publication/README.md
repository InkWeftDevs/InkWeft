# Dialog 读取发布交界的实际设备证据

源码 `5aeefdcdc048a958672c06b95df9266c0b75397d`，同一构建和模拟器，每次独立清除合成测试应用。第一轮三个方法通过，第二轮首项失败后停止：实际 3 PASS、1 FAIL、5 未运行。未以重跑成功覆盖失败。

固定快照成功与失败日志均显示 IO 解码返回及 loaded 发布完成。失败中，之后提交初始 loading 分支，15 秒预算内没有 loaded 分支；成功中先 loading 后 loaded。`compose.*` 来自 SideEffect，是分支提交时刻，不能推断读取 getter 的准确时刻。实际失败截图仍显示读取快照指示器；线程栈显示 Main/IO 空闲。因此本次不能解释为解码慢、effect 未启动或取消。

Compose 1.10.5 实际依赖检查指出：Recomposer apply observer 只处理被 Composition 读取过的 StateObject，首次 CompositionImpl.recordReadOf 设置该读取标志。父 composition 启动 effect，子 Dialog 才首次读取 loaded/error，存在读订阅与异步发布交界。该机制支持修正方向，日志没有直接记录框架内部过滤分支，不能冒称已直接捕获这一内部因果。

原来源窗口 loaded 已作为父 LaunchedEffect 的 key 被读取，但 error 仅在 Dialog 中读取；早先来源窗口失败恰在 error 呈现路径。固定快照 loaded/error 均仅在 Dialog 内读。当前候选以父 composition 先读取值、将明确不可变显示值传入 Dialog，保留 dispatcher、effect key、取消、原 15 秒和安全断言。修复后的设备结果须另列，不由以上失败证据或离线协议替代。

诊断默认关闭，仅目标测试显式开启。观察可能改变调度；有限轮次无失败不等于稳定性证明。以上所有资料为合成资料，无真实笔记、凭据或 APK。

## 固定依赖核验

核查实际缓存的 AndroidX Compose 1.10.5 字节码：`Recomposer` 的 snapshot apply observer、`CompositionImpl.recordReadOf`、`StateObjectImpl` 的 reader kind，以及 `AndroidDialog` 的 `rememberUpdatedState(content)` 和子 composition 创建路径。每个所读 classes.jar 均与对应 AAR 内 classes.jar 摘要一致。

- runtime-android AAR SHA-256：`fd57586cf86d8b89f070d05cf5f0a2afcd15dd57411e767decc9e6b929764d43`
- ui-android AAR SHA-256：`6bc88fc41214aa2eb4a7b980dea2e55e002abdc4cd448ade52b3fd150a9299ea`
- ui-test-android AAR SHA-256：`e26d8331bccc81e87e947840041f4e15aa975d6f62cb340bbccf73df0cab95b2`

生产修正见独立提交（读取任务所属 composition 观察结果再传入来源弹窗）。本地生产/仪器测试编译通过，Android 回归结果仍需本候选的独立回执，不在此预填。
