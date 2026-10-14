# Search causal hypotheses — 607c891

This is a source/evidence review, not a new performance run. Public samples, notes invalidation, snapshot/cancellation semantics and original acceptance bands remain fixed. Subsequent authorization is limited to diagnostic instrumentation and reverting the unproven empty-overlay branch; none of that has measured results here.

The strongest facts are: d66 still regresses against original B on observe-save p50 in all four pairs (+3.0896635 ms / +124.2715% paired medians); its cancellation checks stop obsolete reads earlier; its warm page SQL occupies 97.71–98.16% of the read wall time in the separate diagnostic. These facts do not identify SQLite lock wait, JNI conversion or Kotlin matching individually.

## Sources and limits

- Original B: a128bb07 plus the fixed preserving writer factory. B's fixed `flowOf(request)` collector does not listen to `notes`; candidate does. Note saves change notes/revisions/receipts. This explains additional candidate refresh exposure, not an exemption from the p50 band.
- d66 raw run38498392929: `/workspace/scratch/pr20-candidate3-results-20261014/raw/method-58-C1-records.json`, and its public methods33–55, reviewed in `audit/audit-search.json`. Diagnostic58 is separate from all public metrics.
- Focused run38508580078 compares d66 with a9cc, not B. `/workspace/scratch/pr20-search-screen-execution-20261014/analysis/analysis.json` and `independent-analysis-review.json` preserve official-log payloads. These are not an independent raw ZIP/APK audit.
- Current source at607c891 includes a9cc. `queryPlanObserver` exists, but current performance probes do not emit its plan. Existing Room correctness asserts `MATERIALIZE page` and `SEARCH n USING INDEX`; the final checked plan is the nonempty-draft branch. It does not prove the empty branch plan, a body-read count or absence of repeated evaluation. No complete measured warm/scope plan was found in the reviewed artifacts.

## 1. Warm: current body retrieval and scalar work, not known index latency

**Evidence.** The exact public fixture is500 saved books, each body `Synthetic note body ` +20000 ASCII `b` characters, plus one author note and20000 pages. Query is `target-only`; one page in the last book matches. It is not a long-CJK-body fixture. After indexing, source hit lookup takes roughly a fraction of a millisecond; the page statement takes577.47–584.54 ms in diagnostic58. B evaluates the already-preloaded full `Note.text`; candidate obtains current author bodies through scalar subqueries and passes values from SQLite into Java for Kotlin `contains(ignoreCase=true)`. Both have title→body→tags→page/card-hit OR order.

**Hypothesis.** The ~13.33% unfavorable paired warm result versus B could be repeated body retrieval/conversion/JNI, extra scalar evaluations, or SQL work around the same matcher. It is not yet shown that all97–98% page time is inside `contains`. The SQLite argument is evaluated before the callback, so time measured inside `args.getString` also does not cover all SQLite body fetching. Callback residual is not automatically JNI time.

**Cheap discriminator.** On the exact indexed synthetic fixture, enable a per-read aggregate only: title/body/tags/source call counts and UTF16 lengths; separate wall and current-thread CPU for `args.getString` and `contains`; page statement wall/CPU; actual EXPLAIN QUERY PLAN and SQL. Compare production results with an in-memory reference using the same Boolean OR and same author heads; explicitly exclude reference preload and source-hit preparation from its matching-loop timing. Run a few ordered plain/profiled/reference iterations, reporting each; profiling is causal evidence, not an acceptance sample. Add small mixed-Unicode correctness cases. Default null observer must add no statistics allocation or clock reads.

**Decision.** Repeated body calls above eligible row count implicate SQL evaluation/planning. Get-string/CPU dominance supports conversion work. Contains CPU dominance supports the unchanged matcher workload. A large page CPU residual needs SQL/argument-load inspection; large wall-minus-CPU needs scheduling/blocking investigation. No ASCII/NOCASE substitution or cross-read body cache follows from these measurements.

**OR order bound.** Moving source hits before body may be useful for libraries with many page/card hits, but this public fixture has only one source-matching book among501. Even ideal short-circuiting avoids only one long-body match (~1/500 large bodies), so it cannot plausibly explain a13% warm difference by itself. Verify the call count before proposing this microchange; a separate high-source-hit synthetic case may establish general value later. Public fixture/order remains untouched.

## 2. Observe p50: tracking and restarted foreground reads versus index writer interference

**Evidence.** Diagnostic58 has29 real invalidations,26 cancelled observe reads (23 entered IO). Last work stages of those23: drafts-ready3, scope-start2, scope-end13, page-hits-start5; none reaches metadata. Eighteen complete scope phases total84.610 ms, with83.438 ms overlapping save windows. Empty-draft setup totals30.572 ms. Opening totals11.090 ms, registration+begin2.723 ms. Total cancelled IO wall199.848 ms is not exclusive CPU. Only one index-build-completed/index-completed occurs. The builder listens only to `page_search_text`/`study_cards`; ordinary notes saves do not restart it. Its own docs/FTS/pending mutations are not among those source invalidations. Current data therefore does not support per-note-save FTS rebuild churn.

The largest diagnostic save (sample23,30.773 ms) spans index64→128 and source0→184; the next save advances source185→195. Other28 windows have neither counter increment. That does not exclude uncommitted writer work, but it makes 'every median save waited on an FTS commit' unproven.

**Hypothesis.** Room tracking plus immediate note invalidations cause cancelled foreground SQL setup and scheduling/CPU interference that elevates the median, while index writer transactions principally contribute to some tails. Room must install/refresh tracking for the newly observed notes table, which B's fixed collector lacks. Neither this cost nor writer queue time is currently separated.

**Cheap discriminator.** A synthetic data-only diagnostic with real Committed saves, ordered in forward/reverse blocks:

1. No observer, indexed fixture.
2. Same Room invalidation flow collected into a count only (no library reader), indexed fixture.
3. Real `repo.observe`, indexed fixture.
4. Real `repo.observe`, identical author data but pending index.

These are test control arms, not production changes to freshness. Record all30 save intervals and final revision/body in each arm; in real-observe arms await the final fresh result. Record source-version/read starts/cancels and table-name masks (constant schema names only), builder starts/completions, and every index batch's prepare/request/body-enter/body-exit/return. Add analogous save request/body-enter/body-exit/return and CPU intervals. Flush after timed work, with no sleeps or artificial reader pause. Ensure actual overlap is recorded, not assumed.

Tracking-only minus idle distinguishes tracking/notification overhead; real indexed observe minus tracking-only identifies read/restart plus observation plumbing; pending observe additionally includes both index work and pending source scanning. Transaction entry delay includes Room dispatch/queue/begin, and body-enter→exit excludes final commit/release. Do not label either a pure SQLite mutex wait. Only the phase overlap and CPU/wall evidence can prioritize writer or reader work. The fourth-arm difference alone is not 'index lock cost'. Counts are correlated within each block; none replace the original four-seed public acceptance pairs.

## 3. Scope/query-plan work and physical row width despite zero returned bodies

**Evidence.** Scope is built for every read via CREATE TEMP TABLE + INSERT SELECT from the shelf CTE; metadata later scans the same logical directory. Query does not project `text`, but `NoteRow` physically stores id/revision/title/text/updatedAt and has no covering metadata index. Physical overflow-page effects are a hypothesis, not proved by zero returned body characters. Current stage markers combine table creation, SQL prepare and INSERT execution; they do not reveal the split. Named page materialization proves bounded final projection, not bounded body-matching or physical storage reads.

**Hypothesis.** Repeated scope SQL preparation/materialization and/or touching wide note records, rather than directory object construction or connection opening alone, consumes the residual cancelled-read work. Empty-draft CTE might trade a DDL operation for extra CTE planning with little net gain.

**Cheap discriminator.** First capture actual plans for scope INSERT, page and metadata in a separate untimed diagnostic pass. Split scope create/prepare/execute coarse phases only when enabled. Then compare a tiny test matrix at fixed501 notes, same ids/titles/order/classification, empty query and first600 body characters identical:600-character versus20020-character bodies. Both return the same61 previews/character count and matching ids. Three reads per variant, fresh connection as production, no schema or planner change. Scope/metadata scaling with body width despite identical output supports physical-row access; no scaling shifts attention to fixed statement/temporary-schema cost. If needed only afterwards add note-count variation. EXPLAIN supplies access paths, not I/O counters or causal wall time; do not infer overflow reads merely from a SCAN line.

## Empty CTE decision

Recommend reverting the production a9cc branch to the d66 draft overlay, while retaining the useful empty/nonempty paging assertions. This is a maintenance and causal-baseline choice, **not evidence that reverting makes it faster**. Focused four-pair results show no stable gain: observe p50 two better/two worse, paired-0.1444035 ms/-3.542564%; warm three slower/one faster,+1.395992 ms/+0.437940%; cold three slower/one faster,+14.9076765 ms. All nine formal metrics remain inside their original screening bands. Do not keep adding branches based on 'less SQL must be faster'.

Prioritize discriminator1 and phase instrumentation for2. They address warm and median-save regressions directly and can be run with one data-only candidate. Discriminator3 is a follow-up if phase/call evidence leaves scope dominant. No schema index, connection pool, debouncing, Unicode change or unrelated full-suite run is justified before these results. Any diagnostic-off production candidate still needs unchanged public tests/bands before claiming performance closure.
