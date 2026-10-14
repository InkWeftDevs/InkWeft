# observe collector 内只读 handle 复用：只读可行性审查

**判断：正常、身份稳定的Room数据库上可做局部候选；不能仅把现有 `.use` 外提，也不能目前就声称完整生命周期语义已经保持。** 不需要全局pool、串行线程、debounce更改或direct语义变化。代码和真实Android行为均未修改/验证。OBSERVE 26个IO读取全取消以及writer SQLite END中位wall4.925ms/CPU0.119ms，只说明当前窗口内存在非CPU延迟；不证明连接开关导致它，也不排除复用后END不变或更慢。

## 两个具体阻碍

1. **每read原样addFunction会泄漏旧上下文并增大重配成本。** 对本地实际 `com.github.requery:sqlite-android:3.49.0` AAR的字节码核对：SQLiteDatabase.addFunction每次new SQLiteFunction、向configuration.functions执行List.add，然后pool.reconfigure；SQLiteFunction没有equals覆盖，也没有removeFunction API。SQLiteConnection.reconfigure遍历这些对象并用contains判别新项。即使SQLite按同名替换了当前native函数，Java配置仍保留所有旧callback及捕获的query/Job/统计对象，不能复用这个调用模式。

小范围替代是collector私有owner，一次注册固定UDF集合；每read在进入SQL前绑定一个有界ReadContext（query/foldedAsciiStart、当前Job/context、scanned、当前udf统计与observer），finally清空它。UDF调用读取当前Context，仍用当前read的ensureActive及原Unicode匹配/排序/blank逻辑。这里“重新绑定”指更新请求上下文，不是重复addFunction。普通/诊断UDF名字与arity/flags不变；不能给UDF标deterministic或让statement缓存跨请求缓存结果。默认路径不读诊断时钟。每个collector独立owner，direct仍每调用新开/关闭，不能把owner提升为repository字段。

2. **collector寿命不自动等于数据库文件身份寿命。** 当前每read都会通过 `db.openHelper.readableDatabase.path` 重新open只读数据库；长期handle即使isOpen=true、path字符串相同，也可能仍指向被替换的旧inode。当前repo没有供此owner使用的数据库generation/关闭通知。open-helper对象身份变化可帮助检测Room reopen，但path/isOpen/PRAGMA data_version都不能证明文件未替换；单次stat也有检查与打开之间的竞态。不能静默省略这项语义。

当前App持有lazy单例Room（InkWeftApplication.kt:16）；已查LibraryBackupRepository.restore(:170起)是同库事务合并，并非替换主数据库文件，所以普通恢复不天然要求永久重开。但这只证明已读的生产路径，不构成“所有close/reopen/文件替换都不发生”的契约。若必须在同一个collector存活时支持这些情况，应先给出可靠owner generation/终止-重启边界并加guard；没有此证据前，不建议将无条件长寿命handle作为已安全的生产修复。不能为了候选把实际支持范围缩窄成用户假设。

## 快照、线程与取消：可以保留，但顺序必须明确

- owner只在首个真正IO read时惰性open，沿用OPEN_READONLY和保留作者文件的corruption handler。保留每read自己的fresh CancellationSignal、fresh ReadContext、beginTransactionDeferred；readSnapshot的TEMP表/索引全部在该事务内创建，继续不setTransactionSuccessful，正常和取消都由endTransaction执行ROLLBACK。这样每次read独立WAL快照，idle时不保留事务。不能把事务一起提到collector外，也不能以CREATE IF NOT EXISTS掩盖旧temp表残留。
- BEGIN、所有cursor/execSQL、ROLLBACK必须在同一个非挂起IO代码段、同一线程执行。Requery用ThreadLocal SQLiteSession；不同read可以落到不同IO线程，只要前一个事务完整结束。无需新单线程executor。当前仅OPEN_READONLY、未加ENABLE_WRITE_AHEAD_LOGGING的Requery pool容量为1；不要为“优化”改flags或journal/PRAGMA。已有库支持跨线程使用handle不代表可以跨线程结束同一事务。
- LibrarySearchObservation的mapLatest已有“取消旧read并join后启动下一read”；核对固定coroutines1.10.2字节码确有Job.cancel→Job.join→新UNDISPATCHED child。仍须让每read的watcher结束/加入、所有cursor关闭和rollback完成，才清空Context并允许下一read；旧signal永不复用，迟到的旧watcher不能碰下一read的signal/context。
- owner close放在包含实际reads的结构化scope退出之后，finally以NonCancellable+IO收尾。不能只cancel child后马上close，也不能在read尚未join时由另一个线程关闭handle。回滚必须留在原read线程，不能把整个rollback搬到另一个NonCancellable dispatcher。观察流的错误映射、sourceVersion、generation和150ms debounce逻辑不改。
- begin/SQL/rollback失败或corruption时使该handle失效并关闭，下一次事件再按原安全路径重开；不能继续复用状态不明的连接、不能自动吞错重试同一查询。取消仅在所有cursor/rollback已干净完成时才可留用handle；rollback/close错误保留原异常关系。新连接仍不得使用默认删库corruption handler。

## 最少需要的原生guard

1. 同一collector连续多个成功/取消read，分别在connection-open、snapshot-open、drafts-ready、scope-start/end、hits-ready、page SQL中打断；下一read必须成功、看见之后提交的作者状态，TEMP表/索引无残留、无事务泄漏。现有LibraryPagingTest:108测试的是direct新连接，**不足以覆盖复用**。
2. fresh query/draft/Unicode/title sort/blank folder/allowed分类绑定：已取消A的callback不能影响B；包括diagnostic observer开关、至少数百次read后函数集合/owner状态仍有界、旧request/Job不会被保留。
3. 两个并发collector＋并发direct，各自query/drafts/signal隔离；取消其中一个不终止另两个，direct依然新开/关闭。强制连续两read在不同IO线程执行，并验证每一read BEGIN/END同线程。
4. collector在read、UDF、BEGIN后、rollback附近被取消/下游抛错：close只在owned reads/watchers实际join后发生；native正在执行时不得抢先close；下一collector能正常打开同库。
5. 单个read内快照稳定，read间看到save/page source/card/trash/category变化与完成索引；长时间idle handle不持有WAL reader快照，作者save/checkpoint仍能推进。保留原1024/WAL/FULL设置和保存幂等/撤销守卫。
6. owner DB close/reopen、collector结束后同路径替换、同collector存活时合法文件替换、query/rollback失败和corruption后恢复：不能读旧文件、不能删除作者DB/WAL/SHM、不会错误宣称完成。需要生命周期契约/实现支持，否则此项是落地阻碍，不能跳过。

候选评价首先证明实际open/close次数降低、无活read/context残留，再用相同数据/设备、原seed/阈值比较OBSERVE保存p50/p95/max及真实SQLite END wall/同线程CPU。direct作为不变对照。不能把“26次open变1次”替代保存性能通过，也不能减少每次快照/rollback或改变取消频率来制造收益。

源码入口：LibrarySearchRepository.kt:40–52（每collector调度）、:54–120（每read当前连接/函数/signal/transaction）、:127–170（TEMP对象）；LibrarySearchObservation.kt:25–44（mapLatest/错误映射）；PreservingOpenHelperFactory.kt:44–77（安全open/close）；LibraryPagingTest.kt:108–142（现有direct取消边界）。实际依赖hash见dependency.json，字节码仅本地读取，不是原生执行。
