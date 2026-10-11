# 第四轮 Android 存储集成验证结果

日期：2026-10-11。MuMu12 独立 `InkWeft-Test` 实例 5，Android12/API32、x86_64，root=false。仅安装专用 instrumentation 测试包，产品和已有 workspace 包保持原状。结果属于模拟器功能验证。

累计 **99/99 个不同测试方法 PASS**。其中页大小修正前98项通过；最终source-r5仅修改新库初始化后，定向重跑工厂与空间存储8项全部通过（包含1项新增测试），未重复整套97项。最终逐方法APK绑定见本机归档 `batch-04/verification/final-data-results-r5.json`，修正前汇总原样保留。先行 SQLite/RTree smoke 另为 7/7 PASS，包含在集成集合中，不重复计数。核心 JVM 469/469、data-local JVM 10/10 PASS。

| 执行 | 方法数 | 结果 | runner 耗时 | 原始证据（归档 verification 下） |
|---|---:|---|---:|---|
| 原生 SQLite 与空间存储 smoke | 7 | PASS | 37.179s | `device-attempt02/smoke/` |
| 全部选定存储集成 | 97 | 92 PASS / 5 FAIL | 355.451s | `device-attempt02/integration/` |
| 5 项迁移版本断言修正后复测 | 5 | PASS | 2.484s | `device-attempt03/` |
| 300MB 原件导入、复制、完整备份恢复 | 1 | PASS | 801.226s | `device-attempt02/large-original/` |
| source-r5 新库4KiB及空间存储定向回归 | 8 | PASS | 50.506s | `device-attempt04/` |

5 项失败均为测试仍断言当前 schema17；实现升级为 schema18 后，将这些当前版本断言改为18再复测，测试数据中的旧版本和损坏迁移断言保持原样。两包生产代码相同，不重跑已通过92项。五项分别位于 `PageObjectStorageTest`、`PageObjectRepositoryTest`、`StudyAndSelectionRepositoryTest`、`CardTransformRepositoryTest`、`RecallStudyRepositoryTest`。

大原件用例实际完成300MB原件导入、复制及完整备份恢复，并核对原件内容。其运行与应用编译/lint有主机CPU并发，耗时不是可比较性能分数；附加双驱动小型BLOB探针另存归档，不用模拟器计时推导真机性能结论。

## 关键覆盖

- SQLite RTree 查询与更新、事务回滚、reopen、损坏文件保留以及 noBackupDirectory 路径。
- 60,000项历史归属后新增笔迹仅写受影响成员，失败事务无孤立归属；首次无效写入不提交隐式作者状态。
- 归属头、作者删除标记、复制、备份/恢复与 schema17 迁移；待确认作者状态文件超过旧4MB阈值仍可精确恢复。
- 页对象、图层、映射、批注、学习、只读保护、原件流式保存与完整快照回归。

## APK 与输入

R2完整数据包 `org.inkweft.data.storageopt20261011b4r2.test`，SHA-256：

```text
1172d1699be7d1f9efcc97d89650f4057a96e7f03281076dd4c68d1d323fa742
```

R3五项修正复测包 `org.inkweft.data.storageopt20261011b4r3.test`，SHA-256：

```text
a7e2d8abadd8f0226459ca7f8e0e352fbbfa387e24d191c77f8c56d7b1cd2729
```

R2数据源码与R3修正源码分别冻结在 `source-r2/`、`source-r4/`；初次应用与工具候选的生产输入冻结在 `source-r3/`，最终工厂修正后的输入见文末 `source-r5/`。输入清单、签名、APK的ZIP/原生ELF16KB对齐证据与摘要见同归档 `verification/apk-identity-r2.json`、`apk-identity-r3.json`。64位库对齐通过仅属于二进制检查。

## 首次失败与清理

首次采用系统 SQLite 的测试包因设备系统 SQLite 没有 RTree 模块失败，96项原始结果保留在 `device-attempt01/`；后续采用固定 Requery SQLite3.49.0 后按上述范围通过。失败候选不作为交付通过依据。

R2与R3运行结束均卸载本轮专用测试包，关闭实例5，保持root=false和原旋转设置，并停止私有ADB55099/55101。清理回执分别为 `device-attempt02/cleanup.json`、`device-attempt03/cleanup.json`。原始runner、逐方法时间、失败原因、源码、APK与构建日志分类保留，前三轮归档不变。

真机手写、泄漏与Macrobenchmark性能项目见[真机清单](../../DEVICE-TEST-CHECKLIST.md)，保持待测。source-r5最终应用12项UI回归已通过（41.008s），其冻结APK、原始runner、逐方法结果与环境回执在本机 `batch-04-app-r2/verification/` 独立记录；最终工具功能冒烟1/1、权限拒绝和LeakCanary初始化亦通过，详见[工具记录](../storage-optimization-20261011-tools/README.md)。

## 最终新库页大小修正（SO4-SQLITE-01）

固定Requery3.49.0默认新库页大小为1024。32MiB三路径正反顺序对照显示本机native1024在事务写和顺序扫描的中位耗时分别为native4096的1.52倍和1.53倍，主键读取没有相同方向差异；完整摘要均一致。这只支持此局部配置修正，不说明300MB整合耗时成因或真机性能。原始对照、主机并发记录和清理回执见 `verification/blob-probe-r3/`。

最终工厂仅为不存在的数据库路径建立4096-byte空库，原子发布后交给Requery开启WAL；已有库保持原页大小，不使用VACUUM。source-r5工厂两项验证覆盖新库4096/WAL/RTree/事务/reopen/noBackup、已有1024库与512KiB内容、损坏文件字节保持，以及临时种子集合前后相同；另6项空间存储回归通过。data-local主机10/10再通过。

最终定向包 `org.inkweft.data.storageopt20261011b4r4.test`，SHA-256 `e5fd559b2cb0a5bcb336bb02083270b046e87123a65199a024a41cdde4a8c709`，绑定 `source-r5` / `build-inputs-r5.json`。独立包已卸载，MuMu5关闭、root=false/旋转原值保持，私有ADB55117停止；见 `device-attempt04/cleanup.json`。真机SO4-SQLITE-01仍待测。
