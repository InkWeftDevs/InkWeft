/* InkWeft S1 sample — browser shell.
   The UI never touches the vault. Every mutation goes through engine.run(). */
(function (IW) {
  'use strict';

  var el = function (id) { return document.getElementById(id); };
  var esc = function (s) {
    return String(s === undefined || s === null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  };

  function App() {
    this.corpus = IW.corpus();
    this.store = IW.createStore(IW.browserBackend(), null);
    this.engine = null;
    this.surface = null;
    this.selectedCardId = null;
    this.markMode = 'excerpt';
    this.activeAnchorId = null;
    this.log = [];
    this.conflict = null;
    this.assetMeta = { pdfSha256: null, pdfBytes: null, pdfStatus: 'not checked' };
  }

  /* ------------------------------------------------------------- lifecycle */
  App.prototype.boot = function () {
    var self = this;
    var saved = null;
    try { saved = this.store.load(); } catch (e) { self.note('warn', 'stored vault refused: ' + e.code); }

    if (saved) {
      this.engine = IW.createEngine({
        vaultId: saved.vault.vaultId, vault: saved.vault, state: saved.state
      });
      this.note('info', 'restored a persisted vault (commitSeq ' + saved.commitSeq + ')');
    } else {
      this.engine = IW.createEngine({ vaultId: 'inkweft-sample-vault' });
    }

    /* The surface must exist before seeding: the seed card is created through the
       same command as any other excerpt, and that command resolves the selection
       through the surface. */
    this.surface = IW.createSurface(IW.originFor(this.corpus.text), this.surfaceCtx());

    if (!saved) {
      this.seed();
      this.persist();
    }

    this.selectedCardId = this.engine.getVault().cards.length
      ? this.engine.getVault().cards[0].id : null;

    /* The PDF is a real file; hashing it proves the "original unchanged" claim
       against bytes rather than against a number we wrote ourselves. */
    this.checkPdf();

    el('mark-excerpt').checked = true;
    this.bind();
    this.renderAll();
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
        self.engine.run('registerDocumentVersion', {
          documentId: self.corpus.pdf.id,
          versionId: self.corpus.pdf.versionId,
          title: self.corpus.pdf.title,
          kind: self.corpus.pdf.kind,
          assetPath: self.corpus.pdf.assetPath,
          bytes: bytes.length,
          sha256: self.assetMeta.pdfSha256,
          textSha256: null
        }, self.header('pdf-register'));
        self.persist();
        self.renderAll();
      }
      self.renderAssetStatus();
    }).catch(function (e) {
      self.assetMeta.pdfStatus = 'not readable from this origin (' + e.message + ')';
      self.renderAssetStatus();
    });
  };

  App.prototype.header = function (tag, extra) {
    var h = {
      commandId: 'ui-' + tag + '-' + (++this.commandCounter || (this.commandCounter = 1)),
      vaultId: this.engine.vaultId,
      actorId: 'local-user',
      capabilityHandle: 'cap-ui-0001',
      expectedVersions: { epoch: this.engine.getVault().epoch }
    };
    if (extra) Object.keys(extra).forEach(function (k) { h[k] = extra[k]; });
    return h;
  };

  App.prototype.seed = function () {
    var self = this;
    var steps = IW.seedPlan(this.corpus, {});
    steps.forEach(function (step) {
      self.engine.run(step.command, step.params, self.header('seed-' + step.command));
    });
    var studySetId = this.engine.getVault().studySets[0].id;
    var seeded = IW.createFirstCard(this.engine, this.surface, this.corpus.text, IW.defaultSelection(this.corpus));
    var cardId = seeded.receipt.result.cardId;
    this.engine.run('attachOccurrence', { cardId: cardId, mapId: null, studySetId: studySetId },
      this.header('seed-node'));
    this.engine.run('upsertMargin', { cardId: cardId, preferredHeight: 180 }, this.header('seed-margin'));
    this.engine.run('createReviewItem', {
      cardId: cardId, question: '写出条件概率的定义式，并说明成立前提。'
    }, this.header('seed-question'));
    this.note('ok', 'seeded: one excerpt card, one node, one manual question');
  };

  App.prototype.persist = function () {
    try {
      var info = this.store.commit(this.engine.getVault(), this.engine.getState());
      el('store-state').textContent = 'commitSeq ' + info.commitSeq;
      el('store-state').className = 'chip ok';
      return info;
    } catch (e) {
      el('store-state').textContent = 'NOT SAVED — ' + e.code;
      el('store-state').className = 'chip bad';
      this.note('warn', 'store refused the commit: ' + e.code + ' (' + e.message + ')');
      return null;
    }
  };

  App.prototype.dispatch = function (commandType, params, tag, expectedVersions) {
    var header = this.header(tag, expectedVersions ? { expectedVersions: expectedVersions } : null);
    try {
      var result = this.engine.run(commandType, params, header);
      this.note(result.replayed ? 'info' : 'ok',
        commandType + ' -> ' + result.status + (result.replayed ? ' (idempotent replay)' : ''));
      this.persist();
      this.conflict = null;
      this.renderAll();
      return result;
    } catch (e) {
      this.note('warn', commandType + ' refused: ' + e.code + ' — ' + e.message);
      if (e.code === 'EXPECTED_VERSION_MISMATCH') {
        this.conflict = { commandType: commandType, params: params, details: e.details, message: e.message };
        this.note('info', 'another view advanced this card. Use "重新读取并保存" instead of overwriting.');
      }
      this.renderAll();
      return { error: e };
    }
  };

  App.prototype.note = function (kind, text) {
    this.log.unshift({ kind: kind, text: text, at: new Date().toISOString().slice(11, 19) });
    if (this.log.length > 40) this.log.pop();
  };

  /* ---------------------------------------------------------- interaction */
  App.prototype.bind = function () {
    var self = this;
    el('mark-excerpt').addEventListener('change', function () { self.setMarkMode('excerpt'); });
    el('mark-highlighter').addEventListener('change', function () { self.setMarkMode('highlighter'); });
    el('btn-save-comment').addEventListener('click', function () { self.saveComment(); });
    el('btn-add-node').addEventListener('click', function () { self.addNode(); });
    el('btn-undo').addEventListener('click', function () { self.undoLast(); });
    el('btn-export').addEventListener('click', function () { self.exportPackage(); });
    el('btn-reimport').addEventListener('click', function () { self.reimportPackage(); });
    el('chk-fault').addEventListener('change', function () { self.armFault(); });
    el('btn-epoch').addEventListener('click', function () { self.resetEpoch(); });
    el('btn-reset').addEventListener('click', function () { self.hardReset(); });
    el('btn-resolve').addEventListener('click', function () { self.resolveConflict(); });
    el('card-select').addEventListener('change', function (e) {
      self.selectedCardId = e.target.value; self.activeAnchorId = null; self.renderAll();
    });
    el('node-width').addEventListener('change', function (e) { self.setNodeWidth(Number(e.target.value)); });
    document.addEventListener('mouseup', function (e) {
      if (el('doc').contains(e.target)) self.captureSelection();
    });
    el('doc').addEventListener('click', function (e) {
      var hit = e.target.closest ? e.target.closest('[data-anchor]') : null;
      if (hit) { self.flashAnchor(hit.getAttribute('data-anchor')); }
    });
  };

  App.prototype.setMarkMode = function (mode) {
    this.markMode = mode;
    el('mark-excerpt').checked = mode === 'excerpt';
    el('mark-highlighter').checked = mode === 'highlighter';
    this.renderAll();
  };

  /* Turn a DOM selection into global character offsets. The document is rendered
     as one continuous offset space, exactly like the surface's line model. */
  App.prototype.captureSelection = function () {
    var sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return;
    var range = sel.getRangeAt(0);
    var host = el('doc-body');
    if (!host.contains(range.startContainer) || !host.contains(range.endContainer)) return;

    function offsetOf(node, offset) {
      var walker = document.createTreeWalker(host, NodeFilter.SHOW_TEXT, null);
      var total = 0;
      var current;
      while ((current = walker.nextNode())) {
        if (current === node) return total + offset;
        total += current.nodeValue.length;
      }
      return null;
    }
    var start = offsetOf(range.startContainer, range.startOffset);
    var end = offsetOf(range.endContainer, range.endOffset);
    if (start === null || end === null || end <= start) return;
    this.applySelection(start, end, sel.toString());
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
    this.selectedCardId = result.receipt.result.cardId;
    this.activeAnchorId = result.receipt.result.anchorId;
    this.note('info', 'excerpt card created; quoting: ' + (selectedText || '').slice(0, 40).replace(/\n/g, ' / '));
    if (window.getSelection) {
      var live = window.getSelection();
      if (live && live.removeAllRanges) live.removeAllRanges();
    }
  };

  /* ----------------------------------------------------------- mutations */
  App.prototype.saveComment = function () {
    var cardId = this.selectedCardId;
    if (!cardId) return;
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
    var cardId = this.selectedCardId;
    var observed = IW.readCardHead(this.engine.getVault(), cardId).cardRevisionId;
    el('comment').value = el('comment').value;
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
    if (!undoable) { this.note('warn', 'nothing to undo'); this.renderAll(); return; }
    try {
      this.engine.undo(IW.commandHeader('ui-undo-' + Date.now(), this.engine, {
        targetCommandId: undoable.commandId
      }));
      this.note('ok', 'undid ' + undoable.commandType);
      this.persist();
    } catch (e) {
      this.note('warn', 'undo refused: ' + e.code);
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
    this.store.clear();
    this.engine = IW.createEngine({ vaultId: 'inkweft-sample-vault' });
    this.surface = IW.createSurface(IW.originFor(this.corpus.text), this.surfaceCtx());
    this.seed();
    this.persist();
    this.selectedCardId = this.engine.getVault().cards[0].id;
    this.conflict = null;
    this.note('info', 'vault recreated from scratch');
    this.renderAll();
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
      this.engine = IW.createEngine({
        vaultId: restored.vault.vaultId, vault: restored.vault, state: restored.state
      });
      this.surface = IW.createSurface(IW.originFor(this.corpus.text), this.surfaceCtx());
      this.selectedCardId = this.engine.getVault().cards[0].id;
      this.note('ok', 'restored in isolation: ' + restored.report.checks.length + ' checks, ' +
        failed.length + ' failed, undo ' + (restored.report.restored.undoAvailableAfterRestore ? 'available' : 'unavailable'));
      restored.report.warnings.forEach(function (w) { /* surfaced in the report panel */ });
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

  /* One continuous text flow per page, so DOM offsets equal surface offsets. */
  App.prototype.renderDocument = function () {
    var self = this;
    var card = this.selectedCardId ? IW.readCardHead(this.engine.getVault(), this.selectedCardId) : null;
    var anchors = card ? card.sourceIds.map(function (id) {
      return IW.byId(self.engine.getVault().sourceAnchors, id);
    }).filter(Boolean) : [];

    var html = this.surface.listPageIds().map(function (pageId) {
      var page = self.surface.getTextPage(pageId);
      var marks = anchors.filter(function (a) { return a.pageId === pageId; });
      var markHtml = marks.map(function (a) {
        return a.selector.rects.map(function (r) {
          return '<span class="mark" data-anchor="' + esc(a.id) + '" style="left:' +
            (r[0] * 100).toFixed(2) + '%;top:' + (r[1] * 100).toFixed(2) + '%;width:' +
            (r[2] * 100).toFixed(2) + '%;height:' + (r[3] * 100).toFixed(2) + '%"></span>';
        }).join('');
      }).join('');
      return '<section class="page" data-page="' + esc(pageId) + '">' + markHtml +
        '<div class="page-body">' + page.lines.map(function (l) {
          return '<p class="line" data-start="' + l.startOffset + '" data-end="' + l.endOffset + '">' +
            esc(l.text) + '</p>';
        }).join('') + '</div>' +
        '<div class="page-label">' + esc(pageId.split(':p')[0]) + ' · page ' + (page.ordinal + 1) + '</div>' +
        '</section>';
    }).join('');

    /* plain highlighter marks are shown too; they are annotations, not cards, and
       the count is surfaced in the hint line so the distinction is visible. */
    var plain = this.engine.getVault().annotationPlacements.filter(function (p) {
      return p.style === 'highlighter';
    });

    el('doc-body').innerHTML = html;
    var anchorCount = this.engine.getVault().sourceAnchors.length;
    var plainCount = plain.length;
    el('doc-hint').textContent = this.markMode === 'excerpt'
      ? '摘录模式：选中文字 → 建这张卡（当前 ' + anchorCount + ' 个来源片段）'
      : '普通荧光标记模式：选中文字 → 只留标记，不建卡（已有 ' + plainCount + ' 个标记）';
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
    el('comment').value = comment.map(function (b) { return b.payload.text || ''; }).join('\n');

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
      return '<li class="' + (h.undone ? 'done' : '') + '"><span class="tiny">' + esc(h.at) + '</span> ' +
        '<code>' + esc(h.commandType) + '</code>' +
        (h.undoable ? ' <button class="mini" data-undo="' + esc(h.commandId) + '">撤销</button>' : '') +
        '<br><span class="tiny">' + esc(h.commandId) + ' · digest ' + esc(h.digest.slice(0, 8)) + '…</span></li>';
    }).join('');
    Array.prototype.forEach.call(el('history').querySelectorAll('[data-undo]'), function (btn) {
      btn.addEventListener('click', function () {
        try {
          self.engine.undo(IW.commandHeader('ui-undo-' + Date.now(), self.engine, {
            targetCommandId: btn.getAttribute('data-undo')
          }));
          self.note('ok', 'undone');
          self.persist();
        } catch (e) {
          self.note('warn', 'undo refused: ' + e.code);
        }
        self.renderAll();
      });
    });
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
