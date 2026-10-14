# frame-local PencilTileRenderer 延迟构造独立静态审查

结论：冻结7行新增/4行删除 diff 未发现确定性语义、清理或异常处理阻断。仅审阅源码与现有测试，没有修改源码、运行构建/JVM/Android，也没有候选原生速度结论。

- `StreamedInkRaster.kt:152` 在原worker作用域将eager实例替为nullable局部；约170行仅在未被allowed/omitted排除、stroke bounds与dirty tile相交且pen=PENCIL时首次创建。PEN/HIGHLIGHTER、完全复用瓦片或无可绘制PENCIL路径不再无条件创建铅笔引擎。`InkBrushes.renderer()`与nativeStroke每stroke复用不动。
- unit仍为同一不可变factor/scale计算的 `min(.5f,1/(factor*scale))`，live=false，checkpoint捕获原worker context。没有跨帧/跨layer共享实例；同worker首次成功创建后，在所有后续tile/批次复用该实例，clear后也不置null、不重复构造。
- 原batch尾与最外层finally均保留 `pencil?.clear()`。未创建时没有该engine的Tile/coverage资源可释放；创建后的批尾或中途异常仍走原清理。`PencilTileRenderer.clear()`为同步、清空tiles/sources并将ownedTiles归零后释放原account所有权，反复clear保持原有行为。
- 首次构造现在位于 `painter.save()`后的try内部。构造或draw抛出时，原Canvas finally仍restoreToCount；`.also{pencil=it}`在draw前完成，故draw异常/取消时外层finally可见实例并clear。构造自身不创建Tile、不调用RenderResources.track/admit；若构造未完成，null分支不会遗漏已登记的Tile资源。原外层workerOwner释放、未发布bitmap回收、inFlight递减、generation/epoch校验和通知逻辑均未改变。
- `GraphiteMaterial.alpha()`每次由固定局部seed重新生成4096字节，没有共享随机序列；owner UUID仅用于资源所有权，不参与绘制。延迟构造不会改变混合笔刷的stroke顺序、裁剪/transform、grain取样、PENCIL像素或其余笔刷的native构造计数。

静态边界：nullable可变局部被callback捕获，不能宣称整个frame完全零新增对象；可证明的是避免未使用PencilTileRenderer及其grain/pixels等初始化。65个全PEN诊断frame对应65次原eager构造是源码路径推导，4096-byte grain+4096-int scratch计数也是元素容量计算，不是实测heap、构造计数、延迟收益或既有回退根因。

候选仍需按既有门槛验证：`NativeStrokeTileReuseTest.oneNativeShapePerStrokeMatchesPerTileConstructionThroughTransformsAndClips` 包含PEN/HIGHLIGHTER/PENCIL混合与pencil-only allowed、逐tile像素oracle；原integralPan混合PENCIL及cold像素对照、interruptedPan严格cancel/join，以及全PEN local1/4/8检查未使用分支。此次未新增测试、放宽timeout/assertion或声称这些测试在候选通过。

所审文件SHA-256：

- `android/app/src/main/java/org/inkweft/app/StreamedInkRaster.kt`：`c27b9f9d3326a625a9063b8fa8a446cc68296406b527a8254d8993dd41f44731`
- `android/app/src/main/java/org/inkweft/app/PencilRenderer.kt`：`838b07d7099a3bcbfff07c1767dbc0b626bb1b05dd8397511f1ee8f8f506f995`
- `android/core-domain/src/main/kotlin/org/inkweft/core/BrushRecipe.kt`：`7389abd4c25a9680d7e6692754a4cd884badd119a6b358ee49455da851dd0dea`
