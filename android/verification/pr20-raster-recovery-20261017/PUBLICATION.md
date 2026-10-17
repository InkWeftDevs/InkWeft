# Exact-object recovery publication contract

This is one reviewed recovery round after run 39053188644 failed the first data guard. Preserve that failure. Rebuild all five APKs; do not reuse first-run APKs or add samples, calls, seeds or retries.

1. Publish the exact repaired candidate 1c542a708c709715e11eedbd8398d5434ec9f061 to codex/raster-cache-value-candidate-20261017 and exact inverse control a9a335a535de05398c066ae6f68028efa17977be to codex/raster-cache-value-control-20261017. They have the same repaired test and differ only in the six-line all-miss production branch.
2. Commit the inactive reviewed readiness as R. Bind its immutable frozen plan and all seven authorization hashes into a preparation commit P. Publish exact P on the new codex/kvm-pr20-raster-recovery-20261017 branch. No active recovery workflow exists at P.
3. Independently verify remote C1, C2 and P. Prepare A locally as a direct child of P, changing only the proposed workflow before-SHA guard to P, adding its identical .github/workflows/pr20-raster-recovery.yml copy and the eight-file execution hash manifest.
4. Obtain independent exact-object activation review and the parent launch decision before pushing A. Only the P-to-A push on that branch/path may run; created branches, attempts above one and other before-SHAs are refused. Do not replay the first-round branch/workflow.
5. Use exact Git bundles if authenticated publication is available only in the saved cloud. A rewritten commit SHA is not an acceptable substitute. Verify all bundle/object hashes before importing or publishing. Do not create a PR or alter main/release.
6. No start after 2026-10-17 23:00 UTC. Setup/data/raster/work/job bounds remain 30/45/50/55/60 minutes and cleanup must finish before 2026-10-18 00:00 UTC. An earlier confirmed quota reset ends advancement after this round.

Native validation remains NOT_RUN in this preparation record. Host checks: 12 controller groups, 10 synthetic adb ownership tests and 11 pan host tests. Actionlint is unavailable; YAML and embedded shell syntax are checked. The actual adb binary was separately probed for socket activation and explicit/environment-only remote-host refusal; runtime ownership receipts remain required from the new job.
