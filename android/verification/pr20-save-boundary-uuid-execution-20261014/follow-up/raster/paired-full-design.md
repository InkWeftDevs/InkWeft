# 八层 forced-full：最小成对归因与关闭路径（只读方案）

结论：推荐先做 **16 次具名调用：8 个成对诊断 + 8 个候选正确性守卫**。诊断不能独自关闭公共性能门。若找到具体多余工作并形成最小修复，再做 **8 个原公共比较 + 同8守卫＝16次** 的一次性验证；不靠重跑未改源码、挑样本或改阈值“跑绿”。本方案没有修改源码、生成控制器或启动CI。

## 现有覆盖，避免重复加钩子

原公共 `KvmLocalAppendPairProbe.kt:137–145` 确实先测local、再测full，但 `metadataMs/rasterMs/publicationMs/nativeStrokeBuilds` 全取 **active append**；`forcedFullCompleteMs` 和 full decoded/rebuild 才属于 full。冻结探针SHA256仍是 `a916d2470d54f069d1d6071d0b56a15ac6b07d05ab0b033d0aa7d4420fa74338`，不能修改。

`RasterCausalDiagnosticsTest.kt:148–181` 已对 first/local/**full**/hidden-local/hidden-full分别打开观察。`:213–232` 输出role/phase/layer的frame、metadata和read记录；不是只有append。其result对象的三个阶段汇总字段仍来自active，分析full必须选 `role=full, phase=full, layers=8` 的8帧，不取result里的append字段。历史seed4101的1/4/8层逻辑hash与公共C1记录逐一相等，八层为 `0df59d18c4c4dea150455471d713e6110524e93bbb78b82d0389c1ea29e36990`。现有缺口是 **B侧同口径观察 + 四seed成对数据**。

可直接复用：`RasterFrameDiagnostics.kt:10–18,35–61` 的request/worker/metadata/raster/prepare/publication/notify/ready/finally端点，以及同步native构造wall/同线程CPU；`InkRepository.kt:69–95,383–424` 的默认null metadata observer、准备批次/行数、query/digest和hex/result的wall/CPU；已有readObserver的freeze/decode/entries/payloadBytes/candidateRows。无需先增加计时框架或renderer缓存。所有跨挂起、跨线程差值只叫wall区间；层区间会重叠，不相加当独占时间，不将native以外raster残差叫CPU或锁等待。

## 精确来源与最小源差异

诊断比较固定原 **B a128bb07**（保留已披露的7949011安全open-helper覆盖）与 **C1 cb7e1421** 的生产代码。最新e310严格Job测试及共同诊断测试仅作为明确列出的test overlay；不能把当前整仓新提交冒充原cb7。之后验证修复时另外固定C2。

B缺少观察API，需在隔离的诊断源码Bdiag中仅做3个生产文件的观察性覆盖：新增原样 `RasterFrameDiagnostics.kt`；在B的 `StreamedInkRaster.kt` 按现有语义端点插入默认关闭的同款钩子；在B的 `InkRepository.kt` 加同款metadata类型/observer及时间/计数读取。B的SpatialIndex.read原本已有onBatch，无需改它。**禁止整文件用C1替换B**：B必须保留无geometry cache、原union扫描、eager Pencil、每tile内部原生构造，以及原 `joinToString { "%02x".format(it) }` hex转换。native计时必须包住B原每次InkBrushes.stroke调用（B文件:144），不能移出tile循环；C1包现有每stroke一次构造（当前文件:173）。Bdiag应明确标为带观察覆盖的诊断身份，不能充作原未改B的公共验收APK。

C1生产无需新增钩子。共同诊断测试最少改一份文件：`:24` 的4101限制改为固定4101–4104白名单；建议同时增加一个test-only、逐行有界缓冲的完整ARGB像素SHA256，在cold完成且`:192`同帧sameAs通过后、`:196`clear/隐藏前计算并输出。width/height与ARGB字节顺序固定，两端hash必须相等。这在所有计时之外，不改变公共探针；新增字段进入事先冻结的诊断校验合同。原full总decode/rebuild计数已存在，无需给B伪造BuildStats.nativeStrokeBuilds（原字段缺失仍为null，真实native次数来自新frame观察）。

## 夹具与守卫

每次诊断完整保留 `for(count in listOf(1,4,8))` 的全部建库、first、append、full、隐藏、cleanup流程，使用各自新进程和同一seed的独立Random。不能只运行listOf(8)，也不能仅补耗随机数：数据库名、workspace operationId、layer/stroke/append/hide command ID的流和前两组实际DB/JIT/缓存前置状态都必须保留。原1024-byte/WAL/auto_vacuum=FULL/synchronous=FULL、8张逻辑表hash、固定两列时间规范化保持；两端每seed三组fixture hash全部一致。full raster始终独立新实例、不能共享active geometry，所谓full/cold是完整光栅重建，不是冷OS或冷DB。

保留3000×2000、viewport(1500,1000,1)、原crop/budget、320→321笔、层成员/最后锁定层、authoringRevision、合法first-layer scoped commit；完整像素sameAs、隐藏等待时旧像素不得出现、隐藏后完整像素相同、内存预算和decode/rebuild断言不变。成对诊断的每次draw在Main前后捕获真实Job，包括superseded；finally NonCancellable取消/join所有已捕获Job后，才关闭位图/DB；必须有完整WORKER_FINALLY/READY，dropped=0、无FAILED、全局worker/geometry归原基线。

候选守卫8个（沿原方法，不新增性能门）：TileLocalRedrawTest的append跨闲置层revision/破坏性更新、整数pan与zoom预览不得完成capture、打断pan等待后strict join（3）；StreamedInkRasterTest的pending transform clear、omitted stroke延迟替换（2）；NativeStrokeTileReuseTest四阶段旧逐tile oracle、RasterPerformanceProbe全部14个整数/分数平移缩放和分散追加、StreamedInkRasterTest原1201笔dense（3）。后3个用e310真实Job清理。dense仍原30秒帧门，5秒释放含clear/join更严格。native oracle覆盖PEN/HIGHLIGHTER/PENCIL、finite-paper/crop/erase/allowed/omitted，不能以只跑八层静态图取代这些守卫。

## 次数、顺序与预算

| 阶段 | 固定调用 | 用途 |
|---|---:|---|
| 归因 | Bdiag/C1 × seeds4101–4104 = 8，另C1守卫8，总16 | 两端同观察开销；每调用保留3组/65帧，总520帧，其中八层full64帧 |
| 修复后关闭 | 原受控B/C2 ×四seed公共调用8，另C2守卫8，总16 | 原公共探针和分类，观察默认关闭；此时B不带新增诊断覆盖 |

沿原相对次序：4101 B→C，4102 C→B，4103 C→B，4104 B→C；预先固定，不根据结果调序。每次一个确切JUnit方法，1/1通过且无skip、进程自然退出；失败保留部分记录并停止，不重试或动态减样。全部1/4/8层数据保留，八层full仅是预先指定分析项，不删附带慢结果。

单阶段仅2个app身份、各target+androidTest共4个真实APK，无需data-local测试APK。历史单端全1/4/8诊断官方step为8秒；本地旧三端runner每身份构建约8–11分钟。**估计**单阶段构建20–30分钟、启动/安装/16次调用约5–8分钟，约25–38分钟总计；这不是实测保证。继续原标准runner/API35 ATD x86_64 KVM 2vCPU/2GiB/1920×1200/dpi240、30分钟构建/45分钟方法/55分钟停止工作/60分钟job封顶；超限失败，不扩时。最小UID ACL精确恢复、真实官方native依赖/签名/安装字节/source/fixture绑定、全部partial原文和≤20MiB/3天白名单文本约束不变，先做最大输出形状离线检验，不能截断凑上限。

若强行同一轮带8个公共调用与8个诊断＋8守卫则24调用，但为保持原B公共身份还要第三个Bdiag源码构建/安装身份，成本更大，且无已识别修复时仍不能靠新计时宣布旧信号消失，故不推荐。

## 判断边界

保持原规则：≥3/4同向变慢且paired median相对>10%、绝对>5ms才是signal；若存在个体超原双带但不满足signal，仍INCONCLUSIVE，不等于通过。诊断先回答full的读取/metadata/native/prepare/publication/notify哪项增加；若只看到跨线程等待、没有可识别多余工作，继续记未归因，不能称噪声。形成修复后必须由未改公共探针对B/C2按原规则给出WITHIN_PROPOSED_BAND并通过全部守卫；信号历史永久保留，不和新run合池。仅观察未改C1的后次结果较快，不足以声称找到或修复原因。
