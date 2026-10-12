# 墨织 · 玻璃材质实验

独立、零运行时依赖的 **Web 交互原型**。默认使用克制融合，可切换 Glass Card 和 Liquid Glass 两种参考方向。不是 Android 原生效果，也没有接入正式编辑器或笔记数据。

## 查看

GitHub 的文件页面展示源码，本身不是交互网站。下载 [`downloads/inkweft-glass-demo.zip`](downloads/inkweft-glass-demo.zip) 后解压，再用浏览器打开包内的 `index.html`。也可以在 [`index.html`](index.html) 文件页面点击 **Download raw file**，将文件保存为 `index.html`（不要保存成 `.txt`），再打开；主 demo 不依赖其他文件。三栏对照页需要和主文件放在一起。

- `index.html`：完整单文件 demo，HTML/CSS/JavaScript 全部内嵌，无资源请求。
- `compare.html`：同时嵌入三种方案的对照页，需要与 `index.html` 放在同一目录。
- `evidence/three-styles.png`：浏览器直接渲染三个真实 iframe 的对照截图，没有合成或生成式修图。
- `evidence/blend-writing.png`、`blend-layers.png`、`blend-map.png`：推荐方案的书写、图层和导图状态。
- `evidence/map-375.png`：375px 窄屏，显式点击「定位原文」后的定义与导图。
- `evidence/layer-geometry.json`：三种材质及多种视口下，图层打开前后的纸面、阅读视口、滚动位置和 SVG 变换实测值。

在此目录启动静态服务即可查看：

```sh
python -m http.server 4180 --bind 127.0.0.1
# http://127.0.0.1:4180/index.html
# http://127.0.0.1:4180/compare.html
```

单文件不使用 fetch、模块导入或外部字体，可用于离线文件交付。本次云端 Chromium 的策略禁止 `file://` 导航，因此实际验证走 HTTP；没有把 file 协议验证宣称为通过。上述回环地址只属于执行环境，不是对外托管链接。

## 可操作的范围

1. 切换三种材质、三种环境主题；同一篇合成数学学习笔记保持不透明、高对比。
2. 选钢笔、荧光笔、颜色和笔宽，进行临时试写；可撤销、重做及擦除。擦除也可撤销。
3. 图层是覆盖纸面的弹窗，窄屏为覆盖式底部 sheet；开关不占用文档布局，不改变纸面尺寸、缩放或阅读位置。关闭按钮、外部点击、Escape、浏览器返回均可关闭并恢复图层按钮焦点。弹窗内部操作和关闭手势不会穿透成笔迹。
4. 导图独立处理：宽屏默认停靠对照，较窄视口使用覆盖式非全屏浮窗并保留纸面视口。打开时不自动跳段落；只有点击「定位原文」才滚动。图层开关不会关闭、重排或重置导图。
5. 图层显隐和浓度会实际影响批注与试写；浏览隐藏层不改变落笔目标。保留至少一个可写层；隐藏当前书写层后暂停落笔，重新显示不会自动恢复选择，须明确点选可写层。隐藏高亮不会隐藏印刷正文。
6. 阅读按钮切换正文选择和触屏滚动。试写只在内存中，刷新即重置；界面没有保存、同步、导出等假成功状态。

## 两个参考的实际核验

| 参考 | 固定版本与已核验内容 | 本原型如何运用 |
| --- | --- | --- |
| [Glass Card](https://github.com/alexko0421/glass-card/tree/bcfa6d2110155dbeeef0c4faced6aec9cfe253c7) | macOS 14+ / SwiftUI。读过 `GlassCardMaterial.swift`、`GlassSwatch.swift`、README、MIT LICENSE；实际查看官方 material / layered PNG。材料显式重绘调用方底图，并非自动捕获后方界面。 | 彩色半透明层、柔雾、浅色细边与柔阴影；只用于工具和面板。本原型使用 CSS 近似，没有导入 SwiftUI 包。 |
| [Liquid Companion](https://github.com/aibqoq-cmyk/liquid-glass-design/tree/e4c7ba834e7b37e9fb76cec220ff3c74f1c3aee7) | React / Vite。读过 `glass.css`、`tokens.css`、`presets.js`；已在独立参考目录安装依赖并用云端 Chromium 渲染默认首页。真实默认是奶油粉点阵、乳白卡、暖棕字；后端未连接时展示原项目的不可用状态。 | 奶油粉环境、乳白透光、冷暖边缘高光及轻微按压；只对一级容器做 backdrop blur，子控件使用透明填充。 |

Glass Card 为 MIT，版权 2026 Chunlong Ko。Liquid Companion 设计署名为 **@汪汪豆泥**，该仓库当前未附通用 LICENSE；用户在本线程明确表示已获得作者授权，可直接复制代码和素材。本原型最终采用原创 HTML/CSS/JS 和自行绘制的 SVG，仅参考其材质原则，没有再分发两个参考仓库的源码、图标或截图；没有为第二个项目虚构 MIT 许可或公共再许可。

## 隔离与布局基线

在独立 clone 中工作，草稿分支 `draft/glass-ui-study-20261012`，基线是 PR19/V86 的 `8596a981a76e27c38caf5220659e0e238141d319`。阅读了当前 README 及设计验证记录。

沿用用户认可的 `0219ca84` / `5916cb0` 的轻量双行导航、书写工具和大纸面方向；明确排除了记录中已被用户否定的整页设计。只在 `prototypes/glass-study/` 新增文件，没有修改 Android、正式编辑器、数据库、已有分支或共挂载 AniMemo。

## 图层与导图行为依据

本次修正对照 PR19/V86 的生产代码，而不是沿用先前 demo 的占位侧栏：

- [`EditorControls.kt`](../../android/app/src/main/java/org/inkweft/app/EditorControls.kt) 的 `EditorPanel` 使用可获取焦点的 `Popup`；`PanelKind.LAYERS` 在窄屏采用底部位置，最大高度受限。它不参与纸面测量布局。
- [`InkScreen.kt`](../../android/app/src/main/java/org/inkweft/app/InkScreen.kt) 为图层按钮提供锚点，并独立调用 `PageLayersPanel`。
- [`PageLayersPanel.kt`](../../android/app/src/main/java/org/inkweft/app/PageLayersPanel.kt) 区分浏览层与当前书写层，隐藏/锁定层只供浏览；[`UserLayers.kt`](../../android/core-domain/src/main/kotlin/org/inkweft/core/UserLayers.kt) 要求至少一个可写层，隐藏当前层会清空书写选择。本原型遵循所展示子集的这些规则，未扩展真实数据操作。
- [`BookInkScreen.kt`](../../android/app/src/main/java/org/inkweft/app/BookInkScreen.kt) / [`FloatingStudyWindow.kt`](../../android/app/src/main/java/org/inkweft/app/FloatingStudyWindow.kt) 独立处理导图：默认宽屏停靠，紧凑布局浮在保留的纸面上，默认不全屏。原型只展示这两种有限状态，没有冒充生产导图的拖动、尺寸偏好、最小化、专注模式或完整来源返回流程。

## 验证与界限

`python verify.py http://127.0.0.1:4180` 使用云环境已有的 Playwright 和 Chromium。运行时不需要它们，原型不引入 React、Vite、SDK 或生产依赖。

最终 **103 项检查通过**，证据在 `evidence/validation.json`：

- 三方案切换、相同正文、透明度与无滤镜纸面；可见纸面截取逐字节像素一致。
- 笔色笔宽、荧光、撤销重做、擦除撤销、图层显隐/浓度、隐藏层拒写、阅读模式。
- 图层开关前后纸面 bounds、阅读视口、滚动位置、CSS transform/zoom、SVG 坐标矩阵一致；三种材质均检查，已打开导图时亦保持。
- 弹窗内拖动、外部点击、Escape、浏览器返回、焦点返回、关闭后恢复试写；补充 Chromium 触屏模拟的控制点按和关闭点按事件隔离。
- 真正段落滚动定位、打开导图不自动定位、图层不改变导图选中节点/几何/阅读上下文、刷新重置。
- 1440×1000 主截图；1200×800、800×1100、390×844、375×800 布局检查；显式定位后窄屏四节点及定义可见。
- 减弱动态效果、无 JavaScript 运行错误、无外部资源/数据请求、无 localStorage/sessionStorage 写入。

截图均是云端 **Web 原始渲染**，没有运行用户电脑或真机。原型 SVG 试写是材质与操作演示，不是生产 Ink 引擎；笔迹采用相对纸面的展示坐标，图层弹窗已验证不触发重排；真正的浏览器尺寸改变或导图宽屏停靠仍可使 Web 正文重排，不承诺与某个字的生产级锚定。压感、掌拒、原生输入延迟、Android 模糊性能、正式保存/恢复及跨设备一致性均未验证。

Android 后续适配应继续使用既有 Compose / Ink / 数据路径，只把这里的色彩、圆角、边缘光与面板状态映射过去。SwiftUI 显式重绘底图和 CSS backdrop-filter 都不能直接视为 Android 可复用组件。当前成果未合并、未部署生产、未公开托管。
