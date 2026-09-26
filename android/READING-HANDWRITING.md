# 字体美化、手写查找与文档阅读

适用版本：`0.0.17-reading-handwriting`（17），应用名「墨织工作台预览」，数据库 Schema 12。

## 字体美化

1. 单页模式选择工具，框选或套索一小段手写，点「字体美化」。
2. 离线识别完成后核对错字、标点和分行；可以直接修改识别文字。
3. 选择霞鹜文楷、系统黑体或系统衬线，调整字号、加粗和行距，查看预览后应用。
4. 结果是可编辑文本对象，使用选区第一笔的颜色，初始放在原区域；多色文字建议分色选择。原始笔迹保留，暂时隐藏；对象工具的「恢复原迹」删除美化文本并重新显示原笔迹。对象撤销可以撤回这次操作。
5. 复制整个页面／笔记时同时复制原迹并重新映射引用；单独复制文本对象得到独立文本，不重复占用原迹。

本版为选区识别后美化，尚未实现边写边换字。原有「稳线美化」仍用于减轻笔画抖动。系统衬线的中文字形取决于设备字体；霞鹜文楷随包内置。没有使用参考图片中的商业字体或素材。

建议一次选择一两行、最多 256 笔。公式、图形、草书、竖排、复杂分栏不保证识别，荧光笔不参加识别。预览需要用户核对，模型分数不作为识别准确率宣传。美化对象和笔迹分别维护撤销历史。

## 手写查找

- 编辑工具更多 → 本页手写检索文字 →「识别本页手写」→ 核对 → 保存。返回资料库输入关键词，即可定位原页。
- 文档更多 →「整本手写查找」批量识别。逐页保存，可以停止并保留已完成页；有效的人工关键词不会被批处理覆盖。
- 搜索索引同时检查识别时的笔迹版本和对象版本。普通擦写／撤销或对象修改后失效；纯荧光标注沿用原有索引规则。旧结果不会覆盖识别期间的新页面。
- 模型、字典和文楷字体随安装包提供，无需登录或下载语言包。识别不上传原文；诊断不记录识别文字。搜索范围为手写及文本对象，不包含扫描 PDF 的 OCR 或图片中文字。

## 文档格式与版式

| 格式 | 本版入口 | 实际边界 |
| --- | --- | --- |
| PDF | 资料库「导入文档 / 副本」直接导入 | 保留源 PDF，逐页阅读、缩放、手写批注；不重排正文。原页面等比放入当前纸面，宽屏课件可能留白 |
| EPUB | 同上，离线转固定分页 PDF | 保留可支持的文字、样式与插图；流式电子书原本没有唯一分页，本版固定为 A4、14 pt 后批注，不承诺原阅读器分页一致 |
| MOBI | 同上，离线转 PDF | 支持无加密、无压缩／PalmDOC 压缩的传统 MOBI；KF8、HUFF/CDIC 需先用 calibre 转换 |
| DOC / DOCX / PPT / PPTX | 电脑端 `tools/convert_document.py` 或本机 Office／LibreOffice 导出 PDF 后导入 | 已提供本地转换工具；Android 端尚无直接 Office 渲染器。字体缺失、公式或复杂排版应在转换后核对 |
| AZW3 | calibre 转 PDF 后导入，脚本支持调用其转换器 | Android 端未原生解析；需要本机已安装 calibre，无 DRM 文件适用 |
| CAJ | CAJViewer 打印／导出 PDF，或脚本调用已配置的 caj2pdf | Android 端未原生解析；caj2pdf 仅支持部分 CAJ，HN 等变体可能失败，不能把改扩展名当转换 |
| `.iwpage` / `.iwbook` | 同一导入入口 | 包含可编辑批注、对象与源 PDF；不是完整资料库备份 |

密码和 DRM 文件暂不支持。DOC／PPT、AZW3、CAJ 的转换发生在用户电脑或本机阅读器中，没有在线转换上传。

源文档上限 32 MB／500 页；页面副本上限 36 MB，整本副本上限 64 MB，资料库源文档总预算 80 MB。超过限额会拒绝，不导入半本。源 PDF 在资料库内分块保存，同一本多个页面共享一份内容；删除外部原文件不影响已导入笔记。

复制页面、复制笔记、`.iwpage`、`.iwbook` 和 `.iwbackup` 都携带源文件。新源文件页面使用 IWP6，包含文档的整本副本使用 IWB2，文本对象使用 IWO2；旧版不能读取这些新内容。Schema 12 增量建表保留已有资料，不删除设备数据。导出含批注的通用 PDF、提取 PDF 文字／链接／目录尚待实现。

## 电脑端转换

需要 Python 3；转换器独立安装。先执行：

```powershell
python tools/convert_document.py --check
python tools/convert_document.py "D:\资料\课件.pptx" "D:\资料\论文.doc" --out-dir "D:\资料\墨织PDF"
python tools/convert_document.py "D:\资料\电子书.azw3" --calibre "C:\Program Files\Calibre2\ebook-convert.exe" --out-dir "D:\资料\墨织PDF"
python tools/convert_document.py "D:\资料\论文.caj" --caj2pdf "D:\工具\caj2pdf\caj2pdf" --out-dir "D:\资料\墨织PDF"
```

工具保留源文件，不覆盖已有目标；单个转换超时 180 秒会停止。输出传到平板后，按 PDF 导入。caj2pdf 还需要其官方列出的 Python／mutool 等依赖；缺失时脚本会报错，不伪造成功。当前机器已验证 LibreOffice 的 DOC、DOCX、PPT、PPTX 测试样本；calibre／caj2pdf 未安装，真实 AZW3／CAJ 转换仍待验证。

## 依据与授权材料

- [PaddleOCR 官方识别模型](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx)：模型固定到 `ed152b8b495f84de93cda5709d768548a9127622`，Apache-2.0；配套字符表从同版本配置提取。
- [霞鹜文楷 Lite](https://github.com/lxgw/LxgwWenKai-Lite)：SIL OFL 1.1，未修改字体。
- [ONNX Runtime](https://github.com/microsoft/onnxruntime/tree/v1.30.0)：Android 1.30.0，MIT；关闭遥测。
- [MuPDF Android](https://mupdf.readthedocs.io/en/latest/guide/using-with-android.html)：1.28.5，AGPL-3.0，与本项目开源许可一致；用于电子书转 PDF。显示使用 Android PdfRenderer。
- [LibreOffice 转换参数](https://help.libreoffice.org/latest/en-US/text/shared/guide/start_parameters.html)、[calibre 命令行](https://manual.calibre-ebook.com/generated/en/ebook-convert.html)、[caj2pdf 范围与限制](https://github.com/caj2pdf/caj2pdf)。

随包许可文本见 `app/src/main/assets/licenses/`，模型与字体 SHA-256 见 `app/src/main/assets/asset-manifest.json`。字体图像识别测试是合成样本，只验证模型接通；不能替代用户真实手写准确率、笔感或平板性能测试。实机步骤单列于 T38—T43。
