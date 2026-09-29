# 墨织加密备份实验服务

版本 `0.1.0`。这是手动加密备份与恢复，客户端必须主动连接；不提供多设备双向同步、自动覆盖恢复、开放注册或第三方网盘适配。

## 本机运行

Python 3.12 或更新版本，独立虚拟环境：

```powershell
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
.venv/Scripts/python server.py --db state/backup.db --add-user alice
.venv/Scripts/python server.py --db state/backup.db
```

创建账号时交互输入至少12字符密码，命令行不包含密码。默认监听 `127.0.0.1:18751`，数据库使用完整同步和事务。重启使用同一个数据库，服务器身份、会话撤销、版本及回执都会保留。账号密码由 [pwdlib／Argon2](https://fastapi.tiangolo.com/tutorial/security/oauth2-jwt/) 散列保存。

管理员重置认证：`python server.py --db state/backup.db --reset-password alice`。这会撤销旧会话，**不能恢复备份解密密钥**。

## 容器包

```sh
docker compose build
docker compose run --rm backup python server.py --db /state/backup.db --add-user alice
docker compose up -d
docker compose ps
```

服务数据在命名卷 `inkweft-backup`。仅向宿主回环地址开放端口。对真实设备开放前，应由管理员配置带有效证书的 HTTPS 反向代理和访问限制。Android 正常连接只接受 HTTPS；调试包仅为隔离协议测试允许 localhost／127.0.0.1 HTTP。没有内置通用密码或管理员密钥。

版本升级先备份服务数据库，再停止旧服务、构建并启动新版本。当前只支持服务端 schema 0→1 初始化和1重开，未知版本直接停止，不能强改 `user_version` 绕过。Docker 构建及公网 TLS 部署需单独记录实际执行结果；提供 compose 文件不代表已在 Docker 环境验收。

## 服务端备份／恢复

```sh
python server.py --db state/backup.db --backup-to saved-server.db
```

此命令使用 SQLite 在线备份 API；目标文件必须不存在。不要只复制正在运行的 `.db` 而漏掉 WAL。恢复时停止服务，保留旧数据目录作回退，将快照作为一个新数据目录的 `backup.db`，再以该路径启动。服务端快照包含账号密码散列和会话凭据的散列，应限制访问；备份正文仍是密文。恢复原数据库也恢复原服务器身份。

## Android 用法

资料库 → 设置与数据 → 加密备份与恢复（实验）：连接自己的服务器，保存恢复密钥文件，创建加密快照，再继续上传。关闭此页面仍可继续编辑本地笔记；任务可暂停，进程重启后使用相同服务器／账号／设备／资料库继续。登录失效时重新连接原账号；切换账号不会把旧任务发给新账号。

在另一份空白资料库中登录同一服务器账号，读取恢复密钥文件，查看服务器备份，下载并校验，最后确认恢复。校验前不写作者数据；缺块、密文篡改、错误密钥或资料身份冲突都停止。现有资料不被云端版本覆盖。身份相同、内容完全一致时返回“已存在”。

本轮恢复密钥为随机32字节，不依赖原设备 Keystore。Keystore只保护可撤销的登录会话。密钥文件由用户另存，应用不上传。首次快照取已提交数据的一致视图，可能短暂等待存储事务；网络上传不持有作者数据库事务。尚未承诺长笔中途耐久点和跨重启撤销。

## 协议与限制

所有资料库、版本、上传、块和回执请求都做所属账号检查。主体为服务器ID＋issuer＋用户ID＋设备ID；邮箱或账号名不是跨服务器身份。

|接口|作用|
|---|---|
|GET `/v1/identity`|协议版本及持久服务器身份|
|POST `/v1/sessions`|账号认证，独立设备会话，24小时有效|
|DELETE `/v1/sessions/current`、`/v1/devices/{id}`|退出或撤销该账号的设备会话|
|PUT `/v1/libraries/{id}`|创建账号自有资料库目录|
|PUT `…/uploads/{operation}`|冻结密文块清单，相同ID不同载荷拒绝|
|PUT `…/uploads/{operation}/chunks/{index}`|完整性校验后保存幂等块|
|POST `…/uploads/{operation}/publish`|缺块或哈希不一致不能发布；完整事务确认|
|GET `…/operations/{operation}`|查询未知结果，避免重复发布|
|GET `…/versions`、`…/versions/{id}`、`…/versions/{id}/chunks/{index}`|已发布目录、清单和下载|
|DELETE `…/versions/{id}?operation_id={uuid}`|显式、幂等删除，保留删除回执|

明文按256 KiB分块，经 AES-256-GCM 加密后组成IWBK1容器，传输按1 MiB分块。每份备份独立随机数据密钥，经用户恢复密钥包装；每块nonce为该备份8字节随机前缀＋4字节索引。AAD包含原始头部摘要及索引，头部包含原始总长度。完整块数、长度、AEAD标签和容器尾部均校验。[AES-GCM 的 nonce 与标签约束](https://cryptography.io/en/46.0.0/hazmat/primitives/aead/)。

传输清单的规范JSON使用排序键、无空格、UTF-8；SHA-256作为操作载荷摘要。每库最多10个待提交／已发布上传，每份明文最多500 MiB；服务器只接受最多512个1 MiB传输块。删除不会自动覆盖上一版；当前Android界面提供创建、续传、下载和恢复，版本删除可通过协议执行，客户端删除管理待补。

## 内置组件与资源扩展边界

客户端四个学习模块使用 `WidgetDefinition / WidgetInstance / WidgetHost`，定义命名空间、版本、尺寸及稳定目标引用；未知提供者保留实例并显示占位。分享布局只输出受信内置结构，移除目标、历史和所有外部元数据。移除快捷入口或隐藏组件不会删除作者内容。

后续纸张／导图资源包只允许声明式JSON及静态PNG，单包压缩8 MiB、解压32 MiB、至多256条目、单文件8 MiB、展开比最多20。禁止绝对路径、盘符、`..`、符号链接、重复路径和嵌套压缩包；manifest必须声明命名空间、格式版本、每文件大小和SHA-256。安装仅从用户明确选择的本地文件开始，发布市场前增加签名信任目录；暂不下载执行DEX、脚本或任意网络组件。资源包安装器、升级回滚与卸载用例属于后续独立批次，本轮未宣称实现。

## 验证

```sh
python -m unittest -v test_backup
```

本机7项测试覆盖两个隔离账号、多个设备会话、真实HTTP应用往返、重启读取、越权块下载、撤销／过期／切换、未知结果重复查询、缺块、篡改、磁盘失败注入及幂等删除。`fixture.py` 只创建新的合成数据库供Android回环测试，拒绝复用已有数据库；不可用作真实服务初始化方式。
