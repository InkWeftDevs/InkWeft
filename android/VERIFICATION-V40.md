# v40 验证记录

日期：2026-09-28。最终源码 `5426af6485b3d2df7f4a0d655e165225267fbd6f`，构建时源码干净。

## 构件

- 版本：`0.0.40-beauty-font-fix`，versionCode 40。
- 包名：`org.inkweft.app.a0.workspace`。
- 安装包：`E:/Inkweft/dist/BeautyRepair/InkWeft-v40-workspace-preview.apk`，141982431 字节。
- SHA-256：`cf04995cad39e2320f4961307c7cf9f94a4ebe0f254b4ca0371cecd6878634a9`。
- 固定签名 SHA-256：`18e67dbce88152dbe4d4821b5a3a513a409a3df4eac9261f4c11126c36e43969`。

平板已安装文件的 SHA-256 与此包一致，回执在本地 `BeautyRepair/device/installed-identity.json`。

## 复现与回归

- 使用实际保存状态 `enabled=true / keep-ink=true` 复现 v39 字体入口不可见；新增回归在修复前失败。
- 最终同包本地 18 项相关测试全部通过：参数持久化、从保留笔形选择字体、实际触摸后自动转换、铅笔转换、笔与铅笔颜色、字形位置、局部擦除、撤销与重开、公式原迹整理。
- 平板最终同包：字体入口、文楷切换、自动转换、改衬线继续书写、蓝色保留通过；具体步骤和边界见 DEVICE-REPORT-V40.md。
- 升级前后逐主键比较 28 张表，2030 条原记录完全一致；只新增 Beauty-v40 测试本，升级后共 28 本。
- CI：[36432264256](https://github.com/InkWeftDevs/InkWeft/actions/runs/36432264256)，构建与静态检查通过；194/194 界面与 152/152 数据库回归通过，已下载分片原始报告核对。

## 未验证与限制

真实手持笔压感、掌拒、持续书写和温升未测。中英文混写识别准确率没有在本次改善；平板测试中第二行合成 HI 被识别为 H工，属于识别模型误识别，不能把字体转换通过当作识别准确率通过。局部擦除与撤销本次由自动化覆盖，物理手持笔复验待测。

本地证据目录：`E:/Inkweft/archives/2026-09-28/BeautyRepair/`。含复现失败记录、18 项回归、最终构建、安装回执、截图及升级数据核对；私人备份不上传仓库。
