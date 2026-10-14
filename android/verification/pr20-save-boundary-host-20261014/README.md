# 保存事务边界：host检查，不是原生结果

源码固定为`4f41a501127dbed6dbef3519f93233a105b5d618`，六个源文件hash与第二次编译输入、该提交逐字绑定。只增加默认关闭的诊断边界，没有实施保存调度、失效刷新、持久性或检索语义修改。

| 检查 | 结果 | 实际范围 |
| --- | --- | --- |
| 首次数据层/AndroidTest源码编译 | PASS，187.052秒，5 executed/13 up-to-date | 初稿；后台任务尾部审查修复前，原日志保留 |
| 修复后数据层/AndroidTest编译＋定向JVM | PASS，191.011秒，9 executed/17 up-to-date | 六个输入hash始终一致 |
| SaveBoundaryDiagnosticsTest | 4项实际执行，0 failures/errors/skipped | hook的委托调用次数、上下文恢复、嵌套/异常/observer隔离；不执行Room或SQLite |
| 独立源码审查 | 无剩余源码阻塞 | [最终报告](review/final-review.md)，保留[首轮发现](review/review-compile1.md) |
| Android原生正确性/性能 | **NOT_RUN** | AndroidTest编译不能替代设备执行 |

生产null路径不创建诊断实例、不读clock或ThreadLocal，不发额外SQL；enabled路径委托同一Room串行executor与真实Requery连接。记录真正BEGIN/END、嵌套深度、成功请求及caller返回；SQLite END仍包含commit/rollback与连接释放，Room END还包含异步刷新请求开销，均不是纯fsync或锁等待。时钟、事件存储和JSON只在独立合成测试中；CPU仅能在相同threadId的端点间相减。

独立Android测试覆盖重放、冲突、回滚、排队取消、异常传播、observer失败/容量溢出和默认路径重开。性能诊断采用seed4101、原500本/20k页合成夹具；独立IDLE参照之外，保留DIRECT→暂停reader的单次保存→OBSERVE连续顺序，observe起始revision32、结束62。三臂各30次保存，诊断容量4096 boundary/2048 read events每臂；溢出或不完整失败，不删事件补成成功。默认未启用，不改原公共probe或四seed门槛。

首轮审查发现仅等待sample>=0会漏掉后台索引writer的末尾事件，已改为跟踪全部在途任务；事件发布后才移除pending，停producer/join后等待并核每个录制submit都有终结，暂停窗口后也等待再reset。没有改变Room生产调度。对于本次实际默认异步executor，返回任务正常配对；诊断并非任意inline executor的通用框架，相关范围见最终审查。

两次原Gradle日志、完整命令、JVM XML和审查均原样保存。`manifest.json`记录来源与六源hash，`SHA256SUMS`覆盖本目录其他文件。数据层本地离线编译使用已有JDK17/Gradle9.4.1/SDK缓存，无新CI或设备执行，无Docker/真实笔记/AniMemo访问。后续若运行原生诊断，另行绑定控制计划、源码、job和原始记录；本目录不关闭任何保存性能门槛。
