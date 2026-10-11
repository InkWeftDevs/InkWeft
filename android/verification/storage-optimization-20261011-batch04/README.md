# 第四轮存储优化：空间读取与图层归属分行

基点为 V86 `8596a981a76e27c38caf5220659e0e238141d319`，叠加前三轮补丁及本轮改动。产品版本号未变；本目录记录独立验证包，没有产品安装或发布。前三轮证据保持冻结。

## 当前存储约定

Room18 增加 `page_layer_memberships`：页、内容种类、内容 ID 为主键，非空 layerId 表示归属，空值表示作者删除标记。`canvas_authoring` 的 PAGE payload 仅保存图层配置、留白和有独立预算的批注；批注归属与批注字节仍放在该头内。MAP 保存方式保持。

普通笔迹保存只查询受影响 ID 和新 ID 的归属，只插入新成员；新增笔迹、页版次、图层归属和 durable receipt 共用作者事务。首次隐式创建图层头后发生冲突也会整体回滚。图层修改、完整读取、复制、撤销恢复、备份与旧格式迁移重建完整状态并核对引用。

完整图层状态取消原来的成员数和整页编码字节门槛；旧 `1,900,000` bytes 和成员数阈值仅决定兼容编码表示，不决定当前页是否可继续编辑。批注、单笔和单次操作预算仍各自生效。待确认作者状态取消旧 4 MB 整文件门槛，仍验证长度、格式和 SHA-256。备份取消固定 500,000 行门槛，继续受实际输入长度、归档总字节、字段和校验和约束。

空间索引由 SQLite RTree 派生笔迹范围；编辑窗口加载有限几何，完整可见笔迹按批流式遍历。验证完整历史时仍检查隐藏笔迹与掩码。复制、全页范围和导出必须从完整数据读取，不能把窗口中的一部分当作整页。密集窗口的完整绘制与界面生命周期由应用层回归单独记录。

数据库使用固定 Requery SQLite 3.49.0，继续通过 `SupportSQLiteOpenHelper` 接入 Room2.8.5。系统 SQLite 可能未启用 RTree，首次模拟器运行已实际暴露这种情况；随包原生库提供确定的模块能力。新库在WAL开启前使用4096-byte页；已有库（包括1024-byte页）保持原页大小，不执行VACUUM或重写原库。临时空库完成后原子发布，发生并发创建时不覆盖已存在路径。损坏数据库仍抛错并保留原文件，`noBackupDirectory` 路径与事务、迁移入口保持。arm64-v8a 与 x86_64 原生库的全部 ELF LOAD 段通过 16 KB 对齐检查，实际测试 APK 中的库与预检 AAR 摘要相同；这属于二进制检查，不代替 16 KB 物理设备验证。

## 格式影响

Room17→18 和支持的旧备份在事务或隔离 staging 中拆出归属行，保留内容身份、图层状态、版次和作者回执摘要。新备份覆盖新表。新 PAGE 头采用 IWH1/IWH2，完整交换状态仍采用 IWA3/IWA4。

旧安装包不能直接读取 Room18 数据库或新 PAGE 头。回到旧安装包需使用升级前备份在独立环境重建，不能覆盖降级后的数据库。本轮没有删除用户资料。

## 验证入口与证据

核心 JVM 回归 **469/469 PASS**，data-local 主机回归 **10/10 PASS**。Android 存储验证累计99个不同方法通过，其中最终页大小修正后定向8/8通过；选择已按实际 `@Test` 方法冻结；设备结果另见本目录 `device-results.md`。实机手写、泄漏和性能测量保持待测，模拟器结果不代表性能提升。

在 `android/` 运行：

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' --init-script verification/storage-optimization-20261011-batch04/test-application-id-r4.init.gradle :data-local:testDebugUnitTest :data-local:assembleDebugAndroidTest
```

测试包与产品和旧测试包隔离，实际身份和 SHA-256 见设备结果。本机审计归档为 `E:/Inkweft/archives/2026-10-11/InkWeft-Storage-Optimization/batch-04`，包含源码、构建输入、APK、主机 XML、原始 runner、逐方法时间和清理回执。初次 core 测试暴露 96 MB 笔记导出重复缓冲的内存峰值，改为原件流式写入后在相同测试堆配置下通过；失败 XML 保留。Windows 编译 JAR 文件锁已定位到本轮空闲 Gradle daemon，单独结束该进程后采用单次 daemon 构建。

LeakCanary 和 Macrobenchmark 的构建、签名及依赖隔离入口见[工具说明](../../macrobenchmark/README.md)。物理设备按[真机清单](../../DEVICE-TEST-CHECKLIST.md)执行，并使用[结果模板](results-template.md)追加记录。
