# 最终绘制验收独立复核（hex-v1 原生阶段）

**结论：固定 9 次原生调用已全部结束，17 项执行、15 PASS / 2 FAIL；不能宣布绘制或整体任务验收完成。** 本次仅复核既有小日志、回执及相关源码片段，未操作设备、构建、测试或源码。精确数字、输入哈希和逐次回执见 [JSON](final-raster-acceptance-review.json)。

| 固定组 | 结果 | 可证明范围 |
| --- | --- | --- |
| Tile 诊断开启 | 2/2 | 独立诊断样本，不并入关闭诊断性能组 |
| 关闭诊断 C/B/B/C | 7/8，严格配对 INCOMPLETE | C2 原 pan/zoom 30 秒失败；三个 localAppend 层数仍有完整像素证据，须标注失败前置，不能作为正常预热区组替代失败 |
| FrameProbe 两端 | 各 1/1 | 各 14 帧与本版本冷重建像素一致；不是跨版本图像哈希比较 |
| NativeStrokeTileReuse | 1/1 | 四阶段 native 构造 2/2/1/0，对照同进程逐瓦片构造 24/32/16/0；全像素、擦除、裁剪和预算断言通过 |
| StreamedInkRaster | 3/4 | 258 笔小规模完整帧及取消/遗漏隔离用例通过；1,201 笔原规模失败 |

258 笔 / 8,226 点用例在 12,755.215927 ms 发布完整帧，两批 256+2 行，明确到达 `verified-and-released`，因此其末批像素和严格 worker/geometry 释放断言已执行。NativeReuse 的 `finally` 使用 `withTimeoutOrNull`，该 1/1 结果本身不额外证明严格释放。

原密集帧保持 1,201 笔 / 76,802 点及 30,000 ms 门槛，在 30,222.207101 ms 记录超时：三批各 256 行，共 768 行，0 change callback，`pending=true`、`frameReady=false`、`publishedStats=null`。这些是已解码 snapshot 行，不能推断全部 768 行已完成原生绘制。单次 worker 栈位于 Brush 构造，Main 在 `nativePollOnce`；这不是全耗时剖析，不能据此把失败全部归于 Brush、native 复用或模拟器。记录的资源为 1,848,576 / 134,217,728 字节，仅说明该 tracker 快照低于预算，不代表完整进程堆或释放成功。超时重新抛出，末批像素、全行计数与严格释放断言均未执行。

C2 pan/zoom 仍无法定位到初始、pan cached/cold、zoom cached/cold 五个 `ready` 中的哪一个，最终 `TILE_PAN_EVIDENCE` 缺失。`TileLocalRedrawTest.kt:47` 的 clear 取消但不等待 worker；故 C2 后续 localAppend 即使像素通过，也不能反推没有清理重叠。严格 parser 的 INCOMPLETE 及全部原值保留，不挑选成功尾样本或替换 C2。

## 四个仍未闭合的原用例门槛

| 门槛 | 最后证据 | 当前最小闭合要求 |
| --- | --- | --- |
| 20,000 页搜索：128 条 / 10 秒 | v4 FAIL；本轮未重跑 | 原门槛及后续 Main/索引就绪、latest/清空/分类断言完整通过 |
| 写入期间取消备份：15 秒 | v2 FAIL；本轮未重跑 | 原窗口内进入 partial 写入并发出取消，随后清理、作者内容与重开断言通过 |
| 1,201 笔密集帧：30 秒 | 本轮 hex-v1 再次 FAIL | 原规模全行、完整帧、末批像素及严格释放通过；小规模不替代 |
| 原 Tile pan/zoom：每个 ready 30 秒 | 本轮 C2 FAIL，阶段未知 | 原完整用例按原门槛通过；任何另行授权的后续通过也不删除本次固定区组失败 |

**局部绘制性能回退还未正式验收。** 新 FrameProbe 初始帧 13,555.015→13,824.332 ms（+1.99%），12 种不同变换的描述性中位 6,410.023→5,730.360 ms，分散追加 2,170.598→1,511.899 ms；这些是独立通过的证据，不能补齐失败 Tile 配对，也不能仅凭 hex 阶段下降关闭原单层慢样本。保留旧 v4、重复样本、diagnostic-v1 编译失败、diagnostic-v2、新 C2 和旧/新密集帧失败，各阶段不混合。

结束后的 `candidate-raster-hex-v1-final-process-review.json`（03:50:10 UTC）确认 app/appTest 的 pidof 均 exit 1、stdout 为空，未请求 force-stop。这证明当时目标进程已不存在，**不证明失败用例正常执行了严格释放断言**。最后两组各自前后配置哈希均为 `425d701e…`；仍仅为软件 Android API35 x86_64/TCG/SwiftShader 证据，不代表物理设备、CI 通过或失败根因。

本轮已测候选为 app `d4af1610…` / appTest `5ce3e010…`，基线为 `baseline-raster-diagnostic-v2`，完整身份取既有回执。本报告不把父侧正在构建的 KDoc 单行修正当作新原生验证；该后继构件是否与已测 APK 字节相同，应由独立构建/哈希回执确认。历史 `remaining-acceptance-gates.md` 的 v2 dense 最后运行及三门槛描述不再适合作为当前状态：最终摘要应引用本次 hex-v1 dense 结果、加入 C2 门槛，并保留搜索/备份未重跑的准确身份。
