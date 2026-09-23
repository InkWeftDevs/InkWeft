/* InkWeft S1 sample — DocumentSurfaceAdapter (interface I00) and its concrete
   surfaces.

   The plan fixes this interface before K00/K01 start, precisely so K02 cannot be
   written against two guessed backends. Two honest implementations ship here:

     TextSurface     real, full text geometry over a plain-text origin
     PdfSurface      detects whether a real PDF engine is present and, because
                     none can be vendored offline (no network, no third-party
                     code allowed), reports PDF_ENGINE_MISSING instead of
                     pretending to render.

   Both refuse to answer for geometry they do not actually have, which is the
   point of the `resolutionState` vocabulary. */
(function (IW) {
  'use strict';

  var fail = IW.fail;
  var canonical = IW.canonical;

  var LOCATION_STATES = ['EXACT', 'EXACT_SYNTHETIC', 'STALE', 'CANDIDATE', 'MISSING', 'RESTRICTED'];

  /* --------------------------------------------------------------- paging */
  /* Fixed logical page height only so scrolling and regions have a denominator.
     It is declared synthetic; no view may treat it as a PDF MediaBox.
     Kept small on purpose: the demo corpus then spans more than one logical page,
     so the cross-page selection path is actually exercised instead of assumed. */
  var LINES_PER_PAGE = 4;
  var SYNTHETIC_PAGE = { width: 595, height: 842 };

  function sha256HexSync(input) {
    /* Real SHA-256, implemented locally: the sample may not pull a dependency and
       the browser's crypto.subtle is async-only. Accepts a string (hashed as
       UTF-8) or a byte array, so PDF bytes can be hashed without a lossy
       string round-trip. Used to fingerprint document assets and the exported
       package. */
    var K = [
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ];
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];

    var bytes = [];
    var i, c;
    if (typeof input !== 'string') {
      var view = input instanceof Uint8Array ? input : Uint8Array.from(input);
      for (i = 0; i < view.length; i++) bytes.push(view[i] & 255);
    } else {
      for (i = 0; i < input.length; i++) {
        c = input.charCodeAt(i);
        if (c < 0x80) bytes.push(c);
        else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c >= 0xd800 && c <= 0xdbff && i + 1 < input.length) {
          var lo = input.charCodeAt(i + 1);
          var cp = 0x10000 + ((c - 0xd800) << 10) + (lo - 0xdc00);
          i++;
          bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
        } else {
          bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        }
      }
    }
    var bitLen = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    for (i = 7; i >= 0; i--) bytes.push((bitLen / Math.pow(2, i * 8)) & 255);

    var w = new Array(64);
    for (var off = 0; off < bytes.length; off += 64) {
      for (i = 0; i < 16; i++) {
        w[i] = (bytes[off + i * 4] << 24) | (bytes[off + i * 4 + 1] << 16) |
               (bytes[off + i * 4 + 2] << 8) | bytes[off + i * 4 + 3];
      }
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
        var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], cc = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (i = 0; i < 64; i++) {
        var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        var ch = (e & f) ^ (~e & g);
        var t1 = (h + S1 + ch + K[i] + w[i]) | 0;
        var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        var maj = (a & b) ^ (a & cc) ^ (b & cc);
        var t2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0;
        d = cc; cc = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + cc) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    return H.map(function (x) {
      return ('00000000' + (x >>> 0).toString(16)).slice(-8);
    }).join('');
  }

  function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

  /* ------------------------------------------------------- text page model */
  /* Pages share ONE continuous offset space: page 1's first line starts right
     after page 0's last line. That is what lets a single (start,end) pair be
     asked of every page and produce a genuine per-page split. Numbering offsets
     per page would make every cross-page query silently empty. */
  function buildPages(versionId, lines, textRevision) {
    var pages = [];
    var cursor = 0;
    var pageCount = Math.max(1, Math.ceil(lines.length / LINES_PER_PAGE));
    for (var p = 0; p < pageCount; p++) {
      pages.push({
        pageId: versionId + ':p' + p,
        ordinal: p,
        width: SYNTHETIC_PAGE.width,
        height: SYNTHETIC_PAGE.height,
        geometryKind: 'synthetic_text_flow_not_pdf',
        text: '',
        lines: [],
        extraction: { source: 'TEXT_FLOW', engine: 'TextSurface', revision: textRevision }
      });
    }
    lines.forEach(function (line, i) {
      var page = pages[Math.floor(i / LINES_PER_PAGE)];
      var lineNo = i % LINES_PER_PAGE;
      var start = cursor;
      cursor += line.length + 1;
      var prev = page.lines[page.lines.length - 1];
      var rect = prev
        ? [0.08, prev.rect[1] + prev.rect[3] + 0.02, 0.84, 0.038]
        : [0.08, 0.08, 0.84, 0.038];
      page.lines.push({
        id: versionId + ':p' + page.ordinal + ':l' + lineNo,
        text: line,
        startOffset: start,
        endOffset: start + line.length,
        rect: rect
      });
      page.text = page.lines.map(function (l) { return l.text; }).join('\n');
    });
    return pages;
  }

  function selectorRect(page, selector) {
    if (!selector.lines || !selector.lines.length) return null;
    var boxes = selector.lines.map(function (id) {
      for (var i = 0; i < page.lines.length; i++) if (page.lines[i].id === id) return page.lines[i].rect;
      return null;
    }).filter(Boolean);
    if (!boxes.length) return null;
    var x0 = Math.min.apply(null, boxes.map(function (b) { return b[0]; }));
    var y0 = Math.min.apply(null, boxes.map(function (b) { return b[1]; }));
    var x1 = Math.max.apply(null, boxes.map(function (b) { return b[0] + b[2]; }));
    var y1 = Math.max.apply(null, boxes.map(function (b) { return b[1] + b[3]; }));
    return [x0, y0, x1 - x0, y1 - y0];
  }

  /* Same wildcard rule as the command layer: a version-keyed grant may deny a
     specific source, and the '*' entry is the local default. */
  function grantedVersion(grants, version) {
    if (Object.prototype.hasOwnProperty.call(grants, version)) return !!grants[version];
    return !!grants['*'];
  }

  /* ------------------------------------------------------------ TextSurface */
  function createTextSurface(origin, ctx) {
    /* ctx = { docId, versionId, title, text, capabilityHandle, grants } */
    var lines = String(origin.text).split('\n');
    var pages = buildPages(origin.versionId, lines, 't1');

    return {
      adapterId: 'TextSurface/1',
      kind: 'TEXT_FLOW',
      assetPath: origin.assetPath,
      assetBytes: origin.bytes,
      assetSha256: sha256HexSync(origin.text),
      textSha256: sha256HexSync(origin.text),
      capabilityReport: {
        rendersPages: true,
        textGeometry: 'SYNTHETIC_LINE_BOXES',
        stablePageIds: true,
        rotation: 'NOT_IMPLEMENTED',
        cropBox: 'NOT_IMPLEMENTED',
        exportPresentation: 'UNSUPPORTED',
        notes: [
          'Line boxes are synthetic and identical in shape to the real extractor, ' +
          'so the UI and anchor code paths are exercised, but they are not PDF geometry.',
          'CJK text is stored as text flow; no font embedding is involved.'
        ]
      },

      openImmutable: function () {
        return {
          documentId: ctx.docId,
          versionId: origin.versionId,
          kind: 'text-flow-origin',
          assetPath: origin.assetPath,
          bytes: origin.bytes,
          sha256: this.assetSha256,
          textSha256: this.textSha256,
          pages: pages.map(function (p) {
            return { id: p.pageId, ordinal: p.ordinal, width: p.width, height: p.height, geometryKind: p.geometryKind };
          }),
          capability: this.capabilityReport
        };
      },

      getTextPage: function (pageId) {
        var page = null;
        for (var i = 0; i < pages.length; i++) if (pages[i].pageId === pageId) page = pages[i];
        if (!page) fail('PAGE_NOT_FOUND', 'no page ' + pageId);
        return {
          pageId: page.pageId,
          ordinal: page.ordinal,
          text: page.text,
          lines: IW.clone(page.lines),
          extraction: page.extraction
        };
      },

      /* Offsets are Unicode code-unit indices into the PAGE text; the plan asks
         that scalar-vs-UTF16 be explicit, and this surface declares UTF-16.
         Because page line ranges come from one continuous offset space, the same
         (start,end) pair can be asked of every page and the empty answers drop out
         — which is how a cross-page selection splits per page. */
      listPageIds: function () {
        return pages.map(function (p) { return p.pageId; });
      },

      offsetsToLines: function (pageId, startOffset, endOffset) {
        var page = null;
        for (var i = 0; i < pages.length; i++) if (pages[i].pageId === pageId) page = pages[i];
        if (!page) return [];
        return page.lines.filter(function (l) {
          return l.endOffset > startOffset && l.startOffset < endOffset;
        });
      },

      toCanonical: function (point, viewport) {
        var vp = viewport || { width: SYNTHETIC_PAGE.width, height: SYNTHETIC_PAGE.height, scale: 1, rotation: 0 };
        return { x: point.x / vp.scale, y: point.y / vp.scale, axis: 'canonical-top-left' };
      },

      fromCanonical: function (point, viewport) {
        var vp = viewport || { width: SYNTHETIC_PAGE.width, height: SYNTHETIC_PAGE.height, scale: 1, rotation: 0 };
        return { x: point.x * vp.scale, y: point.y * vp.scale, axis: 'screen' };
      },

      resolveSelector: function (anchor) {
        if (!grantedVersion(ctx.grants, anchor.sourceVersion)) {
          return { state: 'RESTRICTED', detail: 'source version is not readable under the current grant' };
        }
        if (!IW.byId(ctx.versionIndex(), anchor.sourceVersion)) {
          return { state: 'MISSING', detail: 'recorded source version is no longer retained' };
        }
        var doc = IW.byId(ctx.docIndex(), anchor.documentId);
        if (doc && doc.headVersionId !== anchor.sourceVersion) {
          /* Checked before any page lookup: after a version swap the old page
             identity may not exist any more, and STALE is the honest answer. */
          return { state: 'STALE', detail: 'document head moved to another version; old anchor kept as-is' };
        }
        var page = null;
        for (var i = 0; i < pages.length; i++) if (pages[i].pageId === anchor.pageId) page = pages[i];
        if (!page) return { state: 'MISSING', detail: 'page no longer resolves' };
        var flat = page.lines.map(function (l) { return l.text; }).join('\n');
        var exact = anchor.selector.textQuote ? anchor.selector.textQuote.exact : null;
        if (exact !== null && flat.indexOf(exact) === -1) {
          return { state: 'STALE', detail: 'quoted text is no longer present in this page' };
        }
        return {
          state: 'EXACT_SYNTHETIC',
          detail: 'resolved against a synthetic text-flow page, not real PDF geometry',
          rect: selectorRect(page, anchor.selector)
        };
      },

      exportPresentation: function () {
        return {
          state: 'UNSUPPORTED',
          code: 'EXPORT_ENGINE_NOT_IMPLEMENTED',
          detail: 'S1 does not ship a PDF writer; the plan keeps export UNSUPPORTED rather than ' +
                  'shipping a screenshot as if it were faithful.'
        };
      }
    };
  }

  /* ------------------------------------------------------------- PdfSurface */
  /* A real PDF origin is shipped in assets/. Rendering it needs a PDF engine,
     which cannot be vendored here (no network access, no third-party code). The
     adapter says so and never fabricates text or boxes. */
  function createPdfSurface(origin, ctx) {
    var engine = IW.pdfEngineProbe ? IW.pdfEngineProbe() : { available: false, reason: 'NO_PROBE' };
    return {
      adapterId: 'PdfSurface/1',
      kind: 'PDF',
      assetPath: origin.assetPath,
      assetBytes: origin.bytes,
      assetSha256: origin.sha256,
      capabilityReport: {
        provider: engine.name,
        engineAvailable: engine.available,
        reason: engine.reason,
        rendersPages: false,
        textGeometry: 'UNAVAILABLE',
        exportPresentation: 'UNSUPPORTED',
        notes: engine.available
          ? ['Engine present.']
          : [
            'No PDF engine is bundled: this sample must run offline with zero third-party code.',
            'The PDF file itself is real (assets/' + origin.assetPath + '), so the hash check below is genuine.',
            'Drop a local PDF.js build in vendor/ to enable rendering; until then geometry queries fail loudly.'
          ]
      },

      openImmutable: function () {
        return {
          documentId: ctx.docId,
          versionId: origin.versionId,
          kind: 'real-pdf-origin',
          assetPath: origin.assetPath,
          bytes: origin.bytes,
          sha256: origin.sha256,
          pages: [],
          capability: this.capabilityReport
        };
      },

      getTextPage: function () {
        fail('PDF_ENGINE_MISSING', 'no PDF engine is available in this build', {
          adapterId: this.adapterId, reason: engine.reason
        });
      },

      /* No pages can be listed without an engine: returning an empty list is the
         honest answer, and selection commands then fail with SURFACE_REQUIRED or
         PAGE_NOT_FOUND rather than inventing a page. */
      listPageIds: function () { return []; },

      toCanonical: function () { fail('PDF_ENGINE_MISSING', 'no PDF engine is available in this build'); },
      fromCanonical: function () { fail('PDF_ENGINE_MISSING', 'no PDF engine is available in this build'); },

      resolveSelector: function (anchor) {
        if (!grantedVersion(ctx.grants, anchor.sourceVersion)) {
          return { state: 'RESTRICTED', detail: 'source version is not readable under the current grant' };
        }
        return {
          state: 'MISSING',
          code: 'PDF_ENGINE_MISSING',
          detail: 'cannot resolve geometry without a PDF engine; refusing to guess a page'
        };
      },

      exportPresentation: function () {
        return { state: 'UNSUPPORTED', code: 'EXPORT_ENGINE_NOT_IMPLEMENTED', detail: 'S1 ships no PDF writer.' };
      }
    };
  }

  /* Deterministic adapter selection per origin kind. */
  function createSurface(origin, ctx) {
    if (origin.kind === 'pdf-origin') return createPdfSurface(origin, ctx);
    return createTextSurface(origin, ctx);
  }

  IW.LOCATION_STATES = LOCATION_STATES;
  IW.LINES_PER_PAGE = LINES_PER_PAGE;
  IW.sha256Hex = sha256HexSync;
  IW.createSurface = createSurface;
  IW.canonicalJson = canonical;
}(globalThis.IW = globalThis.IW || {}));
