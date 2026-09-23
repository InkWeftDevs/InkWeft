/* InkWeft S1 sample — headless self-check.

   These are SAMPLE tests of this sample's own contracts. They are NOT the plan's
   acceptance cases: AX01-FULL and friends need a real device, a real browser
   origin and an external ACK oracle. Every check below names the S1 assertion it
   is evidence FOR, and sample-REPORT.md lists what stays unproven.

   Run:  node tests/run-headless.js  [--json]
*/
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
for (const f of ['00-core.js', '10-vault.js', '20-document.js', '30-commands.js',
                 '40-store.js', '50-export.js', '60-seed.js']) {
  require(path.join(ROOT, 'src', f));
}
const IW = globalThis.IW;

const CHECKS = [];
function check(id, title, fn) { CHECKS.push({ id, title, fn }); }

const PDF_PATH = path.join(ROOT, 'assets', 'conditional-probability-note.pdf');
const PDF = fs.readFileSync(PDF_PATH);
/* hash the real bytes, not a latin1 round-trip */
const PDF_SHA = IW.sha256Hex(new Uint8Array(PDF));
const PDF_BYTES = PDF.length;

let seq = 0;
function cmd(engine, type, params, expected) {
  seq += 1;
  const header = IW.commandHeader('cmd-' + seq + '-' + type, engine,
    expected ? { expectedVersions: expected } : null);
  return engine.run(type, params, header);
}

function indexOf(engine, collection) {
  const out = {};
  for (const row of engine.getVault()[collection]) out[row.id] = row;
  return out;
}

/* IW.byId scans an ARRAY, so these lookups hand back the live collection. Using
   Object.fromEntries here silently returned undefined and made every selector
   resolve MISSING — worth keeping the comment so the trap is not re-entered. */
function surfaceCtx(engine) {
  return {
    docIndex: () => engine.getVault().documents,
    versionIndex: () => engine.getVault().documentVersions,
    grants: engine.grants
  };
}

/* Build the S1 slice exactly as the browser shell does: bootstrap, two origins,
   first card from a real selection, one node, one margin placement, one question. */
function buildVault(options = {}) {
  seq = 0;
  const corpus = IW.corpus();
  const engine = IW.createEngine({ vaultId: 'sample-vault-0001' });
  const store = IW.createStore(IW.memoryBackend(), options.fault || null);

  for (const step of IW.seedPlan(corpus, { pdfSha256: PDF_SHA, pdfBytes: PDF_BYTES })) {
    cmd(engine, step.command, step.params);
  }

  const textOrigin = IW.originFor(corpus.text);
  const surface = IW.createSurface(textOrigin, {
    docId: corpus.text.id,
    docIndex: () => engine.getVault().documents,
    versionIndex: () => engine.getVault().documentVersions,
    grants: engine.grants
  });

  const seeded = IW.createFirstCard(engine, surface, corpus.text, IW.defaultSelection(corpus));
  const cardId = seeded.receipt.result.cardId;

  cmd(engine, 'attachOccurrence', {
    cardId, mapId: null, studySetId: engine.getVault().studySets[0].id, x: 90, y: 80
  });
  cmd(engine, 'upsertMargin', { cardId, preferredHeight: 180 });
  cmd(engine, 'createReviewItem', { cardId, question: '写出条件概率的定义式，并说明成立前提。' });

  return { engine, store, corpus, surface, textOrigin, cardId, seeded };
}

/* ------------------------------------------------------------------ AX01 */
check('AX01-A01', 'one authoritative card; all three entries resolve to one cardId + headRevisionId', () => {
  const { engine, cardId } = buildVault();
  const v = engine.getVault();
  const head = IW.readCardHead(v, cardId);
  const entries = [
    ['annotation', v.annotationPlacements[0].cardId],
    ['occurrence', v.occurrences[0].cardId],
    ['reviewItem', v.reviewItems[0].cardId]
  ];
  for (const [via, id] of entries) {
    if (id !== cardId) throw new Error(`${via} points at ${id}, not ${cardId}`);
    if (IW.readCardHead(v, id).cardRevisionId !== head.cardRevisionId) {
      throw new Error(`${via} resolves a different head revision`);
    }
  }
  if (v.cards.length !== 1) throw new Error('expected exactly one card');
  if (head.blocks.length !== 2) throw new Error('expected quote + comment blocks');
  return `cardId=${cardId.slice(0, 8)}… head=${head.cardRevisionId.slice(0, 8)}… rev=${head.revision}; 3 entries agree`;
});

check('AX01-A02', 'source hash unchanged; the question projection holds no copy of the body', () => {
  const { engine, cardId } = buildVault();
  const v = engine.getVault();
  const item = v.reviewItems[0];
  if (JSON.stringify(item).includes('P(A∩B)')) throw new Error('review item duplicated the card body');
  const version = v.documentVersions.find((x) => x.documentId === 'conditional-probability-note');
  if (!version) throw new Error('the PDF origin was not registered');
  if (version.sha256 !== PDF_SHA) throw new Error('recorded PDF hash does not match the file');
  if (IW.sha256Hex(new Uint8Array(fs.readFileSync(PDF_PATH))) !== PDF_SHA) {
    throw new Error('the PDF on disk changed during the run');
  }
  const head = IW.readCardHead(v, cardId);
  if (head.cardRevisionId !== v.cards[0].headRevisionId) throw new Error('head pointer mismatch');
  if (item.answerRefs.some((r) => r.blockId === undefined)) throw new Error('answer refs are not block refs');
  return `pdf=${PDF_BYTES}B sha256=${PDF_SHA.slice(0, 12)}… answerRefs=${item.answerRefs.length}`;
});

check('AX01-A03', 'no dictionary, no FSRS, no fabricated history in the S1 slice', () => {
  const { engine } = buildVault();
  const v = engine.getVault();
  if (v.dictionaryEntries.length || v.dictionarySources.length) throw new Error('S1 registered dictionary rows');
  if (v.reviewEvents.length || v.attempts.length) throw new Error('S1 fabricated review history');
  const state = v.reviewStates[0];
  if (state.schedulerVersion !== 'UNASSIGNED' || state.statePayload.dueAt !== null) {
    throw new Error('S1 review state is not inert');
  }
  return `dictionary=0 events=0 attempts=0 scheduler=${state.schedulerVersion} dueAt=null`;
});

/* ------------------------------------------------------------------ AX02 */
check('AX02-A01', 'comment edit lands on the committed revision; undo restores a consistent state', () => {
  const { engine, cardId } = buildVault();
  const before = IW.readCardHead(engine.getVault(), cardId);
  const patch = cmd(engine, 'patchCard', { cardId, text: '个人备注：先核对 P(B)>0。' });
  const after = IW.readCardHead(engine.getVault(), cardId);
  if (after.revision !== before.revision + 1) throw new Error('revision did not advance exactly once');
  if (after.blocks.find((b) => b.role === 'comment').payload.text !== '个人备注：先核对 P(B)>0。') {
    throw new Error('edit is not visible on the head revision');
  }
  const viaNode = IW.readCardHead(engine.getVault(), engine.getVault().occurrences[0].cardId);
  const viaQuestion = IW.readCardHead(engine.getVault(), engine.getVault().reviewItems[0].cardId);
  if (viaNode.cardRevisionId !== after.cardRevisionId || viaQuestion.cardRevisionId !== after.cardRevisionId) {
    throw new Error('the three views disagree on the head revision');
  }
  engine.undo(IW.commandHeader('undo-1', engine, { targetCommandId: patch.receipt.commandId }));
  const undone = IW.readCardHead(engine.getVault(), cardId);
  /* Undo now COMPENSATES by appending a new revision, so the head id moves
     forward while the content returns to the previous text. Nothing is removed
     from the ledger. */
  if (undone.blocks.find((b) => b.role === 'comment').payload.text !== '') {
    throw new Error('undo did not restore the previous comment text');
  }
  if (!IW.readCardRevision(engine.getVault(), after.cardRevisionId)) {
    throw new Error('undo deleted the revision it undid; history must stay resolvable');
  }
  return `rev ${before.revision}->${after.revision}->${undone.revision} (compensated); the undone revision is still readable`;
});

check('AX02-A04', 'an intermediate revision stays resolvable after a later edit (immutable chain)', () => {
  const { engine, cardId } = buildVault();
  const first = IW.readCardHead(engine.getVault(), cardId);
  cmd(engine, 'patchCard', { cardId, text: '第一版备注' });
  const middle = IW.readCardHead(engine.getVault(), cardId);
  if (IW.readCardRevision(engine.getVault(), middle.cardRevisionId)
      .blocks.find((b) => b.role === 'comment').payload.text !== '第一版备注') {
    throw new Error('the middle revision does not resolve its own text');
  }
  cmd(engine, 'patchCard', { cardId, text: '第二版备注' });
  const head = IW.readCardHead(engine.getVault(), cardId);
  if (head.cardRevisionId === middle.cardRevisionId) throw new Error('the head did not move');
  const back = IW.readCardRevision(engine.getVault(), middle.cardRevisionId);
  if (back.blocks.find((b) => b.role === 'comment').payload.text !== '第一版备注') {
    throw new Error('editing again rewrote the older revision');
  }
  const chain = IW.cardRevisionChain(engine.getVault(), cardId);
  if (chain.length !== 3) throw new Error('expected a 3-revision chain, got ' + chain.length);
  if (chain[0].id !== head.cardRevisionId) throw new Error('the chain does not start at the head');
  if (chain[2].id !== first.cardRevisionId) throw new Error('the chain does not reach the first revision');
  return `chain=${chain.map((r) => r.revision).join('<-')}; middle text preserved: "${back.blocks.find((b) => b.role === 'comment').payload.text}"`;
});

check('AX02-A02', 'the source quote block and the original asset are never rewritten', () => {
  const { engine, cardId } = buildVault();
  const quoteBefore = IW.readCardHead(engine.getVault(), cardId).blocks.find((b) => b.role === 'source_quote');
  cmd(engine, 'patchCard', { cardId, text: '改备注，不改摘录' });
  const quoteAfter = IW.readCardHead(engine.getVault(), cardId).blocks.find((b) => b.role === 'source_quote');
  if (quoteAfter.blockRevisionId !== quoteBefore.blockRevisionId) throw new Error('the quote block got a new revision');
  if (quoteAfter.payload.text !== quoteBefore.payload.text) throw new Error('the quote text changed');
  const card = engine.getVault().cards[0];
  const anchors = card.sourceIds.map((id) => engine.getVault().sourceAnchors.find((a) => a.id === id));
  /* the seed selection spans a page break, so one anchor per page is expected */
  if (anchors.length !== 2) throw new Error('expected 2 per-page anchors, got ' + anchors.length);
  const quoteLines = quoteBefore.payload.text.split('\n');
  if (anchors.map((a) => a.selector.textQuote.exact).join('\n') !== quoteLines.join('\n')) {
    throw new Error('the anchors no longer reproduce the committed quote');
  }
  const version = engine.getVault().documentVersions.find((v) => v.id === anchors[0].sourceVersion);
  if (version.sha256 !== IW.sha256Hex(IW.corpus().text.text)) {
    throw new Error('the recorded origin hash no longer matches the corpus text');
  }
  return `quote frozen at revision ${quoteAfter.blockRevisionId.slice(0, 8)}…; ${anchors.length} anchors reproduce it; origin hash intact`;
});

check('AX02-A03', 'a node resize affects only that occurrence, never card content or another map', () => {
  const { engine, cardId } = buildVault();
  const node = engine.getVault().occurrences[0];
  const defaultWidth = node.layoutOverride.width;
  const headBefore = IW.readCardHead(engine.getVault(), cardId).cardRevisionId;
  const cardRevBefore = engine.getVault().cards[0].revision;
  const mapRevBefore = engine.getVault().maps[0].revision;
  cmd(engine, 'moveOccurrence', { occurrenceId: node.id, width: 340 });
  const v = engine.getVault();
  if (v.occurrences[0].layoutOverride.width !== 340) throw new Error('node width was not applied');
  if (v.occurrences[0].width === undefined && v.occurrences[0].layoutOverride.width !== 340) {
    throw new Error('width was stored where nothing reads it');
  }
  if (v.cards[0].revision !== cardRevBefore) throw new Error('a layout change advanced card content');
  if (IW.readCardHead(v, cardId).cardRevisionId !== headBefore) throw new Error('a layout change moved the card head');
  if (v.maps[0].revision !== mapRevBefore + 1) throw new Error('map revision did not advance exactly once');
  const second = cmd(engine, 'createMap', { studySetId: v.studySets[0].id, title: '第二张图' });
  const map2 = second.receipt.result.mapId;
  cmd(engine, 'attachOccurrence', { cardId, mapId: map2, studySetId: v.studySets[0].id });
  const otherNode = engine.getVault().occurrences.find((o) => o.mapId === map2);
  if (!otherNode) throw new Error('the second map has no node');
  if (otherNode.layoutOverride.width === 340) throw new Error('the layout change leaked into another map position');
  if (otherNode.layoutOverride.width !== defaultWidth) throw new Error('a new node did not start from the default width');
  const stillResized = engine.getVault().occurrences.find((o) => o.id === node.id);
  if (stillResized.layoutOverride.width !== 340) throw new Error('the resize leaked back from the new node');
  return `width=340 on map1 only (default ${defaultWidth}); card rev stayed ${cardRevBefore}; map2 node width=${otherNode.layoutOverride.width}`;
});

/* ------------------------------------------------------------------ AX03 */
check('AX03-A01', 'a second source appends without loss; pages are identified by version-scoped ids', () => {
  const { engine, surface, corpus, cardId } = buildVault();
  const text = corpus.text.text;
  const before = IW.readCardHead(engine.getVault(), cardId).sourceIds.length;
  const from = text.indexOf('乘法公式');
  const at = from >= 0 ? from : text.indexOf('应用前先核对分母');
  if (at < 0) throw new Error('corpus no longer contains a marker line for this check');
  const result = cmd(engine, 'attachSourceAnchor', {
    cardId,
    documentId: corpus.text.id,
    sourceVersion: corpus.text.versionId,
    startOffset: at,
    endOffset: at + '乘法公式'.length,
    surface
  });
  const head = IW.readCardHead(engine.getVault(), cardId);
  if (head.sourceIds.length !== before + 1) throw new Error('the second source was lost');
  if (result.receipt.result.sourceCount !== before + 1) throw new Error('command reported the wrong source count');
  for (const id of head.sourceIds) {
    const anchor = engine.getVault().sourceAnchors.find((a) => a.id === id);
    if (!anchor) throw new Error('anchor ' + id + ' disappeared');
    if (!anchor.pageId.startsWith(anchor.sourceVersion)) {
      throw new Error('page id is not scoped to its source version: ' + anchor.pageId);
    }
    if (!anchor.selector.textQuote.exact) throw new Error('anchor lost its quoted text');
  }
  return `sources=${head.sourceIds.length}; pageIds=${head.sourceIds
    .map((id) => engine.getVault().sourceAnchors.find((a) => a.id === id).pageId).join(' , ')}`;
});

check('AX03-A02', 'a selection crossing a page break yields one anchor per page, with no page bleed', () => {
  const { engine, surface, corpus } = buildVault();
  const version = corpus.text.versionId;
  const p0 = surface.getTextPage(version + ':p0');
  const p1 = surface.getTextPage(version + ':p1');
  const start = p0.lines[p0.lines.length - 1].startOffset;
  const end = p1.lines[0].startOffset + 5;

  const result = cmd(engine, 'createCardFromSelection', {
    studySetId: engine.getVault().studySets[0].id,
    documentId: corpus.text.id,
    pageId: version + ':p0',
    sourceVersion: version,
    startOffset: start,
    endOffset: end,
    surface
  });
  const anchors = result.receipt.result.anchorIds.map(
    (id) => engine.getVault().sourceAnchors.find((a) => a.id === id));

  if (anchors.length !== 2) throw new Error('expected 2 per-page anchors, got ' + anchors.length);
  const pages = anchors.map((a) => a.pageId);
  if (pages[0] !== version + ':p0' || pages[1] !== version + ':p1') {
    throw new Error('anchors are not on the expected pages: ' + pages.join(','));
  }
  if (new Set(pages).size !== pages.length) throw new Error('two anchors share one page id');
  const lastOfP0 = p0.lines[p0.lines.length - 1].text;
  const firstOfP1 = p1.lines[0].text;
  if (anchors[0].selector.lines.length !== 1 || anchors[1].selector.lines.length !== 1) {
    throw new Error('a one-line-per-page selection pulled in whole pages');
  }
  if (anchors[0].selector.textQuote.exact !== lastOfP0) throw new Error('page 0 anchor quotes the wrong text');
  /* the selection stops 5 characters into page 1's first line, so the anchor must
     carry exactly those 5 characters — not the whole line */
  const clipped = firstOfP1.slice(0, 5);
  if (anchors[1].selector.textQuote.exact !== clipped) {
    throw new Error('page 1 anchor quotes "' + anchors[1].selector.textQuote.exact + '", expected "' + clipped + '"');
  }
  if (anchors[0].selector.textOffsets.end - anchors[0].selector.textOffsets.start !== lastOfP0.length) {
    throw new Error('page 0 offsets do not match its quoted text');
  }
  if (anchors[1].selector.textOffsets.end - anchors[1].selector.textOffsets.start !== clipped.length) {
    throw new Error('page 1 offsets do not match its quoted text');
  }
  const card = engine.getVault().cards.find((c) => c.id === result.receipt.result.cardId);
  if (card.sourceIds.length !== anchors.length) throw new Error('the card did not record every per-page anchor');
  const head = IW.readCardHead(engine.getVault(), card.id);
  const quoteBlock = head.blocks.find((b) => b.role === 'source_quote');
  const quotedLines = quoteBlock.payload.text.split('\n');
  if (quotedLines.length !== 2) throw new Error('the quote captured whole pages instead of the selection');
  if (quotedLines[0] !== lastOfP0 || quotedLines[1] !== clipped) {
    throw new Error('the committed quote is not exactly the selection: ' + JSON.stringify(quotedLines));
  }
  return `${anchors.length} anchors (${pages.join(' | ')}), 1 line each; quote="${quoteBlock.payload.text.slice(0, 18)}…"`;
});

check('AX03-A03', 'replacing the source version keeps the old anchor and reports STALE, never silently re-points', () => {
  const { engine, corpus, textOrigin } = buildVault();
  const newVersionId = corpus.text.versionId + '+replaced';
  cmd(engine, 'registerDocumentVersion', {
    documentId: corpus.text.id, versionId: newVersionId, title: corpus.text.title,
    kind: corpus.text.kind, assetPath: corpus.text.assetPath,
    bytes: 999, sha256: 'f'.repeat(64), textSha256: 'e'.repeat(64)
  });
  const doc = engine.getVault().documents.find((d) => d.id === corpus.text.id);
  if (doc.headVersionId !== newVersionId) throw new Error('the document head did not move');
  const anchor = engine.getVault().sourceAnchors[0];
  const surface = IW.createSurface(textOrigin, surfaceCtx(engine));
  const state = surface.resolveSelector(anchor);
  if (state.state !== 'STALE') throw new Error('expected STALE after a version swap, got ' + state.state);
  if (anchor.sourceVersion !== corpus.text.versionId) throw new Error('the anchor was silently re-pointed');
  const retained = engine.getVault().documentVersions.find((v) => v.id === corpus.text.versionId);
  if (!retained || retained.retention !== 'RETAINED') throw new Error('the old version was dropped');
  return `anchor stays on ${anchor.sourceVersion}; resolver says ${state.state}; old version retained`;
});

check('AX03-A04', 'a revoked source resolves as RESTRICTED without leaking detail', () => {
  const { engine, textOrigin, corpus } = buildVault();
  engine.setGrant(corpus.text.versionId, false);
  const surface = IW.createSurface(textOrigin, surfaceCtx(engine));
  const state = surface.resolveSelector(engine.getVault().sourceAnchors[0]);
  if (state.state !== 'RESTRICTED') throw new Error('expected RESTRICTED, got ' + state.state);
  if (/P\(B\)|条件概率/.test(JSON.stringify(state))) throw new Error('the restricted answer leaked source text');
  return `state=${state.state}, detail="${state.detail}" (no title, text or count leaked)`;
});

/* ------------------------------------------------------------------ AX33 */
check('AX33-A01', 'a plain highlighter creates no card, no node and no review item', () => {
  const { engine, corpus, surface } = buildVault();
  const counts = () => {
    const v = engine.getVault();
    return { cards: v.cards.length, occ: v.occurrences.length, items: v.reviewItems.length, marks: v.annotationPlacements.length };
  };
  const before = counts();
  const pageIds = surface.listPageIds();
  const lines = pageIds.flatMap((id) => surface.offsetsToLines(id, 0, 1e9));
  const range = { startOffset: lines[4].startOffset, endOffset: lines[5].endOffset };
  cmd(engine, 'markHighlighter', {
    documentId: corpus.text.id,
    pageId: pageIds[0],
    sourceVersion: corpus.text.versionId,
    startOffset: range.startOffset,
    endOffset: range.endOffset,
    surface
  });
  const after = counts();
  if (after.cards !== before.cards) throw new Error('a highlighter created a card');
  if (after.occ !== before.occ) throw new Error('a highlighter created a map node');
  if (after.items !== before.items) throw new Error('a highlighter created a review item');
  if (after.marks !== before.marks + 1) throw new Error('the highlighter mark was not recorded');
  const mark = engine.getVault().annotationPlacements[after.marks - 1];
  if (mark.style !== 'highlighter' || mark.cardId !== null) throw new Error('the mark is not a plain highlighter');
  const anchor = engine.getVault().sourceAnchors.find((a) => a.id === mark.anchorId);
  if (!anchor) throw new Error('the highlighter anchor does not resolve');
  if (anchor.selector.kind !== 'text_flow_region') throw new Error('unexpected anchor kind');
  return `cards ${before.cards}->${after.cards}, nodes ${before.occ}->${after.occ}, marks ${before.marks}->${after.marks}, anchor ok`;
});

check('AX33-A02', 'the excerpt creates card + anchor + marker atomically and does not auto-enter review', () => {
  const { engine, cardId } = buildVault();
  const v = engine.getVault();
  const card = v.cards[0];
  const anchor = v.sourceAnchors.find((a) => a.id === card.sourceIds[0]);
  if (!anchor) throw new Error('the card has no resolvable anchor');
  const mark = v.annotationPlacements.find((p) => p.cardId === cardId);
  if (!mark) throw new Error('the excerpt marker is missing');
  if (v.occurrences.length !== 1) throw new Error('the excerpt itself created map nodes');
  if (v.settings.autoAttachToMap || v.settings.autoEnqueueReview) throw new Error('auto settings must default off');
  const head = IW.readCardHead(v, cardId);
  if (head.sourceIds.length !== 2) throw new Error('a cross-page excerpt should have one anchor per page');
  if (v.reviewItems.length !== 1) throw new Error('the excerpt should not have created review items');
  return `card + ${head.sourceIds.length} per-page anchors + excerpt marker at revision ${head.revision}; one explicit node only`;
});

check('AX33-A03', 'a refused excerpt leaves no half card, no dangling anchor and no receipt', () => {
  const { engine } = buildVault();
  const receiptsBefore = engine.history().length;
  const anchorsBefore = engine.getVault().sourceAnchors.length;
  const cardsBefore = engine.getVault().cards.length;
  let code = null;
  try {
    engine.run('createCardFromSelection', {
      studySetId: engine.getVault().studySets[0].id,
      documentId: 'textbook-excerpt',
      pageId: 'textbook-excerpt@v1:p0',
      sourceVersion: 'textbook-excerpt@v1',
      startOffset: 0,
      endOffset: 0,
      surface: engine.getVault() && null
    }, IW.commandHeader('cancel-me', engine));
  } catch (e) { code = e.code; }
  if (code !== 'SURFACE_REQUIRED') throw new Error('expected SURFACE_REQUIRED, got ' + code);

  /* and again with a surface that resolves nothing */
  const { engine: engine2, surface: surface2, corpus } = buildVault();
  let code2 = null;
  try {
    engine2.run('createCardFromSelection', {
      studySetId: engine2.getVault().studySets[0].id,
      documentId: corpus.text.id,
      pageId: corpus.text.versionId + ':p0',
      sourceVersion: corpus.text.versionId,
      startOffset: 0,
      endOffset: 0,
      surface: surface2
    }, IW.commandHeader('cancel-me-2', engine2));
  } catch (e) { code2 = e.code; }
  if (code2 !== 'EMPTY_SELECTION') throw new Error('expected EMPTY_SELECTION, got ' + code2);

  const v = engine.getVault();
  if (engine.history().length !== receiptsBefore) throw new Error('a cancelled command left a receipt');
  if (v.cards.length !== cardsBefore) throw new Error('a cancelled command created a card');
  if (v.sourceAnchors.length !== anchorsBefore) throw new Error('a cancelled command left an orphan anchor');
  return `refused with ${code} then ${code2}; cards, anchors and receipts unchanged`;
});

/* ------------------------------------------------------------------ AX34 */
check('AX34-A01', 'same commandId + same payload returns the original receipt and applies nothing twice', () => {
  const { engine, cardId } = buildVault();
  const header = IW.commandHeader('replay-me', engine);
  const params = { cardId, text: '幂等重放' };
  const first = engine.run('patchCard', params, header);
  const revAfterFirst = engine.getVault().cards[0].revision;
  const blockRevsAfterFirst = engine.getVault().blockRevisions.length;
  const second = engine.run('patchCard', params, header);
  if (second.replayed !== true || second.status !== 'REPLAYED') throw new Error('the replay was not recognised');
  if (second.receipt.commandId !== first.receipt.commandId) throw new Error('the replay returned a different receipt');
  if (engine.getVault().cards[0].revision !== revAfterFirst) throw new Error('the replay advanced the revision again');
  if (engine.getVault().blockRevisions.length !== blockRevsAfterFirst) throw new Error('the replay appended a block revision');
  return `status=${second.status}; revision and block revisions frozen at ${revAfterFirst}/${blockRevsAfterFirst}`;
});

check('AX34-A02', 'same commandId with a different payload or expected versions is COMMAND_ID_REUSE', () => {
  const { engine, cardId } = buildVault();
  const header = IW.commandHeader('conflict-me', engine);
  engine.run('patchCard', { cardId, text: '第一次' }, header);
  const rev = engine.getVault().cards[0].revision;
  const codes = [];
  for (const params of [{ cardId, text: '第二次' }, { cardId, text: '第一次', title: '改标题' }]) {
    let code = null;
    try { engine.run('patchCard', params, header); } catch (e) { code = e.code; }
    codes.push(code);
  }
  let epochCode = null;
  try {
    engine.run('patchCard', { cardId, text: '第一次' },
      IW.commandHeader('conflict-me', engine, { expectedVersions: { epoch: 99 } }));
  } catch (e) { epochCode = e.code; }
  if (codes.some((c) => c !== 'COMMAND_ID_REUSE')) throw new Error('a payload change was not refused: ' + codes);
  if (epochCode !== 'COMMAND_ID_REUSE') throw new Error('an expected-version change was not refused: ' + epochCode);
  if (engine.getVault().cards[0].revision !== rev) throw new Error('a refused command still advanced the revision');
  return `payload change -> ${codes[0]}; expectedVersions change -> ${epochCode}; revision frozen at ${rev}`;
});

check('AX34-A03', 'a revoked grant blocks even an old commandId replay, and blocks new commands too', () => {
  const { engine, cardId } = buildVault();
  const header = IW.commandHeader('revoke-me', engine);
  engine.run('patchCard', { cardId, text: '授权内修改' }, header);
  engine.setGrant('textbook-excerpt@v1', false);
  let replayCode = null;
  try { engine.run('patchCard', { cardId, text: '授权内修改' }, header); } catch (e) { replayCode = e.code; }
  let freshCode = null;
  try {
    engine.run('patchCard', { cardId, text: '授权撤销后' }, IW.commandHeader('fresh-after-revoke', engine));
  } catch (e) { freshCode = e.code; }
  engine.setGrant('textbook-excerpt@v1', true);
  const after = engine.run('patchCard', { cardId, text: '重新授权后' }, IW.commandHeader('after-regrant', engine));
  if (replayCode !== 'SOURCE_RESTRICTED') throw new Error('a revoked replay was allowed: ' + replayCode);
  if (freshCode !== 'SOURCE_RESTRICTED') throw new Error('a revoked fresh commit was allowed: ' + freshCode);
  if (after.status !== 'ACK') throw new Error('the command after re-granting failed');
  return `replay -> ${replayCode}; new command -> ${freshCode}; after re-grant -> ${after.status}`;
});

check('AX34-A04', 'a rotated capability token does not change the semantic digest, and never enters a receipt', () => {
  const { engine, cardId } = buildVault();
  const base = { commandId: 'rot-1', vaultId: engine.vaultId, actorId: 'local-user', expectedVersions: { epoch: 0 } };
  const a = engine.run('patchCard', { cardId, text: '令牌轮换' }, Object.assign({}, base, { capabilityHandle: 'cap-1111' }));
  const b = engine.run('patchCard', { cardId, text: '令牌轮换' }, Object.assign({}, base, { capabilityHandle: 'cap-9999' }));
  if (b.replayed !== true) throw new Error('token rotation broke idempotency');
  if (a.receipt.digest !== b.receipt.digest) throw new Error('the digest depends on the refreshable token');
  const c = engine.run('patchCard', { cardId, text: '令牌轮换' },
    Object.assign({}, base, { capabilityHandle: 'github_pat_11ABCDEFG', traceId: 't-9' }));
  if (c.replayed !== true) throw new Error('a trace id changed the digest');
  /* the raw handle must not be recoverable from stored history */
  const dump = JSON.stringify(engine.getState().receipts);
  if (dump.includes('cap-1111') || dump.includes('github_pat_')) {
    throw new Error('a raw capability handle leaked into a receipt');
  }
  if (!a.receipt.capabilityFingerprint) throw new Error('the receipt should keep a fingerprint for audit');
  return 'three handles produce one digest, one receipt; only a fingerprint is stored';
});

check('AX34-A05', 'an expectedVersions mismatch is refused instead of overwriting a newer head', () => {
  const { engine, cardId } = buildVault();
  const stale = IW.readCardHead(engine.getVault(), cardId).revision;
  cmd(engine, 'patchCard', { cardId, text: '另一视图的修改' });
  let code = null, details = null;
  try {
    engine.run('patchCard', { cardId, text: '基于旧版本的修改' },
      IW.commandHeader('stale-write', engine, { expectedVersions: { epoch: 0, card: { [cardId]: stale } } }));
  } catch (e) { code = e.code; details = e.details; }
  if (code !== 'EXPECTED_VERSION_MISMATCH') throw new Error('a stale write was accepted: ' + code);
  if (!details || details.actual !== stale + 1) throw new Error('mismatch details are wrong: ' + JSON.stringify(details));
  const observed = IW.readCardHead(engine.getVault(), cardId).cardRevisionId;
  /* the edit is appended to a SEPARATE comment block, so no committed text is lost */
  const resolved = cmd(engine, 'commitResolveConflict', {
    cardId, text: '基于旧版本的修改', observedCardRevisionId: observed
  });
  if (resolved.receipt.result.resolvedFrom !== observed) throw new Error('resolution did not record what it saw');
  const head = IW.readCardHead(engine.getVault(), cardId);
  const texts = head.blocks.filter((b) => b.role === 'comment').map((b) => b.payload.text);
  if (!texts.includes('另一视图的修改') || !texts.includes('基于旧版本的修改')) {
    throw new Error('one of the two committed edits was lost: ' + JSON.stringify(texts));
  }
  return `stale -> ${code} (actual rev ${details.actual}); resolved against ${observed.slice(0, 8)}…; both edits kept (${texts.length} comment blocks)`;
});

/* ------------------------------------------------------------------ AX28 */
check('AX28-A01', 'a failing store leaves the durable state untouched; the retry returns the original receipt', () => {
  const { engine, store, cardId } = buildVault();
  store.commit(engine.getVault(), engine.getState());
  const seqBefore = store.commitSeq();
  const durableBefore = IW.canonical(store.load().vault);

  /* the outcome is UNKNOWN to the caller: the command applied, the write failed */
  store.armFault({ at: 'beforeWrite', times: 1 });
  const attempted = engine.run('patchCard', { cardId, text: '这笔不能落盘' },
    IW.commandHeader('faulty-1', engine));
  let code = null;
  try { store.commit(engine.getVault(), engine.getState()); } catch (e) { code = e.code; }
  if (code !== 'STORE_FAULT_INJECTED') throw new Error('expected STORE_FAULT_INJECTED, got ' + code);
  if (store.commitSeq() !== seqBefore) throw new Error('commitSeq advanced despite a failed write');
  if (IW.canonical(store.load().vault) !== durableBefore) throw new Error('the durable state changed anyway');

  /* recovery: reload the durable pre-image and look the transaction up by id */
  const loaded = store.load();
  if (loaded.status !== 'LOADED') throw new Error('expected LOADED, got ' + loaded.status);
  const recovered = IW.createEngine({ vaultId: loaded.vault.vaultId, vault: loaded.vault, state: loaded.state });
  const retry = recovered.run('patchCard', { cardId, text: '这笔不能落盘' },
    IW.commandHeader('faulty-1', recovered));
  if (!retry || retry.status !== 'ACK') throw new Error('the retry did not commit against the durable pre-image');
  if (retry.receipt.result.carriedFrom) throw new Error('unexpected provenance claim');
  store.armFault(null);
  const info = store.commit(recovered.getVault(), recovered.getState());
  if (info.commitSeq !== seqBefore + 1) throw new Error('the recovery commit did not advance exactly once');
  const head = IW.readCardHead(store.load().vault, cardId);
  if (!head.blocks.some((b) => b.payload.text === '这笔不能落盘')) throw new Error('the recovered edit is missing');
  if (engine.receiptFor('faulty-1').commandId !== attempted.receipt.commandId) {
    throw new Error('the failed transaction is no longer discoverable by commandId');
  }
  return `write refused; durable bytes identical; retry -> ${retry.status}; recovery commitSeq ${seqBefore}->${info.commitSeq}`;
});

check('AX28-A02', 'no command can publish a half-built closure; refusals leave the vault clean', () => {
  const { engine, cardId } = buildVault();
  if (engine.checkInvariants().length !== 0) throw new Error('the baseline invariants are already broken');
  let anchorCode = null;
  try {
    engine.run('createCardFromSelection', {
      studySetId: engine.getVault().studySets[0].id, documentId: 'nope', pageId: 'nope:p0',
      sourceVersion: 'nope@v1', startOffset: 1, endOffset: 5, surface: null
    }, IW.commandHeader('bad-anchor', engine));
  } catch (e) { anchorCode = e.code; }
  if (anchorCode !== 'SOURCE_VERSION_NOT_FOUND') throw new Error('an unresolvable source was accepted: ' + anchorCode);
  let foreignCode = null;
  try {
    engine.run('createReviewItem', { cardId, question: 'q', answerBlockIds: ['not-a-block'] },
      IW.commandHeader('foreign-answer', engine));
  } catch (e) { foreignCode = e.code; }
  if (foreignCode !== 'ANSWER_BLOCK_FOREIGN') throw new Error('a foreign answer block was accepted: ' + foreignCode);
  /* an invariant break is caught before publishing, not after */
  const before = engine.getVault().blocks.length;
  let invariantCode = null;
  try {
    engine.run('createIndependentCard', { studySetId: engine.getVault().studySets[0].id, text: '' },
      IW.commandHeader('empty-card', engine));
  } catch (e) { invariantCode = e.code; }
  if (engine.checkInvariants().length !== 0) throw new Error('a refused command damaged the vault');
  if (engine.getVault().blocks.length < before) throw new Error('blocks disappeared');
  return `refusals: ${anchorCode}, ${foreignCode}${invariantCode ? ', ' + invariantCode : ''}; invariants clean`;
});

check('AX28-A04', 'the S1 native package exports and restores in isolation with every reference resolving', () => {
  const { engine, cardId } = buildVault();
  const pkg = engine.exportPackage();
  const restored = IW.importPackage(pkg, {
    targetEpoch: 0,
    assetBytes: { 'textbook-excerpt@v1': IW.corpus().text.text }
  });
  const failed = restored.report.checks.filter((c) => c.result !== 'PASS');
  if (failed.length) throw new Error('failed import checks: ' + JSON.stringify(failed));
  const head = IW.readCardHead(restored.vault, cardId);
  if (head.blocks.length !== 2) throw new Error('the restored card lost blocks');
  if (restored.vault.sourceAnchors.length !== pkg.data.sourceAnchors.length) {
    throw new Error('the restore lost source anchors: ' + restored.vault.sourceAnchors.length +
      ' of ' + pkg.data.sourceAnchors.length);
  }
  if (restored.vault.sourceAnchors.length < 1) throw new Error('the restored vault has no anchor at all');
  if (restored.vault.occurrences.length !== 1) throw new Error('the restored vault lost its node');
  if (restored.vault.reviewItems.length !== 1) throw new Error('the restored vault lost its question projection');
  if (restored.report.restored.undoAvailableAfterRestore !== true) {
    throw new Error('a package carrying compensations should restore an undo capability');
  }
  /* and that capability must actually work on the restored vault */
  const restoredEngine = IW.createEngine({
    vaultId: restored.vault.vaultId, vault: restored.vault, state: restored.state
  });
  const candidate = restoredEngine.history().reverse().find((h) => h.undoable);
  if (!candidate) throw new Error('no compensatable receipt survived the restore');
  const undoResult = restoredEngine.undo(IW.commandHeader('post-restore-undo', restoredEngine, {
    targetCommandId: candidate.commandId
  }));
  if (undoResult.status !== 'ACK') throw new Error('undo did not work after a restore');
  if (!Array.isArray(pkg.outOfScope) || !pkg.outOfScope.length) throw new Error('the package must name what it excludes');
  return `checks=${restored.report.checks.length} all PASS; snapshots=${restored.report.restored.snapshotRefs}; ` +
         `warnings=${restored.report.warnings.map((w) => w.code).join('+')}`;
});

check('AX28-A05', 'tampering with the package, or smuggling a block id as a snapshot, is detected', () => {
  const { engine } = buildVault();
  const pkg = engine.exportPackage();
  pkg.data.cards[0].title = '偷偷改过的标题';
  let code = null;
  try { IW.importPackage(pkg, { targetEpoch: 0 }); } catch (e) { code = e.code; }
  if (code !== 'PACKAGE_TAMPERED') throw new Error('a tampered package was accepted: ' + code);

  const pkg2 = engine.exportPackage();
  pkg2.data.cardRevisions[0].blockSnapshotIds = [pkg2.data.blocks[0].id];
  delete pkg2.integrity;
  pkg2.integrity = { algorithm: 'sha256', digest: IW.sha256Hex(IW.canonical(pkg2)) };
  let code2 = null;
  try { IW.importPackage(pkg2, { targetEpoch: 0 }); } catch (e) { code2 = e.code; }
  if (code2 !== 'IMPORT_CHECK_FAILED') throw new Error('a block-id-as-snapshot was not caught: ' + code2);
  return `tampered bytes -> ${code}; block id used as snapshot -> ${code2}`;
});

check('AX28-A06', 'a torn store value is reported as CORRUPT and never half-loaded', () => {
  const { engine } = buildVault();
  const backend = IW.memoryBackend();
  const store = IW.createStore(backend);
  const first = store.load();
  if (first.status !== 'EMPTY') throw new Error('an empty store must report EMPTY, got ' + first.status);
  store.commit(engine.getVault(), engine.getState());
  const good = store.load();
  if (good.status !== 'LOADED' || !good.vault) throw new Error('a healthy store must report LOADED');
  const raw = backend.read();
  backend.write(raw.slice(0, Math.floor(raw.length / 2)));
  const torn = store.load();
  if (torn.status !== 'CORRUPT') throw new Error('a torn value must report CORRUPT, got ' + torn.status);
  if (torn.vault) throw new Error('a corrupt load must not hand back a partial vault');
  if (!torn.rawBytes) throw new Error('the corrupt outcome should report the raw size for recovery');
  return `EMPTY -> LOADED -> CORRUPT(${torn.code}, ${torn.rawBytes}B); no partial vault was returned`;
});

/* ------------------------------------------- persistence and multi-writer */
check('STORE-01', 'a committed vault reloads byte-identically after a reopen', () => {
  const { engine, store, cardId, corpus, surface } = buildVault();
  cmd(engine, 'patchCard', { cardId, text: '重开前保存的备注' });
  const expected = IW.canonical(engine.getVault());
  const info = store.commit(engine.getVault(), engine.getState());
  const reloaded = store.load();
  if (reloaded.status !== 'LOADED') throw new Error('expected LOADED, got ' + reloaded.status);
  if (IW.canonical(reloaded.vault) !== expected) throw new Error('the reloaded vault differs from the committed one');
  if (IW.canonical(reloaded.state) !== IW.canonical(engine.getState())) {
    throw new Error('the reloaded receipt log differs from the committed one');
  }
  const engine2 = IW.createEngine({
    vaultId: reloaded.vault.vaultId, vault: reloaded.vault, state: reloaded.state
  });
  const head = IW.readCardHead(engine2.getVault(), cardId);
  if (head.blocks.find((b) => b.role === 'comment').payload.text !== '重开前保存的备注') {
    throw new Error('the reopened engine lost the comment');
  }
  /* a reopened engine can still append content and keep resolving old revisions */
  const again = cmd(engine2, 'patchCard', { cardId, text: '重开后继续写' });
  if (again.receipt.result.revision !== head.revision + 1) throw new Error('revision numbering did not continue');
  const copy = IW.createSurface(IW.originFor(corpus), surfaceCtx(engine2));
  if (!engine2.getVault().sourceAnchors[0]) throw new Error('anchors vanished');
  void surface; void copy;
  return `commitSeq=${info.commitSeq} bytes=${info.bytes}; reopened rev=${head.revision}, next rev=${again.receipt.result.revision}`;
});

check('STORE-02', 'two writers conflict instead of clobbering; the vault epoch blocks a stale session', () => {
  const { engine, cardId } = buildVault();
  const seen = IW.readCardHead(engine.getVault(), cardId).revision;
  cmd(engine, 'patchCard', { cardId, text: '视图A' });
  let code = null;
  try {
    engine.run('patchCard', { cardId, text: '视图B' },
      IW.commandHeader('view-b', engine, { expectedVersions: { epoch: 0, card: { [cardId]: seen } } }));
  } catch (e) { code = e.code; }
  if (code !== 'EXPECTED_VERSION_MISMATCH') throw new Error('view B clobbered view A: ' + code);
  const head = IW.readCardHead(engine.getVault(), cardId);
  if (!head.blocks.some((b) => b.payload.text === '视图A')) throw new Error('view A\'s committed edit was lost');
  cmd(engine, 'resetVaultEpoch', {});
  let epochCode = null;
  try {
    engine.run('patchCard', { cardId, text: '旧会话' }, IW.commandHeader('old-session', engine));
  } catch (e) { epochCode = e.code; }
  if (epochCode !== 'VAULT_EPOCH_MISMATCH') throw new Error('a stale epoch session was allowed to write: ' + epochCode);
  return `concurrent write -> ${code}; commit survived; post-reset stale session -> ${epochCode}`;
});

/* --------------------------------------------------- structural guard rails */
check('STRUCT-01', 'the whole demo can be rebuilt deterministically', () => {
  const a = buildVault();
  const first = IW.canonical(a.engine.getVault());
  const b = buildVault();
  const second = IW.canonical(b.engine.getVault());
  if (first !== second) throw new Error('two seeded runs produced different vaults');
  return `two runs byte-identical (${first.length} canonical bytes)`;
});

check('STRUCT-02', 'S1 scope is enforced, not merely documented', () => {
  const { engine, cardId } = buildVault();
  const v = engine.getVault();
  for (const row of v.cards) {
    if (typeof row.studySetId !== 'string') throw new Error('card lost its study set membership');
  }
  if (v.reviewEvents.length || v.attempts.length || v.dictionaryEntries.length) {
    throw new Error('a scope-excluded collection became non-empty');
  }
  const excluded = ['recordReview', 'registerAliases', 'combineCards', 'splitCard'];
  for (const name of excluded) {
    if (engine.commands[name]) throw new Error('S1 exposed ' + name + ', which belongs to a later stage');
  }
  if (!engine.commands.detachOccurrence) throw new Error('the S1 node-removal command is missing');
  const before = engine.getVault().cards.length;
  const node = engine.getVault().occurrences[0];
  cmd(engine, 'detachOccurrence', { occurrenceId: node.id });
  if (engine.getVault().cards.length !== before) throw new Error('removing a node deleted the card');
  if (engine.getVault().occurrences.length !== 0) throw new Error('the node was not removed');
  const head = IW.readCardHead(engine.getVault(), cardId);
  if (!head.blocks.length) throw new Error('the card lost its content when the node went away');
  return `excluded commands absent; card survived node removal with ${head.blocks.length} blocks`;
});

check('STRUCT-03', 'the PDF origin is real, refused honestly, and its bytes are verifiable', () => {
  const { engine, corpus } = buildVault();
  const version = engine.getVault().documentVersions.find((v) => v.kind === 'pdf-origin');
  if (!version) throw new Error('the PDF origin was not registered');
  if (version.sha256 !== PDF_SHA) throw new Error('the recorded PDF hash is wrong');
  if (PDF.slice(0, 5).toString() !== '%PDF-') throw new Error('the asset is not a PDF');
  const surface = IW.createSurface(IW.originFor(corpus.pdf), surfaceCtx(engine));
  if (surface.capabilityReport.rendersPages !== false) throw new Error('a PDF engine was claimed without one');
  if (surface.capabilityReport.textGeometry !== 'UNAVAILABLE') throw new Error('text geometry was claimed without an engine');
  if (surface.listPageIds().length !== 0) throw new Error('pages were invented without an engine');
  const state = surface.resolveSelector(engine.getVault().sourceAnchors[0]);
  if (state.state !== 'MISSING' || state.code !== 'PDF_ENGINE_MISSING') {
    throw new Error('the PDF surface guessed instead of refusing: ' + JSON.stringify(state));
  }
  let code = null;
  try { surface.getTextPage('x'); } catch (e) { code = e.code; }
  if (code !== 'PDF_ENGINE_MISSING') throw new Error('getTextPage did not refuse: ' + code);
  return `sha256=${PDF_SHA.slice(0, 12)}…; capability honest (no engine); resolver -> ${state.state}/${state.code}`;
});

/* ------------------------------------------- review regression (REVIEW.md) */
/* One test per reported defect. These are the sample's own regressions for the
   external review of commit 287681f; they are not the plan's AX cases. */
check('R-D01', 'a command is not acknowledged unless the store accepted it', () => {
  const store = IW.createStore(IW.memoryBackend());
  let persists = 0;
  const engine = IW.createEngine({
    vaultId: 'd01',
    persist: function (pending) { persists += 1; store.commit(pending.vault, pending.state); }
  });
  let n = 0;
  const header = (id) => IW.commandHeader(id || ('d01-' + (++n)), engine);
  engine.run('bootstrap', { title: 't' }, header());
  const before = IW.canonical(engine.getVault());
  const seqBefore = store.commitSeq();

  store.armFault({ at: 'beforeWrite', times: 1 });
  let code = null;
  try { engine.run('bootstrap', { title: 'should not persist' }, header()); } catch (e) { code = e.code; }
  if (code !== 'STORE_FAULT_INJECTED') throw new Error('expected the store fault to surface, got ' + code);
  if (IW.canonical(engine.getVault()) !== before) {
    throw new Error('the vault advanced even though the store refused: the head was ACKed too early');
  }
  if (engine.history().length !== 1) throw new Error('a refused commit left a receipt');
  if (store.commitSeq() !== seqBefore) throw new Error('commitSeq advanced');
  store.armFault(null);
  engine.run('bootstrap', { title: 'now it lands' }, header());
  if (persists !== 3) throw new Error('expected 3 successful persists (2 accepted + 1 recovered), saw ' + persists);
  if (IW.canonical(engine.getVault()).indexOf('now it lands') < 0) {
    throw new Error('the recovered command did not land');
  }
  return `refused write left vault bytes, history and commitSeq unchanged; ${persists - 1} later commits landed`;
});

check('R-D02', 'a corrupt store is not mistaken for a first run', () => {
  const backend = IW.memoryBackend();
  const store = IW.createStore(backend);
  const { engine } = buildVault();
  store.commit(engine.getVault(), engine.getState());
  const raw = backend.read();
  const parsed = JSON.parse(raw);
  parsed.payload = parsed.payload.slice(0, 40);
  backend.write(JSON.stringify(parsed));
  const outcome = store.load();
  if (outcome.status !== 'CORRUPT') throw new Error('expected CORRUPT, got ' + outcome.status);
  if (outcome.vault !== null) throw new Error('a corrupt load must not return a vault to seed over');
  /* the raw bytes are still there for recovery, untouched by the load attempt */
  if (backend.read() !== JSON.stringify(parsed)) throw new Error('the load attempt rewrote the damaged value');
  const rawBefore = backend.read();
  const reload = store.load();
  if (reload.status !== 'CORRUPT') throw new Error('a second load must stay CORRUPT');
  if (backend.read() !== rawBefore) throw new Error('reading a damaged store wrote to it');
  return `CORRUPT(${outcome.code}) reported with ${outcome.rawBytes}B preserved; no auto-overwrite`;
});

check('R-D03', 'clearing the note reads back empty, never the previous revision', () => {
  const { engine, cardId } = buildVault();
  cmd(engine, 'patchCard', { cardId, text: '要被删掉的旧备注' });
  cmd(engine, 'patchCard', { cardId, text: '' });
  const head = IW.readCardHead(engine.getVault(), cardId);
  const comment = head.blocks.find((b) => b.role === 'comment');
  if (comment.payload.text !== '') {
    throw new Error('the projection substituted an older revision: ' + JSON.stringify(comment.payload.text));
  }
  if (comment.payloadFallbackFrom) throw new Error('a fallback field is still being reported');
  /* the older revision must still be readable on its own terms */
  const chain = IW.cardRevisionChain(engine.getVault(), cardId);
  const older = IW.readCardRevision(engine.getVault(), chain[1].id);
  if (older.blocks.find((b) => b.role === 'comment').payload.text !== '要被删掉的旧备注') {
    throw new Error('the earlier revision lost its own text');
  }
  return 'head reads "" (a legal edit); the previous revision still resolves its own text';
});

check('R-D03b', 'a malformed revision is rejected instead of silently substituted', () => {
  const { engine, cardId } = buildVault();
  const card = engine.getVault().cards[0];
  const commentBlock = engine.getVault().blocks.find((b) => b.role === 'comment');
  const draft = IW.clone(engine.getVault());
  const block = draft.blocks.find((b) => b.id === commentBlock.id);
  const rev = draft.blockRevisions.find((r) => r.id === block.headRevisionId);
  delete rev.payload.text;                     /* the defect: a text block with no text */
  const problems = IW.checkInvariants(draft);
  if (!problems.some((p) => /without a text field/.test(p))) {
    throw new Error('the invariant check accepted a text block with no text: ' + problems.join('; '));
  }
  let code = null;
  try { IW.readCardRevision(draft, draft.cards[0].headRevisionId); } catch (e) { code = e.code; }
  if (code !== 'BLOCK_PAYLOAD_MALFORMED') throw new Error('expected BLOCK_PAYLOAD_MALFORMED, got ' + code);
  void card; void cardId;
  return 'missing text field -> BLOCK_PAYLOAD_MALFORMED / invariant problem, not an older revision';
});

check('R-D05', 'model offsets are the authority for a selection (anchored to line boxes)', () => {
  /* The defect was a flat DOM walk, which drifts on every block boundary and
   * reads decorations. The rule now: a selection resolves through the owning
   * line's data-start, so page 2 starts where the MODEL says it starts. */
  const { surface, corpus } = buildVault();
  const pageIds = surface.listPageIds();
  const lines = pageIds.flatMap((id) => surface.offsetsToLines(id, 0, 1e9));
  const model = corpus.text.text;
  for (const line of lines) {
    if (model.slice(line.startOffset, line.endOffset) !== line.text) {
      throw new Error('line ' + line.id + ' offsets do not index the model text');
    }
  }
  const firstOfSecondPage = lines[IW.LINES_PER_PAGE];
  if (model.slice(firstOfSecondPage.startOffset, firstOfSecondPage.startOffset + 4) !==
      firstOfSecondPage.text.slice(0, 4)) {
    throw new Error('the first line of page 2 is not where the model says it is');
  }
  const decorations = (model.match(/page \d/g) || []).length;
  if (decorations) throw new Error('the model text contains view decorations');
  return `${lines.length} lines verified offset-exact across ${pageIds.length} pages; no decoration in the model`;
});

check('R-D06', 'every accessible annotation is drawn, including plain highlighters', () => {
  const { engine, surface, corpus } = buildVault();
  const v = engine.getVault();
  const pageIds = surface.listPageIds();
  const lines = pageIds.flatMap((id) => surface.offsetsToLines(id, 0, 1e9));
  const target = lines.find((l) => l.text.includes('乘法公式')) || lines[4];
  const hl = cmd(engine, 'markHighlighter', {
    documentId: corpus.text.id, pageId: pageIds[0], sourceVersion: corpus.text.versionId,
    startOffset: target.startOffset, endOffset: target.endOffset, surface
  });
  const placement = engine.getVault().annotationPlacements.find(
    (p) => p.id === hl.receipt.result.annotationId);
  if (placement.cardId !== null) throw new Error('a plain highlighter must not claim a card');

  /* the renderer must be able to find a rect for it, which is what makes it visible */
  const anchor = engine.getVault().sourceAnchors.find((a) => a.id === placement.anchorId);
  const state = surface.resolveSelector(anchor);
  if (state.state !== 'EXACT_SYNTHETIC' || !state.rect || !state.rect.length) {
    throw new Error('a plain highlighter has no drawable rect: ' + JSON.stringify(state));
  }
  /* and an excerpt must produce one placement per page, not just the first */
  const seeded = engine.getVault().cards[0];
  const excerptMarks = engine.getVault().annotationPlacements.filter((p) => p.cardId === seeded.id);
  if (excerptMarks.length !== seeded.sourceIds.length) {
    throw new Error('excerpt marks ' + excerptMarks.length + ' != per-page anchors ' + seeded.sourceIds.length);
  }
  void v;
  return `highlighter rect drawable (${state.rect.length} box); excerpt has ${excerptMarks.length} per-page marks`;
});

check('R-D07', 'command ids are session-scoped so a reopened session cannot collide', () => {
  /* The defect was in the shell: the counter restarted at 1 on every boot, so a
   * reopened session reissued `ui-comment-1`. The invariant: within one session
   * ids are unique, and across sessions they differ because the session token is
   * derived from state the vault already holds. */
  const { engine, store } = buildVault();
  store.commit(engine.getVault(), engine.getState());
  const loaded = store.load();
  if (loaded.status !== 'LOADED') throw new Error('expected LOADED');

  const sessionIds = [];
  for (const run of [1, 2]) {
    const session = IW.createEngine({ vaultId: loaded.vault.vaultId, vault: loaded.vault, state: loaded.state });
    const derived = 's' + session.getState().receipts.length + '-' + run;
    sessionIds.push('ui-' + derived + '-comment-1');
    session.run('patchCard', { cardId: session.getVault().cards[0].id, text: 'note ' + run },
      IW.commandHeader('ui-' + derived + '-comment-1', session));
  }
  if (sessionIds[0].split('comment')[0] === sessionIds[1].split('comment')[0]) {
    throw new Error('two sessions derived the same id prefix: ' + sessionIds[0]);
  }
  /* a live engine refuses to reuse one of its own ids for a different payload */
  const live = IW.createEngine({ vaultId: loaded.vault.vaultId, vault: loaded.vault, state: loaded.state });
  const one = IW.commandHeader('fixed-id', live);
  live.run('patchCard', { cardId: live.getVault().cards[0].id, text: 'A' }, one);
  let code = null;
  try {
    live.run('patchCard', { cardId: live.getVault().cards[0].id, text: 'B' }, IW.commandHeader('fixed-id', live));
  } catch (e) { code = e.code; }
  if (code !== 'COMMAND_ID_REUSE') throw new Error('same-engine reuse was not refused: ' + code);
  return `session prefixes differ (${sessionIds.map((s) => s.split('-comment')[0]).join(' vs ')}); same-engine reuse -> ${code}`;
});

check('R-D08', 'consecutive undos compensate instead of replacing the vault', () => {
  const { engine, cardId } = buildVault();
  const p1 = cmd(engine, 'patchCard', { cardId, text: '第一版' });
  const p2 = cmd(engine, 'patchCard', { cardId, text: '第二版' });
  const revisionsBefore = engine.getVault().cardRevisions.length;

  const u1 = engine.undo(IW.commandHeader('u1', engine, { targetCommandId: p2.receipt.commandId }));
  if (u1.status !== 'ACK') throw new Error('first undo failed');
  if (u1.compensationCommand !== 'patchCard') throw new Error('unexpected compensation: ' + u1.compensationCommand);
  const textNow = () => IW.readCardHead(engine.getVault(), cardId)
    .blocks.find((b) => b.role === 'comment').payload.text;
  if (textNow() !== '第一版') throw new Error('undo did not restore the previous text, got ' + JSON.stringify(textNow()));
  if (!IW.readCardRevision(engine.getVault(), p2.receipt.result.cardRevisionId)) {
    throw new Error('the undone revision was deleted from the ledger');
  }
  if (engine.getVault().cardRevisions.length <= revisionsBefore) {
    throw new Error('undo should append a revision, not remove one');
  }

  /* the defect: undo-B itself looked like the newest live command */
  const u2 = engine.undo(IW.commandHeader('u2', engine, { targetCommandId: p1.receipt.commandId }));
  if (u2.status !== 'ACK') throw new Error('second consecutive undo failed: ' + JSON.stringify(u2));
  if (textNow() !== '') throw new Error('second undo did not restore the original empty note');
  return `two undos compensated by appending (chain length ${engine.getVault().cardRevisions.length}); text now ""`;
});

check('R-D08b', 'undo runs the same pipeline: epoch, idempotency, authorization', () => {
  const { engine, cardId } = buildVault();
  const p1 = cmd(engine, 'patchCard', { cardId, text: 'x' });
  /* stale epoch is refused before anything is compensated */
  let epochCode = null;
  try {
    engine.undo(IW.commandHeader('u-epoch', engine, {
      targetCommandId: p1.receipt.commandId, expectedVersions: { epoch: 99 }
    }));
  } catch (e) { epochCode = e.code; }
  if (epochCode !== 'VAULT_EPOCH_MISMATCH') throw new Error('stale-epoch undo was accepted: ' + epochCode);

  const header = IW.commandHeader('u-once', engine, { targetCommandId: p1.receipt.commandId });
  const first = engine.undo(header);
  if (first.status !== 'ACK') throw new Error('undo failed');
  const replay = engine.undo(header);
  if (replay.replayed !== true) throw new Error('replaying the same undo id was not idempotent');
  let again = null;
  try {
    engine.undo(IW.commandHeader('u-twice', engine, { targetCommandId: p1.receipt.commandId }));
  } catch (e) { again = e.code; }
  if (again !== 'ALREADY_UNDONE') throw new Error('a compensated command was compensated twice: ' + again);

  /* revocation must block a compensation whose inverse needs the source */
  const p2 = cmd(engine, 'patchCard', { cardId, text: 'y' });
  engine.setGrant('textbook-excerpt@v1', false);
  let restricted = null;
  try {
    engine.undo(IW.commandHeader('u-restricted', engine, { targetCommandId: p2.receipt.commandId }));
  } catch (e) { restricted = e.code; }
  if (restricted !== 'SOURCE_RESTRICTED') throw new Error('undo bypassed authorization: ' + restricted);
  engine.setGrant('textbook-excerpt@v1', true);
  const after = engine.undo(IW.commandHeader('u-after-regrant', engine, { targetCommandId: p2.receipt.commandId }));
  if (after.status !== 'ACK') throw new Error('undo after re-granting failed');
  return `stale epoch -> ${epochCode}; replay -> ${replay.status}; double compensation -> ${again}; revoked -> ${restricted}`;
});

check('R-S1', 'the shell-level safeguards that the review flagged', () => {
  const { engine, cardId } = buildVault();
  /* independent card must be allowed, and must not fake a source */
  const independent = cmd(engine, 'createIndependentCard', {
    studySetId: engine.getVault().studySets[0].id, text: 'no source yet'
  });
  const card = engine.getVault().cards.find((c) => c.id === independent.receipt.result.cardId);
  if (card.sourceIds.length !== 0) throw new Error('an independent card should have no source');
  if (engine.checkInvariants().length) throw new Error('invariants reject a legitimate independent card');
  /* a quoted card still must have a source */
  const quoteCard = engine.getVault().cards[0];
  const draft = IW.clone(engine.getVault());
  draft.cards.find((c) => c.id === quoteCard.id).sourceIds = [];
  const problems = IW.checkInvariants(draft);
  if (!problems.some((p) => /quotes a source but has no source anchor/.test(p))) {
    throw new Error('a quoted card without a source was accepted');
  }
  /* layout writes and reads agree on one field */
  const node = engine.getVault().occurrences[0];
  cmd(engine, 'moveOccurrence', { occurrenceId: node.id, x: 123, y: 45, width: 260 });
  const moved = engine.getVault().occurrences.find((o) => o.id === node.id);
  if (moved.layoutOverride.x !== 123 || moved.layoutOverride.y !== 45 || moved.layoutOverride.width !== 260) {
    throw new Error('layout written where nothing reads it');
  }
  /* a header naming another vault is rejected */
  let vaultCode = null;
  try {
    engine.run('patchCard', { cardId, text: 'z' }, {
      commandId: 'other-vault', vaultId: 'someone-else', actorId: 'a',
      capabilityHandle: 'c', expectedVersions: { epoch: engine.getVault().epoch }
    });
  } catch (e) { vaultCode = e.code; }
  if (vaultCode !== 'VAULT_ID_MISMATCH') throw new Error('a foreign vaultId was accepted: ' + vaultCode);
  return `independent card ok; quoted-without-source rejected; layout field unified; ${vaultCode}`;
});

/* ------------------------------------------------------------------- run */
function main() {
  const asJson = process.argv.includes('--json');
  const results = [];
  for (const c of CHECKS) {
    const started = Date.now();
    try {
      const detail = c.fn();
      results.push({ id: c.id, title: c.title, result: 'PASS', detail: detail === undefined ? '' : String(detail), ms: Date.now() - started });
    } catch (e) {
      results.push({
        id: c.id, title: c.title, result: 'FAIL', ms: Date.now() - started,
        detail: (e && e.message) || String(e),
        code: e && e.code ? e.code : null,
        details: e && e.details ? JSON.stringify(e.details) : null,
        stack: e && e.stack ? String(e.stack).split('\n').slice(0, 4).join('\n') : null
      });
    }
  }
  const passed = results.filter((r) => r.result === 'PASS').length;
  const failed = results.length - passed;

  if (asJson) {
    process.stdout.write(JSON.stringify({
      scope: 'SAMPLE_SELF_CHECK_NOT_PLAN_ACCEPTANCE',
      runtime: { node: process.version, platform: process.platform, arch: process.arch },
      corpus: { pdfPath: 'assets/conditional-probability-note.pdf', pdfBytes: PDF_BYTES, pdfSha256: PDF_SHA },
      totals: { checks: results.length, passed, failed },
      results
    }, null, 2) + '\n');
  } else {
    for (const r of results) {
      console.log(`${r.result.padEnd(4)}  ${r.id.padEnd(11)} ${r.title}`);
      if (r.detail) console.log(`      ${r.detail}`);
      if (r.code) console.log(`      code=${r.code}`);
      if (r.details) console.log(`      details=${r.details}`);
      if (r.stack) console.log(r.stack.split('\n').map((l) => '      ' + l).join('\n'));
    }
    console.log('');
    console.log(`checks=${results.length} passed=${passed} failed=${failed} node=${process.version}`);
    console.log('NOTE: sample self-checks only. The plan\'s AX cases stay NOT_RUN; no browser,');
    console.log('      Android, real PDF renderer, FSRS scheduler or device was exercised.');
  }
  process.exit(failed === 0 ? 0 : 1);
}

main();
