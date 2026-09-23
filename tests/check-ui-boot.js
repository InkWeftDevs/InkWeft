/* InkWeft S1 sample — UI boot smoke test in a mock DOM.

   The headless checks cover the model; this covers the SHELL: does the app boot,
   wire every element, seed through the command API, and render without throwing?
   In a browser a mistake here is a blank page, which no model test would catch.

   It is a smoke test, not a rendering test: there is no layout engine, no ink, no
   real selection. Those stay unproven and are listed as such in sample-REPORT.md.

   Run:  node tests/check-ui-boot.js
*/
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

/* ------------------------------------------------------------- mock DOM */
function makeClassList(node) {
  const set = new Set();
  return {
    add: (c) => set.add(c),
    remove: (c) => set.delete(c),
    contains: (c) => set.has(c),
    toggle: (c) => (set.has(c) ? set.delete(c) : set.add(c)),
    _set: set
  };
}

let idCounter = 0;
function makeNode(tag) {
  const node = {
    tagName: String(tag).toUpperCase(),
    children: [],
    attributes: {},
    style: {},
    value: '',
    textContent: '',
    innerHTML: '',
    checked: false,
    disabled: false,
    files: [],
    _listeners: {},
    classList: null,
    parentNode: null,
    _id: 'n' + (++idCounter)
  };
  node.classList = makeClassList(node);
  node.appendChild = (c) => { c.parentNode = node; node.children.push(c); return c; };
  node.removeChild = (c) => {
    node.children = node.children.filter((x) => x !== c);
    return c;
  };
  node.insertBefore = (c) => node.appendChild(c);
  node.setAttribute = (k, v) => { node.attributes[k] = String(v); };
  node.getAttribute = (k) => (k in node.attributes ? node.attributes[k] : null);
  node.removeAttribute = (k) => { delete node.attributes[k]; };
  node.addEventListener = (type, fn) => {
    (node._listeners[type] = node._listeners[type] || []).push(fn);
  };
  node.removeEventListener = (type, fn) => {
    node._listeners[type] = (node._listeners[type] || []).filter((f) => f !== fn);
  };
  node.dispatch = (type, ev) => {
    (node._listeners[type] || []).forEach((fn) => fn(ev || { target: node }));
  };
  node.querySelector = () => null;
  node.querySelectorAll = () => [];
  node.closest = () => null;
  node.contains = (other) => {
    let cur = other;
    while (cur) { if (cur === node) return true; cur = cur.parentNode; }
    return false;
  };
  node.scrollIntoView = () => {};
  node.focus = () => {};
  node.click = () => node.dispatch('click');
  return node;
}

const registry = {};
const documentMock = {
  readyState: 'complete',
  _listeners: {},
  createElement: (tag) => makeNode(tag),
  createTreeWalker: () => ({ nextNode: () => null }),
  getElementById: (id) => registry[id] || null,
  querySelector: () => null,
  querySelectorAll: () => [],
  addEventListener: (t, fn) => { (documentMock._listeners[t] = documentMock._listeners[t] || []).push(fn); },
  removeEventListener: () => {},
  body: makeNode('body'),
  documentElement: makeNode('html')
};

/* Register every id the build check proved the UI dereferences. */
const ids = new Set();
let m;
const idRe = /id="([A-Za-z0-9_-]+)"/g;
while ((m = idRe.exec(html))) ids.add(m[1]);
for (const id of ids) {
  const node = makeNode(id.includes('comment') || id.includes('doc') ? 'div' : 'div');
  node.setAttribute('id', id);
  registry[id] = node;
}
/* the UI reads .value from these and appends to the ones it fills */
registry['comment'].value = '';

const storage = (() => {
  const cell = {};
  return {
    getItem: (k) => (k in cell ? cell[k] : null),
    setItem: (k, v) => { cell[k] = String(v); },
    removeItem: (k) => { delete cell[k]; },
    _cell: cell
  };
})();

const sandbox = {
  console,
  setTimeout,
  clearTimeout,
  Date,
  JSON,
  Math,
  Object,
  Array,
  String,
  Number,
  Boolean,
  RegExp,
  Error,
  TypeError,
  isNaN,
  parseInt,
  parseFloat,
  encodeURIComponent,
  decodeURIComponent,
  document: documentMock,
  localStorage: storage,
  NodeFilter: { SHOW_TEXT: 4 },
  Blob: function Blob() {},
  URL: { createObjectURL: () => 'blob:mock', revokeObjectURL: () => {} },
  /* No fetch on purpose: checkPdf degrades instead of claiming a hash it never read. */
  window: null
};
sandbox.globalThis = sandbox;
sandbox.window = sandbox;
sandbox.window.getSelection = () => ({ isCollapsed: true, rangeCount: 0, toString: () => '' });
sandbox.document.readyState = 'complete';

const problems = [];
function check(name, fn) {
  try { fn(); } catch (e) { problems.push(name + ': ' + (e && e.message)); }
}

const context = vm.createContext(sandbox);
check('inline script evaluates', () => {
  new vm.Script(script, { filename: 'index.inline.js' }).runInContext(context);
});

check('IW.start boots the app', () => {
  if (typeof sandbox.IW === 'undefined' || typeof sandbox.IW.start !== 'function') {
    throw new Error('IW.start is not exposed');
  }
  sandbox.IW.start();
});

const app = sandbox.inkweft;
check('app instance exists', () => { if (!app) throw new Error('globalThis.inkweft was not set'); });

if (app) {
  const v = app.engine.getVault();
  check('seeded exactly one card', () => {
    if (v.cards.length !== 1) throw new Error('cards = ' + v.cards.length);
  });
  check('seeded one node, one margin placement, one question', () => {
    if (v.occurrences.length !== 1) throw new Error('occurrences = ' + v.occurrences.length);
    if (v.marginPlacements.length !== 1) throw new Error('marginPlacements = ' + v.marginPlacements.length);
    if (v.reviewItems.length !== 1) throw new Error('reviewItems = ' + v.reviewItems.length);
  });
  check('the seed excerpt produced one anchor per page', () => {
    if (v.sourceAnchors.length !== 2) throw new Error('sourceAnchors = ' + v.sourceAnchors.length);
  });
  check('nothing was written into the S1-excluded collections', () => {
    if (v.dictionaryEntries.length || v.reviewEvents.length || v.attempts.length) {
      throw new Error('a scope-excluded collection is non-empty');
    }
  });
  check('the vault was persisted', () => {
    if (app.store.commitSeq() !== 1) throw new Error('commitSeq = ' + app.store.commitSeq());
  });
  check('the document panel rendered real offsets', () => {
    const html2 = registry['doc-body'].innerHTML;
    if (!/data-start="0"/.test(html2)) throw new Error('first line has no start offset');
    if (!/class="page"/.test(html2)) throw new Error('no page container rendered');
    if (!/data-anchor="/.test(html2)) throw new Error('the excerpt mark was not rendered');
  });
  check('the card panel rendered the head revision', () => {
    const head = sandbox.IW.readCardHead(v, app.selectedCardId);
    if (!registry['card-head'].textContent.includes(head.cardRevisionId)) {
      throw new Error('card-head does not show the head revision id');
    }
    if (registry['revision-chain'].innerHTML.indexOf('HEAD') < 0) throw new Error('revision chain missing HEAD');
    if (!registry['quote'].textContent.length) throw new Error('quote block is empty');
  });
  check('the review projection renders without copying the body', () => {
    const item = v.reviewItems[0];
    if (!registry['question'].textContent.includes(item.promptSpec.question)) {
      throw new Error('question text not rendered');
    }
    if (registry['question-meta'].textContent.indexOf('dueAt null') < 0) {
      throw new Error('the inert scheduler state is not surfaced');
    }
  });
  check('the PDF surface reports itself as unavailable rather than pretending', () => {
    if (registry['pdf-capability'].textContent.indexOf('renders=false') < 0) {
      throw new Error('capability line does not say renders=false: ' + registry['pdf-capability'].textContent);
    }
  });
  check('saving a comment goes through the command layer', () => {
    const before = sandbox.IW.readCardHead(app.engine.getVault(), app.selectedCardId).revision;
    registry['comment'].value = 'UI 冒烟测试写入的备注';
    registry['btn-save-comment'].dispatch('click');
    const after = sandbox.IW.readCardHead(app.engine.getVault(), app.selectedCardId);
    if (after.revision !== before + 1) throw new Error('revision did not advance: ' + before + ' -> ' + after.revision);
    if (!after.blocks.some((b) => b.payload.text === 'UI 冒烟测试写入的备注')) {
      throw new Error('the comment text is not on the head revision');
    }
  });
  check('a stale write is refused and offers the resolve path', () => {
    const cardId = app.selectedCardId;
    const observed = sandbox.IW.readCardHead(app.engine.getVault(), cardId).revision;
    /* another view commits first */
    app.dispatch('patchCard', { cardId, text: '另一个视图' }, 'other-view');
    const result = app.dispatch('patchCard', { cardId, text: '基于旧版本' }, 'stale',
      { epoch: app.engine.getVault().epoch, card: { [cardId]: observed } });
    if (!result.error || result.error.code !== 'EXPECTED_VERSION_MISMATCH') {
      throw new Error('expected a refused stale write');
    }
    if (!app.conflict) throw new Error('the conflict banner was not armed');
    registry['btn-resolve'].dispatch('click');
    const texts = sandbox.IW.readCardHead(app.engine.getVault(), cardId)
      .blocks.filter((b) => b.role === 'comment').map((b) => b.payload.text);
    if (!texts.includes('另一个视图')) throw new Error('the other view\'s text was lost');
    if (app.conflict) throw new Error('the conflict banner was not cleared');
  });
  check('adding a node and resizing it touches layout only', () => {
    const cardId = app.selectedCardId;
    const headBefore = sandbox.IW.readCardHead(app.engine.getVault(), cardId).cardRevisionId;
    registry['btn-add-node'].dispatch('click');
    if (app.engine.getVault().occurrences.length !== 2) throw new Error('node was not added');
    registry['node-width'].value = 300;
    registry['node-width'].dispatch('change', { target: { value: '300' } });
    const nodes = app.engine.getVault().occurrences;
    if (!nodes.every((n) => n.layoutOverride.width === 300 || n.layoutOverride.width === 220)) {
      throw new Error('unexpected node widths');
    }
    if (sandbox.IW.readCardHead(app.engine.getVault(), cardId).cardRevisionId !== headBefore) {
      throw new Error('a layout change moved the card head');
    }
  });
  check('undo reverses the last command', () => {
    const history = app.engine.history();
    const nodesBefore = app.engine.getVault().occurrences.length;
    registry['btn-undo'].dispatch('click');
    const nodesAfter = app.engine.getVault().occurrences.length;
    if (nodesAfter === nodesBefore && app.engine.history().length === history.length) {
      throw new Error('undo did nothing');
    }
  });
  check('export then isolated restore keeps every reference', () => {
    registry['btn-export'].dispatch('click');
    if (!app.lastPackage) throw new Error('no package produced');
    registry['btn-reimport'].dispatch('click');
    if (!app.lastRestoreReport) throw new Error('no restore report');
    const failed = app.lastRestoreReport.checks.filter((c) => c.result !== 'PASS');
    if (failed.length) throw new Error('failed checks: ' + JSON.stringify(failed.slice(0, 3)));
    const v2 = app.engine.getVault();
    const cardId = app.selectedCardId;
    const head = sandbox.IW.readCardHead(v2, cardId);
    if (!head.blocks.length) throw new Error('the restored card lost its blocks');
    if (v2.sourceAnchors.length < 2) throw new Error('the restored vault lost anchors');
  });
  check('the fault toggle arms the store without wiping anything', () => {
    registry['chk-fault'].checked = true;
    registry['chk-fault'].dispatch('change');
    const seqBefore = app.store.commitSeq();
    const cardsBefore = app.engine.getVault().cards.length;
    registry['comment'].value = '这笔不会落盘';
    registry['btn-save-comment'].dispatch('click');
    if (app.store.commitSeq() !== seqBefore) throw new Error('commitSeq advanced despite the injected fault');
    if (app.engine.getVault().cards.length !== cardsBefore) throw new Error('the vault was damaged');
    if (registry['store-state'].textContent.indexOf('NOT SAVED') < 0) {
      throw new Error('the UI did not surface the failed save');
    }
    registry['chk-fault'].checked = false;
    registry['chk-fault'].dispatch('change');
  });
  check('an ordinary highlighter creates no card', () => {
    const cardsBefore = app.engine.getVault().cards.length;
    const marksBefore = app.engine.getVault().annotationPlacements.length;
    app.setMarkMode('highlighter');
    const pageIds = app.surface.listPageIds();
    const lines = pageIds.flatMap((id) => app.surface.offsetsToLines(id, 0, 1e9));
    app.applySelection(lines[0].startOffset, lines[0].endOffset - 1, lines[0].text);
    const v3 = app.engine.getVault();
    if (v3.cards.length !== cardsBefore) throw new Error('a highlighter created a card');
    if (v3.annotationPlacements.length !== marksBefore + 1) throw new Error('the mark was not recorded');
    const mark = v3.annotationPlacements[v3.annotationPlacements.length - 1];
    if (mark.cardId !== null) throw new Error('the highlighter mark claims a card');
  });
  check('an excerpt selection creates a card through the same entry point', () => {
    app.setMarkMode('excerpt');
    const cardsBefore = app.engine.getVault().cards.length;
    const pageIds = app.surface.listPageIds();
    const lines = pageIds.flatMap((id) => app.surface.offsetsToLines(id, 0, 1e9));
    const from = lines.find((l) => l.text.includes('贝叶斯'));
    app.applySelection(from.startOffset, from.endOffset, from.text);
    const v4 = app.engine.getVault();
    if (v4.cards.length !== cardsBefore + 1) throw new Error('no card was created');
    const newCard = v4.cards[v4.cards.length - 1];
    const head = sandbox.IW.readCardHead(v4, newCard.id);
    if (!head.sourceIds.length) throw new Error('the new card has no source');
    if (!head.blocks.some((b) => b.role === 'source_quote' && b.payload.text.includes('贝叶斯'))) {
      throw new Error('the quote block does not carry the selection');
    }
  });
  check('vault epoch blocks a stale session', () => {
    registry['btn-epoch'].dispatch('click');
    const epoch = app.engine.getVault().epoch;
    if (epoch < 1) throw new Error('epoch did not advance');
    const cardId = app.selectedCardId;
    const result = app.dispatch('patchCard', { cardId, text: 'x' }, 'stale-epoch',
      { epoch: epoch - 1 });
    if (!result.error || result.error.code !== 'VAULT_EPOCH_MISMATCH') {
      throw new Error('a stale epoch write was accepted');
    }
  });
}

/* ------------------------------------------------ reopen (the plan's 重开) */
/* A real reload starts a fresh App against the SAME localStorage. That is the
   "关闭重开后一致" part of the S1 exit, and it is worth an explicit check because
   the boot path has a restore branch that the seeding branch does not exercise. */
const persistedBefore = sandbox.localStorage.getItem('inkweft.s1.vault');
const cardsBeforeReload = app ? app.engine.getVault().cards.length : 0;
let reopened = null;

check('a fresh boot restores the persisted vault instead of re-seeding', () => {
  if (!persistedBefore) throw new Error('nothing was persisted to restore');
  reopened = sandbox.IW.start();
  if (!reopened) throw new Error('IW.start did not return an app on the second boot');
  const v = reopened.engine.getVault();
  if (v.cards.length !== cardsBeforeReload) {
    throw new Error(`card count changed across reopen: ${cardsBeforeReload} -> ${v.cards.length}`);
  }
  const log = reopened.log.map((l) => l.text).join('\n');
  if (log.indexOf('restored a persisted vault') < 0) {
    throw new Error('the second boot seeded instead of restoring');
  }
});

if (reopened) {
  check('content committed before the reopen is still readable', () => {
    const v = reopened.engine.getVault();
    const cardId = reopened.selectedCardId;
    const head = sandbox.IW.readCardHead(v, cardId);
    if (!head.blocks.length) throw new Error('the restored card has no blocks');
    /* The vault also holds a plain-highlighter anchor (cardId null), so compare
       against the card's own sources, not against the whole anchor table. */
    if (head.sourceIds.length < 2) {
      throw new Error('the per-page anchors did not survive the reopen: ' + head.sourceIds.length);
    }
    const unresolved = head.sourceIds.filter((id) => !v.sourceAnchors.some((a) => a.id === id));
    if (unresolved.length) throw new Error('reopened card has dangling source ids: ' + unresolved.join(','));
    const texts = head.blocks.filter((b) => b.role === 'comment').map((b) => b.payload.text);
    if (!texts.includes('另一个视图')) {
      throw new Error('the committed comment was lost across the reopen: ' + JSON.stringify(texts));
    }
  });
  check('the restored app can keep writing and the store accepts it', () => {
    const seqBefore = reopened.store.commitSeq();
    const cards = reopened.engine.getVault().cards.length;
    reopened.setMarkMode('excerpt');
    const pageIds = reopened.surface.listPageIds();
    const lines = pageIds.flatMap((id) => reopened.surface.offsetsToLines(id, 0, 1e9));
    const target = lines[lines.length - 1];
    reopened.applySelection(target.startOffset, target.endOffset, target.text);
    if (reopened.engine.getVault().cards.length !== cards + 1) {
      throw new Error('a new excerpt could not be created after the reopen');
    }
    if (reopened.store.commitSeq() !== seqBefore + 1) {
      throw new Error('the reopened app did not persist its new commit');
    }
  });
  check('the corrupt-store path refuses to boot on bad data instead of half-loading', () => {
    const good = sandbox.localStorage.getItem('inkweft.s1.vault');
    const parsed = JSON.parse(good);
    parsed.payload = parsed.payload.slice(0, Math.floor(parsed.payload.length / 2));
    sandbox.localStorage.setItem('inkweft.s1.vault', JSON.stringify(parsed));
    const third = sandbox.IW.start();
    const log = third.log.map((l) => l.text).join('\n');
    if (log.indexOf('stored vault refused') < 0) {
      throw new Error('a torn stored value was accepted at boot: ' + log.slice(0, 120));
    }
    sandbox.localStorage.setItem('inkweft.s1.vault', good);
  });
}

if (problems.length) {
  console.error('UI BOOT CHECK FAILED');
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log('UI BOOT CHECK PASSED');
console.log('  elements wired: ' + ids.size);
console.log('  boot paths: first boot (seed) + second boot (restore) + torn-store refusal');
console.log('  commands exercised: seed plan, patchCard, stale write + resolve, attachOccurrence,');
console.log('                      moveOccurrence, undo, export, import, fault injection, epoch,');
console.log('                      markHighlighter, createCardFromSelection');
