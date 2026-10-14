# Narrow independent raster publication review

PASS for the reviewed inactive bytes. No remaining finding in this scope. This review touched only its own scratch evidence; it did not edit controller/Android sources, run Gradle/native tests, start CI or push.

The review covered failure transport accounting and the joint 20 MiB / eight fixed buckets in run.py, package.py and size_bounds.py, plus the size module's binder/publication path. The earlier source/12-call/production/helper/full normal-latch reviews were not repeated.

- Independently recomputed all twelve success, first-failure and interrupted-normal bounds, plus 84 method-state wire charges. Success is 17,795,072 B; largest interrupted normal plus REFUSED is 17,803,264 B; largest complete failure path is 19,245,952 B, leaving 1,725,568 B below 20 MiB. The fixed eight buckets sum to exactly 3,145,728 B and are counted once.
- Invoked the real failure-forward function with synthetic immutable UTF-8 raw text and interrupted it immediately after BEGIN, PART and END output. Persisted state deliberately remained RUNNING without a failure receipt or normal latch. All three cases reserve the complete 1,439,104-byte failure wire envelope from persisted state/raw-file presence. This closes the print-before-final-save window without requiring the final failure receipt.
- A persisted normal latch reserves the normal bound plus only 8,192 B REFUSED metadata on a non-PASS outcome. It does not reserve a second full failure body. Unattempted/no-raw methods reserve zero. Runtime status and existing raw files conservatively cover incomplete or missing-receipt cases.
- The actual packager's 102-name fixed selection is unique: 24 method raw/parsed files are outside fixed buckets; all others map once. summary.tmp is explicitly retained and charged to summary. Unknown evidence names fail. upload must be absent before packaging; no prior output is incorporated into the new manifest. Oversized artifact buckets/joint totals fail without truncating original raw evidence.
- Read and independently checked four actual synthetic packager receipts from the owner's final audit: success, killed failure, interrupted normal and summary.tmp. Artifact bytes plus official wire equal the joint total, all bucket receipts close, retained files fit their recorded allowance, and the size_bounds.py manifest hash matches the reviewed module. Binder copies size_bounds.py together with its failure_text.py dependency; package selects both into the final artifact manifest.

The 128 KiB fixed official bucket remains a conservative envelope for this fixed source's CLOCK/METHOD/error/cleanup/FINAL/framing output. Only FINAL has a direct 32 KiB pre-print check. This is explicitly not a bound on arbitrary external output. Likewise, malformed/unbounded producer output is subject to actual refusal rather than a promise of complete publication. Those are documented scope limits, not native performance conclusions.

Reviewed hashes:

- run.py: 5ba57d828a02581a1bbfc21df982abc085fb4f39c39dc481735cfafe46319f60
- package.py: 9e7cdd2b90079d5262707c360ee2611d42d71802170a4123404fca90c10c4900
- size_bounds.py: 82e1d347d341242d7054a090c6b0b4e2364a9ae69854e41e816e16e3e908e1a5
- bind_control.py: 3ac263b06de4680b3263e1188cf99f8e8171fdd13c8036b50b9aa89c89b57bec
- Owner final 16-group/84-state audit: abe7b399ec4ddb218001f2be1ca1d430b4592ee1d509cb24cb648df490476641

Independent evidence: check_narrow.py and results.json. Frozen readiness binding and the final EXECUTION_SHA256SUMS (including size_bounds.py/failure_text.py) remain parent-owned. No activation was performed by this review.
