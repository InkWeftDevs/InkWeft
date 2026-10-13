# Budget3 真实锚点只读复核

结论：PASS，未发现真实填值或哈希绑定阻断。本复核仅本地读取、计算哈希；未重跑 preflight、构建、Android、网络或 CI。

- 原 `pr-anchor-response.http` 的 HTTP 200 与选中字段（PR 20、id `4835003832`、node_id、draft、head `9c9e490ee4e579946a744cb1d0555643812b91c7`、updated_at）与执行目录和控制器的两份最小 receipt 完全相符。未输出完整 PR body。
- 时间 `2026-10-13T17:42:19Z` 对应 epoch `1791913339`，contract、authorization、budget 和 proposed workflow 一致。累计 38/41/53/55 分钟截止依次为 UTC `18:20:19`、`18:23:19`、`18:35:19`、`18:37:19`；实际 job 硬限仍为 60 分钟。
- receipt → contract → budget → authorization 的 SHA-256 引用闭环一致，未发现 CLOCK 占位符残留。
- preflight SHA-256 `2779d41db5b76cc5604476f316007068e4d4c84a2c7747a3ef0d6751e5000d7a`；11 组已有检查均记为 PASS，40 个记录输入的当前哈希全部匹配；checker 当前哈希与回执一致。本次未执行该 checker，PASS 属既有离线检查回执。
- `run.py`、`cleanup.py`、`package.py` 与此前独立审过的 budget3 模板逐字节相同。本次没有更改 57 项方法、源码、探针、阈值或清理算法。

绑定哈希：

| 文件 | SHA-256 |
| --- | --- |
| clock-server-receipt.json | `81b628032f60fc90b6b29881189123304e0b7666efa7cda5b76d55fcee051bc7` |
| clock-contract.json | `27c0402923b91c024485bba9b9c9a8d93f6a87f2fff6c408f3f7c1de46e61939` |
| budget-revision.json | `0de35f7063e25c9c75daa7f4a1719eb525d40d2bdbc311c380133669dcfd582a` |
| authorization.json | `e22b58660ae1262ae3fdfe6a20b03eb5cab2aff759e7293e89a91d52567a9555` |
| workflow.proposed.yml | `036cd396213295f8613d4419e55e6ee8269948b9069f0e818127029a1f2fb4a4` |

边界：proposed workflow 的 before 仍为全 0，后续须只替换为准备提交 SHA，并核对唯一激活变换。actual job start 仍为 null，尚不能声称已实证 anchor ≤ job start；新 job 出现后须以外部服务器元数据核验。准备与排队时间继续消耗固定预算，不能承诺 57 项一定完成。本记录不代表已执行任何原生测试或获得新性能结果。
