# Candidate3 搜索取消边界只读审阅

结论：最终两文件补丁未发现剩余确定性阻断，可进入由 root 统一执行的验证。此结论仅为静态代码审阅，不代表编译、Room 原生测试或性能改善已经通过。

审阅基于主工作树 HEAD `dc16132047dedaca1520e05b58f5966aea35e1e6` 上两处未提交差异；未修改源码或测试，未运行 Gradle、ADB、原生测试或网络操作。最终读取身份：

| 文件 | SHA-256 |
| --- | --- |
| `android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchRepository.kt` | `33b4e9cc4fcf3fac593f0abc5dc6e2c070e10603a179979ec57be2d554198f48` |
| `android/data-local/src/androidTest/kotlin/org/inkweft/data/LibraryPagingTest.kt` | `4deefcf739ebbdd1ead6e4e2259b72e4b5af07a122d2ff766ccff311f3e188de` |

## 发现并已由作者修正

最初版本在 `beginTransactionDeferred()` 成功后、事务 `try/finally` 之前调用 `snapshot-open` observer。新测试的门控 `check(release.await(...))` 超时或其他 observer 异常会跳过显式 `endTransaction()`；外层 `use.close()` 仍会执行，但不应把它描述成正常事务收尾。作者已将该 event 移入同一个事务 try（生产文件第 86–88 行），重新读取确认修正存在。没有改变事务开始条件或连接使用范围。

## 生产路径

- 第 65–88 行在连接打开后、注册与开始事务之间检查取消。事务开始失败不会错误地调用 end；开始成功后的 snapshot observer/readSnapshot 异常和正常取消均经过事务 finally。外层 `use` 保持连接关闭；watcher 仍在 finally 取消，并受原 coroutineScope 约束。
- 第 101–147 行的新检查覆盖空草稿、scope 创建/填充和三条 hits 临时结构 SQL 的边界。`ensureActive()` 不挂起，不引入线程切换或额外事务，因此原 Requery 同线程 native 调用规则不变。
- `library_blank` 新增取消检查，原 Kotlin Unicode `isBlank()` 语义不变。SQL、过滤、排序、分页、author snapshot、正文投影和分类计算本身未改。150 ms debounce、失效表、索引构建与 sourceVersion 路径均未在差异中修改。
- 正常未取消读取新增的是有限边界检查及 blank UDF 的取消检查，没有新 trace、时钟或诊断分配。不能据静态差异宣称性能改善或既有回退已经关闭。

## 新测试的确定性与清理

`LibraryPagingTest.kt:106–140` 新方法逐一暂停六个边界：connection-open、snapshot-open、drafts-ready、scope-start、scope-end、hits-ready。每轮在读线程暂停期间提交真实 note 保存和 workspace 分类更新，随后取消、释放门控并 join，再断言没有进入后继阶段或 metadata-start；已进入事务的五轮还要求 snapshot-closed。

门控没有 sleep：进入用 CompletableDeferred，阻塞门用 CountDownLatch；主测试可同时执行 Room 写入。新建 note revision 为 1，各轮 expectedRevision=`index+1`、保存后 `index+2` 正确；workspace revision 是独立读取的当前值。fixture 的 `coroutineScope` 位于数据库 try/close 范围内部，作用域子任务结束前不会退出到 db.close。finally 先释放 latch 再 cancelAndJoin；正常测试取消路径不会因仍关闭的门而等待自己。

fresh read 复用初始旧 header/request，检查当前 revision、title、正文、folder、tags、favorite 及分类计数，确实能发现误用旧 UI header/body 或旧 snapshot 的结果。正文很短，预览截断不影响这些断言。事件列表只由这一读任务追加，在 join 后断言；没有并行 observe/index tracing 写同一列表。

## 必须保留的验证边界

- 原生测试尚未由本审阅执行。root 报告首轮构建读取了 snapshot-open 修正前输入；不能把该轮结果算作上述最终 SHA 的验证，最终输入须另行核验。
- deferred transaction 在首次读取主库时才固定 author snapshot；六处门控不能统一描述成“都已持有旧 author snapshot”。scope-end/hits-ready 位于 scope author SELECT 之后。
- 新检查保证到达检查点后停止后续工作，不保证正在运行的单条 `execSQL` 可立即中断。三个 hits execSQL 的内部边界和 unfiled/blank UDF 取消不是这六个 observer 门控逐条覆盖的内容。
- 新测试查询始终为 `body`，初始 page source 和后续 note body 都匹配。因此它证明后续返回 author 内容与分类新鲜，不单独证明“旧查询命中消失/新查询命中出现”；既有 source freshness 用例仍负责相关行为。
- 取消异常路径不会执行成功专用的 `connection-closed` trace；不能把该 event 缺失当作连接未关闭。这里的关闭依据仍是原 `use` 结构，而不是新测试的文件句柄或 native 资源实测。
- 六轮使用原明确的进入/保存/join 5 秒以及 latch 10 秒预算，没有通过延长既有门槛或删除断言获得结论。四组性能配对中的回退/不确定状态仍保留，后续不能以本静态审阅替代原生性能证据。
