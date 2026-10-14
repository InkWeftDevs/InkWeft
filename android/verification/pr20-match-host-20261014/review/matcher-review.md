# ASCII起点matcher候选独立静态审查

结论：所审三文件未发现确定性正确性、取消或诊断路径分叉阻断。此结论只涵盖静态语义，未运行Gradle、JVM或Android，也不声明候选获得原生性能收益。

- helper只在query首UTF-16单元为ASCII时启用起点筛选。若text起点也为ASCII，A–Z加32折叠后的不等是忽略大小写不匹配的必要条件；所有非ASCII text起点均保留，继续调用与原实现同方向的 `query.regionMatches(0,text,at,query.length,ignoreCase=true)`。因此İ/ı/ſ/K或代理单元等不受手写Unicode表排除。
- 非ASCII query首单元直接委托原 `text.contains(query,ignoreCase=true)`；空query立即true、比text长立即false；扫描包含 `text.length-query.length` 最后合法起点。ASCII NUL、标点、字母、Unicode后缀仍按原regionMatches校验。
- prepared folded Int从本次request.query生成，仅每read一次。所有当前helper调用使用该同query值。helper不保存可变全局状态、不分配逐行集合或substring；默认UDF与开启profile的totals均调用同helper，不存在只优化benchmark的替代路径。
- Repository diff仅一次准备与五处调用/签名改动；SQL、FTS、UDF arity、source scan计数、flags、author snapshot、连接/事务finally及原ensureActive检查点不变。循环内部没有新增取消点，不能额外声称改善单次UDF内部的取消响应。
- 现有5个纯JVM语义方法包含全部ASCII对、BMP候选对i/s/k/z、Unicode跨ASCII等价、空/过长/NUL/未配对代理/补充字符、固定随机、长文本及最后起点。断言目标是与本平台原contains相同。Android实际regionMatches实现仍需原生Unicode/Room正确性验证；不把JDK通过推成Android性能。

性能边界：该候选只针对已观察的warm body contains CPU热点（由领域报告核数字）；FULL保存期间的读往往在warm page之前取消，不能据此声称修复observe保存p50。匹配首字符密度、非ASCII正文比例及query长度会影响收益，新增起点检查可能在高命中密度输入增加成本；没有通用加速保证。保留原公开探针和门槛，需候选原生验证后再报告收益。

所审SHA-256：

- `android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchMatcher.kt`：`dca79a9188799f608bec31c1d0c799d82490237347142b7f88c7490275793b3b`
- `android/data-local/src/main/kotlin/org/inkweft/data/LibrarySearchRepository.kt`：`50089cb11edae66be4339a7258b96c7ac6b866b440255d48220aea7bf2fdb963`
- `android/data-local/src/test/kotlin/org/inkweft/data/LibrarySearchMatcherTest.kt`：`0c97fa38e8b52810fbf0c3d61ca937791baf9191108eaf244fff775592931d8a`
