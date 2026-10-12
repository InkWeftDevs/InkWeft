# Go 服务构建与运行

长期维护入口是本目录上一级的 Go 服务。`server.py` 与 Python ASGI 应用只保留作 v1、影子同步和 TLS 故障夹具；生产 Docker 镜像只包含 Go 程序，不启动 Python 后端。

Go 版本与依赖由 `go.mod` / `go.sum` 固定。运行 `backup-server/build.ps1`（Windows）或 `backup-server/build.sh`（Linux）会验证模块、运行测试并生成 `bin/inkweft-backup`。使用 `CGO_ENABLED=0`，支持 Windows amd64、Linux amd64/arm64；[modernc.org/sqlite](https://pkg.go.dev/modernc.org/sqlite) 不要求部署 C 编译器或动态 SQLite 库。密码使用 [Argon2id](https://pkg.go.dev/golang.org/x/crypto/argon2)，每次校验固定 64 MiB、3 轮，最多两个同时校验。

```powershell
./bin/inkweft-backup.exe --db state/backup-v2.db --add-user alice
./bin/inkweft-backup.exe --db state/backup-v2.db
```

密码从终端或 stdin 输入，不放进命令行。`--reset-password alice` 重置认证并撤销该账户会话；恢复密钥仍由用户保管。默认只监听 `127.0.0.1:18751`。真实设备连接使用 HTTPS 反向代理，或传 `--tls-cert` 和 `--tls-key`；Android 不跳过证书检查或跟随重定向。

单账户默认预留上限 32 GiB，全站 128 GiB，可用 `INKWEFT_BACKUP_ACCOUNT_BYTES` / `INKWEFT_BACKUP_GLOBAL_BYTES` 调整。每账户最多 4 个 pending、16 个资料库，每库最多 10 个 pending/published/deleting 版本。预留包括密文开销，磁盘预检还扣除未完成的实际分块和 32 MiB 水位。32 项 HTTP 请求、4 个数据库连接、1 个短写事务、每请求最多 1 MiB + 32 KiB；HTTP 和写入排队使用取消上下文。`GOMEMLIMIT=384MiB` 是 GC 软目标，实际峰值以验收测量为准。

## 数据路径与格式变化

Go 服务 schema 为 2003，密文目录为数据库路径加 `.v2-chunks`。v1 Python schema 1 不自动迁移；启动 Go 时必须使用新的数据目录，原目录、原包和恢复密钥保留。旧云版本可用旧客户端导出，再用当前客户端从资料库重新生成并上传 v2；该变化不会自动删除资料。认证路由仍使用 `/v1/sessions`，备份路由使用 `/v2`。旧 Android 的 v1 待办需要在原服务完成或显式放弃后重新生成。

服务端备份需要先停止服务，再一起复制数据库、仍存在的 WAL/SHM 文件和整个 `.v2-chunks` 目录到新的私有目录。单独 SQLite 快照不能表示完整备份。恢复时从独立目录启动同版本程序，核对 identity、账户隔离、操作回执和下载解密；保留旧目录作回退，不能直接替换运行中的文件。

## 持久性与诊断

块校验后写私有 `.partial` 文件并 fsync，Unix 原子 rename 后 fsync 目录；Windows 使用 [MoveFileEx 的 WRITE_THROUGH](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw)。之后 SQLite FULL 事务才登记块回执。发布在写事务外用固定 64 KiB 缓冲逐件复核磁盘长度与 SHA-256，每次约 2 秒后持久化已核验块数，未完成时返回 `PENDING` 和 `verified`。重启继续该进度，最终短事务核对已登记块数/总字节并改变状态。未登记但已落盘的块在同一块重试时重新校验并登记；发布/删除响应丢失只查询原 operation，不产生第二份操作。

下载块必须持有绑定账户、设备、版本、清单摘要的租约，15 分钟刷新、最多 24 小时；删除关闭新的租约，已有下载可完成，删除失败继续保留配额。7 天无新增块进度的待办过期。后台每分钟继续处理过期与待删除目录，清理完成后才释放预留；回执身份始终保留。

错误日志只有服务生成的请求关联号、阶段、状态码；不记录 URL、账户名、正文、登录密码或密钥。`--fixture`、`--test-mode`、`--test-crash` 仅供独立合成验证目录；`--fixture` 拒绝已有账户的数据库，不能作为正式账号配置方式。

验收工具 `client_v2.py` 复用原加解密工具，面向真实 Go HTTP 服务。Android 大容量用例生成完整作者归档，经实际传输后在空白隔离库执行解析、引用校验和恢复。容量计算、普通小包、模拟器和真机结果分别记录。
