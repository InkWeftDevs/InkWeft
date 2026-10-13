此文件保留启动前的预算论证。下文“提案”“未启动”描述论证当时；本次启动及终态以本目录 README 为准。旧轮次原始材料位于[clock2 报告](../pr20-candidate2-clock2-20261013/README.md)。

推荐本次只修预算：下一隔离尝试使用累计 setup38 / correctness41 / common53 / diagnostics55 分钟，仍为实际 job60 分钟硬限；继续串行、单worker、`--no-build-cache`。保留3源、9个真实APK、57方法、原探针/样本/阈值、method17唯一helper例外与原清理。该提案已获父侧转达的新用户授权及独立只读审阅；它增加C1构建机会，不保证完成。没有修改旧控制器或启动新job。

**本轮能确认的事实。** run38433834125 / job115422592925执行active9907a8f96fe0d20061f0a7deba6a69d4416ced50。官方本地终态metadata给出job16:50:55–17:21:15，1820秒；冻结PR服务器anchor16:49:10，比实际job早105秒。旧setup截止17:21:10。B包装步骤16:51:53–17:04:06=733秒、C0包装步骤17:04:06–17:13:55=589秒均成功；C1从17:13:55开始，17:21:09外层timeout exit124，434秒是被截断窗口，不是完整构建耗时。模拟器、30正确性/24公共/3诊断阶段均skipped，57方法没有本轮原生执行。

原job-decoded.log在17:21:09.3103820和17:21:09.7467916各记录一份cleanup原文：emulator=NOT_STARTED、command_groups=STOPPED_CONFIRMED、acl=NOT_TOUCHED、errors=[]。package原文报告34 payload /1268679 bytes/omitted0/truncated0；上传36个总文件，ZIP345179 bytes，artifact11834012592，SHA256 ba2420bb9217f6ed2185bea66dc46451589f0a097e0119ebf86bb3f443577ebb。日志SHA256为ea473725e383204daae37ac4ad4ae44c6ceeaa843d2914e857dfe8ffe80b87ad。它们足以证明这次预算停止和日志中的清理结果；未获取原ZIP，不宣称已核验ZIP内manifest、APK/依赖回执或逐任务构建日志。

源数据为同目录[jobs-terminal.json](../pr20-candidate2-clock2-20261013/metadata/jobs-terminal.json)、[run-terminal.json](../pr20-candidate2-clock2-20261013/metadata/run-terminal.json)、[job-decoded.log](../pr20-candidate2-clock2-20261013/job-decoded.log)、[artifacts.json](../pr20-candidate2-clock2-20261013/metadata/artifacts.json)。没有重试已拒绝的工件存储通道。原ZIP不是这次预算普通修复的前置；有机会收到后再补完整工件验证。

**时间口径。** 上述733/589/434秒都是API包装步骤秒级时长，包含包装内Gradle、源码/产物校验等，不等于Gradle内部task耗时。第四轮summary.actions精确值是B503.509613、旧C0 339.201932、旧C1 405.451996、模拟器准备26.181581、17正确性28.078971、24公共499.497566、3诊断44.920757秒；summary.phases的Gradle命令分别501.661360/337.418673/403.783005秒，不混用。

当前C0是ebb，第四轮同名C0是794。当前B与第四轮B的canonical源码manifest相同；当前C0=ebb与第四轮C1=ebb的canonical manifest同为c0e3b9a4cfa9b5a602e47422f080add43353d64acacea505cc8f3ebee4c91e63。第四轮同口径API步骤另见 `/workspace/scratch/pr20-triple-run-38419007471/jobs-latest.json`（step6 B503秒、step8 ebb405秒），当前 `jobs-terminal.json` 对应step6 B733秒、step7 ebb589秒。故同源API包装应并列B503→733、ebb405→589，不能把旧C0的340秒称为ebb同源对照。这约1.45倍的构建差异没有task/CPU证据可归因为host负载，也不能外推成native慢速系数。不同构建顺序及运行条件仍是限制。

**38/41/53/55/60的量化解释。** 按本轮时间重放，anchor至C1开始是1485秒。setup38=2280秒，因此C1完整包装加AVD/boot/显示配置共有795秒，比本轮多360秒。以下仅是假设；还应扣掉外层2秒保护、步骤间开销和API秒级取整误差。下一run真实anchor提前量也可能不同，不会重设旧anchor。

| 假定C1完整包装 | 假定设备准备 | 795秒窗口余量 |
|---:|---:|---:|
|589秒|60秒|146秒|
|589秒|90秒|116秒|
|589秒|120秒|86秒|
|733秒|60秒|2秒|
|733秒|90秒|−28秒|
|733秒|120秒|−58秒|

第四轮设备准备约26.18秒只是参考，这里采用60/90/120秒敏感性；C1完整时长仍未知。固定阶段剩余窗口为正确性180秒、公共720秒、诊断120秒。公共720/499.497566≈1.441倍、诊断120/44.920757≈2.671倍；这些是对参考时长的预算余量，不是本轮速度预测。30正确性相较旧17项新增13项，180秒无已证实上界。提前结束立即进入下一阶段；内部测试时限、失败停止和原方法均不变，超时原样保留。

工作仍在anchor+55前停止；cleanup45+45+45/package及upload120，含kill grace名义261秒，加39秒步骤开销裕量的既有计划不变；实际job60硬限不变。anchor更早只会缩短工作时间。新run的clock2使用新必要PR更新的服务器时间并一次冻结；绝不能把本轮已冻结anchor后移。

**为什么先不启缓存。** 固定run.py build()明确传`--no-daemon --max-workers=1 --no-build-cache`，即使冻结gradle.properties中`org.gradle.caching=true`，命令行仍显式禁用task build cache；这不等于禁用现有依赖下载缓存。三源码树继续独立，现有依赖哈希/签名/MuPDF四ABI/嵌入身份核验都保留。

官方Gradle说明支持按声明的任务输入复用输出，并能跨本地不同checkout复用；未完整声明输入的任务可能错误命中，绝对路径又可能令缓存失效。因此本job空的私有本地task cache有技术可行性，但不能靠“多数源码相同”保证命中或正确，更不能直接复制其他源码树的build目录。[Gradle build cache官方说明](https://docs.gradle.org/current/userguide/build_cache.html)、[缓存概念](https://docs.gradle.org/current/userguide/build_cache_concepts.html)。此次能读取的current文档标9.8.1；9.4.1版页面工具未取到，项目仍固定9.4.1，未升级或运行验证缓存API。

静态manifest显示core-domain三端122文件相同；C0→C1的data-local仅5文件变化、app仅2文件变化，说明重复编译存在潜力，不证明具体task可缓存。现有app生成身份任务有identityJson输入但未标CacheableTask，BuildConfig也包含每端SOURCE_COMMIT；Room KSP传入绝对schema路径。不得强行修改输入归一化、强制未声明任务可缓存、绕过生成身份或用Java-only包。实际缓存命中范围、压缩/磁盘成本及C1瓶颈需要后续日志，而不能从wrapper时长猜出。

若以后预算单改仍不能完成，最小缓存候选应是另一次显式修订：只启Gradle自身已可缓存任务；在本job新建且最初为空的私有目录供B→C0→C1串行使用；显式关闭remote/push到远端，不恢复跨run缓存或上传缓存/APK；外置、hash绑定的init脚本配置目录，不改冻结三端源码。不应另改GRADLE_USER_HOME把正常依赖缓存一并清空。仍逐端核验源码前后manifest、独立APK路径/签名/MuPDF/依赖、嵌入真实source_commit及active build_commit，并记录实际FROM-CACHE/EXECUTED数量。该方案现在不实施，也没有可承诺的省时数字。

**冻结计划如何处理。** readiness6c72的原PLAN/hash保留，其字面32/38/52/55与无build-cache约束都是历史事实。新的budget-revision、authorization、clock-contract及执行README必须明确：父侧新用户授权替代的是累计阶段预算38/41/53/55，source-spec6e9c、method-plan808、探针/阈值不变，task_build_cache仍false。不能只改controller数字而留下未经解释的PLAN矛盾。原controller、旧工件和旧失败记录不变。

父侧转达的新授权原文为：“请利用官方日志能证明的退出和清理事实继续合理修复预算/构建安排…保持每轮60分钟总上限、原始样本及正确性门禁，可根据已完成第四轮的实测测试阶段时长重新合理分配预算或评估可信构建缓存…不要盲目同配置重跑。”当前具体行动是准备新scratch模板与离线边界检查；真实anchor仍留markers，父侧完成必要状态更新后一次冻结、核验并决定新隔离激活。此文档不执行该动作。
