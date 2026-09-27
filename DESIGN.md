---
name: 墨织 Android 编辑器
description: 白纸、石墨文字与森林绿选态组成的紧凑书写工作台
colors:
  surface: "#ffffff"
  navigation: "#f7f8fa"
  workspace: "#f1f3f5"
  text: "#22272e"
  secondary: "#5e6670"
  accent: "#236653"
  selected: "#eaf3ee"
  divider: "#dee2e6"
  control-border: "#7e8792"
  danger: "#b42318"
typography:
  title-large:
    fontSize: "24sp"
    fontWeight: 600
  title-medium:
    fontSize: "20sp"
    fontWeight: 500
  body-large:
    fontSize: "16sp"
    lineHeight: "24sp"
  body-medium:
    fontSize: "14sp"
    lineHeight: "22sp"
  body-small:
    fontSize: "12sp"
    lineHeight: "18sp"
  label-large:
    fontSize: "14sp"
    fontWeight: 500
rounded:
  small: "8dp"
  medium: "12dp"
  large: "16dp"
spacing:
  compact: "4dp"
  inline: "6dp"
  group: "8dp"
  inset: "12dp"
  panel: "16dp"
components:
  editor-tool:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.secondary}"
    rounded: "{rounded.medium}"
    padding: "5dp 4dp"
  editor-tool-selected:
    backgroundColor: "{colors.selected}"
    textColor: "{colors.accent}"
    rounded: "{rounded.medium}"
    padding: "5dp 4dp"
  editor-options:
    backgroundColor: "{colors.navigation}"
    height: "56dp"
  editor-panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.large}"
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
  button-text:
    textColor: "{colors.accent}"
---

# Design System: 墨织 Android 编辑器

## Overview

**Creative North Star: "紧凑书写工作台"**

白纸承担内容，石墨色承担阅读，森林绿指明当前工具与主要动作，浅灰区分工具和纸面。手写记录与 PDF 批注同等重要；编辑器首先让用户找到工具、在纸面操作、查看结果，再回到书写。

参数靠近任务，以随内容收缩的浮层呈现。常用工具保留中文名称，说明按需展开，避免解释文字和空白挤占纸面。这一方向来自 `EditorControls.kt` 的方向契约与 `PRODUCT.md` 的已确认偏好。

本文件记录当前 Android 原生实现，颜色与字阶来源为 `android/app/src/main/java/org/inkweft/app/ui/designsystem/InkTheme.kt`。前置字段中的尺寸使用 Android 的 dp／sp，不应直接当作网页 px。未显式覆盖的组件外观和交互采用项目所用 Material 3 默认值；不在这里补造一套数值。范围为应用界面，不覆盖用户文档的配色与排版。

**Key Characteristics:**

- 白纸与浅灰工具区分层。
- 森林绿选态配合图标和中文名称。
- 常用入口可见，参数浮层紧凑。
- 先预览再应用，错误就地给出下一步。

## Colors

界面采用低干扰的浅色中性色，森林绿集中承担操作与选态。

### Primary

- **森林绿**（`accent`）：当前工具文字、主按钮、查找结果页码和可操作文字。
- **浅叶绿**（`selected`）：工具与选项选中背景、查找命中片段背景。与文字或图标共同表达状态。

### Neutral

- **白纸色**（`surface`）：应用表面、工具按钮和参数浮层。
- **导航浅灰**（`navigation`）：选项行、辅助操作区和浮层底部。
- **工作区灰**（`workspace`）：主题中的工作区背景令牌。
- **石墨文字**（`text`）：标题与正文。
- **次要灰**（`secondary`）：辅助说明、非选中工具。
- **分隔灰**（`divider`）：区域分界；**控件灰**（`control-border`）：需要辨认的控件轮廓。

错误使用 **警示红**（`danger`）并配文字。现有部分保存提示和对象操作仍有局部颜色；它们不构成新增的全局色阶。

**The 文档颜色独立 Rule.** 主题颜色只控制应用界面，不重写已保存的笔迹、PDF、图片或文本框颜色。笔盒色板是内容颜色，不是界面主题。

当前主题由 `lightColorScheme` 固定提供。系统夜间模式下的可读性检查不能解释为应用已经提供深色主题。

## Typography

界面使用 Android／Material 默认字体，未指定独立品牌字体。前置字阶列出 `InkTheme` 显式设置的角色；工具标签使用 Material 的 `labelMedium`，选中时加为 SemiBold。

- **Title large / medium**：对话框、浮层与层级标题。
- **Body large / medium**：主要内容、表单与可读的任务说明。
- **Body small**：范围说明、进度与辅助信息。
- **Label large**：按钮和结果页码等短标签。

字迹美化的系统黑体、霞鹜文楷与系统衬线是文档内容字体，不能替代界面字体。必要限制应保持可读，不通过缩小文字来换取紧凑。

## Layout

编辑器分为文档上下文、文档动作、工具与当前工具选项、纸面、页码和历史操作。六个主要工具依次为书写、荧光笔、橡皮、套索、美化、插入，设置独立在末端；查找位于文档标题区。工具栏可放顶部或底部。

- 文档标题行最小高度为 60dp，标题单行省略；常用文档动作在窄空间横向滚动。
- `EditorTool` 最小高度为 56dp，六个工具分配等宽空间，标签最多两行。参数区固定为一行 56dp，较多选项横向滚动。
- `EditorAction` 最小高度为 48dp。快捷颜色控件为 48dp；紧凑排列不能只缩小可点击区域。
- `EditorPanel` 位于可用区域右侧居中，外留 12dp，宽度不超过 380dp，高度不超过 600dp；高度随内容收缩。安全区域与键盘通过 `safeDrawing` 和 `imePadding` 留出空间。
- 浮层标题和关闭按钮独立成行，内容区水平留 16dp、垂直留 8dp；底部动作独立放在浅灰区。长内容在各自内容区滚动。
- 笔盒使用锚定参数入口的 320dp 下拉菜单；点击外部关闭，不向下方墨迹画布传递操作。
- 美化预览高 96dp；校对输入默认折叠，展开后高 96–160dp。文本框输入高 96–150dp。
- 当前布局依靠尺寸上限、权重、流式换行与横向滚动适配空间；这些编辑器组件未声明手机／平板的独立断点。

**The 保留纸面 Rule.** 参数内容能收缩就收缩，解释按需展开；增加选项时优先复用当前工具行或浮层，不长期占用新的纸面区域。

## Elevation & Depth

界面主要通过白色、浅灰、细分隔线与选态色区分层级。参数浮层采用原生 Dialog 的模态遮罩，笔盒采用 Material 下拉菜单；不要把原生默认阴影转换成未经测量的品牌阴影值。

`EditorPanel` 未显式设置阴影高度。独立键入文字页的白色编辑表面显式使用 1dp 的 `shadowElevation`，仅适用于该表面。当前没有自定义统一动效时长或缓动曲线。

## Shapes

主题提供小、中、大三级圆角。工具按钮使用中圆角，参数浮层使用大圆角；色样使用圆形。分隔线用于组织区域，不把每段说明都装进卡片。

原生 Button、OutlinedButton、FilterChip、OutlinedTextField 的其余形状和状态继承 Material 3。新增组件先复用这些组件与主题，不通过浏览器示意图反推原生细节。

## Components

### 工具与导航

`EditorTool` 使用上方图标、下方中文名称；选中后改变背景、前景与字重。图标来自随应用打包的 Material Symbols，`Glyph` 常规尺寸为 24dp；纯图标操作补充中文可访问名称。文档动作使用 `EditorAction` 的图标加文字形式。

工具选择与当前参数分开：普通书写提供常用笔 1／2；荧光笔自己的选项行保留颜色及细、中、粗预设。快捷线宽与笔盒均使用 `PenWidthStore.presets(tool)`，避免两处出现不同的同名预设。当前荧光笔预设为 12／22／34，颜色 alpha 为 `0x66`；这些是笔迹参数，不是界面尺寸或主题透明度。

### 按钮、选项与输入

主按钮提交当前任务，如应用美化、保存到此笔、选择文件；文字按钮承担取消、说明和次要动作。美化使用“保留原样”描边按钮与“应用美化”主按钮并列。

FilterChip 表示模式、字体和预设的可选状态。OutlinedTextField 保留明确字段名；查找框单行，含查找图标和可清除入口。错误通过文字说明，忙碌或缺少有效结果时按实际条件禁用提交。沿用原生焦点、按压与禁用反馈，不自行虚构鼠标悬停效果。

### 紧凑任务浮层

`EditorPanel` 复用于美化、查找、橡皮、文本框与导入。标题、关闭、可滚动内容和底部动作的层级保持一致；空副标题不占位。帮助折叠不能隐藏必须在提交前知道的限制。

导入默认呈现支持格式与源文档限制（32 MB／500 页、无密码），其他格式和转换办法按需展开，主要动作始终为“选择文件”。

### 美化字迹

从可见“美化”工具进入，选择换字体或笔形润色。换字体流程在选区识别后显示预览、字体、加粗、字号和行距；“校对”展开识别文字，“说明”展开限制与重新识别入口。加载、识别失败与文字放不下均有对应状态。

**The 如实预览 Rule.** 字体美化是选区 OCR 后换字，不能写成实时自动换字。应用保留原迹关联；通过插入模式选择转换后的文字，可执行“恢复原迹”。

### 查找结果

界面用“查找”“输入关键词”“识别手写”“校对当前页”等任务语言。输入关键词后可自动开始识别；底部呈现进度、暂停、重试或校对。结果按页面列出页码与最多三行摘录，命中词以浅叶绿和加粗标记，点击跳转页面。

搜索范围为手写与文本框，明确显示暂不含 PDF 原文。此文档约束展示范围，不对识别准确率或所有更新时序作额外保证。

### 保存与恢复反馈

保留页面底部的保存状态、撤销与重做入口。待核对、读取失败等状态在需要时显示可操作提示；不能仅用绿色或一次 Toast 宣称保存成功。对象编辑与笔迹历史各自遵循现有操作边界。

## Do's and Don'ts

### Do:

- **Do** 保留六个常用工具的中文名称，让美化和查找入口直接可见。
- **Do** 复用紧凑浮层、当前工具选项行与按需展开的帮助。
- **Do** 在预览、忙碌、空结果、错误和待核对状态给出符合真实行为的反馈。
- **Do** 区分界面主题与用户内容样式，保持保存、撤销和原迹恢复边界。
- **Do** 将新增真机验证需求维护到 `android/DEVICE-TEST-CHECKLIST.md`，按设备与版本记录结果。

### Don't:

- **Don't** 把内部索引、派生数据或存储术语作为主要操作名称。
- **Don't** 用常驻长说明和空白扩张参数面板。
- **Don't** 将选区换字体描述为实时自动美化，或将当前查找描述为 PDF 全文搜索。
- **Don't** 将系统夜间模式下可读描述为已提供深色主题。
- **Don't** 将局部界面复核、模拟输入或截图检查扩大成全功能、压感、掌拒或笔感认证。
