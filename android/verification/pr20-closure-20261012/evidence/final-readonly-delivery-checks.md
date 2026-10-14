Final Git / PR / Actions readback draft

Run these read-only checks when the parent has finished the authorized work and final commit/push. This draft does not include staging, commit, push, merge, deployment, workflow dispatch or retry commands. No remote command below was executed for this preparation task. Do not change or rebuild Docker as part of this readback.

Local checkout and whitespace scope:

```sh
git -C /workspace/InkWeft status --short --branch
git -C /workspace/InkWeft diff --check
git -C /workspace/InkWeft diff --cached --check
git -C /workspace/InkWeft diff --stat
git -C /workspace/InkWeft diff --cached --stat
git -C /workspace/InkWeft log -5 --format='%h %s'
git -C /workspace/InkWeft rev-parse HEAD
```

Read both remote branch tips without fetching or changing local refs:

```sh
git ls-remote https://github.com/InkWeftDevs/InkWeft.git refs/heads/codex/android-perf-integration-20261012 refs/heads/codex/storage-optimization-20261011
```

Read PR #20 metadata and its exact head/base identities:

```sh
gh api --method GET repos/InkWeftDevs/InkWeft/pulls/20 --jq '{number,html_url,state,draft,merged_at,head:{ref:.head.ref,sha:.head.sha,repo:.head.repo.full_name},base:{ref:.base.ref,sha:.base.sha,repo:.base.repo.full_name}}'
```

Confirm the local final HEAD equals the isolated remote branch tip and the PR head SHA; confirm PR #20 is still a draft, is unmerged, and targets `codex/storage-optimization-20261011`. The source branch tip is read for audit, not to reset or overwrite anything. `git diff` may be empty after commit: inspect the final commit history/evidence as well, and report any unrelated pre-existing changes honestly.

Read Actions and checks for the exact final local commit. `--method GET` is explicit because `gh api` otherwise changes its default method when `-f` fields are supplied:

```sh
inkweft_final_head=$(git -C /workspace/InkWeft rev-parse HEAD)
gh api --method GET repos/InkWeftDevs/InkWeft/actions/runs -f head_sha="$inkweft_final_head" -f per_page=100 --jq '{total_count,workflow_runs:[.workflow_runs[]|{id,name,event,status,conclusion,head_branch,head_sha,html_url}]}'
gh api --method GET "repos/InkWeftDevs/InkWeft/commits/$inkweft_final_head/check-runs" -f per_page=100 --jq '{total_count,check_runs:[.check_runs[]|{id,name,status,conclusion,details_url,head_sha}]}'
gh api --method GET "repos/InkWeftDevs/InkWeft/commits/$inkweft_final_head/status" --jq '{sha,state,total_count,statuses:[.statuses[]|{context,state,target_url}]}'
```

Interpret empty Actions/check results as no matching execution reported, not CI success. Preserve queued/in-progress/failure/skipped/cancelled statuses as returned; an older run on another SHA is not final-commit verification. These calls request at most 100 entries each; paginate read-only if totals exceed returned counts. Cloud-local native receipts remain separate from GitHub Actions evidence. Do not dispatch workflows to manufacture a green readback; current CI scope/permission constraints remain documented in `ci-environment-review.md`.

Authentication is supplied through the existing authorized CLI setup. Do not print auth configuration, tokens, environment dumps or credential-bearing remote URLs. If access is unavailable, report the readback blocker; do not alter credentials or permissions.
