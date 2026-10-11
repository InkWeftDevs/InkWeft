# 压力曲线与铅笔绘制优化 · 2026-10-11

基点 V86 `8596a981a76e27c38caf5220659e0e238141d319` 加既有 Room18 空间存储补丁；本轮修改6个生产文件，新增/扩展2个测试文件。工作区 `E:/Inkweft/worktrees/storage-optimization-20261011`。版本号仍为 V86，尚未正式发布。

## 实际变化

钢笔配方 v2 使用压力幂曲线加最低宽度下限，消除原来将最低宽度叠加到整个压力区间的问题。默认参数下，压力0.1的名义宽度比例由0.47554变为0.12589；零压力下限为0.025。灵敏度仍控制既有指数，缺少压力轴仍采用固定压力；原始压力、倾角、方向和时间不改写。最低比例0.025是本项目设计值，参考记录中的绝对最小宽度0.05不直接当作比例使用。

笔盒、各笔种设置和收藏笔读取为当前配方；已经保存的作者笔迹保留原配方版本。v0/v1按原策略重放，v2经实时输入、重开、预览和导出共用的绘制入口处理。铅笔将每笔/每段固定的半径、密度和颜色透明度计算移出像素循环，硬度取值不再创建临时列表；材质、浮点运算顺序和瓦片复用策略保持。

新增笔迹含配方v2，旧安装包不能读取这些笔迹及包含它们的交换文件/备份。Room仍为18，不做作者资料批量改写；降级须在独立环境从升级前备份重建，不能把含v2笔迹的当前备份交给旧包。

## StarNote证据范围

输入 `C:/Users/admin/Desktop/starnote-pen-trace-20261011.zip`，SHA-256 `207551b7e2427cb204e6e28a0dd34ad3cc44a2399e085f4c4e4d2eba55ddc518`。505条清单的大小/摘要全匹配；原始与解码记录的1,816次笔返回、3,632通道、78,527段和441,821浮点值全部一致，无截断。

记录中的稳定钢笔端点支持基础宽度 `max(minWidth, width * pressure^(2*sensitivity))`：313个稳定端点最大绝对差约0.0000172；本项目测试固定其中3个端点，容差0.00005，并另测全压力范围、旧曲线和编解码。两边的灵敏度UI刻度不同，测试显式校准到指数0.75。

上述是带检测代码的合成输入记录，不是物理手写笔或无检测代码的性能数据。完整FIR、预测、收笔几何和铅笔原生材质仍未建立完整等价性，本轮只采纳已有记录能支持的压力下限规律。未执行上传包内脚本或参考程序。逐条依据在本机 `verification/trace-review.json`。

## 验证与耗时

核心累计471个不同方法通过：首次470项通过、96 MB文件边界用例发生测试堆OOM；同一配置单独复核该方法通过，首次失败XML保留。新增压力和版本测试首次即通过。应用与测试包构建成功，最后构建7分15秒，93个任务中71个复用缓存；签名和16KB ZIP对齐通过。110个冻结变化输入均与构建前一致。

MuMu5 / Android12 / API32，12项功能回归通过，runner 4.44秒；另有材质计时、对照图2个方法，共14个不同方法、16次调用。覆盖低压原生渲染、原始采样保留、旧配方重开、预设提升、压力/倾斜材质、复制/擦除、同笔交叠、瓦片复用和真实编辑器跨页落库。两张历史笔迹区域各54,400像素：旧钢笔v1与铅笔均为**0像素变化**；新旧对照图与跨页截图已目视检查。

同一设备、同一测试方法，每包连续3轮，每轮30次增量绘制；计时含位图创建，未做物理触笔延迟测量。基线复用上轮已验证APK，不额外构建。

| 轮次 | 原中位数ms | 新中位数ms | 原P95 ms | 新P95 ms |
|---|---:|---:|---:|---:|
| 1 | 4.231119 | 1.516603 | 8.218039 | 8.057903 |
| 2 | 2.475582 | 1.574121 | 6.468836 | 11.253048 |
| 3 | 2.063249 | 1.099673 | 5.486873 | 6.645648 |

三轮中位数再取中位数：2.475582→1.516603 ms，观察到下降38.7%；三轮P95再取中位数：6.468836→8.057903 ms，增加24.6%。样本小且包含预热和模拟器调度差异，只能说明本次样本的中位开销降低，**没有证实尾部耗时改善或真机提速**。保留全部轮次，真机按PEN2-COST-01另测。

后续只改本轮笔刷逻辑时，可先在 `android` 目录运行以下定向检查；涉及共享存储/编码契约时再按影响扩大。当前通过后未重复整套存储、宏基准、LeakCanary或全应用lint。

```powershell
./gradlew.bat --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :core-domain:test --tests org.inkweft.core.BrushRecipeTest --tests org.inkweft.core.InkModelsTest --tests org.inkweft.core.InkSamplingRegressionTest
./gradlew.bat --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' --init-script verification/pen-optimization-20261011/app-identity.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest
```

使用现有JDK17、SDK36和 `E:/Inkweft/.gradle-home`；本轮不增加依赖。设备复现入口为本机归档的 `device-final/device-run.py`，只接受已登记的MuMu5与新建隔离安装；已有同名包会停止，先保留其资料再安排独立测试。

## 构件与归档

| 构件 | bytes | SHA-256 |
|---|---:|---|
| [独立测试应用](E:/Inkweft/archives/2026-10-11/InkWeft-Pen-Optimization/artifacts/InkWeft-pen-opt-20261011.apk) | 240448597 | `176220086a80a57bf1e902b46c70e0889d454d42c0150d864c2e5134ce6cab32` |
| [Android测试包](E:/Inkweft/archives/2026-10-11/InkWeft-Pen-Optimization/artifacts/InkWeft-pen-opt-20261011-androidTest.apk) | 16849912 | `e87b0f0a8358edbfea92645a2117a9a21bfba9b6ba0b36ead478bf74624eb43c` |

包名为 `org.inkweft.app.storageopt20261011` 及其 `.test`，显示名“墨织优化验证”；供隔离测试使用。本机归档根为 `E:/Inkweft/archives/2026-10-11/InkWeft-Pen-Optimization/`：

- `inputs/`：原ZIP安全展开副本；原ZIP仍在桌面。
- `source-before/`、`source-r1/`：本轮修改前与构建冻结源码；前序存储补丁仍在工作树。
- `verification/`：完整输入摘要、证据审阅、首次失败及定向复核XML、构建日志、APK身份、像素/逐方法/耗时结果。
- `baseline/`、`device-final/`：新旧包的全部轮次、原始runner、截图、安装及清理回执。
- `artifacts/`：最终交付包，保留原构件且未覆盖前序归档。

两轮自有隔离安装已卸载，MuMu5恢复关机、root=false、旋转1/0保持，私有ADB55131/55133停止。日志与失败证据已分类保存；物理设备未安装。真机项目及记录入口见[统一清单](../../DEVICE-TEST-CHECKLIST.md)和[本轮模板](results-template.md)，均待测。
