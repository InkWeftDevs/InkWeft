# Raster publication budget audit

Offline only. No production code, source commits, Android execution, CI, push or data-runtime edits. The two fixed sources remain C1=207bc654e0c922bb58b599d0ca619aff85eeebeb and C2=fcc8dd39f029f4bf04f4c3f1bccc1e3ed4bfc353. This audit found and repaired two publication gaps and made the fixed reserve enforceable by category.

## Repairs and exact scope

1. A failure helper can print before the final method `save()`. If killed there, disk summary remains RUNNING without `failure_log_forwarding`. The old packager omitted that wire. `method_wire_bound` now reserves the complete failure stream for any attempted/incomplete method or existing raw method log without a persisted normal latch. A normal latch reserves only normal output plus 8192 B refusal metadata; it never adds a second full failure stream. The actual helper producer and run.py are unchanged.
2. Existing `upload/` could contain unaccounted files that the workflow wildcard would upload. It must now be newly created. Unknown evidence files are refused. An interrupted atomic `summary.tmp` is explicitly allowed, retained and counted in the summary bucket; it is not silently discarded.
3. The prior fixed3MiB aggregate had no subdivisions. `size_bounds.py` now supplies fixed disjoint buckets shared by arithmetic and packager. `package.py` publishes a bounded `publication-budget.json` receipt and enforces every bucket and the joint total. `bind_control.py` copies that existing size module into the bound runtime. The helper remains7a096a8c… by root instruction. No new fallback framework, runtime output sampling or generic source changes were added.

## Fixed3MiB, counted once

| Category | Limit bytes | Contents |
|---|---:|---|
| Frozen/control | 786432 | Eight frozen input files; nine runtime/controller/auth/hash files |
| Build/dependency | 1048576 | Two build logs, two APK receipts, two dependency receipts, official dependency receipt/log, SDK log |
| Source/install/environment | 262144 | Clock, host/device snapshots, source fetch/reconstruction, acceleration/AVD, all five install logs and20 before/after environment/host files |
| Summaries | 524288 | Final summary, pre-packaging original summary, optional interrupted summary.tmp |
| Cleanup/emulator | 131072 | Both cleanup receipts, exact ACL before/granted/after files, KVM identity, emulator configuration/selected log |
| Method receipts | 163840 | Twelve JUnit files and twelve normal-forwarding receipts; raw/parsed method bodies are excluded here |
| Packaging | 98304 | Complete manifest, packaging metadata, and a16KiB allowance for publication-budget.json |
| Fixed official receipts | 131072 | CLOCK, METHOD, fatal controller, both cleanup stdout receipts, FINAL and package/trace framing |
| **Total** | **3145728** | Exactly3MiB; no other shared reserve |

The selected runtime fixed list has102 entries:24 raw/parsed method body files outside these buckets;78 fixed files assigned exactly once (build9, source/environment34, summaries2, cleanup9, method receipts24). Add17 frozen/control files. Optional summary.tmp is a120th selected input when present. Known frozen/controller bytes are approximately0.56MiB; the exact latest value is in `budget-audit-03/audit.json`. Synthetic small build/cleanup fixtures are not offered as estimates of real runner output.

The packaging maximum is independently rebuilt from the closed120-name set, 20-digit byte-count fields and deliberately excessive rejection fields: manifest46040 B + packaging metadata7781 B + budget receipt16384 B = **70205 B**, below98304 B. The actual receipt is length-checked before writing. An oversize rejection uploads only a bounded packaging-failure.json; its length plus already emitted wire is checked separately. Original evidence remains in the owned evidence directory, and the failed state is not converted to success.

Fixed official framing is modeled at96256 B inside131072 B: CLOCK1024; eleven passing METHOD receipts plus one failure19456; one fatal-controller receipt8192; two cleanup stdout receipts16384; FINAL32768; package receipt2048; bounded controller tracebacks16384. FINAL has a direct32KiB pre-print check. The other allowances describe this fixed source and runner/error framing; they are not a guarantee for arbitrary external output. Unrelated GitHub action/setup logs are outside this evidence-publication budget. Normal/failure marker streams are counted separately below.

Both cleanup invocations are distinct records, so their artifact copies and stdout copies each count. Likewise summary bytes and FINAL's selected repeated metadata are two published copies. The128KiB fixed official reserve is one item inside the shared3MiB; it is not added again to static totals. At runtime it appears in `wire_upper`, while the artifact part of shared excludes it; summing artifact plus wire therefore counts it once. No normal full body and failure full body are combined for a single method. An8192 B refusal after interrupted normal forwarding is extra metadata and is explicitly modeled.

## Method bodies and official streams

Across12 methods:44 marker records; total allowed original JSON3497984 B.

| Successful-path component | Bytes |
|---|---:|
| Complete raw method files, including framing | 3717120 |
| Complete parsed records and per-method envelope | 3694592 |
| Official normal CONTEXT/RECORD/END streams | 7237632 |
| Fixed reserve above, once | 3145728 |
| **Success** | **17795072** |

For each normal method, raw=`payload+512*records+16384`, parsed=`payload+16384`, official=`2*payload+1024*records+16384`. Original producer JSON must be ASCII before forwarding, so nested escaping uses at most twice its bytes. The record/envelope allowances are separate. These are closed source/schema producer bounds, not assertions that any arbitrary malformed input has the same size.

The maximum terminal failure is method12 after eleven complete methods:

| Failure component | Bytes |
|---|---:|
| Eleven preceding complete method artifacts and streams | 11449856 |
| Full failing raw UTF-8 log cap | 1048576 |
| Parsed failure representation plus framing allowance | 2162688 |
| Full failure official stream | 1439104 |
| Shared reserve, once | 3145728 |
| **Late failure** | **19245952** |
| Limit | 20971520 |
| **Headroom** | **1725568** |

The2162688 B parsed allowance is2*raw+65536; that65536 is a failed-method derived-record/framing allowance, not another FINAL/cleanup reserve. The full failure wire uses32 chunks of32768 raw bytes; separate base64 padding per chunk is included, plus1024 B per-part header and8192 B begin/end framing. The later stream cannot begin another method after failure. All twelve first-failure positions are recomputed in the audit script.

The largest interrupted-normal case, including an8192 B refusal, is **17803264 B**. It is smaller than the full-failure case. State charges are conservative even when no bytes actually reached stdout: RUNNING/raw-without-summary reserves the entire failure block, and a persisted normal latch reserves its entire normal block. This closes the process-kill window without relying on a final save. It charges each branch once.

## Residuals, interruptions and practical limits

`upload/` must be absent before packaging. All files it later contains are generated from the fixed selection or the three bounded packaging outputs. Unknown evidence files fail; optional summary.tmp is retained. A full method raw file is always selected if it exists, even after failure. Root-owned binary APK/build caches, sources and emulator-private.log are outside the text publication selection; the declared emulator-selected.log remains included. There is no truncation of method raw bytes to satisfy a limit.

Runtime file coverage was checked against every OUT writer in run.py, cleanup.py and package.py: phase logs are source-fetch/source-reconstruction/sdk/build-C1/build-C2/acceleration/avd; install logs and before/after snapshots expand all five groups; raw/parsed/JUnit/forwarding receipts expand all twelve methods; all literal clock/host/device/KVM/emulator/dependency files are in the102-name fixed set. `save()` uses only summary.tmp then atomic replacement; package's pre-packaging copy is fixed. Private ROOT files are not unexpectedly caught by the evidence-directory refusal.

Early failure means later expected files are missing; packaging returns nonzero and preserves explicit incomplete status, while still publishing available complete raw evidence if all caps permit. A malformed producer or external failure larger than the declared closed bounds can be refused by actual bucket/joint checks. There is no claim that arbitrary output always fits or can safely be forwarded.

`check_budget_audit.py` independently recomputes all numerical totals, runs84 wire-state combinations, checks disjoint file categorization, exercises the real packager with killed-save/interrupted-normal states, retains summary.tmp exactly once, rejects stale upload/unknown evidence, and overflows six actual artifact buckets. Packaging's closed-name maximum and the fixed official model cover the remaining two. Final results: `budget-audit-03/audit.json`, plus the rerun8-group controller suite and11-group source/package suite. All are host/static/synthetic; none closes a native performance gate.
