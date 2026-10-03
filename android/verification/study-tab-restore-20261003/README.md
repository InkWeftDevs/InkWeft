# Study tab 恢复：云端最小修复

- 起点：`36ed86d02837c8afd44bb19b72ed3c8d2c0953d9`，本地 checkpoint，未公开
- 分支：`codex/cloud/study-tab-restore-20261003`
- 本片生产范围仅 `StudySession.kt`；追加 `ReadLockUiTest.kt` 中一个方法及本目录证据
- `StudyWorkspace.kt` 未修改；没有改变节点/卡片/来源/草稿、排序或 Tab 快捷键事务

## 原因与修复

`lastTab` 读取已保存值，但 `compactInitialized` 总是 `false`。`StudyContent` 首次紧凑初始化因此把已恢复的摘要/大纲标签覆盖成导图（2）。

只复用原状态和入口：

1. 保存值仅在类型为 Int 且位于 `0..2` 内视为有效，初始化 `lastTab` 和 `compactInitialized`
2. `selectTab` 同时标记已初始化，使用户、initialMap 和选源等显式选择不会被随后紧凑默认覆盖
3. 无有效保存值时仍先回落 0；首次紧凑进入默认 2，重复初始化不改动当前标签

## 先失败，再修复的宿主实证

修复前从当前源码原样提取 `StudySession` 的标签状态/选择入口，以及 `StudyWorkspace` 的真实 `LaunchedEffect(compactWindow)` 条件，生成小 Kotlin 宿主程序。只有 `SavedStateHandle` 键值容器与 `mutableIntStateOf` 委托是替身；没有运行完整 ViewModel 或 Android/Compose runtime。

- [before-host.log](before-host.log)：修复前运行，退出码 **1**
  - `restored tab survives compact initialization: expected=1 actual=2`
- [after-host.log](after-host.log)：修复后相同脚本，退出码 **0**
  - 已保存 0/1/2、无状态、非法范围 -1/3/Int.MIN_VALUE/Int.MAX_VALUE 和非法类型 `"bad"`、显式选择 0/1/2、新状态实例恢复均 PASS
- `git diff --check`：PASS
- 源码静态核对：真实紧凑入口条件与新增真实 VM 测试中的条件逐字相同

运行命令（只用已有缓存，不安装依赖、不运行 Gradle）：

```sh
python3 android/verification/study-tab-restore-20261003/check_host.py
# 在修复后的工作树重放原始缺陷，应以 1 退出：
python3 android/verification/study-tab-restore-20261003/check_host.py --ref 36ed86d02837c8afd44bb19b72ed3c8d2c0953d9
```

脚本默认复用 `/workspace/shared/inkweft-toolchain` 的 JDK17/Kotlin 2.3.10 缓存，可用 `INKWEFT_TOOLCHAIN` 指向同结构缓存。生成源码和 class 位于临时目录，退出后自动删除。

## 真实 VM 测试源码

新增 `ReadLockUiTest.studyTabSavedStateRestorationSurvivesCompactInitialization`：

- 真实 `StudyViewModel` + `SavedStateHandle` + 合成资料仓库
- 恢复 0/1/2、普通窗口再紧凑、重复紧凑初始化
- 无状态、非法范围及非 Int 保存值安全回落；首次紧凑默认 2
- 显式 `selectTab(0/1/2)` 后初始化不覆盖
- 从保存键值创建新 handle 和新 VM，并断言实例不同；不是 Activity retained VM 重建
- 每次新建前清理前一个 VM 的独立 ViewModelStore，结束时 finally 清理
- 当前地图、草稿/来源引用与作者数据摘要不变断言

最终一次定向编译检查已通过（2026-10-03 UTC，6分4秒）：`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:lintDebug`，单worker／串行、JDK17、SDK36及仓库diagnostic／unsigned参数。lint结果0 Error／0 Fatal／135 Warning。没有构建APK，未重复核心／Room全套；测试源码编译不代表Android执行通过。

## 未执行边界

- 本片 Android instrumentation/真实 ViewModel 测试运行：**NOT_RUN**
- Compose 生命周期/真实紧凑窗口点击、旋转和分屏：**NOT_RUN**
- 真实设备、完整 OS 进程死亡/恢复：**NOT_RUN**
- 本片未安装设备、未推送/PR/合并/发布；仅云端独立分支，文档收尾不重复编译
