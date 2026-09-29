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

传输清单的规范JSON使用排序键、无空格、UTF-8；SHA-256作为操作载荷摘要。每库最多10个待提交／已发布上传，每份明文最多500 MiB；服务器只接受最多512个1 MiB传输块。删除不会自动覆盖上一版；v44 Android界面提供创建、按缺块续传、下载校验、恢复、版本删除和未完成上传管理。

## 内置组件与资源扩展边界

客户端四个学习模块使用 `WidgetDefinition / WidgetInstance / WidgetHost`，定义命名空间、版本、尺寸及稳定目标引用；未知提供者保留实例并显示占位。分享布局只输出受信内置结构，移除目标、历史和所有外部元数据。移除快捷入口或隐藏组件不会删除作者内容。

v44 纸张／导图资源包只允许声明式JSON及静态PNG，单包压缩8 MiB、解压32 MiB、至多256条目、单文件8 MiB、展开比最多20。禁止绝对路径、盘符、`..`、符号链接、重复路径和嵌套压缩包；manifest必须声明命名空间、格式版本、每文件大小和SHA-256。安装仅从用户明确选择的本地文件开始，发布市场前增加签名信任目录；暂不下载执行DEX、脚本或任意网络组件。v44 已实现本地安装器、升级、禁用与卸载；v45 接入创作入口。任意代码插件与公开市场仍后续。

## 验证

```sh
python -m unittest -v test_backup
```

早期基础测试覆盖两个隔离账号、多个设备会话、真实HTTP应用往返、重启读取、越权块下载、撤销／过期／切换、未知结果重复查询、缺块、篡改、磁盘失败注入及幂等删除。`fixture.py` 只创建新的合成数据库供Android回环测试，拒绝复用已有数据库；不可用作真实服务初始化方式。


## v44 可靠性补充

会话过期后显示原目标并允许重连；同一队列仍绑定服务器、issuer、账号、设备和库。断开本机立即清除活动凭据并暂停调度，远端不可达明确显示撤销未确认。恢复密钥不等同于会话密钥。

`GET …/uploads/{operation}` 返回固定 manifest 摘要与已收块序号。Android 对照原密文与摘要，只补缺块。`GET …/versions?include_pending=true` 同时显示未完成上传、时间、体积和状态。删除操作写入持久本地命令后查询／重试同一回执；放弃上传带 `expected_state=PENDING`，并发发布后必须重新确认删除已发布版本。

服务器仍使用 schema 1，没有本轮 schema 迁移。账号最多16个库、4项待上传、2 GiB保留密文；全局8 GiB；每库10项待提交／已发布。配额按 manifest 预留且与发布／删除使用 BEGIN IMMEDIATE 串行事务。7天待办租期到期后，在接收下一次新上传时清除过期密文、保留 EXPIRED 回执。操作回执暂不GC，不宣称无限服务容量。

入口同时最多32项请求，登录密码校验最多4并发；请求体实际流量限制1 MiB＋32 KiB，读取时限30秒。日志仅记录服务生成的请求关联号、阶段和状态码；默认不记录账号、URL、正文或凭据。`/health/live` 表示进程存活，`/health/ready` 核对可写存储。Compose 健康检查不能替代业务恢复监控。

Android 采用一项持久 JobScheduler 任务与应用级执行器。点击上传才授权续传；后台默认非计量网络、电量不低且温度允许，连接页可明确允许计量网络。单次工作120秒、上传每轮约55秒、最多5轮后台重试／30分钟恢复窗口，之后需手动继续。HTTP重试仅暂时性故障，1秒／2秒退避；TLS、权限、冲突、配额不会无限重试。用户强停后必须重新打开应用。系统配额和厂商策略可能推迟执行，不能保证常驻。见 [JobScheduler](https://developer.android.com/reference/android/app/job/JobScheduler) 与 [Android 16 长任务配额说明](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)。

## 隔离部署验证与恢复流程

本机 Docker 引擎未运行；未改动用户 Docker、主机端口、防火墙、DNS 或代理。`container_check.py --output result.json` 会在已有 Docker 环境内建立随机命名的独立容器和卷，检查非 root、只读根文件系统、健康、服务重启身份与数据库备份，再清理自建资源。CI 已接入该脚本；执行结果以当次产物为准，不将配置文件当运行证据。

`Caddyfile.example` 供获准主机使用，域名必须由管理员替换，后端保留回环端口。正式部署前检查证书链、主机名、自动续期及过期告警；客户端不跳过证书验证，不接受重定向。公网未验收；隔离 TLS 的实际状态见 Android 的 VERIFICATION-V45.md。

升级前记录 `docker image inspect` 的镜像身份并保留旧镜像；先执行 `server.py --db /state/backup.db --backup-to /state/before-upgrade.db`，把完整 SQLite 备份复制到独立存储。停服务后升级，在隔离卷使用备份运行同版本镜像，核对服务器 identity、账号、版本与回执；新版本健康失败时停新容器，用升级前数据库与旧镜像恢复。不要直接拷贝正在写的 `.db` 或把同版本重启当迁移测试。本轮未知 schema 拒绝测试覆盖不覆盖升级兼容保证。

## v45 隔离验证工具

服务 schema 仍为 1。`python -m unittest discover -v -p 'test_*.py'` 包括只读 SQLite、限容 SQLITE_FULL、原版本恢复和持久影子服务。测试使用合成账号；不向已有资料目录注入故障。

`container_check.py` 现在增加真实 2 MiB 随机明文加密、预先接收块 0 后续传、重复发布回执、重启解密校验，以及服务在线快照复制到独立新卷后的身份／回执／解密核对。测试密钥仅保存在它自己的临时卷，结束后清理随机命名的容器与卷。v44 容器基础 PASS 不代表这份扩展脚本已通过；必须看当次输出。此工具不配置公网，也不建立 TLS。

TLS 使用另外的、只绑定回环的进程：

```sh
python tls_fixture.py --directory /absolute/disposable/tls
```

生成器建立有效、过期、错主机名、未知 CA 和重定向五个端点（18761–18765）。证书有效期仅三天，过期后换新的测试目录。将对应端口以 `adb -s <专用模拟器> reverse tcp:端口 tcp:端口` 映射；以 `-PinkweftDiagnosticBuild=true -PinkweftInsertionPreview=true -PinkweftTlsFixture=/absolute/disposable/tls/res` 构建 `:app:assembleTlsProbe :app:assembleTlsProbeAndroidTest`。只安装到专用测试设备，再显式运行 `org.inkweft.app.TlsBackupProbe`。包名为 `org.inkweft.app.a0.insertion.tlsprobe`，不能覆盖用户 workspace 包。

普通 debug/release 源集不包含该 CA。使用 Android [独立网络安全配置](https://developer.android.com/privacy-and-security/security-config)，不安装系统 CA、不使用 trust-all。若测试系统默认信任管理器接受未知签发者，负例应保持 FAIL 并换可信系统复验，不能降低断言。

故障与持续负载工具在 `ci/device_fault_probe.py`、`ci/concurrent_writing.py`，命令见 [v45 交付说明](../android/DELIVERY-V45.md)。`fixture.py --upload-delay 4 --port 18754` 为持续备份测量提供实际上传期间的受控延迟。

影子同步仅供隔离测试调用 `create_app(db, enable_shadow=True)`，普通服务默认不挂载其路由。其 `.shadow.db` 与备份数据库分离，普通备份快照不包含它；实验重启须保留该文件。真实格式、限制和未开启能力见 [影子同步合同](../sync-lab/ANDROID-SHADOW.md)。
