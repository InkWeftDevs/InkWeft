# v49 分支手动回忆验证

日期：2026-10-01。版本 `0.0.49-branch-recall`（49），包名 `org.inkweft.app.a0.workspace`。基线提交 `8e8adbaf4bb6a9d5e2996fb4a4a615c0528f9238`，加完整保留的既有 v48 未提交工作和本轮变更；未提交／推送／更新 PR。

## 实际结果

| 检查 | 本轮结果 | 证据 |
|---|---|---|
| 离线原生构建 | PASS：核心、主APK、两个instrumentation APK与lint构建成功 | `E:/Inkweft/archives/2026-10-01/BranchRecall/build-review.log`，首次3m37s；测试源修正构建 `build-review-tests.log` 1m30s |
| core-domain | **266/266**，0失败／错误／跳过，28个XML suite | 归档 `core-test-results/`、`evidence/build-checks.json`；含新增6项领域回归 |
| Room整合回归 | **169/169** | `evidence/room-all.txt`；包含新增12项固定修订、跨范围拒绝、回收后读取、冲突与回执故障测试 |
| 受影响原生UI | **19项最终通过**：新增7项＋既有12项 | 首轮 `evidence/ui-existing-initial.txt` 为10通过／2失败；定位并修正测试夹具后 `evidence/ui-recall.txt` 为7新增＋2修复，9/9；不重复算首轮失败项 |
| lintDebug | **0错误、0 Fatal、132警告** | `lint-results-debug.xml/html`；警告总量与v48保存结果相同，不等同零警告 |
| 签名 | PASS：原固定证书 | `evidence/apksigner.txt`；证书 SHA256 `18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969` |
| v48→v49隔离覆盖安装 | PASS：schema12，29张表／21条合成记录逐字段摘要一致，变化／缺失0 | `evidence/upgrade-v48.json`、`upgrade-v49.json`、`upgrade-comparison.json`；仅本轮全新模拟器合成夹具 |
| 已安装包字节 | PASS：实际设备base.apk与构建输出SHA256完全相同 | `evidence/installed-artifact.json`；APK SHA256 `ed1facac8fd3eb142f8643bd556334d0bae616c05bf4c352f24a8ae9829abbed`，143524418 bytes |
| UI截图复核 | PASS：8张原生截图，实际查看窄屏操作／下一题及资料库重建截图 | `evidence/screens/`；375dp、font_scale1.6的长答案操作完整可滚到，下一题从开头显示且不含答案 |

环境为本任务新建官方 Android35 Google APIs x86_64 AVD，`emulator-5556`，独立 adb5038，临时目录 `C:/Users/admin/AppData/Local/Temp/InkWeft-BranchRecall-V49`。未启动、清空或安装到其他模拟器／真实资料设备。测试结束归档后关闭本任务 AVD 和 adb，并按路径检查清理临时设备。

## 新回归的实际覆盖

- 领域6项：40层分支完整遍历；折叠／重复图位不截题，同卡两独立问题保留；结构主题不计卡；不存在／不可用分支不回退到全图；冲突身份／卡版本拒绝；全本包含未入图卡而全图排除它们；队列防御复制。
- Room12项：同本两图及移除节点范围隔离；原生40层后代；全图／全本分开；问答后续编辑、新增／移除问题不改变本轮；历史修订缺失明确失败；错卡／跨笔记引用拒绝；图回收不扩大范围；已入轮答案保留但回收／改版后标记拒绝；提交后回执重放不重复写；回执前故障整笔事务回滚并原操作重试。
- 新UI7项：真实节点菜单折叠结构分支计数；冻结问答及回源／两次Activity重建无标记；答案冲突时不覆盖、不推进；375dp／1.6字号长题长答操作可达、下一题滚动归零且遮答；Library复习入口恢复；Book知识及卡片属性两个祖先恢复；故障 Unknown 期间退出／跳过／回源／标记禁用、同题与原命令保留、重试后Question仅到revision2且回执只新增一次，进入下一题遮答。
- 既有UI12项：`UiR2Test`7、`LearningWorkbenchTest`3、`MapInteractionUiTest`2（名称草稿重建与窄屏）。这些是本轮实际执行，不沿用v48的43项定向数字。

## 失败尝试与修正

1. 升级夹具首次误用新v49测试runner配v48应用，产生 `NoSuchMethodError`。已改用v48归档匹配runner，`upgrade-v48-fixture.txt` 为1/1通过；失败记录 `upgrade-runner-mismatch.txt` 保留，不能归为v48或v49产品回归。
2. `UiR2Test.systemBackWaitsForLiveStrokeAndKeepsPageIdentity` 调用旧测试导航辅助，对常驻手写模式按钮执行 `performScrollTo()`，实际没有滚动父节点。改为实际可见按钮点击，保留后续活笔／返回／页身份断言，修正后通过。
3. `LearningWorkbenchTest.directoryUsesStableIdentitiesAndManualInboxState` 假定全局收藏为空，与同suite先前用例的收藏夹具冲突。改为保留进入用例时的收藏、只增加／删除本例稳定引用，同时断言不影响原收藏，修正后通过。未清空测试库规避断言。

## 交付边界

作者schema12／IWO9不变，真实库同步关闭，没有加入FSRS、云AI或跨本投递。冻结会话只保存身份与修订引用，不将答案正文塞入Activity Bundle；历史版本读取失败明确保留本轮，不悄悄使用当前答案。原笔迹、美化和其他未提交工作保留，未重置任何用户变更。

指定 ChatGPT 精确对话最新全文仍未获取；本批依据随后授权的历史确认需求推进，不能宣称覆盖它未知的新要求。完整读写忆状态、所有视图统一遮答、三类留白、portal／跨本等仍见 `DEVELOPMENT-GAPS-V49.md`。

**未测：真实平板覆盖安装、真实笔与笔感、物理分屏／TalkBack完整组合、真实进程死亡与低存储等硬件故障、用户原始“多字变我”失败笔迹及多作者手写验收。** 模拟器Activity重建和注入IOException不是实机断电／真人验收。详细操作与记录模板已加入 `DEVICE-TEST-CHECKLIST.md` 的 BR49 项；v48 ABF真实样本待测继续保留。

候选包：`E:/Inkweft/dist/BranchRecall-V49/InkWeft-v49-workspace-preview.apk`。源码与构件摘要清单随包保存，远端push／PR／合并／发布及向第三方上传私人资料均未执行。
