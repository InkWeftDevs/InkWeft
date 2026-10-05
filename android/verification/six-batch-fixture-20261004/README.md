# 六批固定大样本与受控验收入口

最新状态（2026-10-04 16:28 UTC）：已实际运行云端样例，早期下文的准备记录只描述其标注源码。当前以 [status.json](status.json) 及 [45ccb定向实跑](../six-batches-v11-20261004/ci-37214898049/result.json) 为准。尚无同一最终源码的五阶段全通过证据，不能把不同源码结果拼作完整验收。新候选增加第7张回忆评分控件图，最终39张。

## 当前事实

本目录是验收准备，不是通过报告。固定提交2338ce0的app主和AndroidTest Kotlin已编译通过（3分57秒）；新增生成器与受控Android用例仍未运行。该次尚不含B4新UI/取消修复10eacce或B5数据3a6fc00；没有当前耗时、内存、截图、签名APK或真人证据。当前状态文件为 [status.json](status.json)。不得用准备工作填成PASS。

单一参数源：[six-batch-fixture-v1.json](../../app/src/androidTest/assets/six-batch-fixture-v1.json)。参数不可因超时、失败或设备不足而调小。每次运行记录原始spec的SHA-256及实际计数，持续复用同一runId和已保存资料；不清库重造“重开”结果。

- 主本：12页中文PDF，副本：1张知识页；两本最终各48卡，总96
- 主本44张原卡，经生产MERGE +1、SPLIT +2、SUMMARY +1得到48张；另一本48张。原卡全部保留，合并/拆分/总结及多来源历史真实存在
- 三图各120活动节点，共360出现位置，每图20条6级分支。统计已有SUMMARY自动节点，再按缺额填至120，不能硬加120
- 12条跨本引用：6条真实同步引用、6条卡片间跨本关联；同时含实/虚线、单/双箭头与关系注释
- 原长卡12000字符正文、4000字符注释；2个4096×3072不可变PNG原件，显示预览与原件分开；1份公式合成原迹
- 第12页严格1000筆，每笔100点，总100000点，使用4次生产批量笔迹事务；本页3层为基础900筆＋可见锁定100筆＋隐藏空层，仍1000筆全部可见，覆盖新分层渲染路径；这些是合成压力输入，不能证明真人笔感/掌触
- B4样本在第4页用12笔真实作者内容分配基础/隐藏/锁定3层，每层4笔；2段留白中1段已折叠。主图有1条绑定实际出现位置的原迹。数据投影与完整备份待运行，原生可见导出/交互仍待UI验证
- B5新增待编译数据链：主本12题配置问答/文字挖空/原迹遮挡三类型，生产API写两个合成UTC日共24次正式作答、1次临时练习及练习撤销；2份手写/25份文字答案，3次提示正式评分显式≤2，练习与其撤销不动正式排程。固定合成时钟起点2026-10-01T09:00Z，不改设备时间、不冒充真人经历
- 数据齐备时仅标FULL_SYNTHETIC_DATA_READY_NATIVE_B4_B5_REQUIRED；原生B4/B5完整操作和人工体验仍待验，不当作六批PASS

## 运行与安全门槛

1. 只能使用已经配置好的专属空白模拟器与本次主/测试APK，不启动设备、不安装、不清数据、不改KVM/VPN/系统权限
2. 运行器要求emulator序列号；测试再核验ro.kernel.qemu=1、显式dedicated-empty-emulator参数及安装APK的完整SOURCE_COMMIT。prepare还要求资料库为空；条件不符就停止，绝不自动清库
3. 第一次用prepare，后一次独立instrumentation用reopen；确认同一模拟器后只force-stop同包，启动新进程；不重新seed、不卸载/清空应用
4. 超时记TIMEOUT_RESULT_UNKNOWN；不宣布设备失败/成功，不自动重试作者操作。调查后保留同一fixture继续

示例（APK由集成人另行构建、安装，源提交必须匹配）：

```sh
python3 android/verification/six-batch-fixture-20261004/run-fixture.py \
  --serial emulator-5554 --package org.inkweft.app.a0.insertion --source-commit "$INKWEFT_HEAD_SHA" \
  --expected-app-sha256 "$APP_SHA256" --expected-test-sha256 "$TEST_SHA256" \
  --phase prepare --output android/build/evidence/six-batch-fixture
python3 android/verification/six-batch-fixture-20261004/run-fixture.py \
  --serial emulator-5554 --package org.inkweft.app.a0.insertion --source-commit "$INKWEFT_HEAD_SHA" \
  --expected-app-sha256 "$APP_SHA256" --expected-test-sha256 "$TEST_SHA256" \
  --phase reopen --output android/build/evidence/six-batch-fixture
```

这不是当前已执行的命令。CI的KVM授权/ACL清理由主工作流管理，本运行器没有相关权限操作。

## 观测边界

生成和验证只走生产import/workspace/ink/study/knowledge/transform/reuse/backup API；SQLite只读查询用于全表规范化哈希。记录每阶段墙钟耗时、阶段前后Java堆/native分配/PSS及实际计数，非连续峰值测量；不虚构帧率、热状态或百分位。

安装源提交、实际安装APK SHA-256及签名证书SHA-256由设备内读取；这是该自动化构件身份，不自动等于最终交付签名。每个步骤另记执行源码，便于后续在同样本上升级B4/B5。

受控链：12页资料→读/写→120节点大纲真实整支拖动→撤销/重做→卡片A的真实跨本关系预览→B→原卡暂不可用时留在B且返回记录不丢→恢复原卡后详情内可见的“返回关联前位置”→A（同一根层返回栈，不穿透对话框点击后方按钮）。过预算原件从公共保存边界拒绝，原对象和原件元数据不变。

完整备份经生产inspect/restore，第二次restore必须幂等；与归档规范化全表哈希对账，不只比卡片数量。恢复验证库只含刚生成的合成资料，验证后删除该派生副本，主应用资料及合成备份保留。PDF/原图/快照/卡片身份在同一runId下对照。

原生截图仅为待视觉审阅证据，不因存在截图或按钮可点即通过发现性。B4/B5未接入前本用例的baseline PASS也不能改写完整六批验收状态。

运行器在确认manifest.synthetic=true后，仅拉取固定白名单01–08八张PNG，记录字节数/SHA-256并标待视觉审阅；不导出整个files目录、数据库或原文日志。prepare与reopen之间不能清数据。CI的隔离合成库清理若确实需要，由外层既有prepare_case在prepare之前负责，运行器不承担清理。

包身份在执行前通过指定app/test两包的pm path和设备sha256sum核验；CI传入同次构建文件的--expected-app-sha256及--expected-test-sha256，不一致立即拒绝。仅记录路径/摘要，不拉取APK；冷重开还要求与同目录prepare回执两包哈希完全一致。sourceCommit最终由安装应用内BuildConfig检查，不能由命令行参数单方面证明；签名证书最终以主构建apksigner回执为准。

### 编译检查点（不等于样例运行）

- 固定源码：`2338ce0edbdcb413e4b787b4a56734cdea08fb39`
- 命令：`:app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`，JVM `-Xmx3g -XX:MaxMetaspaceSize=1g`
- 实际结果：app主＋AndroidTest Kotlin编译PASS，3分57秒；44 tasks（17执行/27缓存）。未执行JVM、Android、lint、APK打包或截图
- 日志：`six-batch-fixture-2338ce0-compile.log`，SHA-256 `db10a20097830d93e605e9e5385e3be65524b5cfdee1689526f8afd42f11f427`
- Runner宿主mock复核：双包hash不一致在instrumentation/force-stop前拒绝、Android `~~` 安装路径、冷重开同构件和历史4＋1固定PNG白名单均通过。mock不是Android结果


### 分组原生视觉取证（新增源码，尚未编译/运行）

同一prepare/reopen资料可另跑`--phase visual`，主/测试包hash必须与prepare回执相同。它不另造小资料：宽窗口1440dp/字体1.0与窄窗口375dp/字体1.6各拍12个真实生产界面。窄窗口是分屏可用宽度模拟，不是真机OS分屏验收。窗口、density、font_scale及连续翻页偏好在finally恢复。

固定白名单为`visual-{wide,narrow}-{stage}.png`，stage仅这12项：01-default-tools、02-advanced-pen、03-map-selected、04-outline-selected、05-long-card-title、06-long-card-body、07-long-card-annotation、08-long-card-source、09-page-layers、10-whitespace-expanded、11-whitespace-collapsed、12-bound-region-collapsed。正文/注释仍使用原12000/4000字卡片；图层和留白仍来自同一fixture。

按钮使用可见控件真实触摸；折叠批注区域后原位展开恢复并核验作者状态。图片只标CAPTURED_PENDING_VISUAL_REVIEW，须逐张实际查看密度、标题正文注释层级、选中反馈、截断和宽窄可达性后才可作视觉结论。B5遮挡作答截图另等持久回忆真实UI冻结后接入，当前明确PENDING_B5_UI。

### 原生帧与图层证据门槛修复（源码待当前构件运行）

旧01仅等待Compose空闲即可截到“正在载入文档…”，旧05仅确认数据库/ViewModel已读到1000笔，未确认native图层raster完成、源页绑定或可见像素；它们不构成通过证据。本修复不改fixture规格、笔迹原件或生产渲染器。

- 每次源页取证先核验同一PDF摘要与真实源页序号，再等同一已选择页面的原生View、完整原迹序列摘要、作者图层指纹、非空PDF tile、当前viewport已完成draw，且PDF、笔迹、原图渲染均无pending
- 01与冷重开04仍是第1页真实连续视图；不得带加载占位。05明确通过文档概览选第12页，单页整页取景，断言1000笔/100000点/1000可见笔，再直接从系统截屏分别读取基础层顶部与锁定层底部的蓝色笔迹像素，PDF原文颜色不计入
- 生产图层面板有高度上限，不能在一屏同时展示三层的全部控件。新增06-pressure-page-layers.png拍当前基础层，07-pressure-hidden-layer.png真实滚动至隐藏空层，08-pressure-locked-layer.png真实滚动至锁定压力层；不改变显隐/锁定状态、不拼接截图
- 连续滚动/pinch独立记录elapsed与阶段内存观测。这包含等待和断言开销，不冒充帧率、峰值内存或截图性能。固定96卡/12页/1000笔/100000点/三层/两留白及原备份、隐藏投影验证保持
- runner仅接受manifest中同runId、同源码、同源页、同PNG摘要的nativePageCaptures回执；01/04/05还需完整原生draw证据，05需两处真实蓝像素，06–08需对应面板区域断言。截图仍为PENDING_VISUAL_REVIEW，Host契约测试不是Android结果

宿主契约检查：python3 -m unittest discover -s android/verification/six-batch-fixture-20261004 -p 'test_native_evidence.py'。本修复未在本地运行Gradle或模拟器；由集成CI编译并运行当前APK后逐图审阅。
