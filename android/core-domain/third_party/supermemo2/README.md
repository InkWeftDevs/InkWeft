# SuperMemo2 numerical kernel provenance

Reuse category: **minimal source port**, not a Python dependency and not merely an algorithm citation.

- Upstream: https://github.com/alankan886/SuperMemo2
- Release: v3.0.1, 2024-06-23
- Exact commit: `0aaf428cf362b976f49a2dece5e01211785caec2`
- Source: `supermemo2/sm_two.py`, git blob `f9c04b33b977b27149a8818a888d08fc16cffa85`
- Tests: `tests/test_sm_two.py`, git blob `41cceef7b485533146e34f4784d3f5d69fae49cd`
- License: MIT, Copyright 2020 Alan Kan; exact LICENSE retained here and bundled as app asset `licenses/supermemo2-MIT.txt`
- Verified against the repository GitHub API at that SHA on 2026-10-04. Repository archived 2026-06-26; no upstream maintenance is assumed.

`SuperMemo2Kernel.review` ports the numeric branches and update ordering. `Sm2Schedule.grade` actually calls it. The existing SM2-IW1 format and already correct result semantics remain compatible. Python datetime parsing/default wall clock, packaging, pytest/freezegun are not brought into Android. Kotlin retains exact hundredth ease rather than binary float drift, explicit checked UTC epoch, quality validation, hint cap, 100-year interval cap, and transaction history in InkWeft's wrapper.

The source uses **ceil**, with the old ease factor before its update. MIT is retained alongside the project's AGPL code, with attribution. The upstream library test numbers are ported separately from our independently derived official-formula tests. Integer ease intentionally does not reproduce accidental floating-point near-integer ceil drift. We do not claim bit-for-bit identity for arbitrary float inputs or an official SuperMemo certification.
