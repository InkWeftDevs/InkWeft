/* InkWeft S1 sample — the demo corpus and its first card.

   Everything here goes through IW.createEngine(...).run(...): the bootstrap is a
   normal command sequence, not a privileged seed path. That matters because the
   sample's whole claim is "there is one write entry point". */
(function (IW) {
  'use strict';

  var fail = IW.fail;

  /* Origin A — plain-text excerpt shipped with the plan package (fixtures/textbook-excerpt.txt).
     Short on purpose: with 4 lines per logical page (see 20-document.js) this
     spans three pages, so page-local selection and cross-page selection are both
     exercised by the same corpus.
     Offsets are UTF-16 code units into the joined text, which is what the
     TextSurface declares. */
  var TEXT_EXCERPT = {
    docId: 'textbook-excerpt',
    versionId: 'textbook-excerpt@v1',
    title: '概率原文（教材节选）',
    assetPath: 'assets/textbook-excerpt.txt',
    kind: 'text-flow-origin',
    text: [
      '第 3 章 条件概率',
      '当 P(B)>0 时，条件概率定义为 P(A|B)=P(A∩B)/P(B)。',
      '若 P(B)=0，则该公式无定义，需要单独讨论。',
      '应用前先核对分母，是本章最容易失分的一步。',
      '乘法公式：P(A∩B)=P(B)·P(A|B)，要求 P(B)>0。',
      '全概率公式把样本空间按 B 与其余集划分后再求和。',
      '贝叶斯公式把先验概率与似然结合起来得到后验概率。'
    ].join('\n'),
    bytes: null
  };

  /* Origin B — a real PDF shipped in assets/. Its bytes are hashed at runtime, so
     the "original hash did not change" claim is checked against real bytes. */
  var PDF_EXCERPT = {
    docId: 'conditional-probability-note',
    versionId: 'conditional-probability-note@v1',
    title: 'Conditional Probability (PDF)',
    assetPath: 'assets/conditional-probability-note.pdf',
    kind: 'pdf-origin',
    bytes: null,
    sha256: null
  };

  function corpus() {
    return {
      text: {
        id: TEXT_EXCERPT.docId, versionId: TEXT_EXCERPT.versionId, title: TEXT_EXCERPT.title,
        kind: TEXT_EXCERPT.kind, assetPath: TEXT_EXCERPT.assetPath, text: TEXT_EXCERPT.text
      },
      pdf: {
        id: PDF_EXCERPT.docId, versionId: PDF_EXCERPT.versionId, title: PDF_EXCERPT.title,
        kind: PDF_EXCERPT.kind, assetPath: PDF_EXCERPT.assetPath,
        sha256: PDF_EXCERPT.sha256, bytes: PDF_EXCERPT.bytes
      }
    };
  }

  /* Build the origin record the engine/surfaces need, filling in the text hash.
     `bytes` is only supplied for origins whose asset was actually read. */
  function originFor(corpusItem) {
    var text = corpusItem.text || '';
    return {
      docId: corpusItem.id,
      versionId: corpusItem.versionId,
      title: corpusItem.title,
      kind: corpusItem.kind,
      assetPath: corpusItem.assetPath,
      text: text,
      bytes: corpusItem.bytes,
      sha256: corpusItem.sha256 || (text ? IW.sha256Hex(text) : null)
    };
  }

  function header(commandId, engine, extra) {
    var h = {
      commandId: commandId,
      vaultId: engine.vaultId,
      actorId: 'local-user',
      capabilityHandle: 'cap-local-0001',
      expectedVersions: { epoch: 0 }
    };
    if (extra) Object.keys(extra).forEach(function (k) { h[k] = extra[k]; });
    return h;
  }

  /* The one place a card is born in the sample: a plain command call.
     The engine resolves the selection into per-page anchors through the surface,
     so this function never hand-builds a rectangle. */
  function createFirstCard(engine, surface, corpusItem, selection) {
    return engine.run('createCardFromSelection', {
      studySetId: engine.getVault().studySets[0].id,
      documentId: corpusItem.id,
      pageId: corpusItem.versionId + ':p0',
      sourceVersion: corpusItem.versionId,
      startOffset: selection.startOffset,
      endOffset: selection.endOffset,
      surface: surface,
      title: selection.title || '',
      comment: ''
    }, header('seed-0001-card', engine));
  }

  /* bootstrap -> register both origins -> first card -> one node -> one question.
     Exactly the S1 slice; anything else is refused by scope, not by accident. */
  function seedPlan(corpusData, assetMeta) {
    var meta = assetMeta || {};
    var steps = [];

    steps.push({ command: 'bootstrap', params: { title: '概率基础' } });

    steps.push({
      command: 'registerDocumentVersion',
      params: {
        documentId: corpusData.text.id,
        versionId: corpusData.text.versionId,
        title: corpusData.text.title,
        kind: corpusData.text.kind,
        assetPath: corpusData.text.assetPath,
        bytes: corpusData.text.text.length,
        sha256: IW.sha256Hex(corpusData.text.text),
        textSha256: IW.sha256Hex(corpusData.text.text)
      }
    });
    if (meta.pdfSha256) {
      steps.push({
        command: 'registerDocumentVersion',
        params: {
          documentId: corpusData.pdf.id,
          versionId: corpusData.pdf.versionId,
          title: corpusData.pdf.title,
          kind: corpusData.pdf.kind,
          assetPath: corpusData.pdf.assetPath,
          bytes: meta.pdfBytes,
          sha256: meta.pdfSha256,
          textSha256: null
        }
      });
    }

    return steps;
  }

  /* Selection used to seed the first card. Deliberately spans a logical page
     break (the "题目正文…" line lives on the next page), because that is the
     common real case — a heading or caveat attached to the line before it — and
     it exercises the one-anchor-per-page path on every launch. */
  function defaultSelection(corpusData) {
    var text = corpusData.text.text;
    var at = text.indexOf('应用前先核对分母');
    if (at < 0) fail('CORPUS_MARKER_MISSING', 'the demo corpus changed; cannot locate the seed selection');
    var end = text.indexOf('，要求 P(B)>0。', at);
    if (end < 0) fail('CORPUS_MARKER_MISSING', 'the demo corpus changed; cannot locate the seed selection end');
    return { startOffset: at, endOffset: end + '，要求 P(B)>0。'.length };
  }

  IW.corpus = corpus;
  IW.originFor = originFor;
  IW.commandHeader = header;
  IW.seedPlan = seedPlan;
  IW.defaultSelection = defaultSelection;
  IW.createFirstCard = createFirstCard;
}(globalThis.IW = globalThis.IW || {}));
