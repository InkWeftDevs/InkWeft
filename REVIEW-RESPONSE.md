# 对 REVIEW.md 的修复报告

针对外部审阅 `REVIEW.md`（固定提交 `287681f94c6a2dc29fad8f9ce7f65af4b7f36f1f`，
树 `e6e20e1d71c48d77467a017d77ea10380286a02d`）。

我先逐条复核了 D01–D08，**确认八条都成立**，然后按「先修实现、不重写规划」的口径修掉了它们，
并为每条补了回归。本文件按编号给出：缺陷 → 证据 → 修法 → 回归。

结论：`README.md` 里那句「样品，不是验收」不变。应用的 37 条 AX 仍全部 `NOT_RUN`。

---

## D01 UI 提前取得 ACK，存储提交没有接入命令事务 —— 已修

**原来**：`createEngine` 每次创建都没传 `persist` 钩子，`dispatch` 先 `engine.run` 拿到 ACK、
把新状态和回执发布进内存，然后才单独 `persist()`；`persist` 失败只显示 `NOT SAVED`，
`dispatch` 依然返回 ACK、清冲突、重绘。于是「保存失败」的内容仍是权威头、历史和导出范围。

**现在**：所有 `createEngine` 调用都传 `persist: this.persistHook()`；
钩子在引擎内部**先**落盘再发布、再回 ACK。失败时引擎在 `run()` 的发布步骤之前抛出，
所以权威头、回执列表、持久字节三者都不会前进。种子数据也改走 `dispatch`，
不再绕开写路径（否则这个样品就不能证明写路径）。

**回归**：`R-D01` —— 注入写失败后断言 vault 字节、`history()` 长度、`store.commitSeq()`
三者都没变；随后一次正常提交能落地。UI 侧 `check-ui-boot.js` 的
「fault toggle」用例断言界面显示 `NOT SAVED` 且 `commitSeq` 不前进。

---

## D02 损坏载入与首次运行混为一谈 —— 已修

**原来**：`store.load()` 用抛异常表示损坏，`App.boot` 把异常记进日志后 `saved` 仍为 `null`，
于是走「新库」分支 `seed + persist`，**试图覆盖原始损坏值**。

**现在**：`store.load()` 返回判别结果 `EMPTY | LOADED | CORRUPT`（`loadOrThrow()` 保留旧语义）。
`CORRUPT` 时界面进入**只读恢复模式**：不写、不播种、不覆盖；`persistHook` 直接
`fail('STORE_READ_ONLY')`；界面显示损坏码与原始字节数，并提供显式的
「丢弃并重新播种」按钮（带确认）。原始字节在启动与浏览后逐字节不变。

**回归**：`R-D02`（`EMPTY → LOADED → CORRUPT`，且损坏值不被读操作改写）与
`check-ui-boot.js` 的「corrupt-store path」用例（`loadState==='CORRUPT'`、`readOnly===true`、
写被 `STORE_READ_ONLY` 拒绝、localStorage 里的损坏字节前后完全相同）。

---

## D03 清空备注后显示旧文本 —— 已修

**原来**：`readCardRevision` 在 `payload.text === ''` 时向 `previousRevisionId` 回退，
于是返回的 `blockRevisionId` 是**新**修订、内容却来自**旧**修订。空字符串是合法修改，不是损坏。

**现在**：按确切修订返回原始 `payload`，**空就是空**。只有「结构上损坏」才报错：
文本块的 `payload` 缺失或没有 `text` 字段 → `BLOCK_PAYLOAD_MALFORMED`，
同时 `checkInvariants` 也会把这种修订列为问题，让它在提交前就被挡住。
`payloadFallbackFrom` 字段已删除。

**回归**：`R-D03`（清空后头读到 `""`，而更早的修订仍能读回自己的旧文字，历史完好）、
`R-D03b`（删掉 `payload.text` → 不变式报错 + `BLOCK_PAYLOAD_MALFORMED`），
以及 `check-ui-boot.js` 的空备注用例。

---

## D04 新建摘录后选择状态与显示状态分离 —— 已修

**原来**：`dispatch` 内部先 `renderAll()`，`applySelection` 返回后才设 `selectedCardId`，
之后不再重绘；显示可能还是旧卡，下一次保存却按新 `selectedCardId` 执行。

**现在**：状态更新在渲染之前（`selectCard()` 先把 `selectedCardId`/`renderedCardId`/
`commentBinding` 一起换掉，再统一 `renderAll()`）。编辑缓冲**绑定 `cardId + headRevision`**：
切卡会重绑并丢弃未提交缓冲；`saveComment` 在 `binding.cardId !== selectedCardId` 时
明确拒绝并提示，绝不把一张卡的输入提交到另一张卡。

**回归**：`check-ui-boot.js` 的摘录建卡用例断言 `renderedCardId === selectedCardId`；
空备注用例覆盖「同一张卡连续两次保存」的绑定行为。

---

## D05 DOM 选区偏移与 TextSurface 偏移不是同一坐标系 —— 已修

**原来**：`captureSelection` 用一次性 `TreeWalker` 扫整个容器累加文本长度。
块级元素不自动贡献换行（模型每行按 `line.length + 1` 计），页码装饰也会被读进来，
所以只有第一行对。审阅给出的 Chromium 复现与我读到的问题一致。

**现在**：每行渲染成 `<div class="line" data-start data-end><span class="line-body">`，
端点偏移**由所属行推导**：先 `closest('[data-start]')` 拿到模型起点，
再只在该行的 `.line-body` 内累加文本到端点，最后夹在 `[lineStart, lineEnd]` 内。
页码与覆盖层（`data-decoration`）不参与。`captureSelection` 不再直接决定坐标，
真正的锚点区间由引擎按 `offsetsToLines(pageId, start, end)` 逐页裁剪生成。

**回归**：`R-D05` 断言模型行盒与语料文本逐行一致、第二页首行起点就是模型说的位置、
且模型文本里不含任何视图装饰。

---

## D06 普通荧光标记计数增加但没有加入绘制 —— 已修

**原来**：`renderDocument` 只画当前知识卡的 `sourceIds`；`highlighter` 数组仅用于提示数量，
没有 `cardId` 的普通标记因此**完全不可见**；跨页建卡也只创建第一个 `AnnotationPlacement`。

**现在**：绘制集合按**文档**枚举全部 `AnnotationPlacement`（解析每个 anchor 的 rect），
摘录标记与普通标记都画；`data-kind="excerpt|highlighter"` 用不同色相区分，
选中态只是强调、不决定其他标记是否存在。跨页摘录改为**每页一个 placement**
（`annotationIds` 一起返回），所以每个锚点都能被单独回源与撤销。

**回归**：`R-D06`（荧光标记有可绘制 rect；摘录标记数 === 每页锚点数）与
`check-ui-boot.js` 的「highlighter mode actually draws」用例（计数与 DOM 里的
`.mark[data-kind=highlighter]` 数量同步 +1）。

---

## D07 重开后 UI 命令 ID 重复 —— 已修

**原来**：`commandCounter` 只活在当前 App 对象里，重开恢复旧回执后从 1 重新开始，
两个会话的首个备注都叫 `ui-comment-1`；载荷不同时会撞 `COMMAND_ID_REUSE`。
这是**壳层的身份生成问题**，不是幂等机制的问题。

**现在**：命令 ID 带会话前缀 `ui-<sessionId>-<tag>-<n>`，`sessionId` 由
「已存回执数 + 时间戳 + 进程内单调 boot 序号」派生，因此同一毫秒内的两次启动也是两个会话。
`header()` 的 `capabilityHandle` 同样带上会话，避免跨会话复用。

**回归**：`R-D07`（两次会话派生的前缀不同；同一引擎内复用固定 ID 仍被
`COMMAND_ID_REUSE` 拒绝）与 `check-ui-boot.js` 的「every command id carries the session」用例
（两次启动的 ID 不同、形状受检、单会话内不自撞）。

---

## D08 撤销只有第一次顺畅，且破坏正式历史解析 —— 已修

**原来**有三个问题：
1. `live` 计算没有排除 `undo` 回执本身，撤销 B 之后 `undo-B` 成了「最新」，
   再撤销 A 就报 `UNDO_NOT_LATEST`；
2. 撤销用 `preImage` 整体替换 vault，刚被撤销的 `CardRevision`/`BlockRevision`
   从正式集合消失，`readCardRevision` 再也解析不到；
3. `undo()` 只检查 header 字段存在，没有像 `run()` 那样复核 epoch、授权与 commandId 幂等。

**现在**：撤销改成**追加式补偿**。每条命令在提交时从预映像算出一个
`inverse`（补偿命令 + 参数），撤销就是**用同一执行管线去跑那条逆命令**：
- `patchCard` → 追加一个带旧文本的新块修订；
- `createCardFromSelection` / `createIndependentCard` → `trashCard`（墓碑，不删内容）；
- `attachOccurrence` → `detachOccurrence`；`moveOccurrence` / `reparentOccurrence` → 恢复原布局；
- `trashCard` ↔ `restoreCard`。

于是被撤销的修订**仍在台账里、仍可解析**，撤销本身也走
「header 校验 → vault 身份 → 实时授权 → epoch/期待版本 → 摘要幂等 → 不变式闭包 → 先落盘再 ACK」。
没有逆命令的（如 `markHighlighter`、`upsertMargin`、`resetVaultEpoch`）在历史里标为
`不可补偿`，界面不给按钮，而不是假装能撤销。回执里的 `preImage` 已删除，
导出包只带 `inverse`（小命令，不再整份复制 vault）。

**回归**：`R-D08`（连续两次撤销都成功，文本逐级回到原值，台账只增不减，
被撤销的修订仍可读）与 `R-D08b`（陈旧 epoch → `VAULT_EPOCH_MISMATCH`；
同一 undo ID 重放 → `REPLAYED`；同一目标二次补偿 → `ALREADY_UNDONE`；
撤权后补偿 → `SOURCE_RESTRICTED`，重新授权后成功）；
`check-ui-boot.js` 另有连按两次撤销按钮的用例。

---

## 第 3 节「仍需注意」的处理

| 项 | 处理 |
|---|---|
| `createIndependentCard` 与不变式矛盾 | **已修**：无来源的独立卡合法；只有**带 `source_quote` 块**的卡才必须有来源锚点 |
| `moveOccurrence` 写 `x/y`、读 `layoutOverride.position.x/y` | **已修**：位置/宽度统一写在 `layoutOverride`（`collapsed` 留在节点上）；`attachOccurrence` 不再另写节点级 `x/y` |
| 未显式拒绝不匹配的 `vaultId`，undo 也不同路 | **已修**：`run` 与 `undo` 都先校验 `VAULT_ID_MISMATCH` |
| capabilityHandle 遮盖末 4 位参与摘要、原 handle 进回执 | **已修**：capability **完全不参与摘要**（它是授权、不是语义），回执只留 `capabilityFingerprint`，原始 handle 不入库。`AX34-A04` 同步改成断言这个更强的性质 |
| `getVault/getState` 暴露可变对象 | **保留并写明**：这是样品内部句柄，不是插件 SDK 的只读安全接口；真实插件边界需要单独设计（见 README 限制） |
| STORE-02 只是一个引擎里模拟两个视图，`localStorage` 整库同步写不是多标签并发保证 | **保留并写明**：`STORE-02` 的说明与 README 都明确它是**同进程**模拟；`localStorage` 没有 CAS，不作为正式多标签并发承诺 |
| 每命令完整 preImage + 全量序列化会持续增加空间与主线程成本 | **已减一半**：回执不再存整份 `preImage`，只存小 `inverse`；持久化仍是整库单键写，README 明确标为样品级取舍 |

---

## LICENSE

已补 `LICENSE`：GNU Affero General Public License v3.0（完整标准文本，34273 字节，LF 行尾）。
与用户既有 `inkweft-r6/LICENSE` 逐字节一致（SHA-256
`5132c7f0475b02c8107a2e0f0363e70423c62d2664ab927e76193226e5e05905`）。
结构自检：标题、Preamble、第 13 节与「Remote Network Interaction」条款、
`How to Apply These Terms`、FSF 指针均在。

**说明**：本轮无法联网，所以我没能把这份文本与 FSF/GitHub 的发布版逐字节比对；
它来自用户自己的项目文件。若要正式对外声明许可，建议对一次上述 SHA-256，
并考虑在 GitHub 仓库设置里把 License 标为 `AGPL-3.0-or-later`。

---

## 我自己的测试还暴露了一个同类缺陷（审阅没报，但值得记）

把 UI 测试改成可重复启动之后发现：`App.bind()` 每次启动都往同一批元素上挂监听，
于是**一次点击会执行多次命令**（备注每按一次就多追加一截）。
真实页面里一次加载只 bind 一次，所以浏览器里看不出来，但这仍是一个真实陷阱。
现在 `bind()` 对每个元素幂等（`_inkweftBound`），并且**处理函数解析"当前活跃 App"
而不是闭包捕获某一个实例**，避免重启后由废弃监听器驱动旧引擎。

---

## 复跑方式

```powershell
node tools/build.js
node tests/run-headless.js      # 39 项（含 R-D01…R-D08b 回归）
node tests/check-build.js
node tests/check-ui-boot.js     # mock DOM：首启/重开/损坏只读/建卡/保存/撤销/导出
python tools/serve.py 8777      # 另开一个终端
node tests/check-http-origin.js 8777
```

`README.md` 与 `sample-REPORT.md` 已同步更新计数与范围说明。
