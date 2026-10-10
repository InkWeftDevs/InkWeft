# V85 复测问题修复与验证 · 2026-10-10

三处功能问题已修复：含括号归纳的独立副本、键盘下的标题编辑、PDF 字面文本；同时修正了 PDF 自动化测试的协程调度。28 项最终功能配置与 4 次原 PDF 流程均通过。最终 lint：PASS。

## 交付身份

- 版本：85 / `0.0.85-retest-fixes`，包名 `org.inkweft.app.a0.insertion`；Android 12 / API 31 起，四 ABI。可覆盖同签名的原测试包，不要求清空资料。
- [安装包](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/builds/candidate-4/app-debug.apk)，235,976,151 字节；SHA-256 `5b9e1f5e60a78ab7dcd5a823d6cb91db3d833d048eb4fc203b55175bd9e6566c`。
- 签名证书 SHA-256：`345a1e67b72623000c611b0a17e70a8fdf1438768ec79a1144c407c28c14d2f2`。APK v2 签名验证通过，完整 MuPDF 1.28.5 原生库与原 V85 相同。
- 源码工作区：`E:\Inkweft\worktrees\retest-v85`，修复分支 `codex/fix-v85-retest-20261010`。构建输入为基线 `ff67a288d6093adbad760bf0b66c3ef0ed30a5fc` 加本轮冻结修复；提交记录见该分支 Git 历史。最终冻结清单：`builds/candidate-4/source-freeze.json`；可应用补丁见 `delivery/fix-v85.patch`。

## 修复与证据

| 问题 | 原因与修复 | 验证结果 |
|---|---|---|
| 含归纳组的独立副本报 NOT_A_MAP_RECORD | 图事务收集受影响地图时漏掉 MapSummaryGroup；在共同入口纳入对应 mapId，沿用原原子事务、校验和回执 | 原方法此前两次失败；现在副本归纳成员映射、当前布局、身份独立与单次回执通过；关联复制、闭包备份及非法写入回滚通过 |
| 标题输入时取消/完成被裁切或点不到 | 键盘压缩父视口后又保留阅读区；活动重建的 IME 反馈还可能晚到。编辑草稿期间利用剩余空间，隐藏不适用的整理操作；编辑区保留完整按钮区域并整体滚入视口；草稿期间撤下会覆盖末端按钮的缩放手柄 | 普通横屏与 375dp/字体 1.3 的大纲输入、物理保存与取消通过；原迹 12 笔和 10 个结构主题保留。375dp/字体 1.6 的导图编辑、活动重建、共享标题、硬件 Enter/软件 Next 连续同级输入、窗口恢复通过 |
| PDF 看起来正确，提取却变成部首字符 | Android 字体中的同形字共用字形，默认 ToUnicode 不能恢复作者原字；保留 Android 矢量绘制和顺序，以每个主题/归纳的可见文字写 ActualText，保留文本块换行 | 进行中/依赖与风险/里程碑逐字一致；作者主动输入的 行⾏ 风⻛ 里⾥ 也保留；部首数量及单个省略号精确，隐藏尾文不导出；Android PdfRenderer 单页打开、原生 MuPDF 和主机提取通过；Poppler 图像已目视核对 |
| PDF 自动化出现 WrongThreadException | Compose 测试默认调度可能在 IO 完成线程推进重组；PDF 测试使用 StandardTestDispatcher，由主测试时钟推进 | 3 个原搜索 UI 方法通过；原 200 页文字 PDF 与 500 页扫描 PDF 输入各完整执行两次，无原线程异常。产品 PDF 搜索/渲染调度代码未更改 |

PDF 保持单页可缩放图形，PNG 绘制保留。Room、作者记录、容量和备份格式沿用 V85。本次没有新增格式迁移或删除资料步骤。PDF 仅包含当前可见文字投影，原迹与完整资料继续用备份。

## 实际执行

- 核心：447 测试 / 53 类，0 失败、0 错误、0 跳过；首次修复构建执行，后续改动未触及 core-domain。
- 最终功能结果：27 个不同回归方法、28 个配置全部 PASS；另有 4 次 PDF 正式功能流程 PASS 和 2 次输入准备 PASS。按改动仅复跑受影响方法；JSON 列出每项对应构建，未声称全套都在最后 APK 重跑。
- 所有轮次：51 次方法执行，45 PASS、6 FAIL；失败回执原样保留，最终对应项均已通过。第 2/3 轮 lint 在后续修复前主动终止，不计通过；最后 lint 独立记录。
- 最终构建与 app/appTest/dataAndroidTest 打包通过。lint：`{"Error": 0, "Fatal": 0, "Warning": 171, "status": "PASS"}`；首次 lint 0 Error/Fatal、171 Warning 原始报告也保留。
- 环境：专用 API 35 Google x86_64 模拟器 `emulator-5586 / inkweft_fix_v85`；2560×1600、density 320；窄屏 750×1600、字体 1.3/1.6，设置已恢复。没有安装或修改实体平板。
- PDF 原输入 SHA 核对、逐次 instrumentation/JUnit/logcat、有限应用诊断、截图、导出文件和源码冻结均在归档内。更新的采集驱动仅用于本次复测，保留完整原动作与断言；清理后源码副本见各构建的 `untracked-source`。
- 本轮存在构建与功能检查并行，PDF 测试调度也已改变；这些耗时不用于与 V83/V80 的性能对比，不作为真机速度、帧率、笔感或硬件认证。
- 新大纲用例截图在键盘可见断言前采集，窄屏截图可能处于键盘展开前；键盘可见、控件范围及物理保存/取消结论来自随后执行的断言与事务结果。导图窄屏用例另有稳定布局后截图。未将截图时机差异当作真机或额外性能证据。

## 复核入口

[逐项结果及失败历史](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/reports/test-summary.json) · [PDF 文字与视觉核对](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/reports/pdf-export-validation.json) · [最终导出渲染](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/reports/unicode-map-poppler.png) · [最终构建](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/builds/candidate-4/build.log) · [最终 lint](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/builds/candidate-4/lint.log) · [签名](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/builds/candidate-4/apksigner.txt)

工作区 `android/verification/fix-v85-retest/results-template.md` 与 `android/DEVICE-TEST-CHECKLIST.md` 已维护 RT85-FIX 稳定编号；主工作区 `E:\Inkweft\recon\android` 同步真机清单和模板。所有真机项目仍为 NOT_RUN，不以模拟器代替。

清理：两个临时采集源文件已与冻结副本逐字节核对后从工作区移出，完整源仍在 `builds/candidate-4/untracked-source`；需复现本轮采集测试 APK 时取回这两份文件。任务模拟器已停止，AVD 状态、全部 APK 和成功/失败证据分类保留；重复渲染图校验相同后去重。源码、原有未提交内容、签名密钥和工具链保留。见[清理记录](E:/Inkweft/archives/2026-10-10/Fix-V85-Retest/reports/cleanup.json)。

PDF 文本块边界的实现核对参考：[MuPDF 1.28.5 结构化文本实现](https://github.com/ArtifexSoftware/mupdf/blob/1.28.5/source/fitz/stext-device.c)。实际修复结论以本轮生成文件及原生回执为准。
