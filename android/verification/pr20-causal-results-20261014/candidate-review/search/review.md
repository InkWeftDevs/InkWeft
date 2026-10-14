# ASCII matching alternatives — not selected

The ASCII first-character rejection filter has the smallest semantic and implementation surface of the three candidates. It neither changes SQL nor creates a lowercased copy of each body. This is a static preference conditional on actual native `contains` CPU attribution, not a performance recommendation or an implemented change. No production/probe source was edited.

## 1. SQL ASCII branch

For a nonempty ASCII query and **valid Unicode TEXT**, `length(value)=length(CAST(value AS BLOB))` accepts NUL-free ASCII in UTF-8. A non-ASCII UTF-8 codepoint uses more bytes; embedded NUL truncates the text length while remaining in the blob length. UTF-16 nonempty strings fail this equality, so they fall back. Empty text is harmless. `lower` only folds ASCII, and `instr` performs a literal substring search, so `%`, `_`, quotes and backslashes need no pattern escaping. These facts follow from SQLite's [core function definitions](https://www.sqlite.org/lang_corefunc.html) and [database encoding rules](https://www.sqlite.org/pragma.html#pragma_encoding).

This proof needs qualifications:

- Require `typeof(value)='text'`. An arbitrary BLOB passes bare length equality because both sides count bytes. A BLOB containing UTF-8 `İ` could otherwise bypass Kotlin's `i` match through ASCII-only SQLite lower. Keep non-TEXT and NULL on the existing UDF path.
- Raw malformed TEXT is outside the valid-Unicode proof. Host SQLite accepts `CAST(x'ff' AS TEXT)` with text length and blob byte length both 1. The equality is therefore not an unconditional ASCII validator for arbitrary corrupt SQLite TEXT. Its behavior relative to Requery's Java decoder would need specific evidence. The alternatives operating on the actual returned String avoid this encoding proof.
- Conservatively restrict the SQL fast query to NUL-free ASCII; queries containing NUL can remain on the old UDF. Blank-query handling must remain unchanged, before any matcher expression. Unicode queries must fall back even when the text is ASCII, because Unicode characters can case-match ASCII.

CASE guarantees that an unselected result branch is not evaluated. It does **not** memoize an expression repeated in its condition and result. Correlated subqueries are reevaluated when their result is needed; both properties are documented in [SQLite expressions](https://www.sqlite.org/lang_expr.html).

The existing body expression is a COALESCE of draft and saved-body scalar lookups. Repeating it in the proposed guard gives three references (two lengths, then lower or fallback), or four with a type check. The local counted-source experiment actually observed **1 → 3 → 4** author-body evaluations, respectively, on host SQLite 3.53.1; EXPLAIN plans are retained. This is not a guarantee of the Android query plan, but it is a concrete reason not to assume the branch is free. Moving bodies into a broader/materialized CTE or joining them earlier would change query planning and when text is fetched. That would need separate evidence and is not the proposed smallest change.

Bind the pre-lowered ASCII query; never interpolate it. Reusing `?1` is possible only if all other parameters in that statement become consistently numbered (e.g. page limit/offset become `?2`/`?3`). Leaving anonymous placeholders mixed with a newly repeated parameter is easy to bind incorrectly. FTS source statements have their own term parameters and must remain independently correct. Applying the branch to source text also bypasses its UDF/scan counter, changing diagnostic and probe observability; that is an additional scope concern.

## 2. Actual-String ASCII scan, lower, then ordinary contains

Preparing the lowercase ASCII query once per read and using `text.lowercase(Locale.ROOT).contains(queryLower, false)` **only after both actual Strings are proven ASCII** is equivalent to the old operation. Non-ASCII queries/text keep the exact old function. This version can safely include NUL in the ASCII predicate because Java/Kotlin String operations do not use SQLite's text-length truncation. Null handling remains `getString(...).orEmpty()` before the helper, so SQL NULL behavior is unchanged.

It leaves SQL, binding, cursor scale and UDF invocation counts unchanged. Its cost is an extra ASCII scan for each eligible-query body, followed by lowercasing and substring search. A non-ASCII character late in the body adds a long pre-scan before running the old algorithm anyway. Uppercase bodies may require a new body-sized lowercase allocation; avoiding that allocation on already lowercase text is an implementation detail, not an API guarantee to assume in a performance claim. A CJK prefix causes an early fallback, so this option does not help the long ASCII suffix of such text.

## 3. ASCII first-character rejection, original region verification

Prepare the folded first UTF-16 query character once per read if it is ASCII. For each viable candidate start, skip only when the actual source character is also ASCII and their ASCII folds differ. Otherwise call the original full `regionMatches(ignoreCase=true)` verification. Fold only `A..Z`; do not use a broad punctuation-changing bit trick as the definition of case folding.

The rejection is exact: two ASCII first characters with different folds cannot match under the old comparison. Every uncertain/non-ASCII start still reaches the original verifier. There is no hardcoded Unicode-equivalence table; dotted/dotless I, long s, Kelvin sign, surrogate pairs and unpaired surrogates remain governed by the original routine. The rest of the query can be Unicode even when its first character is ASCII.

Boundary rules are small but necessary: empty query returns true before reading its first character; a longer query returns false; viable starts are `0..text.length-query.length` in UTF-16 units. A first non-ASCII query character takes the original contains path. Preserve the original verifier's receiver direction: cached Kotlin 2.3.10 bytecode calls `query.regionMatches(0, text, candidate, query.length, true)`. No whole-body pre-scan or lowercase allocation is required, and SQL/UDF counts stay the same. An allocation-free `while` loop is sufficient; no new cache or index is involved.

The filter can still add branch/loop overhead when most source starts survive or are non-ASCII. It is not automatically faster. On the synthetic mixed CJK-prefix/20,000-ASCII-character example, only the four non-ASCII starts reached region verification; this is an operation-count illustration, not a time measurement.

## Cancellation and validation boundaries

Both String alternatives can retain the current UDF `ensureActive()` before extracting/matching text, the same synchronous execution thread, and all existing SQL-boundary checks. The current matcher itself has no internal cancellation checks. Whole-body ASCII/lower work adds work after that entry check; the prefix filter keeps a scan and full verifications. Neither should be described as providing new cancellation latency bounds.

SQL fast paths remove per-row UDF checks for matching rows. The existing CancellationSignal remains attached to `query`, but interruption occurs when SQLite can observe it; scalar-function bodies have no promised per-character interruption deadline. SQLite documents that interruption occurs at an opportunity and an almost-finished operation may complete. Existing scope checks and stale-publication prevention still matter. See [sqlite3_interrupt](https://www.sqlite.org/c3ref/interrupt.html).

Host-only checks used the cached Kotlin 2.3.10 library on OpenJDK 17.0.16: **626,137** comparisons, zero differences for the whole-ASCII-lower and first-character-filter helpers. They include all ASCII one-character pairs; all BMP candidate characters against nine queries; random multicharacter cases with NUL and surrogate units; and larger boundary strings. SQLite guard routing was checked in **36** synthetic cases across UTF-8, UTF-16le and UTF-16be. No timings were collected. These do not establish Android behavior or performance.

Before choosing any change, use the pending native diagnostic to distinguish `Args.getString`, actual matching CPU and residual SQL work. If matching is dominant, the prefix filter is the lowest-scope experiment. It still needs focused native Unicode/NUL/empty/longer-query equivalence and existing cancellation/snapshot/draft/paging checks, followed by the unchanged public search/save gates. Mixed text and frequent first-character matches must remain in coverage. None of these alternatives justifies a cache, schema migration, changed debounce, weakened freshness or removed samples.
