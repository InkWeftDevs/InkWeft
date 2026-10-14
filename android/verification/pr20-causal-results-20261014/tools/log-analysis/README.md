# Offline causal diagnostic log closure

This analyzer reads already-obtained official decoded logs and terminal metadata. It never fetches artifacts, queries GitHub, launches CI, runs Gradle/ADB, or changes frozen inputs. Its result is **DIAGNOSTIC_ONLY**, with `numeric_acceptance: NOT_EVALUATED`.

The actual run currently selected by root is38514139119/job115689021611, attempt1, workflow commit `d4a107dd84c6e309b5dbbbfb3a57d69371024408`, source `78a5babeaeb92292026423222d5e08635989198b`, readiness `b2e1d9f9de66a26f5a4b122ea6754d77198005f8`. These identifiers alone are not a result. Root supplies the original terminal metadata, decoded log and final execution clone after completion.

```sh
python3 /workspace/scratch/pr20-causal-20261014/log-analysis/analyze_logs.py \
  --job-log /ABS/official-job-decoded.log \
  --run-metadata /ABS/run-terminal.json \
  --jobs-metadata /ABS/jobs-terminal.json \
  --binding /ABS/binding.json \
  --frozen /ABS/control/android/verification/pr20-causal-20261014 \
  --control-root /ABS/control \
  --run-id 38514139119 --job-id 115689021611 \
  --workflow-sha d4a107dd84c6e309b5dbbbfb3a57d69371024408 \
  --source-commit 78a5babeaeb92292026423222d5e08635989198b \
  --readiness-git b2e1d9f9de66a26f5a4b122ea6754d77198005f8 \
  --out /ABS/NEW-analysis-directory
```

`binding.json` is root's curated official identity receipt. Required fields are `scope` (`DIAGNOSTIC_ONLY`), string `run_id`, `job_id`, `run_attempt` (`1`), `workflow_sha`, `source_commit`, `readiness_git`, exact `prep_sha`, `run_status`, `run_conclusion`, `job_status`, `job_conclusion`, `input_hashes` and `control_hashes`. The six input hashes use the controller authorization keys. Control hashes include exact repository-relative `ci/pr20-causal/{run.py,markers.py,package.py,cleanup.py,authorization.json,workflow.proposed.yml}` and exactly one active `.github/workflows/*.yml`. Every listed control file is read and hashed. The exact prep parent must match the active guard. This binding is cross-checked against unmodified official run and jobs API JSON; exactly one job, push event, branch/path, attempt and expected IDs must agree.

The parser requires the original UTC timestamps retained in decoded log lines. It checks the actual job span<=60minutes, the first-step30/45/50/55 cutoff contract, actual ordered setup/build/emulator/data/raster step times and each method block's first and last receipt within its corresponding method step. The one-second allowance on a step's completed timestamp accounts only for whole-second API precision. It never extends a phase/work cutoff.

Every method, including zero-marker guards1–7, must close CONTEXT→RECORD*→END→METHOD PASS. Diagnostic markers require frozen closed shapes, exact counts/order, original JSON byte length/hash and reconstructed compact records hash. Unknown/malformed prefixes, duplicate JSON keys, NaN/Infinity, unexpected records, skipped/failed JUnit, wrong arguments/source/test/APK identity, incomplete package cleanup or missing files fail closed. Contexts bind all three selected/installed APKs across the two groups and a single signer/source/APK receipt. FINAL must bind all13 method logs, two successful groups, stable environment hashes and both zero-error exact-ACL cleanup claims.

A complete result uses `analysis_status: COMPLETE_SCOPED_CAUSAL_LOG_EVIDENCE` and exit0. Any input/parse/closure/metadata/file-read error uses `INCOMPLETE` and exit1. An actual failed run is parsed far enough to preserve validated prior/partial records, but its official failure remains in `issues`; no partial file has `whole_run_complete:true`.

Outputs:

- `analysis.json`: `expected` IDs, `input_hashes`, input file hashes, limitations, `issues`, and `state`. `state.blocks` is a list keyed by `context.ordinal`; each entry retains context/ordered payload hashes, `ended`, `method_pass`, first/method receipt epochs and record count. `state.methods` holds passed method receipts; `state.observed_method_receipts` also retains a failed receipt. `state.final` is set only after final cross-checks; `state.final_observed` preserves a received FINAL before those checks.
- `method-NN-C1-records.json`: original semantic records in compact JSON `[{"marker":"...","data":{...}}]` plus one newline, matching the controller's `records_sha256` for a complete block. Guards1–7 have empty arrays. Methods8/9/10 feed search analysis;11/12 feed GC;13 feeds raster.
- `extracted_records` in `analysis.json` is a list of `{ordinal,path,sha256,record_count,block_closed,method_pass,whole_run_complete}`. Consumers must require the top-level complete status, no issues, a unique matching item and matching local file hash before treating results as a complete diagnostic.

The official log contains compact receipts, not full original JUnit files/APK binaries/ACL files. This analyzer cannot independently re-audit those objects. Environment-after equality is a bound-controller group PASS claim; its before hash and global before/after hashes are forwarded. Numerical diagnostic interpretation belongs to the separate search/GC/raster analyzers; no performance threshold, formal acceptance, zero-observer-cost or exclusive causal claim is produced here.

`frozen_markers.py` is an exact copy of the already-frozen pure controller validator (SHA3514d2bca1f7b91efe7886ea42763b37eca873c8914938e8ad3dfeb03eaa1592). The analyzer verifies that this matches the execution controller's `markers.py`; it does not edit/import the runner itself.

`test_analyzer.py --out <NEW scratch directory>` runs synthetic offline cases. It uses frozen schemas/identities with explicitly synthetic run900001/job900002, generated payloads and temporary metadata. Its PASS is parser preparation only, never Android evidence. Historical `check-01` and `check-02` are retained; `check-03` covers49 cases including failed method11 preserving the first ten closures and a late file-read failure after successful parsing.
