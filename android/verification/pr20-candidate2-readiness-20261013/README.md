# PR20 第二候选：减少取消目录工作与追加重复解码

代码固定在 `45b4861ae23ac19eda3246fcd34cf3d2132dc1ee`。本目录是新的原生执行准备，**尚无该候选的Android性能结果**；历史第四次完整结果保留在 [pr20-triple-results-20261013](../pr20-triple-results-20261013/README.md)。

搜索把目录头和分类组装移到命中及有限分页SQL之后，取消读取可避免废弃对象组装；仍使用同一个读快照，不改变排序、检索新鲜性、150ms调度或保存协议。新增真实来源扫描取消检查及下一次完整分类读取检查。此改动没有解释warm查询瓶颈，也尚未证明关闭observe保存p50回退。

栅格仅在同一repository/page、投影/视口/图层/authoring配置、allowed/omitted及非空appendGeneration身份都兼容时，允许解码cache向前推进revision；当前空间标识、遮罩epoch、每批及末尾版本检查仍保留，普通调用默认失效。缓存仍受512笔/1MiB预算限制；未引入跨帧native stroke缓存。新增跨版本、遮罩、修改payload、隐藏/撤销、批间写入、全幅像素及取消释放检查。新app夹具在同一当前层合法写入视口外再视口内笔迹，避免绕过层写入守卫。

本地串行单worker、JDK17/Gradle9.4.1离线检查：

- data JVM31项，0失败/错误；data与app Android测试源码编译成功，首轮9分20秒。
- test-only夹具修正后app Android测试重新编译与data lint成功，3分44秒；lint0错误、14警告。
- 首轮源码hash在构建启动后记录，但两位所有者此前已经停写，结束时逐项相同；最终追加检查前后哈希相同。首轮app夹具编译通过不意味着它可正确运行，因此保留独立审阅发现及test-only修正记录。
- [检查回执与原文](evidence/)仅是host编译/JVM/lint及静态审阅，原生Room/UI/绘制/性能全部NOT_RUN。

[PLAN.md](PLAN.md)与[method-plan.json](method-plan.json)固定57次调用：原44次全部保留，另增13项正确性。四seed、公共探针、24性能调用、720保存、原阈值及nearest-rank不变。B仍原始受控基线，本计划C0改为上一候选ebb4848，C1为45b4861；不得把本计划C0与历史7949011混淆。

[source-spec.json](source-spec.json)及三个manifest固定实际构建字节；[prepare_sources.py](prepare_sources.py)只重建和校验源码。执行另用隔离一次性分支，单标准runner、32/38/52/55/60分钟累计边界、9APK、最小临时ACL与双清理、20MiB非敏感文本/3天保留。既有持续授权内继续；不改变用户源分支、main、Docker目录或旧证据，不合并部署。
