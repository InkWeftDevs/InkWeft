# PR20 首次三端复验：用户工件恢复与完整核验

用户上传原工件后，经Library授权文件传输读取。ZIP大小334,961字节、SHA256 `8022e2ea3510bbef1e68d91795f55e8d5237945c5ea60dc13791ef42ab8021eb` 与官方元数据完全一致。没有重试此前Forbidden的GitHub下载地址。

全部30个ZIP条目可读，其中28项payload逐项原始/上传长度及SHA256相等，共1,187,893解压字节，无截断、无被省略项。冻结方案、源码清单、公共探针、授权与控制器哈希已对应固定6e/4f8提交核对。原始文本存于evidence，保持逐字节不变；本报告不会修写首次工件。

结论仍为 **INCOMPLETE：准备阶段外层超时，44项原生测试全部NOT_RUN**。GitHub终态和exit124见[已归档公开元数据](../pr20-triple-attempt1-20261013/README.md)。

- B、C0原始构建日志均为BUILD SUCCESSFUL，各124项任务实际执行、无任务缓存；完整build action分别664.471077与501.247638秒。两端共6个APK的公开身份回执、同一debug证书摘要、相同四ABI MuPDF ELF回执、879项依赖记录一致。工件不含APK，因此这里核验的是运行时回执，不是在本地重新验签APK。
- C1只有约350.514秒名义准备窗口，日志止于`processDebugResources`，没有BUILD SUCCESSFUL，也没有已显示的编译错误。没有C1 APK/依赖完整回执，不能称9 APK完成，不能推断C1代码编译失败或完整构建耗时。
- 44项方法与冻结方法计划逐字段一致且全部NOT_RUN，没有安装组、instrumentation或原生性能值。
- `cleanup-trap.json`与`cleanup-always.json`均为 `emulator=NOT_STARTED`、`command_groups=STOPPED_CONFIRMED`、`acl=NOT_TOUCHED`、`errors=[]`。这证明回执中模拟器未启动、所拥有进程组已停止、ACL没有被改动；此处应称“未触及”，不是“已做逐字节恢复”。
- 原始summary顶层残留`SOURCES_FROZEN`，build-C1的action及phase残留`RUNNING`；这是外层终止后的静态状态记录缺口，不代表任务仍在运行。原始summary及其SHA256完整保留，`verification.json`另记派生终态，不编造C1结束时长、JUnit或提交结果。

原下载阻塞和未读原工件的历史报告保持不变；其“原始回执尚未核验”缺口现在已闭合。旧候选性能1项回退、4项不确定及GC竞争覆盖缺口仍然未闭合。

## 最小后续修订

保持三端固定源码、9 APK、44方法/14安装组、原样本与阈值、单标准runner、串行单worker、禁用任务构建缓存。仅把新尝试的累计截止改为32/38/52/55/60分钟；55分钟仍开始收尾，总硬限60分钟。原首次计划不回写，新的预算说明、授权关联、控制器及workflow单独固定。

按完整action口径，job开始到C1 action开始实测1209.486242秒；假设C1完整action等于C0的501.247638秒，剩余模拟器/衔接/环境记录取30/60/90秒时，预测setup为29:00.734/29:30.734/30:00.734，32分钟余179.266/149.266/119.266秒。旧setup余数不再次相加，避免重复核验开销；这些是敏感性假设，不是C1实测。

公共比较旧外推543.283529秒，加假设60秒安装核验，假设原生倍率1.0/1.3/1.5时分别603.284/766.269/874.925秒。倍率没有用构建慢速校准，1.5情景超过14分钟窗口34.925秒。阶段提前完成即推进；17项正确性、60个GC新库和带观测library的总耗时仍未知，不保证55分钟内完成，不减样本补齐。

同时修正控制器的两个小问题：打包前保留原始summary，把actions/phases/install_groups/methods遗留RUNNING/INSTALLED归为INCOMPLETE，并覆盖下一阶段尚未启动便被预算中止的暂态；保留既有PASS/FAIL/NOT_RUN/JUnit，不捏造结束时间。为trap/always/打包各设置45秒外层上限（另2秒强制结束宽限），保留上传120秒，名义收尾上限261秒、留39秒调度余量。时间上限不等于恢复成功，仍要求两份真实清理回执；cleanup算法和权限范围不改。

三个替代方案已独立审查：

| 方案 | 结论 |
| --- | --- |
| 同一job重新分配阶段预算 | 首选；不改变构建输入或测量路径，最小且可核验。 |
| 本job初始为空的私有Gradle本地任务缓存 | 备选，未实施。可按声明输入复用输出，但任务输入缺失可能误命中、路径可能导致不命中，项目收益尚未测量。不能人工复制另一端build目录或强行让任务可缓存。[Gradle官方说明](https://docs.gradle.org/current/userguide/build_cache.html) |
| 跨多个hosted job拆构建/比较 | 不选。标准hosted job各用新VM，不能当同机比较延续；本次又不允许上传APK。现有同job分步骤已经保留阶段边界，不会减少总构建量。[GitHub官方说明](https://docs.github.com/en/actions/concepts/runners/github-hosted-runners) |

后续执行结果将另行记录。本报告没有新的原生性能数值；JVM/编译/静态控制器验证均不能替代设备测量。
