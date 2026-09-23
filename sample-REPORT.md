# 样品实现报告（S1 纵向切片）

依据规划包 `inkweft_card_axis_plan_v1_1_1` 的 `K00 / K01 / K02` 范围。
报告分列：改了哪些文件、在什么环境跑、跑了哪些 profile/断言、原始证据在哪、**没测什么**、旧资料怎么处置。
格式按 `DEVELOPER-ENTRY.md` 的要求。

状态：**SAMPLE / PARTIAL**。应用验收：**37 条 AX 全部保持 `NOT_RUN`**，本报告不改动它们。

---

## 1. 文件改动

全部位于 `E:\Inkweft\inkweft\`，此前为空目录。写入方式均为新增，**未修改** `inkweft-r6/`、
`inkweft_card_axis_plan_v1_1_1/` 或任何其他既有目录。

| 文件 | 用途 |
|---|---|
| `index.html` | 单文件零依赖产物（`tools/build.js` 生成；147,097 字节；无外链、无 `http(s)://`） |
| `assets/conditional-probability-note.pdf` | 真实 PDF 来源（1774 B，xref 偏移逐项校验通过，无内嵌字体） |
| `assets/textbook-excerpt.txt` | 文本来源（408 B，UTF-8，LF） |
| `src/00-core.js` | 身份、语义摘要、时钟、错误码 |
| `src/10-vault.js` | vault 形状、不可变修订链、引用闭包不变式 |
| `src/20-document.js` | `DocumentSurfaceAdapter` + `TextSurface`/`PdfSurface`、本地 SHA-256 |
| `src/30-commands.js` | 唯一写入口（幂等/原子/授权/撤销） |
| `src/40-store.js` | 持久化 + 校验和 + 故障注入 |
| `src/50-export.js` | S1 原生包导出/导入校验 |
| `src/60-seed.js` | 演示语料与播种计划 |
| `src/70-ui.js` | 四面板界面 |
| `src/style.css`、`src/index.template.html` | 样式与模板 |
| `tools/build.js` | 源文件 → 单文件产物 |
| `tools/serve.py` | 本地 HTTP 服务 |
| `tools/make_sample_pdf.py` | 可复现地重新生成 PDF |
| `tests/run-headless.js` | 39 项模型/命令/持久化/导出/审阅回归自检 |
| `tests/check-build.js` | 产物结构、id 引用、离线约束 |
| `tests/check-ui-boot.js` | mock DOM 下 UI 启动冒烟 |
| `evidence/host-check.json` | 39 项自检的机器可读原始输出 |
| `evidence/build-check.txt`、`evidence/ui-boot-check.txt` | 另两项的原始输出 |
| `README.md`、`sample-REPORT.md` | 说明与本报告 |
| `LICENSE` | AGPL-3.0 完整标准文本（34273 B） |
| `REVIEW-RESPONSE.md` | 对 `REVIEW.md` 报的 D01–D08 的逐条修复说明与回归 |

---

## 2. 实际环境

| 项 | 值 |
|---|---|
| 平台 | Windows，`win32 x64` |
| Node | `v24.12.0`（`node --version`） |
| Python | `3.12.10`（仅用于 `serve.py` 与 `make_sample_pdf.py`） |
| 第三方依赖 | **零**。无 `package.json`，无 CDN，无 `http(s)://` 引用（`check-build.js` 强制） |
| 网络 | 构建与自检全程不需要；构建期曾探测 jsDelivr 失败，因此决定不引入 PDF.js |
| 浏览器 | **未运行**。UI 启动路径在 mock DOM 中执行（见 §4），不是真实渲染 |
| 真机 | **未运行**。没有 Android、没有平板、没有笔设备 |

---

## 3. 已执行的断言（39 项，全部 PASS）

原始输出：`evidence/host-check.json`（`scope: SAMPLE_SELF_CHECK_NOT_PLAN_ACCEPTANCE`，
`runtime_cases_executed` 概念上为 0 —— 这不是应用运行）。

| 断言 ID | 结果 | 关键证据串 |
|---|---|---|
| AX01-A01 | PASS | 三个入口（annotation/occurrence/reviewItem）解析到同一 `cardId` 与同一 `headRevisionId` |
| AX01-A02 | PASS | 记录的 PDF 哈希 `fc4d5eef9175…` 与磁盘字节实时 SHA-256 一致；问答投影不含正文 |
| AX01-A03 | PASS | `dictionary=0 events=0 attempts=0 scheduler=UNASSIGNED dueAt=null` |
| AX02-A01 | PASS | rev `1→2→1`，撤销恢复原修订 |
| AX02-A02 | PASS | 源摘录块修订冻结；2 个锚点复现被提交的引文；来源哈希未变 |
| AX02-A03 | PASS | 节点宽度 340 只作用于 map1；卡修订不动；map2 节点仍是 220 |
| AX02-A04 | PASS | 链 `3←2←1`；中间修订文本在后续编辑后仍可解析 |
| AX03-A01 | PASS | 来源数 2→3；pageId 均以 sourceVersion 为前缀 |
| AX03-A02 | PASS | 跨页选区 → 2 个锚点（`p0` | `p1`），各 1 行；引文精确等于选区而非整页 |
| AX03-A03 | PASS | 换版本后旧锚点保持 `STALE`，旧版本 `RETAINED`，未静默重指 |
| AX03-A04 | PASS | 撤权后 `RESTRICTED`，且不泄漏标题/文本/计数 |
| AX33-A01 | PASS | 荧光标记：卡 1→1、节点 1→1、标记 1→2、`cardId=null` |
| AX33-A02 | PASS | 摘录原子产出卡 + 2 个每页锚点 + 标记；未自动入图入队 |
| AX33-A03 | PASS | 取消 → `SURFACE_REQUIRED` / `EMPTY_SELECTION`；卡/锚点/回执数均不变 |
| AX34-A01 | PASS | 重放返回 `REPLAYED`，修订与块修订数冻结 |
| AX34-A02 | PASS | 改载荷与改期待版本都 `COMMAND_ID_REUSE`，修订冻结 |
| AX34-A03 | PASS | 撤权后重放与新命令都 `SOURCE_RESTRICTED`；重新授权后 `ACK` |
| AX34-A04 | PASS | `cap-1111`/`cap-9999`/`cap-2222+traceId` 三态同一摘要、同一回执 |
| AX34-A05 | PASS | 旧版本写入 `EXPECTED_VERSION_MISMATCH (actual rev 2)`；更正后两份文字都在 |
| AX28-A01 | PASS | 写失败：commitSeq 不前进、durable 字节不变；重开后可复查原 commandId |
| AX28-A02 | PASS | 三类非法命令被拒（`SOURCE_VERSION_NOT_FOUND`/`ANSWER_BLOCK_FOREIGN`/`INVARIANT_VIOLATION`），不变式仍干净 |
| AX28-A04 | PASS | 导出 → 隔离恢复：15 项检查全 PASS，`snapshots=2`，警告仅 `ASSET_BYTES_NOT_PROVIDED`、`UNDO_UNAVAILABLE_AFTER_RESTORE` |
| AX28-A05 | PASS | 篡改 → `PACKAGE_TAMPERED`；块 ID 冒充快照 → `IMPORT_CHECK_FAILED` |
| AX28-A06 | PASS | 截断的存储值 → `STORE_CORRUPT`，不返回半份 vault |
| STORE-01 | PASS | 重开后 vault 与回执日志逐字节一致；修订号续接 |
| STORE-02 | PASS | 并发写 `EXPECTED_VERSION_MISMATCH`（A 的提交保留）；epoch 后旧会话 `VAULT_EPOCH_MISMATCH` |
| STRUCT-01 | PASS | 两次播种逐字节一致（7132 canonical bytes） |
| STRUCT-02 | PASS | `recordReview`/`registerAliases`/`combineCards`/`splitCard` 不存在；删节点不删卡 |
| STRUCT-03 | PASS | PDF 为真：`%PDF-` 头、哈希 `fc4d5eef…`；表面报告 `rendersPages=false`；解析器返回 `MISSING/PDF_ENGINE_MISSING` 而非猜测 |

另两项：

- `check-build.js`：**PASS** —— 无外部脚本/样式/绝对 URL；UI 引用的 47 个 id 全部存在于标记中；
  8 个模块按依赖顺序内联；披露用语（`NOT_RUN`、`EXACT_SYNTHETIC`、`不建卡`、`PDF 引擎`）仍在产物中。
- `check-ui-boot.js`：**PASS** —— 47 个元素接线；在 mock DOM 中依次演练
  播种、保存备注、版本冲突与更正、加节点与改宽、撤销、导出、隔离恢复、注入写失败、epoch、
  荧光标记、摘录建卡；随后用**同一份 localStorage 再启动一次**，验证「重开」走恢复分支而非重新播种、
  已提交的备注与每页锚点仍在、重开后仍能继续写入并落盘；再验证损坏值会让界面进入
  **只读恢复模式**（写被拒绝、损坏字节前后不变）、命令 ID 带会话前缀、连按两次撤销能回到原值。
- `check-http-origin.js`：**PASS**（需先起 `python tools/serve.py 8777`）——
  在真实 HTTP 来源下重新读取 PDF 字节并复算 SHA-256 = `fc4d5eef9175…`，与记录值一致，
  并用该哈希播种成功。它覆盖 HTTP 来源与字节校验，**不覆盖** IndexedDB/原生持久化与耐久性。

### 3.1 外部审阅回归

`REVIEW.md`（针对提交 `287681f`）报的 8 个缺陷全部复现、修复并加了回归：
`R-D01`（存储拒绝时不得 ACK）、`R-D02`（损坏 ≠ 首次运行，损坏时只读）、
`R-D03`/`R-D03b`（清空备注读回空串；缺字段报错不替旧值）、`R-D05`（选区偏移锚定行盒）、
`R-D06`（普通荧光标记真的被绘制；摘录每页一个标记）、`R-D07`（命令 ID 带会话）、
`R-D08`/`R-D08b`（追加式补偿撤销；撤销走完整管线）、`R-S1`（独立卡合同、布局字段统一、
`vaultId` 校验）。逐条说明见 `REVIEW-RESPONSE.md`。

修复过程中我自己的测试还暴露了同类的第九个缺陷：`App.bind()` 在重复启动时会叠加监听，
一次点击执行多次命令。现已对每个元素幂等，并让处理函数解析「当前活跃 App」而非闭包捕获实例。

---

## 4. 未测项（关键，不要跳过）

| 未测项 | 为什么没测 | 由哪条 AX 覆盖 |
|---|---|---|
| 真实 PDF 页渲染与文字几何 | 本包不内置 PDF 引擎（离线零依赖）。`PdfSurface` 只做能力报告与拒绝 | AX01/AX03/AX12–AX16 |
| 真实选区与命中测试 | 没有浏览器布局引擎；`check-ui-boot.js` 直接给命令传偏移量 | AX01/AX03/AX14 |
| 手写、压感、掌拒、套索 | 样品无墨迹层 | AX15/AX21/AX32 |
| 持久化的真实耐久性 | `localStorage` 单键同步写，**不是 WAL、不是 fsync**；断电语义未测 | AX28 全量 |
| 外部 ACK oracle 与进程级故障 | 只用进程内故障注入钩子 | AX28-A01（FULL） |
| FSRS / 调度算法 | S1 明确不做；`schedulerVersion=UNASSIGNED` | AX26 |
| 词典匹配 | `dictionaryEntries` 为空集合 | AX08–AX11/AX35 |
| 沉浸回忆与防侧栏泄题 | 未实现 | AX23–AX25 |
| 嵌入留白与展开版导出 | 未实现；`exportPresentation` 返回 `UNSUPPORTED` | AX12–AX16 |
| 真机、热、续航、笔轴 | 无设备 | AX29/AX32 |
| 多标签/多进程并发 | 只在同一进程内模拟两个写者 | AX31 |
| 无障碍树 | 未做读屏检查 | AX24/AX32 |

**因此本样品不能把任何 AX 用例标为 PASS。** 它只提供 K00/K01/K02 的宿主级证据。

---

## 5. 旧资料与用户工作区处置

- `inkweft-r6/`：**只读参考**，未修改。样品没有复用其任何代码、样式或资产；
  构建期只读过 `src/app.js`、`src/editor.js`、`src/workspace-store.js` 以核对规划包里的静态观察。
- `inkweft_card_axis_plan_v1_1_1/`：**未修改**，仅作为规范来源读取。
- 没有删除、迁移或覆盖任何既有文件；`E:\Inkweft\inkweft\` 此前为空。
- 样品自己的运行时数据只在浏览器 `localStorage` 的 `inkweft.s1.vault` 键下，
  界面里有「重建资料库」按钮可以清掉。

---

## 6. 已知简化与偏离

见 `README.md` §6。摘要：

1. `layoutOverride` 嵌套位置/宽度，与夹具契约的字段划分略有差异。
2. 卡片与专题用 `card.studySetId` 单值，而非 `StudySet.cardRefs` 数组（多专题时需要换）。
3. 荧光标记会产生 `cardId: null` 的 `AnnotationPlacement`；不变式检查把它当合法标注。
4. 撤销保存完整预映像（样品规模可行，真实数据量下要换）。
5. `commitResolveConflict` 用新增备注块保住两份文字，不是真正的冲突合并模型。

---

## 7. 下一步（建议）

1. **先做 I00 接口评审记录**，把 `src/20-document.js` 与 `src/30-commands.js` 的接口形状、
   坐标/版本约定、错误码表定下来，再动 K01 的真实引擎。
2. 接真实 PDF 引擎（PDF.js 或 MuPDF），把 `PdfSurface` 的 `rendersPages` 打开，
   并用**人工标注的页面锚点**作 oracle 校验 `resolveSelector`（不能用同一个转换函数生成期望值）。
3. 把样品里已通过的宿主断言，按 `spec/acceptance.json` 的 profile 逐步升级成真机运行，
   并在 `generated/runtime/{case_id}/{run_id}/record.json` 里留下绑定构件的原始证据。
4. 保持 `dictionaryEntries` 为空直到 K03 真正开工 —— 不要让“看起来有词条”伪装成匹配能力。
