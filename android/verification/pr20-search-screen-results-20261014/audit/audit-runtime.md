# Focused search screen runtime audit

**PASS_SCOPED_COMPACT_RECEIPT_AUDIT** — 185/185 checks, 0 issues. This independently reconciles supplied official decoded job logs, terminal metadata and immutable controller/plan/source bindings. It does not substitute for an original ZIP, raw JUnit or APK binary audit.

Run `38508580078`, job `115671626209`, attempt1 succeeded on commit `02aaa0e3df3d77c6a726931cee060e2daff4bbf2`; exact activation parent `5cd3eb9b95a1cf5816ed345c3e71b2d5b408a01e`. C0 is d66b60d (previous candidate), C1 is a9ccf2cc (empty-draft CTE). Frozen source/plan/probe hashes match committed inputs. The single standard runner uses no Gradle task cache and builds only one data test APK per version.

## Execution and timing

Official job span: **03:53:35–04:04:27 UTC =652s (10m52s)**. Last Complete job step ends04:04:24, yielding649s (10m49s); the latter excludes the final3s of the job envelope. Both values are preserved, with652s used for like-for-like API job comparisons. Step metadata has one-second resolution; the build steps include validation/hashing, not just Gradle execution.

| GitHub step interval | Seconds |
|---|---:|
| startup checkout java | 7 |
| source sdk | 39 |
| C0 data APK build and validation | 209 |
| C1 data APK build and validation | 147 |
| KVM emulator | 23 |
| correctness10 including install | 8 |
| library8 including installs | 199 |
| first cleanup | 13 |
| second cleanup | 2 |
| package | 1 |
| upload | 1 |
| post step to job terminal | 3 |

Total652s. Setup including both builds/emulator finished04:00:40 (423s from the local anchor); correctness ended04:00:48 (431s); common comparisons ended04:04:07 (630s). Their cumulative30/35/50-minute deadlines were04:23:37,04:28:37,04:43:37; work cutoff04:48:37 and job hard timeout60minutes were also respected. The first-step clock is a screening clock, observed2.619s after actual job start, not a conservative PR-server anchor or exact job-start claim.

All18 METHOD receipts match the exact frozen selections/order:10 correctness followed by8 common library calls across4101–4104. All report raw-JUnit PASS, adb0, host PASS and NATURAL_EXIT. All9 exact installation groups report PASS. The8 marker blocks contain96 original JSON payloads; strict closed schemas, original payload sizes/digests, ordered END manifests and reconstructed records.json hashes match. Fixture logical hashes/counts/writer settings match within every seed pair. No arbitrary note text or unrestricted string fields are forwarded by these probe schemas.

C0 selected/installed APK SHA256: `f4ee266375fc5ce69f1ae43aa357dc5b80136fcea408d843fdb7f7f20b5d27e7`. C1: `fa96eec07cd97d70a26265762153c3dbb3f42042d8a0f98e5d02613a87b3bca2`. Each identity is stable across all four common visits; both signers are `63885ea1bf0cc2e9f8b10cd4beb0a57662aa1981bc01800de60e5e31f1bc3409`. These are compact verified-build/install receipts, not independent signature or APK/ELF inspection here. The initial correctness group's APK is asserted by its C1 group identity and the bound controller, without a separate forwarded APK context.

All8 common pre-install environment hashes equal global before/after `bd8a6a68e3862125cb02a5bf4d9e4ca24b46bc961f2fce19815488711cd63cba`. The bound controller checks settings before/after all groups, but the underlying environment files and host-load data are not in this compact-log audit.

Both direct cleanup lines and FINAL report STOPPED_CONFIRMED for emulator and owned command groups, RESTORED_EXACT for ACL, zero errors. Both before/after hashes are `f3fffbc1c7ccf8e0e966c07730ac09719c1040b79090df185e9009e75b601f89` with exact_match=true. This verifies the receipt/hash relationship; original ACL bytes and process observations were not obtained independently.

FINAL reports EXECUTION_COMPLETE_ANALYSIS_PENDING, missing_files=[], packaging_complete=true. Safe packaging reports145 payload files,1,673,716 bytes before manifest metadata,0 omitted/0 truncated; upload reports147 files/406,469-byte ZIP, three-day retention. Upload receipt digest is `fcfb81862a8d2d3c45912ba6b8cde42abc281995c2e3a853890fe4049398be91`. No ZIP or rejected storage channel was accessed. The existing scoped analyzer consumes this exact decoded log SHA `1539266f4bbbc4e8034f9af8f2d21029036d9f4277e78a6c5368b2cfba73fcae` and reports no issues.

## Comparison with the previous full run

Previous run38498392929 was39m02 (2342s), on the same jobs.started_at→completed_at basis. This screen is10m52 (652s): **28m10 (1690s),72.1605% shorter**. Workload changed from3 identities/9 APKs/58 methods (31 correctness+24 common+3 diagnostics) to2 identities/2 data APKs/18 methods (10 correctness+8 library). App/UI/raster/GC and original-B comparison were omitted by design. This is a smaller-scope turnaround result, **not evidence of cache benefit, equal-workload build acceleration or product-performance improvement**; both controllers explicitly use --no-build-cache. New CTE direction belongs to the separately bounded same-run library comparison, not this cross-run wall-time ratio.

The full20k rapid-input correctness gate, original-B regression closure, raster/GC and full formal acceptance are not established by this screen. No workflow, native process, build, download, source edit or push was performed for this audit.
