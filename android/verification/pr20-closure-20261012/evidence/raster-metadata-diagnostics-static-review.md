# 局部绘制诊断静态复核

静态审阅通过，无阻断；**尚无原生诊断结论，不表示回退已经定位或解决**。只读两份候选源码、补丁与已有基线应用回执；未修改源码、构建、运行 ADB/测试或提交。父侧正在负责构建和后续串行原生验证。

| 绑定对象 | SHA-256 |
| --- | --- |
| InkRepository.kt | `aebfc90c0a9354a15995b47ed0f9d62037adb9009ecfb5fbf21c7749d7e0c7ea` |
| TileLocalRedrawTest.kt | `31325f357e719d4fe61354645f499ce06ecf8079e64fddb471c069f44ad3f5ec` |
| raster-metadata-diagnostics-portable.patch | `1fc0348e2b4aae0ecf38cace3959fad5c4798871acee6ec1aac7d46452883d08` |

- 与 HEAD 比较，原 **18 行含 assert 的源码、3 行含 withTimeout 的源码逐字节相同**；这些是源码行数，不把一行多个断言误报为单个调用。SpatialIndex 无改动。精确行组哈希见同名 JSON。
- 默认 null 观察器不创建 probe/记录，也不执行安全赋值右侧的 clock 调用；该短路行为已核对 [Kotlin 官方说明](https://kotlinlang.org/docs/null-safety.html#safe-call-operator)。CPU 仅首尾线程相同、两个采样及差值均非负时输出，否则为 null；它是线程累计 CPU，不是协程独占 CPU。
- admissionAndPreparation 包含准备、yield、Room 调度及事务取得，不能称纯锁等待。queryAndDigest 与 hexAndResult 足以定位大段；后一段仍含 SHA finalization、原 Formatter 和结果封装，表达式与顺序未变，不能仅凭其变慢便独归因 Formatter。
- 成功退出 SpatialIndex.read 后才调用 observer，位于事务外；不吞异常或取消。Tile 仅局部追加启用，COW 内存收集，finally 清除；JSON/状态输出在计时外。启用诊断仍有观测开销，不能直接替换默认关闭的性能数值。
- preparationRows 是已有 onBatch 工作行累加，可含 hints/cuts 和重复身份；不是唯一笔数。可选 nativeStrokeBuilds 在计时外读取已有 getter；基线 getter 缺失时不输出字段，绝不补零。

已读父侧 `baseline-raster-diagnostic-patch-application.json`：记录零模糊应用成功、734 项中仅指定两路径变化；本复核未重新扫描全树或再次应用补丁。后续须用绑定源码/APK的原生数据判断 hex/result 热点或 metadata 等待，原时限和验收缺口继续保留。
