# 墨织 · 玻璃材质实验

独立、零运行时依赖的 **Web 交互原型**。默认使用克制融合，可切换 Glass Card 和 Liquid Glass 两种参考方向。不是 Android 原生效果，也没有接入正式编辑器或笔记数据。

## 查看

GitHub 的文件页面展示源码，本身不是交互网站。下载 [`downloads/inkweft-glass-demo.zip`](downloads/inkweft-glass-demo.zip) 后解压，再用浏览器打开包内的 `index.html`。也可以在 [`index.html`](index.html) 文件页面点击 **Download raw file**，将文件保存为 `index.html`（不要保存成 `.txt`），再打开；主 demo 不依赖其他文件。三栏对照页需要和主文件放在一起。

- `index.html`：完整单文件 demo，HTML/CSS/JavaScript 全部内嵌，无资源请求。
- `compare.html`：同时嵌入三种方案的对照页，需要与 `index.html` 放在同一目录。
- `evidence/three-styles.png`：浏览器直接渲染三个真实 iframe 的对照截图，没有合成或生成式修图。
- `evidence/blend-writing.png`、`blend-layers.png`、`blend-map.png`：推荐方案的书写、图层和导图状态。
- `evidence/map-375.png`：375px 窄屏，原文定义与四个导图概念同时可见。

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
3. 图层选择、显隐和浓度会实际影响批注与试写。隐藏层拒绝落笔；隐藏高亮不隐藏印刷正文。
4. 导图默认与原文对照，点击节点后可以定位到实际对应段落。窄屏打开导图时直接显示选中概念的定义。关闭后保留阅读位置。
5. 窄屏图层和导图互相切换，避免两个面板挤成无法阅读的细栏；宽屏可同时打开。
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

## 验证与界限

`python verify.py http://127.0.0.1:4180` 使用云环境已有的 Playwright 和 Chromium。运行时不需要它们，原型不引入 React、Vite、SDK 或生产依赖。

最终 **59 项检查通过**，证据在 `evidence/validation.json`：

- 三方案切换、相同正文、透明度与无滤镜纸面；可见纸面截取逐字节像素一致。
- 笔色笔宽、荧光、撤销重做、擦除撤销、图层显隐/浓度、隐藏层拒写、阅读模式。
- 真正段落滚动定位、关闭导图保留上下文、弹窗 Escape/焦点返回、刷新重置。
- 1440×1000 主截图；1200×800、800×1100、390×844、375×800 布局检查；窄屏四节点及定义可见。
- 减弱动态效果、无 JavaScript 运行错误、无外部资源/数据请求、无 localStorage/sessionStorage 写入。

截图均是云端 **Web 原始渲染**，没有运行用户电脑或真机。原型 SVG 试写是材质与操作演示，不是生产 Ink 引擎；笔迹采用相对纸面的展示坐标，响应式正文重排时不承诺与某个字的生产级锚定。压感、掌拒、原生输入延迟、Android 模糊性能、正式保存/恢复及跨设备一致性均未验证。

Android 后续适配应继续使用既有 Compose / Ink / 数据路径，只把这里的色彩、圆角、边缘光与面板状态映射过去。SwiftUI 显式重绘底图和 CSS backdrop-filter 都不能直接视为 Android 可复用组件。当前成果未合并、未部署生产、未公开托管。
