# 声明式模板包 v1

入口：资料库 → 设置与数据 → 本地模板包 → 选择模板包。文件扩展名 `.iwpack`，内容为 ZIP。

安装前显示名称、作者声明、版本、类型、数量、体积和“本地未认证来源”。包内哈希仅验证完整性，不能证明作者身份。

## 格式与开发工具

`manifest.json` 使用 `inkweft.resource-pack.v1`，包含 id、title、author、整数 version、resources；额外 PNG 在 files 中声明 bytes 和 sha256。实例见 `paper/manifest.json` 与 `map/manifest.json`。首批只接受已有 PaperStyle 样式或静态 PNG 纸面，以及 `MapTemplates` 的 right／bilateral 结构。占位节点使用 MapStructure，不创建复习卡。

```text
python resource-packs/pack.py resource-packs/paper --output paper.iwpack
python resource-packs/pack.py paper.iwpack
python resource-packs/pack.py resource-packs/map --output map.iwpack
```

工具执行基本打包安全检查；Android 安装器仍做完整格式、模板语义、PNG 尺寸和预算校验。输出文件已存在时拒绝覆盖。

预算：压缩 8 MiB、实际展开 32 MiB、256 条目、单文件 8 MiB、展开比 20；JSON 256 KiB／深度16／字符串4096／节点8192；PNG 最大2048×2048且像素内存不超过16 MiB。路径仅小写 ASCII 相对路径；拒绝软链接、重复路径、点目录、盘符、嵌套包以及非 JSON／PNG。最多32个登记版本、包存储64 MiB。

## 数据生命周期

完整校验后先写不可变摘要文件，再用 AtomicFile 登记当前版本；新版本登记成功才禁用旧版。失败时旧登记不变，遗留未登记文件不会执行或显示。

从纸张新建时复制现有样式；PNG 生成自有单页 PDF 并进入现有文档存储。从导图新建时复制结构到独立笔记。两者与 RESOURCE 来源回执在同一作者事务中提交。完整备份包含实际纸面／导图及来源回执，不依赖安装目录；目录中显示使用该版本的笔记名称。

禁用仅阻止之后实例化。卸载删除无作者引用的版本；有实例副本的版本停用并保留。升级不重写既有笔记。安装目录与个人设置不属于作者资料备份。

## 示例授权

`paper`、`map` 的文字、结构与程序生成 PNG 为本任务原创示例，以 CC0-1.0 提供；没有使用用户给出的竞品截图、字体或图案。它们是未签名演示包，不宣称经过市场认证。
