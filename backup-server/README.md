# 墨织 Go 加密备份服务

当前实现使用单个 Go 程序、SQLite 元数据和不可变密文分块文件。单份明文资料库最多 8 GiB，AES-256-GCM 每块 256 KiB，网络块 1 MiB、最多 8,193 块。恢复密钥由用户保管；服务端接收密文。

Windows 运行 `./build.ps1`，Linux 运行 `./build.sh`。构建需 `go.mod` 指定版本的 Go；程序以 `CGO_ENABLED=0` 构建，部署不要求 C 编译器或动态 SQLite 库。Windows amd64、Linux amd64/arm64 的交付身份与实际验证记录见 Android 本轮验证报告。

```powershell
./bin/inkweft-backup.exe --db state/backup-v2.db --add-user alice
./bin/inkweft-backup.exe --db state/backup-v2.db
```

默认监听 `127.0.0.1:18751`，密码从终端输入。账户默认 32 GiB、全站 128 GiB，配额按整份密文原子预留；物理余量另行检查。支持设备会话撤销、账户隔离、幂等上传/发布/删除、上传缺块续传、下载租约与删除等待。登录接口仍为 `/v1/sessions`，发现与备份接口使用 `/v2`。

部署配置、运行依赖、数据路径与停机备份方法见 [GO-DEPLOYMENT.md](protocol-v2/GO-DEPLOYMENT.md)，协议和状态规则见 [CONTRACT.md](protocol-v2/CONTRACT.md)。Go schema 为 2003；旧 Python schema 1 必须保留原目录并在新目录重建，不能修改版本号强行打开，也不会自动删除旧资料。

```sh
docker compose build
docker compose run --rm backup inkweft-backup --db /state/backup-v2.db --add-user alice
docker compose up -d
```

Docker 镜像只包含 Go 服务。真实设备连接需有效 HTTPS 证书或反向代理；Android 不跳过 TLS 校验或跟随重定向。提供构建文件不代表已部署公网或通过 Docker 运行验收。

验收运行 `go test ./...`，再设置 `INKWEFT_BACKUP_BIN` 指向交付程序并运行 `python -m unittest discover -v -p 'test_*.py'`。Python `client.py` / `client_v2.py` 用于密码学与协议验收；`server.py`、`fixture.py`、`go_fixture.py` 和 `tls_fixture.py` 保留旧 v1/影子同步夹具及测试代理。v2 备份请求始终由真实 Go 进程处理，这些 Python 夹具不作为生产后端。

Android 独立大容量用例生成真实作者归档、执行原生客户端上传与空库恢复。真机结果单独记录，不由模拟器或容量算式代替。实际执行证据见 [本轮验证](../android/verification/cloud-performance-20261012/README.md)。
