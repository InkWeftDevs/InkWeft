# Data-only inactive controller handoff

Source: `fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353`.

Copy only the16 regular text files enumerated in `controller-SHA256SUMS` from `controller/`; never copy `__pycache__`. SHA256 of that manifest: `6611c43b87a4e01d7d9c34dba890f59a8f554664bdf340f5fd2c5829effd928c`. Structured manifest: `controller-manifest.json` (`1a44493b38308080ffabd14e77f3f4d8148973ee0f138a2822ebd3655a04c8e0`).

The scope is exactly5 existing GC guards +2 GC author-only methods +1 snapshot-control method, one data APK and one installation group. Root alone freezes readiness, commits it, binds an isolated preparation parent and activates the workflow. This handoff did none of those actions.

Root freeze command, when ready:

```sh
python /workspace/scratch/pr20-gc-save-ci-20261014/controller/freeze_readiness.py --repo /workspace/InkWeft --git fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353 --out /workspace/scratch/pr20-gc-save-ci-20261014/frozen-readiness
```

That fresh output contains source-spec, exact canonical source manifest, method-plan, marker-contract, PLAN, original-protocol, prepare_sources and SHA256SUMS. Preserve the existing source→readiness→preparation→active chain. `bind_control.py` requires the exact committed readiness bytes and emits only a new scratch bound controller; its workflow parent remains zero until root binds the exact preparation SHA. This is a local command plan, not evidence those steps happened.

Validation: final `offline-ready/preflight.json` has17 PASS groups, SHA256 `26741103b637e485de83bf73b7b9d2fec81a77dd0a7e0b2f68536e14d02f3a82`. Inputs are synthetic/local Git only. Independent GC semantic review has56 checks, pure failure+budget review44 cases, and runtime/package review27 cases. Their final reports are in `independent-gc-review/` and `independent-failure-review/`. Parent independently reran17 groups before the final missing-file exit fix; final local checks include that fix.

Final runtime hashes: run.py `506561ce1c03013ef2043855f82fd8a659b9601bfb8696c4d76a0b297af62fb1`; markers.py `850707164f4c89ae1920c1f9c99ad9e1a68be7f71b8c456b437671ddada96bda`; package.py `ed6e966941c3cbdbba620d2a85ec7ac4b5c77da5642bbcf6478ff3c9cd4c3f46`.

The conditional worst text case is19,675,060B of20,971,520B, with1,296,460B remaining. One shared3MiB allocation is enforced by five actual buckets; original raw logs plus precharged official stream bytes are checked together. Complete failure bytes replace normal forwarding; the full charge/latch is saved before any output. Late normal failures only emit REFUSED metadata. No records.json artifact duplicate, sample/cap reduction, retry, truncation or extra download path was introduced. Missing required artifacts now cause nonzero package exit.

Remaining execution prerequisites belong to root: final report collection, exact source reconstruction/readiness commit, bound offline checks, exact preparation/activation/run binding, and any separately authorized native execution. No native timing or performance result is established by this handoff. Raster is a separate scope and has no validation claim here.
