# 搜索审计：run38498392929

**SQL取消边界已工作，observe保存p50仍未关闭。** B→C1四个seed均超过原`max(10%,1ms)`门槛，配对中位+3.0896635ms/+124.2714994%。C0→C1两好两差，中位-0.1834835ms/-3.0827630%，只能说在原band内，不能认定回退修复。p95/max收益、cold收益与p50分别报告。

本轮固定身份B=a128bb07+既定preserving writer工厂，C0=45b4861，C1=d66b60d。C1相对C0只有同步SQL边界取消检查和六阶段Room回归；没有防抖、notes失效或正文匹配语义改变。下面的实测均属于这些固定版本。后续获准的空draft CTE改动尚未测试，单列于最后。

## 独立核对

公共32–55中仅12个库方法进入本审计：33/35/37/39/41/43/45/47/49/51/53/55。方法58仅用于候选阶段归因。13份原instrumentation日志逐条重解析与records完全相等；12个最终evidence与冻结analysis相等。720次保存均Committed，direct revision2–31、observe33–62，每个start/end与原序耗时核对一致。每轮最终20000条索引，313次批回调计数完整递增。

四seed三端逻辑夹具SHA相等；公共probe仍为`90525d56efe48b32441be890e29c021d0f699fe7d82389d1e049283cfb10f98c`。p50使用30值排序后下标14，p95下标28；原probe的legacy p50下标15不用于正式比较。独立复算27项库比较，逐对差值、band状态、聚合中位均与冻结结果一致（浮点舍入容差1e-10）。完整原序窗口/逐读阶段/文件SHA在`audit-search.json`。

## 公共结果：毫秒，依次seed4101–4104

| 指标 | B a128 | C0 45b | C1 d66 |
|---|---|---|---|
| Cold未索引 | 3298.470 / 3290.270 / 3373.531 / 3305.833 | 1913.127 / 1994.139 / 1947.520 / 1989.254 | 2017.654 / 1931.615 / 1968.678 / 1961.166 |
| Warm五次中位 | 531.559 / 521.515 / 542.038 / 525.370 | 583.926 / 600.317 / 592.851 / 591.090 | 602.817 / 593.093 / 585.998 / 594.990 |
| Direct保存p50 | 2.152 / 2.196 / 2.181 / 2.188 | 2.468 / 2.406 / 2.606 / 2.326 | 2.588 / 2.495 / 2.505 / 2.496 |
| Direct保存p95 | 2.947 / 2.621 / 2.999 / 2.738 | 5.276 / 5.583 / 5.292 / 5.855 | 7.211 / 5.047 / 7.740 / 5.580 |
| Observe保存p50 | 2.562 / 2.394 / 2.449 / 2.519 | 5.879 / 5.744 / 5.853 / 5.661 | 5.041 / 6.320 / 5.307 / 5.840 |
| Observe保存p95 | 93.464 / 86.205 / 85.403 / 87.736 | 13.816 / 14.822 / 13.070 / 12.476 | 10.931 / 11.966 / 16.315 / 11.490 |
| Observe保存max | 3209.243 / 3207.328 / 3219.182 / 3228.144 | 32.755 / 33.750 / 31.525 / 31.709 | 32.450 / 31.773 / 34.963 / 42.989 |

B→C1 cold配对中位-1351.6611145ms/-40.9843520%，四对均更快；“cold”是夹具hash后的首次未索引查询，不是冷OS/文件缓存。direct p50/p95四对均高于B，但本轮绝对差未跨1ms/5ms原保护线，状态WITHIN_PROPOSED_BAND，不称全面提速。direct max从约3.17–3.25秒降至5.89–9.88ms。

observe p50：B→C1四对均过门槛，REGRESSION_SIGNAL；C0→C1无单对过门槛，WITHIN_PROPOSED_BAND，但两对更差，改善未成立。observe p95中位-75.2424605ms/-86.5116385%，max中位-3180.506301ms/-98.9513912%；尾部收益不抵消p50回退。

warm：C1对B四对均慢，分别+71.258276/+71.578298/+43.960377/+69.619340ms；配对中位+70.438808ms/+13.3285059%。协议没有独立warm数值门槛，保留不利观察。C0→C1为+18.890822/-7.223342/-6.853107/+3.899160ms，中位-1.4769735ms，两好两差。

## 索引、重叠和读取规模

公共C0/C1每轮30次observe保存都跨一次64条提交（64→128），该保存窗口内sourceChecks也实际推进；全部Committed。B每轮64→448，六个保存窗口内有批提交，另一个长窗口推进原文扫描，二者没有同窗。不能把30次保存都描述为持续索引临界区竞争；不同版本保存墙钟长度不同，B被阻塞得久反而允许更多批完成。counter不变也不等于不存在未提交writer事务。

| 指标四轮范围 | B | C0 | C1 |
|---|---|---|---|
| 全20000条提交 | 29.432–29.802s | 29.269–29.549s | 29.096–29.360s |
| 首批→末批增量速率 | 671.325–679.741 rows/s | 677.287–683.747 rows/s | 681.681–687.960 rows/s |
| 索引和搜索ready联合完成 | 29.974–30.352s | 29.875–30.156s | 29.690–29.958s |

增量速率按`(20000-64)/(最后提交timestamp-首次提交timestamp)`算；包含批间yield、调度和竞争，不是writer持锁吞吐。B→C1完整索引中位-370.7806725ms/-1.2485476%，联合完成-330.4284425ms/-1.0921113%；C0→C1约-286.210018/-288.7827765ms。均在原`max(10%,500ms)` band内。first/final字段与批回调时间来自先后独立nanoTime读，JSON保留差值，不误当严格同一timestamp。

所有C0/C1：header和目录返回正文均0；最终60条shelf预览35425字符；SQL含lookahead投影61条/36025字符；按需全文完全相等。B header/目录各返回10010000字符、最终shelf1181180字符；baseline投影observer不存在，值为null。候选暂停只读快照时writer能提交，B不能。该结果是**实际返回正文规模**，不证明物理磁盘页为0，也不代表非空query只扫描61本正文。

## 方法58：边界检查实际作用

独立diagnostics-on，originNs=705084579652。60次保存全部Committed；observe30次原序窗口origin后4039.496599–4256.432450ms，耗时和216.334286ms。该诊断自身observe nearest-rank p50/p95/max=6.202344/13.751264/30.772934ms，不能与公共无诊断样本混算。

29次真实invalidation、29次调度；一次初始debounce取消，28次observe-read-start中26取消、2成功。26取消里23进入IO，3在IO前终止。23个IO取消读的最后工作stage：drafts-ready3次、scope-start2次、scope-end13次、page-hits-start5次；无任何metadata-start。**18次在命中查询准备完成前停止，边界修复有效；还有18次完整scope，作废前置成本仍在。** 不将不同run旧方法57的绝对时间当配对节省。

| 取消读区间 | 有完整边界的次数 | 合计墙钟ms | 中位ms | 与保存窗口交集ms |
|---|---:|---:|---:|---:|
| read-start→connection-open | 23 | 11.090001 | 0.193821 | 11.056107 |
| connection-open→snapshot-open | 23 | 2.722943 | 0.097743 | 2.722943 |
| snapshot-open→drafts-ready | 23 | 30.572346 | 0.951587 | 30.502726 |
| scope-start→scope-end | 18 | 84.610481 | 4.052390 | 83.438360 |
| scope-end→hits-ready | 5 | 4.568523 | 0.644774 | 2.290778 |
| read-start→read-cancel | 23 | 199.848033 | 7.120200 | 189.951816 |

完整IO总计行包含前面区间，不相加；scope统计不包含2个只有scope-start、没有scope-end的取消。停止于scope-end不证明一条hits DDL也没有执行，仅表明没有完成hits-ready；诊断粒度不能再细分。全部是墙钟（含抢占、调度），不是CPU、锁等待、或可直接从保存延迟中扣除的预算。

sample23（零基序号，revision56）4192.283567–4223.056501ms，30.772934ms，是最大保存：index64→128，source0→184。read23开库4196.666504–4198.425319ms，scope4199.479788–4202.140558ms，page-hits开始4202.661832ms。后一个sample24再推进source185→195，read23到4225.743873ms才snapshot-closed、4226.305340msobserve取消完成。其余28个保存窗口均无index/source计数推进，却仍与TEMP准备/取消重启重叠；counter不变不能排除索引尚未提交。

sample21保存6.773951ms，read21的connection-open前区间跨3.088668ms，随后drafts-ready前2.938341ms，该读最终停在drafts-ready。开库也有尾值，但23读开库中位仅0.193821ms；23读snapshot-closed→read-cancel共6.895643ms混合关闭/异常展开，再到observe取消共10.431413ms混合返回调度。现有日志不能把这些全归为数据库锁。

C1相对B的额外工作有明确来源：公共probe固定`flowOf(request)`，SaveNote只更新notes/revisions/receipts；a128 observe没有监听notes，候选监听notes并立即取消、重新查当前author快照。因此baseline在这个固定collector上不会逐次响应这些正文保存，候选会。不能删除notes监听、增加延迟掩盖此成本；**这一暴露差异不豁免原p50门槛**。

## 最小后续候选与定向验证

未选择连接复用：开库11.090ms、注册函数+begin2.723ms显著小于空draft DDL阶段30.572ms及scope84.610ms。复用还要保证每次新WAL快照、TEMP DDL rollback、线程绑定begin/end、旧CancellationSignal watcher退出、函数闭包的query/context更新；Requery addFunction会追加配置，不可逐次追加注册。现有数据不支持优先引入这个更复杂生命周期。

父侧随后明确授权并已落实**无draft时省掉空临时表**：在`$shelf`前缀增加显式五列`library_drafts(id,revision,title,text,position) AS (SELECT NULL,NULL,NULL,NULL,NULL WHERE 0)`；有draft时保留原CREATE/逐条INSERT。源码检索确认所有draft查询引用位于带`$shelf`的SQL中（scope填充、分页/正文表达式、目录查询）；page/card命中SQL不引用drafts。零行关系不会产生NULL草稿记录，LEFT JOIN/COALESCE仍取author值，UNION缺失draft分支仍为空；列名明确，NULL值无实例进入结果；但无类型NULL与原TEMP表的列affinity不同，编译期UNION类型推导仍需谨慎。建议以五个显式CAST(NULL AS TEXT/INTEGER/TEXT/TEXT/INTEGER)保持原affinity，已提交父侧协调构建窗口，目前源码仍冻结未改该行。保留drafts-ready、原cancel点、同一author快照、排序、terms和预览上限。无需schema迁移或缓存框架。

此新补丁**不属于本run的d66，尚未原生验证**，不能用上表宣称效果。它去除普遍空overlay场景中的无数据DDL，不特化query/正文夹具；仍可能增加零行CTE规划成本或改变执行计划，净收益待筛查。scope及warm主成本不会因为这一个变化自动消失。

源码/测试冻结SHA：

- `LibrarySearchRepository.kt`: `aa441cab97464dd4372e940cdacc252c48d71d385412f93d592d5eff1c67c76f`
- `LibraryPagingTest.kt`: `b05c4c93f2c0ed36204bb548c20f8d95c0dca5a35238e05361ac3655f6f9a061`

只扩展现有`sqlPagingMatchesUnicodePinnedOrderAndLoadsOnlyBoundedPreviews`，让空overlay和有draft均跑Unicode/pinned/folder、17条多页稳定性与正文投影上限；没有新增实现镜像测试或schema探针。`git diff --check`已通过，本agent未启动构建/native。

建议下一次只做数据层search筛查：LibraryPagingTest全部5个，加SearchRepository的classification、Unicode分页、draft/filter/page-before-card、短夹具取消/clear/category、stale/deleted source五项；再按父侧原计划执行固定KvmLibraryPairProbe四seed，不改30+30样本/门槛。需要阶段归因时单独开启诊断，不与公共计时混合。新候选对d66的两端筛查只能选择/否定此小改，不能冒充B配对验收。20k rapid的原门槛继续保留，不由小夹具绿灯推定通过。无调用关系变动依据要求重新跑raster/GC/Docker全量。

## 暖读及仍未闭合的因果

方法58的warm五次page SQL为577.469–584.545ms，占read-requested→read-end的97.713–98.165%，对应公共warm稳定不利结果。B在预载的完整Note正文上Kotlin匹配；候选每读快照经SQL取当前正文、UDF保持contains(ignoreCase=true)语义。page区间还包含SQLite扫描/排序、解码/JNI、匹配及预览；没有线程CPU/分配/物理IO细分，不能把全部差异归因某一种。空draft小改不声称修复warm，也不引入ASCII/NOCASE、target-only特化或跨读正文缓存。

若新筛查仍无改善，先用既有阶段确认空draft准备确实消失、取消数/其余scope总量如何变化；需要新测量时只补选定的query plan、cancel到达、作者排队/事务或warm提取/匹配归因之一，不在缺乏定位时扩大架构。四seed是比较单位，不能把720保存或warm5次当独立重复。父侧已验完整工件与58回执，本审计独立核对选定raw、来源与公式，不重复APK/环境全部审计；原probe/样本/阈值未改。

