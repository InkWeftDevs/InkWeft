# Run 38520196523: frozen classification rule audit

**Conclusion: the frozen analysis did not deviate from its original rule.** The original pre-run PLAN and pre-run analyzer both require at least three positive paired deltas, together with both paired medians exceeding the numeric bands. They do **not** require at least three individually over-band pairs. Applying the actual frozen rule gives **45 WITHIN_PROPOSED_BAND / 5 INCONCLUSIVE / 4 REGRESSION_SIGNAL**. Substituting the proposed `over_band >= 3` requirement gives **45 / 6 / 3**, but that is an alternative-rule sensitivity calculation, not the original contract.

This is an engineering screening signal, not proof of a stable causal regression or final acceptance. The different aggregate rule does not change any observation, individual threshold, sample, seed, percentile, or search/save result.

## Historical source chain

- Earliest pre-run commit: `c37ba03232b1a0985e85cbaec4d349dbab945919`, authored `2026-10-13T09:49:30Z`, titled “冻结四组KVM配对协议与一次性执行脚本（尚未运行）”. `ci/pr20-kvm-paired/PLAN.md:114` says: “同指标至少3/4对同向变慢，配对中位时间增幅>相应relative，且中位绝对增量>absolute。” SHA-256 `028e1b7051626235636c08d7f6627a0d1f9298d3ce9009d0cae3803caad38ee4`. This is byte-identical to `android/verification/pr20-kvm-paired-20261013/evidence/frozen-PLAN.md:114` now in the repository.
- The same pre-run commit's `ci/pr20-kvm-paired/analyze.py:63` implements `sum(p['delta_ms'] > 0) >= 3`; lines 64–65 require both medians beyond their bands. SHA-256 `d3bb05d493691be7924ecef0569d747645628c7abf0005287a8b045a69e36adf`.
- The same pre-run commit's `ci/pr20-kvm-paired/protocol.json:56` supplies numeric bands, but no aggregate 3/4 rule. SHA-256 `1065177d7809426d949c4b4b67bd639a47c3fc1943f706a77c2dfabb985e9aba`. It is byte-identical to the current `android/verification/pr20-match-candidate-20261014/original-protocol.json` and the earliest archived `frozen-protocol.json`. The protocol hash alone cannot establish an `over_band >= 3` requirement absent from that file.
- The later pre-run `android/verification/pr20-native-readiness-20261013/PLAN.md:102` explicitly retains the same positive-delta-plus-medians rule. SHA-256 `d5efd83c08b6bb6e107c397db8ca3999337693dc2af45f0615c5e198bac93077`.
- Current frozen `android/verification/pr20-match-candidate-20261014/analyze.py:40` implements the same rule at lines 43–46. SHA-256 `fdbf7b76ebbf587bccaede00910209ba2586c0982bbd0da80cb26cb94ce07740`.
- This difference was already explicit in `android/verification/pr20-candidate3-results-20261014/README.md:19`: a prior single-layer append signal with only 2/4 individual over-band pairs was kept as REGRESSION_SIGNAL because all four were slower and the paired medians crossed both bands. That historical result must likewise remain unchanged.

## Independent reconstruction

`recompute.py` reads the actual official decoded job log, independently verifies all 365 original payload byte counts/hashes and all 62 parsed-record hashes, and checks equality against all 62 extracted records. It reconstructs the 18 metrics from the common 24 calls, including all 720 raw ordered save values with their timestamp-derived durations and committed flags. Save percentiles use nearest-rank indexes 14 / 28 / 29, not the legacy probe p50 or pooled values.

The script imports no frozen analyzer code. It recomputes every one of the 54 comparisons under both rules, every individual over-band flag, positive-pair count, paired median, and threshold; all original results and numeric pair records match the frozen output exactly. `review.json` preserves all 54 comparisons and all four pairs. `comparisons.csv` is a small 54-row view.

Official decoded log SHA-256: `d1634bbeb118100737e2c9d6d6111fca972f2fb05999b5604ff34f0d0e7c9f62`. This audit does not replace the separate execution, cleanup, or APK provenance review.

## The single changed classification

For B→C1 `raster.8.forcedFullCompleteMs`, the four seed-ordered deltas are `[-21.328534, +61.678235, +16.397921, +65.572811]` ms. Three pairs are slower; only two exceed the individual `delta > max(10% × B, 5 ms)` test. Their paired median delta is **+39.038078 ms**, and paired median relative change is **+12.219478%**. Thus the original rule classifies it REGRESSION_SIGNAL; the alternative 3-individual-over-band rule calls it INCONCLUSIVE. This is the only difference among 54 results.

For C0→C1 on the same metric, all four are slower and two are individually over band, but the paired median relative change is **+8.926004%**, below 10%; paired median delta is **+29.610528 ms**. Both rules classify it INCONCLUSIVE. The difference between these two comparisons follows directly from the relative-median condition, not rounding or a different threshold.

The other three original signals survive either rule:

| Comparison and metric | Slower / over band | Paired median delta | Paired median relative |
| --- | ---: | ---: | ---: |
| B→C0 observe p50 | 4 / 4 | +4.573128 ms | +138.076676% |
| B→C1 observe p50 | 4 / 4 | +4.406300 ms | +128.576177% |
| B→C1 direct p95 | 4 / 3 | +6.736855 ms | +180.558737% |

## Minimal correction

Preserve the original frozen inputs and the original generated output. Correct any report saying the original rule requires three individually over-band pairs; report 45 / 5 / 4 under the original rule. If useful, present 45 / 6 / 3 explicitly as a secondary sensitivity calculation alongside it. Keep the eight-layer result described as a **screening signal under the frozen rule**, with the 2/4 individual count and C0→C1 uncertainty visible; do not describe it as a proven persistent performance regression. Do not silently replace the classifier after observing results, rerun the completed native job, or change any thresholds.

Reproduction (read-only source/evidence access; output goes to scratch):

```sh
python3 /workspace/scratch/pr20-match-execution-20261014/rule-audit/recompute.py --repo /workspace/InkWeft --out /tmp/pr20-rule-review.json
```
