# v47 原生编辑器与连续书写核验

日期：2026-09-29。保留既有工作树、schema12／IWO9、签名及资料。输入任务包SHA256 `8d04bc304e46313aee17476cf7db92e1b291a2e990aca440c0971c8fb1b26c30`。本机证据根目录 `E:/Inkweft/archives/2026-09-29/NextStageV47/`。此记录区分阶段构件，最终身份另列。

## CI实际结果

精确修复提交970df3dd616f2c9266bc9e0dd1c6161fd4ed54c1的[CI 36567769505](https://github.com/InkWeftDevs/InkWeft/actions/runs/36567769505)通过全部4个job。App241（121+120）、Room157、核心259、选择器19、服务18、同步4；TLS5/5；容器4项全部通过。构件ZIP哈希与GitHub公布值一致，汇总见 verification/v47/ci-970df3d.json。

只读失败根因是封存快照仍采用WAL、没有可写边车条件；仅对独立、关闭后的恢复快照封存为DELETE，服务原库继续WAL。验证只读文件系统EROFS、SQLITE_READONLY、独立8MiB tmpfs ENOSPC／SQLITE_FULL后原密文不变，以及独立新卷恢复身份／回执／密文／明文摘要一致。保留首次失败日志。

这些远端结果覆盖970df3d，不能冒充后续UI候选的远端结果。当前UI采用本机受影响领域验证。

## 原生样板与方向确认

初版P1固定用户构件cb6f67a：原生五状态在1920×1200、750×1600／320dpi／1.6字号、1200×1920均通过数据及交互断言；包含真实软键盘占用高度与截图。MuMu最初IME显示标志与实际屏幕不一致，改用官方API35模拟器并保留失败证据。窄屏操作条实际遮挡邻近节点已修复，未用语义节点存在替代像素查看。

用户已确认方向并提出细节：适宽纸张、标签栏配色、导图重复按钮、末页上拉追加、手写／电容笔模式。方向认可不等于最终所有截图审美通过；最终细节和真实笔感另记。

P1曾在vivo iPA2673覆盖安装，v46→v47；2374条原记录无缺失、无字段变化。物理截图和备份只在本机private-device-*归档，Git不包含私人样本。最终411ccf9覆盖及USB复验已完成：2374原行仍无缺失、无字段变化；最新基线2427行中仅自建样板本／页面的centerY和zoom共2行发生预期变化，增加18行测试资料。详见DEVICE-REPORT-V47。

## 连续书写新增验证

首轮CanvasNavigation两项、ContinuousPageGesture两项通过；完整NotebookNavigation通过实际页面追加、空内容、沿用纸面、拒绝旧末页请求、单指写入、重开页数与模式保持。

完整编辑器测试最初卡在app getter经ActivityScenario触发隐式waitForIdleSync，线程栈保存在details-regression-03；改用Instrumentation目标Application读取资料，保留相同真实数据库断言后通过。没有降低页面数或保存断言。

受影响原有25项全部通过（details-regression-05）：BrushB1Input、ContinuousWriting、TemplateCreation、BackupResource、LearningWorkbench、EditorVisualContract。长笔取消门禁与失败后重试等8项通过（details-regression-06）；最终411ccf9的窄屏固定模式按钮及真实追加／重开2项通过（details-regression-07）。

## 保留的证据边界

- 性能旧v46三轮四条件12/12完成；P95中位数writing7.705ms、map8.600ms、encryption8.056ms、PNG7.475ms，均为该专用模拟器工程输入，非真实笔尖延迟。
- PNG资源任务窗口覆盖24/24笔输入，但真实decode每轮只重叠1/24；这是前台优先调度后的资源任务，并非持续PNG CPU压力。最终候选已按相同口径完成比较，详见下表。
- NEXT45-26 Android限容／隐私和NEXT45-28同本串行链路已实际执行，见下文；测试包身份与用户包分别列出。
- 真实手持笔、15分钟温升／耗电、独立至少3位书写者评测仍待测。用户暂不能提供独立作者样本，现有开发样本不进入独立准确率。

新增／受影响实机步骤见 DEVICE-TEST-CHECKLIST.md 的 VIS-13～17。诊断包覆盖应用事件与状态摘要，不替代系统全日志、真实原文和笔感录像。

## 最终候选身份与视觉

用户构件固定为411ccf9a9eebde1264fd49529d2147fcdf465598，0.0.47-native-editor-preview（47），SHA256 `2e317da82cd1c38a238c0073ec783d91e97976c2ca3830879a82dcbb5dee0b82`，149989924字节。签名与原workspace包一致；未修改作者schema12／IWO9。

最终用户包／debug测试包／profileable性能包的编译均成功；lint 0 Error、132 Warning（保留既有警告，不将成功写成零警告）。后续b21c517、80c880c只修测试和验收runner，未替换已冻结用户／性能构件。

最终横屏、375dp／1.6字号和竖屏均完成NativeEditorVisualProbe数据断言、实际截图与录屏；已查看常用参数、图窗／节点、真实IME及正常宽度虚线投递。发现窄屏手写模式按钮换行遮挡后，生产修复411ccf9固定模式入口、其余工具横向滚动，保持48dp和单行。

样板纸面改用适宽显示；作者1000×1414坐标和用户已有视野不批量重置。浅蓝标签栏、白色选中页签、导图视图菜单收拢重复适配按钮已反映到实际界面。原生完整对照和录屏在本机 `VISUAL-REVIEW-V47.md`，结构化摘要见verification/v47/visual-results.json。

## NEXT45-28：同一本资料的串行闭环

`journey-411ccf9-02` 的runId `b66034f8-41b4-49ce-afa9-a19057b02f80`，同一候选用户APK，13个持久步骤连续执行：安装模板→建本→插两页→跨3页书写→after-group-commit真实杀进程→重开整组恢复／撤销／重做→摘录到主图→改同一卡名称→搜索回源→实时图嵌入→实际识别待校对并将文字校对为HI→应用／局部擦除／撤销→加密备份→新建空隔离Room库恢复。

页、整笔片段、卡、节点、来源与实时图身份全程不变；备份前后核对页内对象、笔迹编码摘要、来源快照、卡／节点及LIVE嵌入解析结果一致。prepare与finish之间没有清库／换fixture，只有指定的真实进程终止。夹具安装、摘录／改名命令、请求美化和加密入口使用正式API／ViewModel，页面操作、搜索回源、校对与擦除撤销使用原生UI；不宣称每一步都是手工点击或识别准确率通过。

首轮失败保留在journey-411ccf9-01：插入导图后仍处于对象选择模式，直接给canvas赋pen不等于UI切工具；探针改为点完成、选择笔并适配纸面后，完整重跑通过。末次版本核对使用当前页视野。验证没有放宽保存／引用断言。

## NEXT45-26：隐私出口与限容

隐私Probe在同一候选用户包使用合成密码、token、恢复密钥、标题／正文、目标／组件身份；15个实际出口（备份密文、错误状态/UI、恢复预览/错误、资源失败、匿名布局、诊断ZIP各条目、诊断日志、当前进程logcat）均未命中7类秘密。源数据只在独立DB，预览没有写入主作者库；无原始秘密输出，清理完成。资源选择最初点在初始化忙状态，修为等待按钮可用且断言系统选文档监视器命中后通过。证据privacy-fault-411ccf9-03，测试APK SHA `4dd4085c086aacf0e167da09f7faf61c5a75e6fa83c139e26f0d46116ecb0197`。

Android空间测试实际进入被测应用的mount namespace，在其私有cache内挂独立8MiB tmpfs；runner验证PID、UID、允许的私有路径及原目录inode，探针再验证挂载与容量。文件写、加密暂存、解密暂存均精确ENOSPC，32MiB备份预留门禁为BACKUP_LOW_SPACE；无已发布失败文件／partial、旧密文摘要未变，并成功恢复到空隔离库。卸载、目录／握手文件删除完成，未填父文件系统。证据space-namespace-411ccf9-03，测试APK SHA `d6348b8b0a07d74afe6e75d037e11a70aca7423d17f0ab99e1ed933eae133891`。

初次失败包括JUnit方法返回值、宿主shell挂载不能跨应用namespace、canonical路径别名与nsenter参数；均单列留档，不能当作空间PASS。最终不放宽8MiB／ENOSPC／旧密文恢复条件。服务容器只读与新卷部分使用前述970df3d精确CI，不重复引用更早基础运行。


窄屏投递补验：最初截图未出现虚线，源点处于浮窗／笔盒覆盖范围，不能算视觉通过。把原浮窗下移、改从实际可见的选区起拖后，`visual-final-narrow-preview-02` 出现明确虚线及“松手添加”提示，截图已查看，取消后作者摘要一致；用户APK保持411ccf9，测试源641f625。原失败与坐标条件留档。


## NEXT45-27：实际编辑器帧与trace

固定v46与411ccf9的profileable包，各3轮×4条件，共24场；使用同一个测试APK `081f5b49…`，相同600笔种子、视口、1920×1200／240dpi／60Hz配置，图窗为64节点。正式测量时停止官方模拟器与Gradle。每场新增24笔并核对总624笔，seed摘要不变；每trace有1窗口、192输入标记、24保存标记。全部trace error／data_loss为0，FrameMetrics掉报告0。候选共2632帧，基线2675帧。

|场景|v46三个P95的中位数ms|v47三个P95的中位数ms|
|---|---:|---:|
|正常书写|7.705|7.761|
|64节点图窗|8.600|8.256|
|16MiB加密|8.056|6.800|
|合法PNG资源任务|7.475|7.548|

这些是有限三轮观测，不足以宣称稳定优化或无性能退化。PNG的deadline超时帧旧2/664、新8/654；新首轮P95为9.873ms，最慢31.460ms，draw21.868ms。trace对应主线程Running24.263ms、Record View#draw21.833ms，与并发GC及ClassLinker锁1.207ms重叠；该轮7个慢帧均未与实际PNG decode重叠，最后decode在最慢帧约2.515秒前已结束。现有证据不足以定位稳定生产热点，保留不利样本，不凭单轮增线程或删校验。

PNG后台任务覆盖24/24输入，但真实decode仅与1/24输入重叠；加密实际调用覆盖24/24。不能称作持续解码压力。所有GPU_DURATION为模拟器异常负值，明确不解读GPU耗时。输入至保存含固定注入节拍和轮询，不代表笔尖延迟；并发GC墙钟不等于暂停时长。

完整逐轮分母、CPU／GC与trace核验见 [性能结论](verification/v47/performance-comparison.md)和[机器摘要](verification/v47/performance-comparison.json)。独立性能进程、自有trace会话与远端临时材料已清理，本地原始慢样本保留。

## 收尾

保留当前工作树与中文提交；本批UI／测试／文档尚未推送，PR未合并、未发Release。用户APK在dist固定路径，早期P1交付副本移存本轮归档的pilot-delivery并校验摘要。清理79份本轮ADB拉取夹带的历史截图副本，共12203073字节；最终截图／录像链接核对有效。原始失败、私人备份、最终构件、源码、签名密钥与工具链保留，分类索引见本机ARCHIVE-INDEX.md。
