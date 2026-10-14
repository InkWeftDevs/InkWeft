# library_scope WITHOUT ROWID 窄范围评估

结论：对于当前合法数据，单独把 `CREATE TEMP TABLE library_scope(id TEXT PRIMARY KEY)` 改为 `... WITHOUT ROWID` 未发现确定性语义阻断，能够消除该临时表的 rowid/主键两个实体B-tree中的一个。**本轮不建议将它作为已有证据支持的保存延迟修复直接并入**：500行host对照绝对差仅约0.04ms，原生JNI/Room/并发保存收益仍未知；既有FULL scope区间73.294/68.747ms不能直接归因该B-tree成本或写锁等待。未修改任何生产或测试源码。

## 语义与边界

- `LibrarySearchRepository.kt:161` 仅存 `shelf` 的id集合；三个消费者为page/card来源筛选与分页的 `IN (SELECT id FROM library_scope)`（约184/196/216行）。没有读取rowid、last_insert_rowid、插入顺序或该表的update hook。输出页有显式pinned/title/updatedAt/draftPosition/id排序；page命中最终按position/id挑选，card命中仅存在性，因此主键存储遍历顺序不构成UI语义。
- Room导出的notes.id与notebook_workspace.noteId是TEXT NOT NULL主键；draft.base由 `Note` 的非null String与UUID校验产生。saved与draft join均一对一，新增draft分支排除已存在note，scope不会合法地产生NULL或重复id。两种结构均维持TEXT affinity/BINARY键比较，不把Unicode标题/分类改成SQLite ASCII排序。
- 重要差异已实测：普通rowid表的TEXT PRIMARY KEY可接受NULL，WITHOUT ROWID隐含NOT NULL并拒绝NULL。该差异只在违背现有合法输入/Room schema时可达；不能泛称所有SQL输入等价。重复主键两者仍失败，未建议OR IGNORE或改变冲突策略。
- proposed只改单个CREATE末尾，仍在同一私有reader/deferred事务中；前后 `check()`、CancelSignal watcher、finally endTransaction与connection close都不动。它**不会**让现有单条execSQL内部获得新的协作取消点，不保证减少取消后等待时长。没有跨连接临时表或作者写入行为变化。
- 当前 `LibraryPagingTest` 已包含空/非空draft的Unicode标题、pinned/updatedAt/id稳定分页与folder过滤，旧快照同时保存、source取消、新六stage取消后fresh作者/分类、Unicode空白分类。若parent以后采纳一行候选，需由parent按原门槛在原生Room重验；本次未执行这些测试，host不能替代。

## HOST_ONLY 对照

脚本 `host_scope_compare.py` 只新建Python SQLite `:memory:` 合成数据，无真实笔记/设备；使用当前生产原样shelf CTE与最新导出Room表结构，仅替换scope CREATE。SQLite `3.53.1`，4096页，与Android Requery3.49.0不同。DEFAULT与MEMORY各500/20000行，8对AB/BA交替；计时仅CREATE+INSERT SELECT，建fixture、验证、查询计划与dbstat均在计时外。连接/缓存重复样本有关联，只是描述性数据，不是独立验收样本。

| temp_store | rows | rowid中位ms | WITHOUT中位ms | 配对差中位ms | 配对相对差中位 | 更快对数 |
|---|---:|---:|---:|---:|---:|---:|
| DEFAULT | 500 | 0.696914 | 0.668313 | -0.045270 | -6.08% | 6/8 |
| DEFAULT | 20000 | 33.362757 | 29.254801 | -3.572544 | -10.94% | 7/8 |
| MEMORY | 500 | 0.667136 | 0.633556 | -0.038277 | -5.72% | 7/8 |
| MEMORY | 20000 | 39.551277 | 34.028918 | -3.852112 | -9.92% | 7/8 |

这里分别列两端中位与逐对差中位；没有把“中位之差”误作“配对差中位”。保存延迟、锁等待、WAL并发、JNI、Room调度或Android CPU均未测量。

物理结构（dbstat，排除其他临时对象）：500行rowid表7页+主键索引7页=57344字节，WITHOUT仅7页=28672字节；20000行213+247=460页/1884160字节，WITHOUT229页/937984字节。`PRAGMA index_list`两者仍显示PK名称，不等于两棵实体树；使用temp.sqlite_schema根页与dbstat区分。

两个结构的代表性 `IN` 计划均使用主键FOR IN-OPERATOR，notes主键查找与显式ORDER BY临时排序相同；不是生产全查询的原生plan断言。六组saved+existing/new draft合成结果（all/trash/unfiled/folder/favorite/tag）逐行相同，含Unicode标题/分类与分页顺序。host的Python Unicode辅助函数只做双方相等对照，不声称其与Kotlin所有Unicode规则完全等价。

## 证据绑定

- 被读取Repository SHA-256 `50089cb11edae66be4339a7258b96c7ac6b866b440255d48220aea7bf2fdb963`（包含并行matcher候选，但scope DDL仍未改）。
- 导出schema `/workspace/InkWeft/android/data-local/schemas/org.inkweft.data.NoteDatabase/19.json`，SHA-256 `45aeaac766d03ff5f466b4ef88b638ccecb64d16c39f39b35529d9c5b40df068`。
- `host-results.json` SHA-256 `c04b4f172d18ea3634708f03c726d63d30bb2c71065d58f7d1e2c85f6299474b`。
- `host_scope_compare.py` SHA-256 `7347008584107535a83bf1f0f62e192248f3f2e476b6f0e6357e0588b1c434ad`。

建议停在这一候选评估记录；当前收益不足以称解决FULL保存回退，也不建议把别的temp表一并泛化修改。若parent选择探索，应与matcher分别标识并保留未验证声明，不更改原公开探针/门槛。
