/* InkWeft S1 sample — core primitives.
   No bundler, no dependencies: each file is an IIFE appending to the single
   global `IW`, so the same source runs in the browser and under `node` tests. */
(function (IW) {
  'use strict';

  /* ---------------------------------------------------------------- identity */
  var HEX = '0123456789abcdef';

  function bytesToUuid(b) {
    var s = '';
    for (var i = 0; i < 16; i++) s += HEX[(b[i] >> 4) & 15] + HEX[b[i] & 15];
    return s.slice(0, 8) + '-' + s.slice(8, 12) + '-5' + s.slice(13, 16) + '-' +
      '8' + s.slice(17, 20) + '-' + s.slice(20);
  }

  /* Deterministic UUIDv5-shaped ids so a seeded run is reproducible; the shape is
     what the plan's fixture contract checks, not cryptographic unguessability.
     `reserve(rows)` advances the counter past anything already in the vault: a
     reloaded engine must not mint an id that collides with a restored one, which
     is exactly the bug a persisted-then-replayed command hits. */
  function createIds(seed) {
    var n = 0;

    function candidate(i) {
      var out = [];
      for (var k = 0; k < 4; k++) {
        var h = 2166136261 >>> 0;
        var text = String(seed) + ':' + i + ':' + k;
        for (var j = 0; j < text.length; j++) {
          h ^= text.charCodeAt(j);
          h = Math.imul(h, 16777619) >>> 0;
        }
        out.push(h);
      }
      var bytes = [];
      out.forEach(function (h) {
        for (var m = 0; m < 4; m++) bytes.push((h >>> (m * 8)) & 255);
      });
      return bytesToUuid(bytes);
    }

    return {
      next: function () {
        var id = candidate(n);
        n += 1;
        return id;
      },
      peek: function () { return candidate(n); },
      reserve: function (rows) {
        var used = {};
        (rows || []).forEach(function (row) {
          if (row) {
            if (typeof row.id === 'string') used[row.id] = true;
            if (typeof row.reviewItemId === 'string') used[row.reviewItemId] = true;
          }
        });
        for (var i = 0; i < 4096; i++) {
          if (!used[candidate(n)]) return n;
          n += 1;
        }
        return n;
      },
      counter: function () { return n; },
      setCounter: function (value) { n = value; }
    };
  }

  /* ------------------------------------------------------------------ digest */
  /* Stable canonical JSON: object keys sorted, no whitespace, arrays in order. */
  function canonical(value) {
    if (value === null || typeof value !== 'object') return JSON.stringify(value);
    if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
    var keys = Object.keys(value).sort();
    return '{' + keys.map(function (k) {
      return JSON.stringify(k) + ':' + canonical(value[k]);
    }).join(',') + '}';
  }

  function fnv1a(text) {
    var h = 2166136261 >>> 0;
    for (var i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 16777619) >>> 0;
    }
    return ('00000000' + h.toString(16)).slice(-8);
  }

  /* Two independent 32-bit passes => 16 hex chars. Good enough to detect payload
     changes for idempotency; the plan does not ask this to be a signature. */
  function semanticDigest(parts) {
    var a = fnv1a('a|' + canonical(parts));
    var b = fnv1a('b|' + canonical(parts));
    return a + b;
  }

  /* ------------------------------------------------------------------- clock */
  function createClock(startMs, stepMs) {
    var t = startMs;
    return {
      now: function () { var v = t; t += stepMs; return v; },
      iso: function () { return new Date(this.now()).toISOString().replace(/\.\d{3}Z$/, 'Z'); }
    };
  }

  /* ------------------------------------------------------------------ errors */
  /* Every failure carries a stable machine code: the UI, the tests and the
     export report all branch on `code`, never on human prose. */
  function CommandError(code, message, details) {
    this.name = 'CommandError';
    this.code = code;
    this.message = message || code;
    this.details = details || null;
  }
  CommandError.prototype = Object.create(Error.prototype);
  CommandError.prototype.constructor = CommandError;

  function fail(code, message, details) {
    throw new CommandError(code, message, details);
  }

  IW.ids = createIds;
  IW.clock = createClock;
  IW.canonical = canonical;
  IW.semanticDigest = semanticDigest;
  IW.CommandError = CommandError;
  IW.fail = fail;
}(globalThis.IW = globalThis.IW || {}));
