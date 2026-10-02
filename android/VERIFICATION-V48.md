# v48 自动美化整改验证记录

适用版本：`0.0.48-automatic-beauty`（48），2026-09-30。源码基线：`8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`，另含本轮未提交改动。核心260、Room157、最终定向App43、窄屏4及同用户APK8项检查通过；lint零错误、132警告。跨批次App去重65项，不等于最终完整App或CI运行。

整改范围见 [AUTOMATIC-BEAUTY-V48.md](AUTOMATIC-BEAUTY-V48.md)。用户确认暂无此次“多个字仅一个候选”的原始区域笔迹，先完成代码整改与合成回归；因此本记录不能定位该真人故障首次丢内容的层，也不能宣布该原始故障已复现或通过。

## 固定交付构件

- 用户APK：`E:/Inkweft/dist/AutomaticBeauty-V48/InkWeft-v48-workspace-preview.apk`；包名 `org.inkweft.app.a0.workspace`，版本48。
- APK SHA256：`1030c07057c33301a1c5a430d553aefcf94547d90ec4b90be1c233bdb5221997`。
- 签名证书 SHA256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。`apksigner verify`通过，与v47证书一致；安装到本任务官方AVD后拉取实际base.apk，摘要与交付文件相同。
- 用户包测试APK SHA256：`4b5ef5757acd68b9740fa7c0ec3eac7109766a6de738fe32807c7a943e85942a`；归档 `test-build/owner/app-debug-androidTest.apk`。
- 回执：[artifact.json](E:/Inkweft/dist/AutomaticBeauty-V48/artifact.json)。409个Android构建输入的逐文件摘要见同目录 `source-manifest.json`，该清单SHA256为 `3d0c3720dda1b036adb9051d38e8e25b6722cec882e62fb2099fb24294deafdb`。清单包括新增源码和测试，排除忽略的私人文件与文档。
- 内部 `BuildConfig` 的source/build commit仍为 `local-unknown`，没有把未提交内容冒称为基线提交；构件身份由实际APK摘要、证书及上述源文件清单建立。作者schema12／IWO9未改。本轮未安装到用户平板、清理用户资料或执行远端操作。

## 环境与证据范围

- 工作树：`E:/Inkweft/recon`；本机归档：`E:/Inkweft/archives/2026-09-30/AutomaticBeauty`。归档位于仓库之外，私人区域原迹、输入图和候选不加入 Git、PR 或 CI。
- 本轮原生测试运行于隔离合成资料模拟器 `emulator-5556`。实际基础诊断记录：Android15／SDK35、`sdk_gphone64_x86_64`、x86_64／arm64-v8a，常规窗口1920×1200、240dpi、字号1.0。
- 大范围Instrumentation目标包为 `org.inkweft.app.a0.insertion`，版本48，debug；最终用户包另以 `org.inkweft.app.a0.workspace` 实测8项。两组测试APK分别保存，不能混用包身份。用户包复验不执行清库，只创建合成测试本。
- 注入候选仅测试来源所有权、状态、布局、事务和撤销。实际ONNX识别另列合成字体轮廓样本；二者均不替代真人书写评测、真机笔感或物理故障验收。
- 先前61项在中间版本运行；事务／缓存修复后补验32项。最终再加入合并候选页边界校验和截图等待，执行43项受影响回归、4项窄屏和8项用户包复验。数据库与核心源码在其通过批次之后未改变；最终构建也检查其任务和报告。所有结论按实际批次区分。

## 已完成验证矩阵

|范围／证据批次|实测结果|支持的结论与边界|本机证据|
|---|---|---|---|
|核心域 JVM|27个测试类，260用例；失败0、错误0、跳过0|核心变更后的完整XML；最终构建该任务UP-TO-DATE，不伪称全部重新执行|归档 `core-domain-final/TEST-*.xml`|
|Room／data-local，integration-current|157/157，通过|原生仓储整合；并非真机或最终全部源码重验|`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/integration-current/room-current/instrumentation.txt`|
|受影响App／UI，integration-current|61/61，通过|包含自动美化、实际模型、字体／擦除、连续书写、诊断等15类；属于事务／缓存最后补修之前的中间版本|`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/integration-current/summary.json` 及各用例 `instrumentation.txt`|
|事务／缓存补修，transactions-final|32/32，通过|12项AutomaticBeautyRegression、2项Canvas、3项Quality、1项PairedEntry、1项Review及13项ContinuousWriting；为这次补修后的定向回归|`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/transactions-final/summary.json` 及各用例 `instrumentation.txt`|
|未知自动回执下继续落笔，canvas-current|单页及连续页2/2，通过|合成原生事件证明两个入口能接受下一笔；已包含在上述批次，不能额外累加独立用例|`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/canvas-current/summary.json`|
|最终页边界／全部美化相关回归，guarded-final|43/43，通过|13项Regression、实际模型4、Canvas2、Quality3、Paired1、Color3、Layout3、Review1、Diagnostics3、FontQuality6、Settings3、LiveErase1；最终源码|`guarded-final/summary.json`及逐项日志；`candidate-guarded-build.log`|
|最终375dp／字号1.6，narrow-guarded|4/4，通过|Review1及Diagnostics3；截图有效并已目视；滚动内容可达|`narrow-guarded/summary.json`及截图|
|实际固定用户APK，owner-final|8/8，通过|实际ONNX／诊断合同4、原生校对1、诊断入口／基础ZIP／Provider3；安装APK字节已核对|`owner-final/summary.json`及三个Instrumentation日志|
|最终固定用户包构建／lint|BUILD SUCCESSFUL；0 errors、132 warnings|核心任务、App、用户包测试APK与lint成功；保留警告，不称无警告|`delivery-final-build.log`及 `lint-final/lint-results-debug.{txt,xml,html}`|
|旧基线四项定向回归|4运行、4失败（预期）|证明定向用例能击中旧实现缺陷；不得计入当前成功或原始真人故障复现|`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/baseline-regression.log`|

61项与32项按完整 `class#method` 去重，交集29、并集64。最终43项包含其中42项及新页边界用例，所以跨批次并集为 **65个独立App用例**。窄屏和不同包复验不再累加；不能把65称为最终完整套件。事务批次新增的3项是：

- `AutomaticBeautyRegressionTest#manualApplyOfAutomaticReviewRetainsExplicitTransaction`
- `AutomaticBeautyRegressionTest#cachedCandidateIsInvalidatedWhenSameSourceIdHasNewCuts`
- `AutomaticBeautyRegressionTest#fullObjectBudgetKeepsOriginalAndOffersReviewInsteadOfStayingBusy`

最终另增 `AutomaticBeautyRegressionTest#alignedAppendOutsidePageKeepsOriginalButWorldCanvasAllowsIt`：原始小字号能放下，新片段继承旧行字号后越界；有限页拒绝候选并保留原迹，无限画布仍放行。

旧基线四失败分别为缺／重复来源、默认候选预览、失败单元进入全页fresh累积、可靠段落被人工确认门槛阻断。相应用例已在本轮通过批次中重验，但这四个工程缺陷不等于用户截图故障的完整根因。

## 实际随包模型：四段合成字体轮廓

最终隔离包的结果文件是：

`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/guarded-final/org.inkweft.app.BeautyNativeReplayTest#actualModelReplayReportsAccuracyAndAutomaticCoverageSeparately/abf-native-results.jsonl`

同目录保存 `abf-outline-0.zip` 至 `abf-outline-3.zip` 和 `instrumentation.txt`（`OK (1 test)`）。实际用户包另产生 `owner-final/abf-native-results.jsonl`、四份ZIP和本地评分报告；也为4段28字符零编辑、4/4正确自动保存。早期同名结果只作历史。

样本由WENKAI字体轮廓采样为原迹，再运行真实Android／ONNX推理，`source_kind=SYNTHETIC_FONT_OUTLINE_NOT_HANDWRITING`、`split=development`、作者为生成器。reference只用于评分／断言，不向识别器注入正确候选。这是开发控制样本，不是独立评估集，更不是真人书写分布。

|样本|reference字符数（含空格）|两次真实输出|自动保存|两遍直接识别总时长ms|自动观察至确认对象ms|
|---|---:|---|---|---:|---:|
|outline-0：12345|5|12345／12345|是|382.622077|1098.124366|
|outline-1：2026|4|2026／2026|是|49.327193|1115.764856|
|outline-2：墨织手写查找|6|墨织手写查找／墨织手写查找|是|62.280540|1137.662524|
|outline-3：InkWeft notes|13|InkWeft notes／InkWeft notes|是|56.862100|1130.844682|

本组共4段、28字符；第一遍和边距扰动第二遍均28/28字符正确，编辑距离0，因此本组CER=0/28。可靠候选4/4，实际正确自动保存4/4（100%），待校对0/4、人工应用0/4、错误自动替换0/4；不是全部拒绝后报告成功。实际保存断言同时核对文字、完整来源ID集合以及原迹仍在仓储。

`recognition_ms`是两遍直接识别的合计，未分开记录单遍耗时。隔离方法单独启动，outline-0包含模型首次使用；outline-1～3在同一进程已加载模型后运行。此冷／暖区分只适用于本次模拟器单次运行，没有多轮性能统计。`end_to_end_ms`在先行两遍直接识别之后，从新原迹进入 `observeBeauty` 计至自动对象确认，模型已暖，包含750ms调度及第二遍等待／推理／布局／保存；不包含前述首次加载，不是光学笔尖延迟，也不能代表真人最后落笔到结果的完整时延。用户包在同类其他方法也使用模型的批次内运行，不单列它的首段为冷启动。

每个ZIP有两份实际记录：`attempt-1/trace.json` 为直接两遍评估（`EVALUATED_NOT_COMMITTED`）；`attempt-2/trace.json` 为自动路径（`SAVED`）。不能将第一份的“未提交”误读为自动失败，也不能将它单独当成保存证明。两份均附区域原迹、base／padded实际模型输入PNG、输出与来源映射。前三段实际张量为 `[1,3,48,320]`；英文base为 `[1,3,48,324]`、padded为 `[1,3,48,320]`。诊断记录BGR、`pixel/127.5-1`、右侧填充值0与非空范围；PNG包含实际张量填充区。

两遍来自同一个随包ONNX模型，第二遍改变边距；一致只证明此扰动下稳定，不是两个独立识别器的正确性证据。top2原始分数未经校准，不能称准确率。本轮未执行独立笔迹时序识别器对照，也未采集多作者测试集。

## 注入测试、诊断和隐私边界

状态／事务用例用注入识别器验证：来源遗漏／重复／外来来源拒绝、短候选覆盖宽来源与越界token拒绝、补笔使单元及缓存候选失效、无关书写不重跑旧失败单元、原位行字号和基线保持、段落自动与撤销、未知回执时保留原迹并继续书写、手动应用保留明确事务、对象容量拒绝可恢复。这些不计入模型CER、真人正确覆盖或识别准确率。

`BeautyNativeReplayTest#blankRasterGroupRemainsMappedAndCannotHideOtherInk` 用真实识别验证被擦空的输入组仍保留空输出和来源映射，不借另一组输出隐藏它。结构诊断、预算与新会话、实际诊断入口及FileProvider范围在最终隔离包和用户包均通过。

基础诊断ZIP实物：

`E:/Inkweft/archives/2026-09-30/AutomaticBeauty/owner-final/diagnostics-emulator.zip`

该包只含 `README.txt`、`report.json`、`events.jsonl`、`manifest.json`。测试校验manifest摘要、USER_MARK、版本／应用ID，并检查专门构造的私人标题未出现。基础范围为应用／签名、设备型号／系统／窗口、内存电量热状态快照、最近最多200条固定类型事件、已观察保存状态及数量、有限退出原因数值；不附笔记正文／标题、原迹坐标、页面／笔划ID、PDF／图片／数据库、路径／URI、账号／凭据、设备序列号、异常正文／堆栈或系统logcat。数量快照不是数据库完整性审计，最后约1秒持久诊断可能未落盘。

独立美化诊断默认关闭，用户主动开始后记录最多最近3次尝试。结构录制含页面／笔划ID、版本和来源映射、点数／笔内时间范围、分组／包围盒、张量范围、候选分数和拒绝／提交状态；这些ID只在美化包。未勾选私人附件时不附区域原迹、模型输入PNG或完整候选文字；勾选后才附这些内容及逐步top2。`defaultAndStructuralCaptureKeepPrivateAttachmentsOutOfBasicZip`还校验基础包没有测试页面／笔划ID，并校验美化包可内嵌 `basic-diagnostics.zip`。

每条元数据512KiB上限、单附件4MiB、附件合计8MiB、美化ZIP12MiB；达到上限明确截断。美化捕获在内存中，重启丢失；强停后的终局回执不由它补录。笔内 `elapsedMs` 不等于全局笔顺或笔间停顿。两类ZIP都为明文，用户主动保存，基础包也可主动分享；不自动上传。系统保存选择器、分享目标最终接收端和低资源硬件观察仍待单独验收。

## 最终复验与真机待测

|事项|结果|证据／边界|
|---|---|---|
|375dp／字号1.6|最终4/4通过；主代理目视确认|`narrow-guarded`。原迹／未应用提示清楚，正文与固定页脚不重叠；超出内容区的选项通过滚动可达，不把滚动裁切误当丢字|
|正常宽度校对截图|最终有效截图已目视|`guarded-final/...BeautyReviewUiTest...`及 `owner-final/fq-paper-*.png`。五个阶段有截图；纸面原迹可见，显式对比按钮明确，邻近正文和保存后的来源由断言核对。历史黑屏不作视觉证据|
|诊断录制状态截图|正常／窄屏最终同步断言和截图通过|`guarded-final`、`narrow-guarded`、`owner-final` 的 `abf-diagnostics-opt-in.png`。显示“停止美化诊断”，私人选项在录制中锁定；尚无捕获时保存按钮不可用|
|alignToLine之后页边界|已修复，新增回归通过|最终合并后复用 `PageObjectRepository.validateBounds`；有限页越界不进入保存，world仍允许。见新增方法和43项回归|
|实际workspace用户APK|8/8通过|实际模型4、MainActivity纸面校对1、实际诊断3，全部在专用官方AVD合成资料上；与交付APK拉取摘要一致|
|固定签名交付包|身份与复制完整性通过|前文APK／测试APK摘要、证书、版本和409个源输入清单；只在本机交付，不自动安装到用户平板|
|完整App、CI、真机|未执行／不作通过声明|按实际运行范围填写，禁止外推|
|用户原始失败区域，ABF-03|NOT_RUN；缺原始区域|取得授权原迹后逐层回放来源、分组、实际输入、输出和纸面|
|真人手持笔／半字长停顿／多作者，ABF-01、02、05、09|NOT_RUN|单列真人CER、正确自动覆盖、介入、误替换和等待，保留有利／不利样本|
|物理未知回执／强停／低资源与出口，ABF-08、10|NOT_RUN|备用设备故障切点与系统保存／最终接收端，不用模拟器替代|

真机用例、适用版本、条件、具体步骤、预期及证据与结果模板统一维护在 [DEVICE-TEST-CHECKLIST.md](DEVICE-TEST-CHECKLIST.md) 的ABF-01～10。新增／受影响也包括FQ04～08、FQ10～13和V46-07；历史设备结果保持。本记录只建立工程证据，真机栏不预填通过。

异常反馈优先在应用“诊断与导出”标记时间、开始美化录制、复现并保存ZIP，只另补日志无法表达的操作及视觉／笔感证据。基础包不是完整系统日志，美化包不是备份；私人附件仅本机授权范围使用。

## 归档与本轮收尾

源码、安装包、固定密钥与依赖缓存保留。最终证据集中于 `E:/Inkweft/archives/2026-09-30/AutomaticBeauty`，早期记录标为开发历史，不混入最终结果。安装APK摘要核对所用的临时拉取副本已删除，保留核对回执。本轮代码与文档留在未提交工作树；未推送、合并或上传任何私人资料。最终清理索引及模拟器还原记录见归档 `ARCHIVE-INDEX.md`。
