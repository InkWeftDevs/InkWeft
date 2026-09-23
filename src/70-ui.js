/* InkWeft S1 sample — browser shell.
   The UI never touches the vault. Every mutation goes through engine.run(). */
(function (IW) {
  'use strict';

  var el = function (id) { return document.getElementById(id); };
  /* Monotonic across App instances in this page: two boots inside the same
     millisecond must still be two different sessions, or they would reissue each
     other's command ids. */
  var bootCounter = 0;
  var esc = function (s) {
    return String(s === undefined || s === null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  };

  function App() {
    bootCounter += 1;
    this.bootSeq = bootCounter;
    this.corpus = IW.corpus();
    this.store = IW.createStore(IW.browserBackend(), null);
    this.engine = null;
    this.surface = null;
    this.sessionId = 's0-boot';
    this.commandCounter = 0;
    this.selectedCardId = null;
    this.renderedCardId = null;
    this.commentBinding = null;
    this.commentFocused = false;
    this.loadState = 'EMPTY';
    this.corruptInfo = null;
    this.readOnly = false;
    this.markMode = 'excerpt';
    this.activeAnchorId = null;
    this.log = [];
    this.conflict = null;
    this.assetMeta = { pdfSha256: null, pdfBytes: null, pdfStatus: 'not checked' };
  }

  /* ------------------------------------------------------------- lifecycle */
  App.prototype.boot = function () {
    var self = this;
    var loaded = this.store.load();

    /* A damaged stored value is NOT an empty store. Treating it as one made the
       app overwrite a corrupt vault with a fresh demo one, which is the single
       worst thing a store can lead a caller into. Corrupt => read-only recovery. */
    this.loadState = loaded.status;      /* EMPTY | LOADED | CORRUPT */
    this.corruptInfo = loaded.status === 'CORRUPT'
      ? { code: loaded.code, message: loaded.message, rawBytes: loaded.rawBytes }
      : null;
    this.readOnly = false;

    if (loaded.status === 'CORRUPT') {
      this.readOnly = true;
      this.sessionId = this.sessionIdFrom(null);
      this.note('warn', 'stored value refused at load: ' + loaded.code +
        ' — entering read-only recovery (nothing will be written)');
      this.engine = IW.createEngine({ vaultId: 'inkweft-sample-vault' });
    } else if (loaded.status === 'LOADED') {
      this.engine = IW.createEngine({
        vaultId: loaded.vault.vaultId, vault: loaded.vault, state: loaded.state,
        persist: this.persistHook()
      });
      this.sessionId = this.sessionIdFrom(loaded.state);
      this.note('info', 'restored a persisted vault (commitSeq ' + loaded.commitSeq + ')');
    } else {
      this.engine = IW.createEngine({ vaultId: 'inkweft-sample-vault', persist: this.persistHook() });
      this.sessionId = this.sessionIdFrom(null);
    }

    /* The surface must exist before seeding: the seed card is created through the
       same command as any other excerpt, and that command resolves the selection
       through the surface. */
    this.surface = IW.createSurface(IW.originFor(this.corpus.text), this.surfaceCtx());

    if (loaded.status === 'EMPTY') {
      this.seed();
    }

    this.selectedCardId = this.engine.getVault().cards.length
      ? this.engine.getVault().cards[0].id : null;
    this.renderedCardId = this.selectedCardId;
    this.commentBinding = { cardId: this.selectedCardId, cardRevisionId: null };

    /* The PDF is a real file; hashing it proves the "original unchanged" claim
       against bytes rather than against a number we wrote ourselves. */
    this.checkPdf();

    el('mark-excerpt').checked = true;
    this.bind();
    this.renderAll();
  };

  /* Every command write goes through this hook, so the engine persists BEFORE it
     publishes the new state or answers ACK. Without it the UI used to ACK first
     and save afterwards, which meant a failed save still counted as the
     authoritative head, history and export scope. */
  App.prototype.persistHook = function () {
    var self = this;
    return function (pending) {
      if (self.readOnly) {
        IW.fail('STORE_READ_ONLY', 'the stored vault is damaged; recovery mode does not write');
      }
      var info = self.store.commit(pending.vault, pending.state);
      self.lastCommit = info;
      return info;
    };
  };

  /* A reopened session must not reissue ids it already used. The counters are
     derived from what the vault already contains, not from a fresh App object. */
  App.prototype.sessionIdFrom = function (state) {
    var count = (state && state.receipts ? state.receipts.length : 0);
    var stamp = Date.now().toString(36).slice(-6);
    return 's' + count + '-' + stamp + '-' + this.bootSeq;
  };

  App.prototype.surfaceCtx = function () {
    var engine = this.engine;
    return {
      docId: this.corpus.text.id,
      docIndex: function () { return engine.getVault().documents; },
      versionIndex: function () { return engine.getVault().documentVersions; },
      grants: engine.grants
    };
  };

  App.prototype.checkPdf = function () {
    var self = this;
    var url = this.corpus.pdf.assetPath;
    if (this.readOnly) { this.assetMeta.pdfStatus = 'not checked (recovery mode)'; return; }
    if (typeof fetch !== 'function') {
      this.assetMeta.pdfStatus = 'fetch unavailable (file:// origin?)';
      return;
    }
    fetch(url).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.arrayBuffer();
    }).then(function (buf) {
      var bytes = new Uint8Array(buf);
      self.assetMeta.pdfBytes = bytes.length;
      self.assetMeta.pdfSha256 = IW.sha256Hex(bytes);
      self.assetMeta.pdfStatus = 'verified ' + self.assetMeta.pdfSha256.slice(0, 16) + '…';
      if (!self.engine.getVault().documentVersions.some(function (v) { return v.kind === 'pdf-origin'; })) {
        self.dispatch('registerDocumentVersion', {
          documentId: self.corpus.pdf.id,
          versionId: self.corpus.pdf.versionId,
          title: self.corpus.pdf.title,
          kind: self.corpus.pdf.kind,
          assetPath: self.corpus.pdf.assetPath,
          bytes: bytes.length,
          sha256: self.assetMeta.pdfSha256,
          textSha256: null
        }, 'pdf-register');
      }
      self.renderAssetStatus();
    }).catch(function (e) {
      self.assetMeta.pdfStatus = 'not readable from this origin (' + e.message + ')';
      self.renderAssetStatus();
    });
  };

  /* Command ids are scoped to this session, and the session is derived from what
     the vault already holds. Previously the counter restarted at 1 on every boot,
     so a reopened session reissued `ui-comment-1` and tripped COMMAND_ID_REUSE on
     the user's second edit — an identity bug in the shell, not in idempotency. */
  App.prototype.header = function (tag, extra) {
    var h = {
      commandId: 'ui-' + this.sessionId + '-' + tag + '-' + (++this.commandCounter || (this.commandCounter = 1)),
      vaultId: this.engine.vaultId,
      actorId: 'local-user',
      capabilityHandle: 'cap-ui-' + this.sessionId,
      expectedVersions: { epoch: this.engine.getVault().epoch }
    };
    if (extra) Object.keys(extra).forEach(function (k) { h[k] = extra[k]; });
    return h;
  };

  App.prototype.seed = function () {
    var self = this;
    var studySetId = null;
    /* Seeding goes through dispatch too, so it obeys the same persist-before-ack
       rule as a user edit. A demo that seeds around the write path would prove
       nothing about the write path. */
    IW.seedPlan(this.corpus, {}).forEach(function (step) {
      var result = self.dispatch(step.command, step.params, 'seed-' + step.command);
      if (result.error) throw result.error;
    });
    studySetId = this.engine.getVault().studySets[0].id;
    var seeded = IW.createFirstCard(this.engine, this.surface, this.corpus.text,
      IW.defaultSelection(this.corpus));
    var cardId = seeded.receipt.result.cardId;
    this.engine.run('attachOccurrence', { cardId: cardId, mapId: null, studySetId: studySetId },
      this.header('seed-node'));
    this.engine.run('upsertMargin', { cardId: cardId, preferredHeight: 180 }, this.header('seed-margin'));
    this.engine.run('createReviewItem', {
      cardId: cardId, question: '写出条件概率的定义式，并说明成立前提。'
    }, this.header('seed-question'));
    this.note('ok', 'seeded: one excerpt card, one node, one manual question');
  };

  /* Only for bookkeeping writes the engine does not own, e.g. nothing today.
     Command writes must go through dispatch so the persist hook stays in charge. */
  App.prototype.commitEngineState = function () {
    try {
      return this.store.commit(this.engine.getVault(), this.engine.getState());
    } catch (e) {
      el('store-state').textContent = 'NOT SAVED — ' + e.code;
      el('store-state').className = 'chip bad';
      this.note('warn', 'store refused a direct commit: ' + e.code);
      return null;
    }
  };

  App.prototype.dispatch = function (commandType, params, tag, expectedVersions) {
    var header = this.header(tag, expectedVersions ? { expectedVersions: expectedVersions } : null);
    try {
      /* The engine persists through the hook BEFORE publishing and before this
         returns. There is no "ACK now, save later" window any more. */
      var result = this.engine.run(commandType, params, header);
      this.note(result.replayed ? 'info' : 'ok',
        commandType + ' -> ' + result.status + (result.replayed ? ' (idempotent replay)' : ''));
      el('store-state').textContent = 'commitSeq ' + this.store.commitSeq();
      el('store-state').className = 'chip ok';
      this.conflict = null;
      return result;
    } catch (e) {
      this.note('warn', commandType + ' refused: ' + e.code + ' — ' + e.message);
      if (e.code === 'EXPECTED_VERSION_MISMATCH') {
        this.conflict = { commandType: commandType, params: params, details: e.details, message: e.message };
        this.note('info', 'another view advanced this card. Use "重新读取并保存" instead of overwriting.');
      }
      if (e.code === 'STORE_FAULT_INJECTED') {
        /* The write was refused before publication, so the head, the history and
           the durable bytes are all still the previous ones. */
        el('store-state').textContent = 'NOT SAVED — ' + e.code;
        el('store-state').className = 'chip bad';
        this.note('info', 'nothing was published: check the authoritative head below, then retry the same commandId');
      }
      return { error: e };
    } finally {
      this.renderAll();
    }
  };

  App.prototype.note = function (kind, text) {
    this.log.unshift({ kind: kind, text: text, at: new Date().toISOString().slice(11, 19) });
    if (this.log.length > 40) this.log.pop();
  };

  /* ---------------------------------------------------------- interaction */
  /* Two properties this must keep:
       1. binding is idempotent — booting twice must not stack listeners, or one
          click would run a command once per accumulated app;
       2. handlers resolve the LIVE app instead of closing over one instance, so
          a re-boot (mock suite, or an app restart) cannot leave dead listeners
          driving a discarded engine. */
  App.prototype.bind = function () {
    var self = this;
    var live = function () { return globalThis.inkweft || self; };
    if (el('btn-save-comment')._inkweftBound) { this.renderAll(); return; }
    var mark = function (node) { node._inkweftBound = true; return node; };
    mark(el('mark-excerpt')).addEventListener('change', function () { live().setMarkMode('excerpt'); });
    mark(el('mark-highlighter')).addEventListener('change', function () { live().setMarkMode('highlighter'); });
    mark(el('btn-save-comment')).addEventListener('click', function () { live().saveComment(); });
    mark(el('btn-add-node')).addEventListener('click', function () { live().addNode(); });
    mark(el('btn-undo')).addEventListener('click', function () { live().undoLast(); });
    mark(el('btn-export')).addEventListener('click', function () { live().exportPackage(); });
    mark(el('btn-reimport')).addEventListener('click', function () { live().reimportPackage(); });
    mark(el('chk-fault')).addEventListener('change', function () { live().armFault(); });
    mark(el('btn-epoch')).addEventListener('click', function () { live().resetEpoch(); });
    mark(el('btn-reset')).addEventListener('click', function () { live().hardReset(); });
    mark(el('btn-resolve')).addEventListener('click', function () { live().resolveConflict(); });
    mark(el('card-select')).addEventListener('change', function (e) {
      var app = live();
      app.selectedCardId = e.target.value;
      /* Rebind the editor to the newly selected card so a later save cannot
         commit this buffer against the previous card. */
      app.commentBinding = { cardId: e.target.value, cardRevisionId: null };
      app.commentEdited = false;
      app.activeAnchorId = null;
      app.renderAll();
    });
    mark(el('comment')).addEventListener('focus', function () { live().commentFocused = true; });
    mark(el('comment')).addEventListener('blur', function () {
      var app = live();
      app.commentFocused = false;
      app.commentEdited = false;
      app.renderAll();
    });
    mark(el('comment')).addEventListener('input', function () { live().commentEdited = true; });
    mark(el('node-width')).addEventListener('change', function (e) { live().setNodeWidth(Number(e.target.value)); });
    if (!document._inkweftBound) {
      document._inkweftBound = true;
      document.addEventListener('mouseup', function (e) {
        var app = globalThis.inkweft;
        if (app && el('doc').contains(e.target)) app.captureSelection();
      });
    }
    mark(el('doc')).addEventListener('click', function (e) {
      var hit = e.target.closest ? e.target.closest('[data-anchor]') : null;
      if (hit) { live().flashAnchor(hit.getAttribute('data-anchor')); }
    });
  };

  App.prototype.setMarkMode = function (mode) {
    this.markMode = mode;
    el('mark-excerpt').checked = mode === 'excerpt';
    el('mark-highlighter').checked = mode === 'highlighter';
    this.renderAll();
  };

  /* Turn a DOM selection into model offsets.

     The model counts `line.length + 1` per line, so DOM offsets can only match it
     if they are anchored to the line boxes. A flat TreeWalker over the document
     drifts on every line (block elements contribute no newline) and happily reads
     the page label as content — the earlier implementation did exactly that, so
     only the first line was ever right. Instead: find the `.line` element owning
     each endpoint and read its model start from the data attribute. */
  App.prototype.captureSelection = function () {
    var sel = window.getSelection ? window.getSelection() : null;
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return;
    var range = sel.getRangeAt(0);
    var host = el('doc-body');
    if (!host.contains(range.startContainer) || !host.contains(range.endContainer)) return;

    var start = this.lineOffset(range.startContainer, range.startOffset, 'start');
    var end = this.lineOffset(range.endContainer, range.endOffset, 'end');
    if (start === null || end === null || end <= start) return;
    this.applySelection(start, end, sel.toString());
  };

  /* Model offset of one endpoint. Returns null when the endpoint is not part of a
     text line at all (padding, a page label, an overlay mark). */
  App.prototype.lineOffset = function (node, offset, edge) {
    var element = node && node.nodeType === 1 ? node : node && node.parentNode;
    while (element && element.nodeType !== 1) element = element.parentNode;
    var line = null;
    while (element) {
      if (element.getAttribute && element.getAttribute('data-start') !== null) { line = element; break; }
      element = element.parentNode;
    }
    if (!line) return null;
    var lineStart = Number(line.getAttribute('data-start'));
    var lineEnd = Number(line.getAttribute('data-end'));
    var body = line.querySelector ? line.querySelector('.line-body') : null;
    if (!body) return null;

    /* Only text inside .line-body counts; page labels and marks are excluded. */
    var inner = 0;
    var matched = false;
    var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, null);
    var current;
    while ((current = walker.nextNode())) {
      if (current === node) { inner += offset; matched = true; break; }
      inner += current.nodeValue.length;
    }
    if (!matched) {
      /* The endpoint is the element itself or sits outside the text nodes: treat
         it as the line boundary closest to the requested edge. */
      if (node === line || node === body) inner = edge === 'end' ? body.textContent.length : 0;
      else inner = edge === 'end' ? body.textContent.length : 0;
    }
    return Math.max(lineStart, Math.min(lineStart + inner, lineEnd));
  };

  App.prototype.applySelection = function (start, end, selectedText) {
    var pageId = null;
    var pageIds = this.surface.listPageIds();
    for (var i = 0; i < pageIds.length; i++) {
      var lines = this.surface.offsetsToLines(pageIds[i], start, end);
      if (lines.length) { pageId = pageIds[i]; break; }
    }
    if (!pageId) { this.note('warn', 'the selection did not intersect any text line'); return; }

    var params = {
      documentId: this.corpus.text.id,
      pageId: pageId,
      sourceVersion: this.corpus.text.versionId,
      startOffset: start,
      endOffset: end,
      surface: this.surface
    };
    var result;
    if (this.markMode === 'highlighter') {
      result = this.dispatch('markHighlighter', params, 'mark');
      if (!result.error) {
        this.note('info', 'ordinary highlighter: annotation only — no card, no node, no question');
      }
      return;
    }
    params.studySetId = this.engine.getVault().studySets[0].id;
    params.comment = '';
    result = this.dispatch('createCardFromSelection', params, 'excerpt');
    if (result.error) return;
    /* Update the selection BEFORE rendering, otherwise the panel keeps showing the
       previous card while the next save targets the new one. */
    this.selectedCardId = result.receipt.result.cardId;
    this.renderedCardId = this.selectedCardId;
    this.activeAnchorId = result.receipt.result.anchorId;
    this.note('info', 'excerpt card created; quoting: ' + (selectedText || '').slice(0, 40).replace(/\n/g, ' / '));
    if (window.getSelection) {
      var live = window.getSelection();
      if (live && live.removeAllRanges) live.removeAllRanges();
    }
    this.renderAll();
  };

  /* ----------------------------------------------------------- mutations */
  App.prototype.saveComment = function () {
    /* The edit buffer is bound to a card. If the panel moved on to another card
       while the user was typing (or an excerpt was created), committing the
       buffer against the new card would silently transplant one card's text onto
       another. Refuse and say why. */
    var bound = this.commentBinding;
    if (!bound || !bound.cardId) return;
    if (bound.cardId !== this.selectedCardId) {
      this.note('warn', 'the panel now shows another card; the editor was rebound — nothing was saved');
      this.renderAll();
      return;
    }
    var cardId = bound.cardId;
    var text = el('comment').value;
    var observedRevision = IW.readCardHead(this.engine.getVault(), cardId).revision;
    var expected = { epoch: this.engine.getVault().epoch };
    expected.card = {};
    expected.card[cardId] = observedRevision;
    var result = this.dispatch('patchCard', { cardId: cardId, text: text }, 'comment', expected);
    if (result.error && this.conflict) {
      this.conflict.params = { cardId: cardId, text: text };
    }
    /* On success nothing else needs updating by hand: the node, the margin
       placement and the question all read the same head revision. */
  };

  App.prototype.resolveConflict = function () {
    if (!this.conflict) return;
    var cardId = this.conflict.params && this.conflict.params.cardId
      ? this.conflict.params.cardId : this.selectedCardId;
    if (!cardId) return;
    var observed = IW.readCardHead(this.engine.getVault(), cardId).cardRevisionId;
    this.dispatch('commitResolveConflict', {
      cardId: cardId, text: el('comment').value, observedCardRevisionId: observed
    }, 'resolve');
  };

  App.prototype.addNode = function () {
    var v = this.engine.getVault();
    if (!this.selectedCardId) return;
    this.dispatch('attachOccurrence', {
      cardId: this.selectedCardId, mapId: null, studySetId: v.studySets[0].id
    }, 'node');
  };

  App.prototype.setNodeWidth = function (width) {
    var node = this.currentNode();
    if (!node) return;
    this.dispatch('moveOccurrence', { occurrenceId: node.id, width: width }, 'width');
  };

  App.prototype.undoLast = function () {
    var history = this.engine.history();
    var undoable = null;
    for (var i = history.length - 1; i >= 0; i--) {
      if (history[i].undoable) { undoable = history[i]; break; }
    }
    if (!undoable) {
      this.note('warn', 'nothing compensatable left to undo in this sample');
      this.renderAll();
      return;
    }
    try {
      /* Undo now runs the same pipeline as any write (authorization, epoch,
         idempotency, persist-before-ack) and compensates by APPENDING work, so
         the revision being undone stays readable afterwards. */
      var result = this.engine.undo(this.header('undo-' + undoable.commandId, {
        targetCommandId: undoable.commandId
      }));
      this.note('ok', 'compensated ' + undoable.commandType + ' with ' +
        result.compensationCommand + ' (history kept)');
      el('store-state').textContent = 'commitSeq ' + this.store.commitSeq();
      el('store-state').className = 'chip ok';
    } catch (e) {
      this.note('warn', 'undo refused: ' + e.code + ' — ' + e.message);
      if (e.code === 'STORE_FAULT_INJECTED') {
        el('store-state').textContent = 'NOT SAVED — ' + e.code;
        el('store-state').className = 'chip bad';
      }
    }
    this.conflict = null;
    this.renderAll();
  };

  App.prototype.armFault = function () {
    var on = el('chk-fault').checked;
    this.store.armFault(on ? { at: 'beforeWrite', times: 1 } : null);
    this.note('info', on
      ? 'next write will fail once (fault injection) — the durable value must not change'
      : 'fault injection off');
    this.renderAll();
  };

  App.prototype.resetEpoch = function () {
    this.dispatch('resetVaultEpoch', {}, 'epoch');
    this.note('info', 'vault epoch advanced; a session holding the old epoch can no longer write');
  };

  App.prototype.hardReset = function () {
    if (!window.confirm || window.confirm('这会丢弃当前存储里的资料库并重新播种一份演示数据。继续？')) {
      this.store.clear();
      this.readOnly = false;
      this.corruptInfo = null;
      this.engine = IW.createEngine({ vaultId: 'inkweft-sample-vault', persist: this.persistHook() });
      this.surface = IW.createSurface(IW.originFor(this.corpus.text), this.surfaceCtx());
      this.seed();
      this.selectedCardId = this.engine.getVault().cards[0].id;
      this.renderedCardId = this.selectedCardId;
      this.commentBinding = { cardId: this.selectedCardId, cardRevisionId: null };
      this.conflict = null;
      this.note('info', 'vault recreated from scratch');
      this.renderAll();
    }
  };

  App.prototype.exportPackage = function () {
    this.lastPackage = this.engine.exportPackage();
    var text = JSON.stringify(this.lastPackage, null, 2);
    el('package-out').textContent = text;
    this.note('ok', 'exported an S1 native package: ' + this.lastPackage.integrity.digest.slice(0, 16) + '…');
    /* offer it as a download too, so the recovery path is not hand-waving */
    try {
      var blob = new Blob([text], { type: 'application/json' });
      var a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'inkweft-s1-package.json';
      a.click();
      URL.revokeObjectURL(a.href);
    } catch (e) { /* download is a convenience, not the contract */ }
    this.renderAll();
  };

  App.prototype.reimportPackage = function () {
    if (!this.lastPackage) { this.note('warn', 'export a package first'); return; }
    try {
      var restored = IW.importPackage(this.lastPackage, {
        targetEpoch: this.engine.getVault().epoch,
        assetBytes: (function (o) { o[this.corpus.text.versionId] = this.corpus.text.text; return o; }).call(this, {})
      });
      var failed = restored.report.checks.filter(function (c) { return c.result !== 'PASS'; });
      /* Rebuild with the persist hook too, so the restored vault is not read-only
         by accident and further edits still commit before acknowledging. */
      this.engine = IW.createEngine({
        vaultId: restored.vault.vaultId, vault: restored.vault, state: restored.state,
        persist: this.persistHook()
      });
      this.sessionId = this.sessionIdFrom(restored.state);
      this.commandCounter = 0;
      this.surface = IW.createSurface(IW.originFor(this.corpus.text), this.surfaceCtx());
      this.selectedCardId = this.engine.getVault().cards.length ? this.engine.getVault().cards[0].id : null;
      this.renderedCardId = this.selectedCardId;
      this.commentBinding = { cardId: this.selectedCardId, cardRevisionId: null };
      this.note('ok', 'restored in isolation: ' + restored.report.checks.length + ' checks, ' +
        failed.length + ' failed, undo ' + (restored.report.restored.undoAvailableAfterRestore ? 'available' : 'unavailable'));
      this.lastRestoreReport = restored.report;
      this.renderAll();
    } catch (e) {
      this.note('warn', 'import refused: ' + e.code + ' — ' + e.message);
      this.renderAll();
    }
  };

  /* -------------------------------------------------------------- render */
  App.prototype.currentNode = function () {
    var cardId = this.selectedCardId;
    if (!cardId) return null;
    return this.engine.getVault().occurrences.filter(function (o) { return o.cardId === cardId; })[0] || null;
  };

  App.prototype.renderAll = function () {
    this.renderStatus();
    this.renderCards();
    this.renderDocument();
    this.renderCardPanel();
    this.renderStructure();
    this.renderHistory();
    this.renderLog();
    if (this.lastRestoreReport) this.renderRestoreReport();
  };

  App.prototype.renderStatus = function () {
    var v = this.engine.getVault();
    el('vault-id').textContent = v.vaultId;
    el('epoch').textContent = 'epoch ' + v.epoch;
    el('counts').textContent = v.cards.length + ' card / ' + v.blockRevisions.length + ' block revisions / ' +
      v.cardRevisions.length + ' card revisions / ' + v.occurrences.length + ' node';
    el('commit-log-count').textContent = this.store.commitSeq();
    el('card-count').textContent = v.cards.length + ' 张卡';
    if (this.readOnly) {
      el('store-state').textContent = '只读恢复模式 — ' + (this.corruptInfo ? this.corruptInfo.code : 'DAMAGED');
      el('store-state').className = 'chip bad';
    }
    var recover = el('recovery-bar');
    if (recover) {
      recover.style.display = this.readOnly ? 'flex' : 'none';
      if (this.readOnly && this.corruptInfo) {
        el('recovery-text').textContent =
          '存储里的资料库无法解析（' + this.corruptInfo.code + '，' + this.corruptInfo.rawBytes +
          ' 字节）。为避免覆盖原始字节，本会话不写入任何内容；要重新开始必须显式确认。';
      }
    }
    this.renderAssetStatus();
  };

  App.prototype.renderAssetStatus = function () {
    var v = this.engine.getVault();
    var pdfVersion = v.documentVersions.filter(function (x) { return x.kind === 'pdf-origin'; })[0];
    el('asset-state').textContent = this.assetMeta.pdfStatus === 'not checked'
      ? (pdfVersion ? 'hash recorded: ' + pdfVersion.sha256.slice(0, 16) + '… (bytes not re-read)' : 'not registered yet')
      : this.assetMeta.pdfStatus;
    el('asset-state').className = 'chip ' + (this.assetMeta.pdfSha256 ? 'ok' : '');
    var surface = IW.createSurface(IW.originFor(this.corpus.pdf), {
      docId: this.corpus.pdf.id,
      docIndex: function () { return v.documents; },
      versionIndex: function () { return v.documentVersions; },
      grants: this.engine.grants
    });
    var cap = surface.capabilityReport;
    el('pdf-capability').textContent = 'PDF surface: renders=' + cap.rendersPages +
      ', geometry=' + cap.textGeometry + (cap.engineAvailable ? '' : ' (' + cap.reason + ')');
    el('pdf-capability').className = 'chip warn';
  };

  App.prototype.renderCards = function () {
    var self = this;
    var select = el('card-select');
    select.innerHTML = this.engine.getVault().cards.map(function (c) {
      return '<option value="' + esc(c.id) + '"' + (c.id === self.selectedCardId ? ' selected' : '') + '>' +
        esc(IW.readCardHead(self.engine.getVault(), c.id).title || '(untitled)') +
        (c.trashedAt ? ' [trashed]' : '') + '</option>';
    }).join('') || '<option>(no cards)</option>';
  };

  /* Renders the document surface. Two things matter here:
       1. every line is a block with an explicit model offset, so a selection can
          be mapped back to the model instead of guessed from DOM positions;
       2. ALL accessible annotations are drawn — a plain highlighter has no card
          but still has to be visible, otherwise the mode is a lie. */
  App.prototype.renderDocument = function () {
    var self = this;
    var v = this.engine.getVault();
    var selectedCard = this.selectedCardId ? IW.readCardHead(v, this.selectedCardId) : null;
    var selectedAnchors = {};
    if (selectedCard) selectedCard.sourceIds.forEach(function (id) { selectedAnchors[id] = true; });

    /* annotation placements (excerpt marks and plain highlighters alike) */
    var annotations = v.annotationPlacements.map(function (p) {
      var anchor = IW.byId(v.sourceAnchors, p.anchorId);
      if (!anchor) return null;
      return { placement: p, anchor: anchor };
    }).filter(Boolean);

    var html = this.surface.listPageIds().map(function (pageId) {
      var page = self.surface.getTextPage(pageId);
      var marks = annotations.filter(function (m) { return m.anchor.pageId === pageId; });
      var markHtml = marks.map(function (m) {
        var isSelected = !!selectedAnchors[m.anchor.id];
        var kind = m.placement.cardId ? 'excerpt' : 'highlighter';
        return m.anchor.selector.rects.map(function (r) {
          return '<span class="mark ' + (isSelected ? 'selected' : '') + '" data-kind="' + kind +
            '" data-anchor="' + esc(m.anchor.id) + '" data-placement="' + esc(m.placement.id) + '" style="left:' +
            (r[0] * 100).toFixed(2) + '%;top:' + (r[1] * 100).toFixed(2) + '%;width:' +
            (r[2] * 100).toFixed(2) + '%;height:' + (r[3] * 100).toFixed(2) + '%"></span>';
        }).join('');
      }).join('');
      return '<section class="page" data-page="' + esc(pageId) + '">' + markHtml +
        '<div class="page-body">' + page.lines.map(function (l) {
          return '<div class="line" data-start="' + l.startOffset + '" data-end="' + l.endOffset + '">' +
            '<span class="line-body">' + esc(l.text) + '</span></div>';
        }).join('') + '</div>' +
        '<div class="page-label" data-decoration="1">' + esc(pageId.split(':p')[0]) +
        ' · page ' + (page.ordinal + 1) + '</div>' +
        '</section>';
    }).join('');

    el('doc-body').innerHTML = html;

    var anchorCount = v.sourceAnchors.length;
    var plainCount = v.annotationPlacements.filter(function (p) { return !p.cardId; }).length;
    el('doc-hint').textContent = this.markMode === 'excerpt'
      ? '摘录模式：选中文字 → 建这张卡（当前文档 ' + anchorCount + ' 个来源片段，' + annotations.length + ' 个已绘制标记）'
      : '普通荧光标记模式：选中文字 → 只留标记，不建卡（已有 ' + plainCount + ' 个普通标记）';
  };

  App.prototype.flashAnchor = function (anchorId) {
    this.activeAnchorId = anchorId;
    var node = el('doc-body').querySelector('[data-anchor="' + anchorId + '"]');
    if (node) {
      node.classList.add('flash');
      setTimeout(function () { node.classList.remove('flash'); }, 1200);
      if (node.scrollIntoView) node.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
    var anchor = IW.byId(this.engine.getVault().sourceAnchors, anchorId);
    if (anchor) {
      this.note('info', '回源 ' + anchor.pageId + ' · ' + anchor.selector.textQuote.exact.slice(0, 30));
    }
    this.renderStructure();
  };

  App.prototype.renderCardPanel = function () {
    var cardId = this.selectedCardId;
    if (!cardId) {
      el('card-title').textContent = '（没有卡片）';
      return;
    }
    var v = this.engine.getVault();
    var card = IW.byId(v.cards, cardId);
    var head = IW.readCardHead(v, cardId);
    var chain = IW.cardRevisionChain(v, cardId);
    var comment = head.blocks.filter(function (b) { return b.role === 'comment'; });
    var quote = head.blocks.filter(function (b) { return b.role === 'source_quote'; });

    el('card-title').textContent = head.title || '(无标题)';
    el('card-identity').textContent = 'cardId ' + cardId;
    el('card-head').textContent = 'headRevision ' + head.cardRevisionId + ' · rev ' + head.revision;
    el('quote').textContent = quote.map(function (b) { return b.payload.text; }).join('\n\n---\n\n');

    /* Bind the editor to (cardId, headRevision). The buffer is only preserved
       while the user is actually editing it; once focus leaves, the head wins.
       Stale buffer text silently surviving a rebind is how one card's note ends
       up looking like another's. */
    var bound = this.commentBinding;
    var headText = comment.map(function (b) { return b.payload.text || ''; }).join('\n');
    var boundChanged = !bound || bound.cardId !== cardId ||
      (bound.cardRevisionId !== head.cardRevisionId && this.commentEdited !== true);
    if (boundChanged) {
      if (!this.commentFocused) el('comment').value = headText;
      this.commentBinding = { cardId: cardId, cardRevisionId: head.cardRevisionId };
      this.commentEdited = false;
    } else if (!this.commentFocused && el('comment').value !== headText) {
      el('comment').value = headText;
      this.commentEdited = false;
      this.commentBinding = { cardId: cardId, cardRevisionId: head.cardRevisionId };
    }
    this.renderedCardId = cardId;

    var node = this.currentNode();
    el('node-info').textContent = node
      ? '节点宽度 ' + node.layoutOverride.width + 'px · map ' + node.mapId.slice(0, 8) + '… · siblingOrder ' + node.siblingOrder
      : '这张卡还没有脑图节点（摘录不会自动入图）';
    el('node-width').value = node ? String(node.layoutOverride.width) : '220';
    el('node-width').disabled = !node;

    var item = v.reviewItems.filter(function (r) { return r.cardId === cardId; })[0];
    var state = item ? v.reviewStates.filter(function (s) { return s.reviewItemId === item.id; })[0] : null;
    el('question').textContent = item ? item.promptSpec.question : '（还没有提问投影）';
    el('question-meta').textContent = item
      ? 'reviewItemId ' + item.id.slice(0, 8) + '… · promptRevision ' + item.promptRevision +
        ' · lifecycle ' + item.lifecycle + ' · scheduler ' + (state ? state.schedulerVersion : '-') +
        ' · dueAt ' + (state && state.statePayload.dueAt === null ? 'null（S1 不排程）' : '-')
      : '';
    el('answer-refs').textContent = item
      ? item.answerRefs.map(function (r) { return r.blockId.slice(0, 8) + '…'; }).join(' , ')
      : '—';

    el('revision-chain').innerHTML = chain.map(function (r, i) {
      return '<li' + (i === 0 ? ' class="current"' : '') + '><code>' + esc(r.id.slice(0, 8)) + '…</code> rev ' +
        r.revision + ' · ' + r.blockSnapshotIds.length + ' snapshots' +
        (i === 0 ? ' <span class="tag">HEAD</span>' : '') + '</li>';
    }).join('');

    el('conflict-bar').style.display = this.conflict ? 'flex' : 'none';
    if (this.conflict) {
      el('conflict-text').textContent = '另一视图已把这张卡推进到 rev ' + this.conflict.details.actual +
        '，本次写入基于 rev ' + this.conflict.details.expected + ' 被拒绝（EXPECTED_VERSION_MISMATCH）。';
    }
  };

  App.prototype.renderStructure = function () {
    var self = this;
    var v = this.engine.getVault();
    var cardId = this.selectedCardId;
    var card = cardId ? IW.byId(v.cards, cardId) : null;

    el('anchors').innerHTML = !card ? '' : card.sourceIds.map(function (id) {
      var a = IW.byId(v.sourceAnchors, id);
      if (!a) return '';
      var surface = self.surface;
      var state = surface.resolveSelector ? surface.resolveSelector(a) : { state: '?' };
      var active = a.id === self.activeAnchorId ? ' class="active"' : '';
      return '<li' + active + ' data-anchor="' + esc(a.id) + '"><code>' + esc(a.id.slice(0, 8)) + '…</code> ' +
        esc(a.pageId) + '<br><span class="tiny">' + esc(a.selector.textQuote.exact.slice(0, 34)) +
        '…</span><br><span class="tag ' + (state.state === 'EXACT_SYNTHETIC' ? 'ok' : 'warn') + '">' +
        esc(state.state) + '</span> <span class="tiny">' + esc(a.sourceVersion) + '</span></li>';
    }).join('') || '<li class="tiny">（无来源）</li>';

    el('occurrences').innerHTML = v.occurrences.filter(function (o) {
      return !cardId || o.cardId === cardId;
    }).map(function (o) {
      return '<li><code>' + esc(o.id.slice(0, 8)) + '…</code> map ' + esc(o.mapId.slice(0, 8)) +
        '… · x/y ' + o.layoutOverride.position.x + '/' + o.layoutOverride.position.y +
        ' · width ' + o.layoutOverride.width + '</li>';
    }).join('') || '<li class="tiny">（无节点）</li>';

    var rows = v.annotationPlacements.concat(v.marginPlacements);
    el('placements').innerHTML = rows.map(function (p) {
      var tag = p.cardId ? 'card ' + p.cardId.slice(0, 8) + '…' : '<span class="warn">no card (annotation only)</span>';
      return '<li><span class="tag ' + (p.cardId ? 'ok' : 'warn') + '">' + esc(p.style || 'margin') + '</span> ' +
        tag + '</li>';
    }).join('') || '<li class="tiny">（无标记）</li>';
  };

  App.prototype.renderHistory = function () {
    var self = this;
    var history = this.engine.history();
    el('history').innerHTML = history.slice().reverse().map(function (h) {
      var label = h.commandType === 'undo'
        ? 'undo → ' + (h.result && h.result.compensationCommand ? h.result.compensationCommand : '?')
        : h.commandType;
      return '<li class="' + (h.compensated ? 'done' : '') + '"><span class="tiny">' + esc(h.at) + '</span> ' +
        '<code>' + esc(label) + '</code>' +
        (h.undoable ? ' <button class="mini" data-undo="' + esc(h.commandId) + '">撤销</button>' : '') +
        (h.inverse ? '' : ' <span class="tag warn">不可补偿</span>') +
        '<br><span class="tiny">' + esc(h.commandId) + ' · digest ' + esc(h.digest.slice(0, 8)) + '…</span></li>';
    }).join('');
    Array.prototype.forEach.call(el('history').querySelectorAll('[data-undo]'), function (btn) {
      btn.addEventListener('click', function () { self.undoTarget(btn.getAttribute('data-undo')); });
    });
  };

  App.prototype.undoTarget = function (targetCommandId) {
    try {
      var result = this.engine.undo(this.header('undo-' + targetCommandId, {
        targetCommandId: targetCommandId
      }));
      this.note('ok', 'compensated with ' + result.compensationCommand + ' (the undone revision is still readable)');
      el('store-state').textContent = 'commitSeq ' + this.store.commitSeq();
      el('store-state').className = 'chip ok';
    } catch (e) {
      this.note('warn', 'undo refused: ' + e.code + ' — ' + e.message);
      if (e.code === 'STORE_FAULT_INJECTED') {
        el('store-state').textContent = 'NOT SAVED — ' + e.code;
        el('store-state').className = 'chip bad';
      }
    }
    this.renderAll();
  };

  App.prototype.renderLog = function () {
    el('log').innerHTML = this.log.map(function (l) {
      return '<li class="' + esc(l.kind) + '"><span class="tiny">' + esc(l.at) + '</span> ' + esc(l.text) + '</li>';
    }).join('');
  };

  App.prototype.renderRestoreReport = function () {
    var r = this.lastRestoreReport;
    el('restore-report').innerHTML =
      '<p class="tiny">integrity ' + esc(r.integrityDigest.slice(0, 20)) + '… · checks ' + r.checks.length +
      ' · warnings ' + r.warnings.length + '</p>' +
      '<ul class="checks">' + r.checks.map(function (c) {
        return '<li><span class="tag ' + (c.result === 'PASS' ? 'ok' : 'warn') + '">' + esc(c.result) + '</span> ' +
          esc(c.message) + '</li>';
      }).join('') + '</ul>' +
      '<ul class="checks">' + r.warnings.map(function (w) {
        return '<li><span class="tag warn">' + esc(w.code) + '</span> ' + esc(w.detail) + '</li>';
      }).join('') + '</ul>';
  };

  IW.App = App;

  IW.start = function () {
    var app = new App();
    globalThis.inkweft = app;
    app.boot();
    return app;
  };
}(globalThis.IW = globalThis.IW || {}));

/* bootstrap */
(function () {
  function go() { globalThis.IW.start(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go);
  else go();
}());
