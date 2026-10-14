# Budget3 最终激活只读复核

结论：PASS，未发现激活变换或保守时钟边界偏离。仅本地读取 Git 和已保存的服务器元数据；未重跑检查、访问网络或触发其他 job。

- 当前 HEAD 为 `6b180ef65fa0d966f42967fe6a592f7bfe0a8ae5`，唯一父提交为准备提交 `6e3438e0d03fe473979cc7e4b5f7d909ca148e0c`。`git status --short` 为空。
- 完整提交差异仅新增 `.github/workflows/pr20-kvm-triple.yml`。其内容逐字节等于准备提交内 `workflow.proposed.yml` 将唯一的 `github.event.before == '0000000000000000000000000000000000000000'` 替换为准备提交 SHA；无其他变换。
- 准备/激活两提交的 `ci` 树均为 `fb8796905730a7bf585d8da3507e26da470c6053`，`android` 树均为 `17985fa0a7a7312435452f9a167ebc4d518fd9d2`。控制器、真实锚点和冻结 Android 源未随激活改变。
- 已保存的激活前元数据分别显示准备提交运行数 0、仓库 in_progress 0、queued 0。激活后元数据仅列出本分支的一个 push run `38441089594`，attempt 1、head 为该激活提交；此为读取时快照，不是未来无其他运行的保证。
- `jobs-initial.json` 独立给出唯一 job `115447545993`，开始时间 `2026-10-13T17:44:33Z`，对应 epoch `1791913473`。冻结锚点 `1791913339`（17:42:19Z）提前 134 秒。因此所有锚点累计截止均比相同偏移的实际 job 截止早 134 秒；没有后移锚点。
- 累计 38/41/53/55 分钟的固定截止仍为 UTC 18:20:19、18:23:19、18:35:19、18:37:19。实际 job 的 60 分钟硬限对应 18:44:33；该余量不构成清理完成证明。
- 初始 job 快照显示首时钟、checkout、Java 已成功，源码/SDK 步骤正在进行。构建、KVM、57 项方法与清理尚未在此快照中完成；本记录不证明 APK 身份、ACL 恢复、原生正确性或性能结果。

可复核哈希：

| 对象 | SHA-256 |
| --- | --- |
| 准备提交 proposed workflow | `036cd396213295f8613d4419e55e6ee8269948b9069f0e818127029a1f2fb4a4` |
| 激活 workflow | `bb24a9559b0e1bc3821eb893ef54bbc205ad584fcba26fd0d7a31da5e710f7a6` |
| bound-guard.json | `02157f8fdf5ffa0b691357fcbd5dc1d7e4431184d3bb4c023f66178189571a1a` |
| jobs-initial.json | `1cc5f89976055f9afdc0e0cebeb0c5b6cc51acd46ed1d67101738c64999f288b` |
| anchor-vs-job-start.json | `e4cff43fcc462080f09bef5163054d916dbfa6a359835e8ec5bf4a009d7877dd` |
| runs-after-activation.json | `ddbb4153d5606bd196623ba647738d649968bbee6754918b607fe65e856a4776` |

`bound-guard.json` 的已有检查结论与本次独立字节比对相符；没有重跑其中记录的 actionlint。
