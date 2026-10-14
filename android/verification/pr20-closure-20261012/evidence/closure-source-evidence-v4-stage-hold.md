# v4 stage evidence audit — delivery on hold

Recorded 2026-10-13T02:10:44.011290+00:00. Final delivery is paused pending independent hotspot/minimal-change review of the roughly 4.5x single-layer local-append regression. Four existing-APK repeats are now complete. This is a historical v4-stage record; no final manifest was frozen and no acceptance gate was closed.

- Selected 247 completed literal evidence/script payloads, including the previous 172 + 12 files, remaining baseline native/environment/artifact records, paired summaries, source bindings and final stage reviews. All 184 earlier payload hashes remain unchanged. Historical failures remain included.
- Scanned 249 exact literal payloads with nine sensitive-data patterns: no hits, prohibited binaries or environment dumps. The Gradle runner literal remains safe at the recorded SHA; it was not executed and runtime proxy/environment values were not read.
- Candidate v4 744/744 frozen inputs match working-tree bytes and source commit `c60fba7455cfc0b919d6f9c2d3b06f17ec25713d`; baseline 734/734 build inputs and all 1894 full-manifest source files match. All four shared factory/probe paths match. Docker Git tree remains unchanged.
- Both saved performance runs passed five tests across four selections. Each probe has `VERIFIED_NATIVE_RUN` on both sides and `PAIRED_RECORDED_ENVIRONMENT` with no parser issues. All four recorded environment fingerprints match. This does not establish identical transient scheduling or physical-device speed.
- Four additional TileLocalRedrawTest runs each passed 2/2; all eight new environment fingerprints match the original four. Original single-layer 1.436→6.428 s is retained, alongside same candidate APK repeat1 4.177 s and repeat2 1.612 s. All raw logs, receipts, setup, environment and installation records, plans and the source investigation are included. These observations do not establish resolution or a unique cause.
- Both sides' three APK artifact/signature/install/after-run continuity receipts join correctly; v4 final-restore receipts also match. No APK was installed, rebuilt, copied or freshly read from the device in this audit.
- Debug metadata remains `local-unknown`, `FORMAL_BUILD=false`. External source and APK manifests bind tested bytes; the APK does not embed the later source commit.
- README local links are mapped in the JSON. 59 selected files are not yet in the repository delivery directory; copying remains the parent's task, after the hold is resolved. This audit does not edit README or repository files.

Original search 10s, backup cancellation 15s and dense frame 30s gates remain open. All observed latency regressions are preserved. Future hotspot diagnostics or code changes are excluded until completed and separately bound. Any later production, probe or artifact change needs a new binding and updated final manifest.

Artifacts: `closure-curated-manifest-v4-stage-hold.json`, `closure-privacy-review-v4-stage-hold.json`, and `closure-source-evidence-v4-stage-hold.json`. Pattern scanning is limited to the exact selected bytes, not a universal absence guarantee. Synthetic fixtures share code/scale, not database bytes.
