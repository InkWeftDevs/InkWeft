# 空草稿查询路径独立静态审查

结论：未发现阻断或确定性语义回归。仅核对本地源码与亲本差异；未运行 Gradle、SQLite、Android、CI 或网络操作，未修改源码。实际原生执行与性能收益仍未由本审查验证。

冻结输入：

- `LibrarySearchRepository.kt` SHA256 `aa441cab97464dd4372e940cdacc252c48d71d385412f93d592d5eff1c67c76f`。
- `LibraryPagingTest.kt` SHA256 `b05c4c93f2c0ed36204bb548c20f8d95c0dca5a35238e05361ac3655f6f9a061`。
- 当前 HEAD `68431ea1db07b105a3d93f6819268d8b29fd24bf` 中两文件均逐字节等于候选 d66b60d6 的已提交版本。亲本 SHA 分别为 `33b4e9cc4fcf3fac593f0abc5dc6e2c070e10603a179979ec57be2d554198f48`、`4deefcf739ebbdd1ead6e4e2259b72e4b5af07a122d2ff766ccff311f3e188de`。

SQL 作用域已逐处核对：

1. Repository 104–112 行仅在 `drafts.isEmpty()` 时定义具名五列、零行的 `library_drafts` CTE。非空分支的 TEMP 表结构、草稿插入顺序、revision/title/text/position 数据以及逐条取消检查原样保留。
2. 144 行 scope INSERT、193–202 行分页 SQL（含相关正文子查询及最外层 draft LEFT JOIN）、224 行 metadata SELECT 都以同一 `$shelf` 开头。因此每条使用 `library_drafts` 的语句均拥有自己的 CTE 定义，不依赖上一条语句的 CTE 生命周期；EXPLAIN 复用完整分页 SQL。page/card source 查询只引用已填充的 `library_scope`，不需要草稿关系。
3. 空关系的 LEFT JOIN 没有命中，COALESCE 仍取当前 notes 值；UNION ALL 的新增草稿分支返回零行。具名列避免调用方依赖 `SELECT NULL` 的自动列名。不存在草稿时，正文子查询与预览 join 同样回退到 notes；未把旧 UI header 当成作者正文。
4. 排序表达式、LIMIT/OFFSET/lookahead、主键稳定次序、workspace 分类、Unicode 大小写匹配、UTF-16 标题排序键和 SQLite 预览截取均未改动。CTE 不引入新的数据转换或非空草稿覆盖规则。

快照与取消：保留原独立只读连接和 deferred transaction，scope、hits、分页、目录与 pending 仍处于同一事务内。删除空 TEMP 建表没有新增事务、提前关闭连接或移出 snapshot 的作者读取；也不承诺 deferred transaction 在第一次作者读取之前已固定作者快照。入口及 drafts-ready 后的 `check()`、后续 SQL 边界检查、六阶段 trace、endTransaction/use/finally 清理全部未动。空分支不再执行建表，非空分支仍执行原逐草稿检查。

测试变化：同一既有分页方法新增 empty/nonempty overlay 两种分支，再覆盖 title/date 两种排序及 all/folder 两种筛选，逐页比较完整稳定顺序与去重，保留每页大小、正文空目录、预览上界和计划断言。样本包含 Unicode 大小写、补充平面字符、相同时间戳、置顶及新增/修改草稿。未修改 deadline、原断言或方法数。

补充覆盖边界：原有 tail-only 正文查询、作者更新新鲜性、Unicode blank folder、旧快照分类及六阶段取消测试均使用 emptyMap，因此其实际执行也会验证新 CTE 路径。最后 query-plan 断言仍检查循环最终的非空 overlay；本新增测试直接加强的是空 overlay 的结果与分页等价性，并未单独证明空路径查询计划或量化加速。编译/主机筛查通过不能替代这些原生 Room 测试实际执行。

## 最终列 affinity 补充复核

最终生产 SHA256：`99cdd6b82f48f3553577ae9fad7473019a77293f618df8f3c1dcb29650ad4571`。空 CTE 五列现在依次为 `CAST(NULL AS TEXT/INTEGER/TEXT/TEXT/INTEGER)`，对应原 TEMP 表 id/revision/title/text/position 的列 affinity，同时保留 `WHERE 0` 的零行性质。

将这一行替换回上文已审表达式后，整文件 SHA 恢复为 `aa441cab97464dd4372e940cdacc252c48d71d385412f93d592d5eff1c67c76f`，证明没有其它生产改动。测试文件仍为 `b05c4c93f2c0ed36204bb548c20f8d95c0dca5a35238e05361ac3655f6f9a061`。该补充消除未显式类型 NULL 与旧列 affinity 的差别，不改变 CTE 作用域或上述取消/快照结论；无新增阻断。仅做本地字节与表达式复核，未重跑构建或原生测试。
