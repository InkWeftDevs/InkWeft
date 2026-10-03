# V67 云候选与本地验收合同

这是明日验收准备候选，尚未经过真实 Android 运行或正式版验收。版本标识为 67 / 0.0.67-cloud-candidate；诊断工作台 applicationId 保持 org.inkweft.app.a0.workspace。既有 integration/75051f7 首接手点不变。

## 来源与归属

- 本轮结果/精确再练：626ccd5d515ab405d18e1a9dc5f4ecdc37dedb96，草稿 PR #3
- 原文预览局部释放：603ffb1c01841422eb7a47a1de15f7aa7d5d97df，草稿 PR #4
- 两分支从同一 75051f7 派生。三方树合并无冲突；生产文件互不重叠，清单两处修改均保留。候选提交保留两个父提交，不合并上述 GitHub PR，不移动 main/integration
- 候选整合只额外调整版本标识与本合同，没有新增功能、schema、依赖或签名配置
- cloud 当前持有上述模块；local 接手前声明具体任务和源 commit，使用自己的任务分支。cloud/local 不在同一目录或同一分支并发编辑

## 成对产物与检查

主包与 app AndroidTest runner 必须来自同一候选源码和同一构建参数。主包预期 org.inkweft.app.a0.workspace；runner 预期 org.inkweft.app.a0.workspace.test，targetPackage 必须为主包，runner 为 androidx.test.runner.AndroidJUnitRunner。

构建时显式设置 INKWEFT_HEAD_SHA 与 GITHUB_SHA 为实际候选完整 commit，使诊断信息能回到源码。采用 JDK17、项目 Gradle wrapper、compileSdk36/build-tools36.0.0，串行低 worker：

    ./gradlew --no-daemon --max-workers=1 --no-parallel -Pkotlin.compiler.execution.strategy=in-process -PinkweftDiagnosticBuild=true -PinkweftInsertionPreview=false -PinkweftUnsignedBuild=true -Pandroid.experimental.useDefaultDebugSigningConfigForProfileableBuildtypes=false :app:assembleDebug :app:assembleDebugAndroidTest

云端不生成签名 key。实际 APK 大小/SHA-256、输入清单、zipalign、unsigned 检查、真实执行的任务与耗时以随产物交付的候选构建回执为准；本文件不是构建通过声明。

## 本地安全接续

1. 使用新目录 clone 或全新的 worktree，验证交付的完整候选 commit、git status 与输入清单；不要在旧 dirty 目录 reset、clean、stash 或覆盖
2. 在已核 commit 建自己的 codex/local/<task>-<date> 分支。先读本合同、COLLABORATION.md 与 DEVICE-TEST-CHECKLIST.md，确认 owner 和明确目标设备；此步骤不授权安装
3. unsigned APK 不能安装。经明确授权后，仅在本地使用原私钥给主包和 runner 分别签名，私钥与密码不进入聊天/Git/云端。签后 apksigner verify --print-certs，核两包证书及主包原签名、公钥摘要、包名、targetPackage、版本与 SHA，并记录签前/签后回执
4. 不同签名不能覆盖旧真机资料。遇签名不匹配立即停止；不得通过卸载、清除数据或更改 applicationId 绕过。相同证书也不等于真实升级/恢复已验证
5. 仅在授权目标的合成资料/可恢复副本运行准确方法，逐方法记录 PASS/FAIL/NOT_RUN、日志与截图。设置前后值记录并恢复。真实资料、原始诊断只走获授权私有渠道
6. 将脱敏修复和测试结果提交自己的分支，回传完整 commit、设备/构建参数、失败复现及尚未执行项，以草稿 PR 汇合，不自动合并

## 当前未执行清单

- V66 局部动效新增3项+旧回归8项，含本候选增强的 LocalMotionUiTest 方法：全部 NOT_RUN
- BranchReviewRoundUiTest：5项 NOT_RUN；准确方法见 verification/review-round-20261002/ui-checkpoint.json
- BranchReviewRoundRepositoryTest：12项 NOT_RUN。它属于 data-local AndroidTest，不能因 app runner 成功就算已执行；需本地对应模块 runner/明确合成测试环境
- 完整导入→写入→摘录/导图→复习→导出/空库恢复当前候选链路，完整 OS 进程死亡，真人原迹、笔感、旧库升级、真实备份恢复：仍各自待验

祖先分批已通过306项核心测试、主源码/AndroidTest编译和lint；候选会记录自身阶段核心回归和成对构建。静态/编译/缓存结果不替代 Android runtime。不得将候选、草稿 PR 或版本号描述成正式可用版完成。
