# 云端 Docker 验证交接

本轮按用户安排跳过本地 Docker 执行，完成代码与本地回归后推送 `codex/storage-optimization-20261011`。云端 Codex 拉取该分支后执行以下检查。`container_check.py` 的执行结果目前为待测；本地 Go 进程与 Android 的结果见 `android/verification/cloud-performance-20261012/README.md`。

## 准备与执行

先按云端环境的运行时说明检查 Docker、代理与 CA 信任。使用 `go.mod` 固定的 Go 版本和 Python 3.12 或更高版本。所有测试使用独立合成账户和资料，不连接已有作者数据库。

```sh
git checkout codex/storage-optimization-20261011
git pull --ff-only
python3 -m venv /tmp/inkweft-go-test-venv
/tmp/inkweft-go-test-venv/bin/pip install -r backup-server/requirements.txt
cd backup-server
go mod verify
go test -race ./...
CGO_ENABLED=0 go build -trimpath -o /tmp/inkweft-backup .
INKWEFT_BACKUP_BIN=/tmp/inkweft-backup /tmp/inkweft-go-test-venv/bin/python -m unittest discover -v
/tmp/inkweft-go-test-venv/bin/python protocol-v2/verify_design.py
/tmp/inkweft-go-test-venv/bin/python container_check.py --output /tmp/inkweft-go-container-results.json
```

`-race` 需要云端 C 编译器；部署构建继续使用 `CGO_ENABLED=0`。若容器构建需要会话代理或 CA，按环境提供的方式配置；保留原 Dockerfile 的部署默认值与测试结果，不把网络失败记为协议通过。

## 容器验收

| 用例 | 操作与预期 | 本轮状态 |
| --- | --- | --- |
| GO-CLOUD-01 | 构建 Go 镜像，UID=10001，根目录只读、全部 capabilities 移除；就绪检查成功。 | 待测 |
| GO-CLOUD-02 | 实际分块上传、发布、重启，再下载与解密；摘要一致，身份和操作回执保持。 | 待测 |
| GO-CLOUD-03 | 停止原容器后，用相同非 root UID 将数据库、WAL/SHM 和整个分块目录复制到独立卷；恢复身份、回执与数据。 | 待测 |
| GO-CLOUD-04 | 在专用 48 MiB tmpfs 接收一块后填满该卷；后续上传返回 507，已登记块不增加。删除该测试填充文件后，按原 operation 续传、发布、解密一致。 | 待测 |
| GO-CLOUD-05 | 满盘故障后再次检查独立恢复卷，原已发布资料摘要一致。 | 待测 |

脚本使用随机容器/卷名称，结束时清理本次容器与卷；镜像可继续复用。保存结果 JSON、镜像 ID、源码 commit、失败阶段和相关日志。失败时保留实际失败，不以容量计算或 Windows 结果替代 Linux 容器通过。

GitHub Actions 的 `android-a0.yml` 也已接入相同 Go 测试与容器脚本；当前分支的远端执行结果仍需单独记录。本轮没有公网部署。
