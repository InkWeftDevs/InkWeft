# 墨织 InkWeft · S1 纵向切片样品

这是**样品**，不是产品，更不是验收通过。它把规划包 `inkweft_card_axis_plan_v1_1_1`
里 **K00 / K01 / K02** 的核心合同做成可运行、可点、可自我检查的一小块：一次真实摘录，
同一张卡在原文、脑图节点、手动问答投影三处共享同一个 `cardId` 与同一个 `headRevisionId`。

工具链：**零第三方依赖**，只用语言标准库。可离线运行。

---

## 1. 怎么跑

```powershell
# 方式一：直接双击 index.html（零依赖单文件，file:// 也能用）
start index.html

# 方式二：起本地服务（推荐，见下）
python tools/serve.py 8777
# 然后打开 http://127.0.0.1:8777/index.html

# 自检（不需要浏览器）
node tests/run-headless.js     # 模型/命令/持久化/导出，29 项
node tests/check-build.js      # 产物结构、id 引用、离线约束
node tests/check-ui-boot.js    # 在 mock DOM 里跑真实 UI 启动路径

# 起了服务之后，再验一次“HTTP 来源 + 字节哈希”这条路径
node tests/check-http-origin.js 8777
```

**为什么推荐方式二**：`file://` 下浏览器禁止 `fetch()` 读取同目录文件，于是
“真实 PDF 字节哈希未变”这一条会降级为“已记录哈希但未复核字节”（界面会明说）。
用 HTTP 打开时它会真的去读 `assets/conditional-probability-note.pdf` 并算 SHA-256。

改源码后要重建单文件产物：

```powershell
node tools/build.js     # 由 src/*.js + src/style.css + src/index.template.html 生成 index.html
```

---

## 2. 界面里的三件东西（对应三条验收主线）

| 面板 | 内容 | 它在证明什么 |
|---|---|---|
| ① 原文 | 合成文字流的可选中段落，摘录处有真实坐标高亮 | 选区 → 每页一个 `SourceAnchor`；几何状态明确标为 `EXACT_SYNTHETIC` |
| ② 知识卡 | **唯一权威正文**：源摘录块(只读) + 个人备注块(可编辑) + 节点 + 问答投影 + 修订链 | 三处视图都调 `IW.readCardHead()`，没有第二份副本，也没有定时同步 |
| ③ 结构 | 锚点解析状态、脑图出现位置、标记、**命令历史与幂等摘要** | 每次写入都能看到 `commandId` 与语义摘要，撤销按钮就在旁边 |
| ④ 故障与恢复 | 注入写失败、推进 epoch、导出 S1 原生包、隔离恢复、运行日志 | 保存失败不谎报；恢复后引用全部可解析 |

**上手顺序**：在 ① 里划一句 → ② 里看到新卡 → 改备注并保存 → 在 ③ 里看到新修订与命令记录 →
按撤销 → 打开 ④ 勾“注入失败”再保存（会显示 NOT SAVED）→ 取消勾选 → 导出并隔离恢复。

---

## 3. 这个样品真的实现了什么

**K00（共享知识卡与原子写入）**

- 唯一写入口：`engine.run(commandType, params, header)`。UI 只调它，从不直接改 vault。
- 不可变链：`BlockRevision`（正文）与 `CardRevision`（快照）只追加；
  `CardRevision.blockSnapshotIds` **只能指向 BlockRevision.id**，用 ContentBlock.id 冒充会被拒。
- 命令级幂等：同 `commandId` + 同语义摘要 → 返回**原回执**，不重复推进版本；
  同 `commandId` + 不同载荷/期待版本 → `COMMAND_ID_REUSE`，原回执不被覆盖。
- 语义摘要刻意排除可刷新的 `capabilityHandle` 秘密、retry 时间与 traceId。
- 授权先于回执：撤销授权后，**连旧 commandId 的重放都被拒**（`SOURCE_RESTRICTED`）。
- 原子性：命令在副本上执行 → 校验全量引用闭包 → 才发布并写回执；
  任何一步失败都是「整项未发布」，预映像字节不变。
- `expectedVersions` 不匹配 → `EXPECTED_VERSION_MISMATCH`，并给出“重新读取并保存”的更正路径，
  而不是盲覆盖。
- 撤销：恢复到该命令的预映像，且只允许撤销最新一条，旧撤销不会盖掉别处的新改动。
- vault epoch：`resetVaultEpoch` 后，旧 epoch 会话的写入被拒。

**K01（来源与几何）**

- `DocumentSurfaceAdapter` 的七个方法按 `spec/interface-contract.json` 落地
  （`openImmutable / renderPage / getTextPage / toCanonical / fromCanonical / resolveSelector / exportPresentation`）。
- `TextSurface`：真实可用的行盒几何，坐标空间是**全局连续偏移**，
  于是一次跨页选区可以被逐页切开。
- `PdfSurface`：`assets/conditional-probability-note.pdf` 是**真实 PDF**（1774 字节，xref 校验通过），
  但本包**不内置 PDF 引擎**（离线、零依赖），所以它诚实报告
  `rendersPages=false / textGeometry=UNAVAILABLE`，几何查询返回 `PDF_ENGINE_MISSING`，
  **绝不猜一个页面**。
- 解析状态词汇分离：`EXACT_SYNTHETIC / STALE / MISSING / RESTRICTED`。
  换底稿版本后旧锚点保持 `STALE` 且旧版本保留；撤权后返回 `RESTRICTED` 且不泄漏标题/文本/计数。

**K02（最小三视图贯通）**

- 摘录一次 → 卡 + 每页一个锚点 + 原文标记，**同一事务**内提交。
- 同一 `cardId` 出现在：原文标记、一个脑图节点、一个 `ReviewItem` 问答投影。
- 一个 `ReviewItem`，只有身份与手动问答投影：**没有 FSRS、没有到期队列、没有评级、没有多题历史**，
  `ReviewState.schedulerVersion` 就是 `UNASSIGNED`、`dueAt` 是 `null`。
- 普通荧光标记与摘录**分离**：荧光只产生标注，不建卡、不入图、不入复习队列。
- 导出/隔离恢复：S1 最小原生包带整体完整性摘要，恢复后逐项校验引用闭包；
  包内显式列出**不包含**什么，恢复报告里也会说明撤销在恢复后不可用。

---

## 4. 自检覆盖（29 + 1 + 1 项，全部当前通过）

`node tests/run-headless.js` 的每一项都注明它对应哪条 S1 断言：

| 检查 | 对应 S1 断言 |
|---|---|
| `AX01-A01..A03` | 三处入口同一 `cardId`/`headRevisionId`；源哈希未变；无词典/无 FSRS/无伪造历史 |
| `AX02-A01..A04` | 已提交修订上可见 + 撤销；中间修订在后续编辑后仍可解析；源摘录与原件哈希不被改写；节点改宽只作用于该出现位置 |
| `AX03-A01..A04` | 追加第二来源不丢失；跨页选区→每页一个锚点且不夹带整页；换版本保留旧锚点报 `STALE`；撤权报 `RESTRICTED` 不泄漏 |
| `AX33-A01..A03` | 荧光不建卡；摘录原子建卡且不自动入队；取消不留半卡/悬空锚点/假回执 |
| `AX34-A01..A05` | 同 ID 同载荷重放返原回执；同 ID 异载荷/异期待版本 `COMMAND_ID_REUSE`；撤权后连重放都拒；令牌轮换不改摘要；期待版本冲突被拒且有更正路径 |
| `AX28-A01..A06` | 写失败不改进持久状态且可用原 commandId 复查；半成品闭包不可能发布；S1 包导出+隔离恢复全引用可解析；篡改与「块ID冒充快照」被检出；撕裂值在载入时被拒 |
| `STORE-01/02` | 重开字节一致；两个写者冲突而非互相覆盖；epoch 阻断旧会话 |
| `STRUCT-01..03` | 两次播种逐字节一致；S1 范围是**强制**的（越界命令不存在）；PDF 来源真实、能力诚实、字节可校验 |

`node tests/check-build.js`：产物无外链、无 `http(s)://`、无外置样式/脚本；
UI 里 `el('...')` 用到的 **43 个 id 全部存在**；模块拼接顺序正确。

`node tests/check-ui-boot.js`：在 mock DOM 里跑真实启动路径，并依次演练
播种、保存备注、版本冲突与更正、加节点与改宽、撤销、导出、隔离恢复、
注入写失败、epoch、荧光标记、摘录建卡；随后**用同一份 localStorage 再启动一次**，
验证「重开」走的是恢复分支而不是重新播种、已提交内容与锚点仍在、
撕裂的存储值会被拒绝而不是半份载入。

`node tests/check-http-origin.js 8777`（需先起 `serve.py`）：在真实 HTTP 来源下重新读取
`assets/conditional-probability-note.pdf` 的字节并复算 SHA-256，与已记录值一致；
并用这个哈希播种一次，证明「原件字节未变」是对着字节验的，不是对着自己写的数字验的。
这一项**不覆盖** IndexedDB/原生持久化，也不覆盖耐久性 —— 那些仍是 `NOT_RUN`。

---

## 5. 明确没做（不要把这个样品当成那些能力）

- **真实 PDF 渲染与文字几何**：需要本机自备 PDF.js 或 MuPDF，本包不内置。
- **手写/笔迹**：样品里没有任何墨迹、压感、套索、掌拒。
- **沉浸回忆（K07）**：没有读写忆三态、没有遮挡、没有防侧栏泄题。
- **嵌入留白（K05）**：只有页边标记（`AnnotationPlacement`）与留白位置数据，没有分段布局与导出。
- **完整脑图（K06）**：只有一个平面节点列表与位置/宽度，没有树形层级 UI、没有子图/关系/合并拆分。
- **词典与别名（K03）**：`dictionarySources`/`dictionaryEntries` 是**空集合**，没有词条匹配。
- **正式复习调度（K08）**：没有 FSRS、没有 ReviewEvent、没有作答历史、没有到期队列。
- **同步与多设备**：只有一个本地单键存储。
- **真机**：没有在 Android、任何平板或任何笔设备上运行过。

**并且**：规划包里的 37 条 AX 用例仍然是 `NOT_RUN`。本样品的自检是**这个样品自己的**检查，
不是 AX01-FULL 等 profile 的执行证据 —— 那些需要真机构件、真实来源浏览器与外部 ACK oracle。
`tests/run-headless.js` 的输出里也印着这句话。

---

## 6. 与规划包 v1.1.1 的关系

已对齐：命令头字段与语义摘要规则、`CardRevision.blockSnapshotIds` 只指向块修订、
一卡多视图单一权威正文、`ReviewState` 复合身份、S1 不做词典/调度/嵌入留白、
导出包显式标注范围外内容、PDF 能力诚实降级。

**本样品主动记录的三处偏离**（实现工作区可以有自己的取舍，但不能不说）：

1. `MapOccurrence` 的呈现数据（位置/宽度）实际写在 `layoutOverride` 内，
   而规划包夹具契约把 `layoutOverride` 与 `siblingOrder` 并列。样品保持嵌套，
   如果实现阶段要展平，应以夹具契约为准。
2. 夹具契约给 `cards` 定义了 `tags`，样品同样保留；但**卡片与专题的成员关系**
   样品用 `card.studySetId` 单值表达，而规划包的 `StudySet` 有 `cardRefs` 数组。
   一旦一张卡要属于多个专题，这个单值就必须换成成员关系表。
3. `markHighlighter` 为普通荧光标记创建 `SourceAnchor`，于是 `annotationPlacements`
   出现 `cardId: null` 的行。夹具契约要求标注可解析到卡；样品在不变式检查里
   把 `cardId: null` 视为**合法的标注**、`cardId` 非空才必须可解析。
   如果实现阶段要让荧光标记不产生锚点，这条要改。

另有两条**已知的样品级简化**，都不伪装成完整实现：

- 撤销靠保存每条命令的完整预映像，小资料库可行，真实数据量下要换成细粒度逆变更。
- `commitResolveConflict` 用**新增一个备注块**的方式保住两个视图各自的文字；
  真实产品应把它建模为冲突解决，而不是并列两个块。

---

## 7. 文件

```text
inkweft/
  index.html                  ← 零依赖单文件产物（由 build.js 生成，不要手改）
  assets/
    textbook-excerpt.txt      文本来源（真实文件，哈希会被复核）
    conditional-probability-note.pdf  真实 PDF（1.7 KB，xref 有效，无内嵌字体）
  src/
    00-core.js                身份（UUIDv5 形状+抗碰撞推进）、语义摘要、时间、错误码
    10-vault.js               vault 形状、不可变修订链、全量引用闭包不变式
    20-document.js            DocumentSurfaceAdapter 接口 + TextSurface / PdfSurface、本地 SHA-256
    30-commands.js            唯一写入口：幂等、原子提交、授权先检、撤销重做
    40-store.js               单键同步持久化 + 校验和 + 故障注入钩子
    50-export.js              S1 原生包导出/导入与逐项校验
    60-seed.js                演示语料与首张卡的播种计划
    70-ui.js                  四面板界面；只调 engine.run()
    style.css / index.template.html
  tools/
    build.js                  拼接成单文件 index.html
    serve.py                  本地 HTTP 服务（让 PDF 字节校验真正跑起来）
    make_sample_pdf.py        重新生成那份 PDF（可复现）
  tests/
    run-headless.js           29 项模型/命令/持久化/导出自检（--json 可出机器可读结果）
    check-build.js            产物结构与离线约束检查
    check-ui-boot.js          mock DOM 下的 UI 启动冒烟
    check-http-origin.js      HTTP 来源下的字节哈希复核（需先起 serve.py）
  evidence/
    host-check.json           29 项自检的机器可读原始输出
    build-check.txt           check-build.js 原始输出
    ui-boot-check.txt         check-ui-boot.js 原始输出
    http-origin-check.txt     check-http-origin.js 原始输出
  sample-REPORT.md            实现报告：改了什么、跑了什么、没跑什么
```

---

## 8. 一句诚实的话

这个样品能证明的是：**共享内容模型、单写入口、幂等与原子提交这些“地基层”是能落地的，
而且能在一张真实的摘录卡上跑通。**

它不能证明的是：任何 PDF 渲染质量、任何手写手感、任何调度算法正确性、任何真机性能。
那些仍然需要实现方按 `spec/acceptance.json` 的 profile 逐条真跑，并把状态从 `NOT_RUN` 改过来 ——
本样品不会替它们改。
