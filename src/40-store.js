/* InkWeft S1 sample — persistence with an honest durability story.

   The plan explicitly refuses to dress a browser store up as a native WAL/fsync
   (docs/02 §5). So this module states exactly what it does:

     - one synchronous whole-document write of a single key
     - a `commitSeq` that only advances after the write returns
     - a checksum so a torn/garbled value is detected on load instead of being
       silently half-applied
     - an explicit fault hook so the atomicity claim can be attacked on purpose

   `commit()` throws before touching the value if the fault hook fires, which is
   what makes "either nothing is published or the whole reference closure is"
   testable in this sample. */
(function (IW) {
  'use strict';

  var fail = IW.fail;
  var KEY = 'inkweft.s1.vault';
  var SCHEMA = 'InkWeft.S1Store/0.1';

  function encode(doc) {
    var payload = { schema: SCHEMA, vault: doc.vault, state: doc.state };
    /* Payload is persisted as the CANONICAL string, not the object. Storing the
       object and re-canonicalising on load looked equivalent but was not: writes
       keep insertion order while `canonical` sorts keys, so the recomputed
       checksum never matched and every reload failed. */
    var body = IW.canonical(payload);
    return JSON.stringify({
      schema: SCHEMA,
      commitSeq: doc.commitSeq,
      checksum: IW.sha256Hex(body),
      payload: body
    });
  }

  function decode(text) {
    if (text === null || text === undefined || text === '') return null;
    var outer;
    try { outer = JSON.parse(text); } catch (e) { fail('STORE_CORRUPT', 'stored value is not JSON'); }
    if (!outer || outer.schema !== SCHEMA) fail('STORE_SCHEMA_MISMATCH', 'stored value has an unknown schema');
    if (typeof outer.payload !== 'string') fail('STORE_CORRUPT', 'stored payload is not a canonical string');
    if (IW.sha256Hex(outer.payload) !== outer.checksum) {
      fail('STORE_CHECKSUM_MISMATCH', 'stored value failed its checksum; refusing to load a torn commit');
    }
    var payload;
    try { payload = JSON.parse(outer.payload); } catch (e) { fail('STORE_CORRUPT', 'stored payload is not JSON'); }
    return { vault: payload.vault, state: payload.state, commitSeq: outer.commitSeq };
  }

  function memoryBackend() {
    var cell = null;
    return {
      kind: 'memory',
      read: function () { return cell; },
      write: function (text) { cell = text; },
      clear: function () { cell = null; },
      describe: function () { return 'in-process memory cell (tests, no durability claim)'; }
    };
  }

  function localBackend(storage, key) {
    return {
      kind: 'localStorage',
      read: function () { return storage.getItem(key); },
      write: function (text) { storage.setItem(key, text); },
      clear: function () { storage.removeItem(key); },
      describe: function () {
        return 'window.localStorage[' + key + '] — synchronous single-key write; NOT a WAL and ' +
               'not fsync\u2019d, so it is the weakest durability class in the plan';
      }
    };
  }

  /* fault: null | { at: 'beforeWrite' | 'afterWrite' | 'writeThrows', times?: n } */
  function createStore(backend, fault) {
    var seq = 0;
    var lastError = null;
    var faultState = fault ? { remaining: fault.times === undefined ? 1 : fault.times } : null;

    function trip(stage) {
      if (!faultState || faultState.remaining <= 0) return;
      if (fault && fault.at === stage) {
        faultState.remaining -= 1;
        fail('STORE_FAULT_INJECTED', 'injected store fault at ' + stage);
      }
    }

    return {
      backendKind: backend.kind,
      describe: backend.describe,

      load: function () {
        var raw = backend.read();
        if (raw === null || raw === undefined) return null;
        var doc = decode(raw);
        seq = doc.commitSeq || 0;
        return doc;
      },

      /* The single commit path. Throws (after touching nothing) if the fault hook
         trips, so the caller's in-memory pre-image stays authoritative. */
      commit: function (vault, state) {
        trip('beforeWrite');
        var nextSeq = seq + 1;
        var text = encode({ vault: vault, state: state, commitSeq: nextSeq });
        if (fault && fault.at === 'writeThrows') {
          faultState.remaining -= 1;
          fail('STORE_FAULT_INJECTED', 'injected store fault while writing');
        }
        backend.write(text);
        trip('afterWrite');
        seq = nextSeq;
        lastError = null;
        return { commitSeq: seq, bytes: text.length };
      },

      commitSeq: function () { return seq; },
      lastError: function () { return lastError; },
      clear: function () { backend.clear(); seq = 0; },
      armFault: function (f) {
        fault = f;
        faultState = f ? { remaining: f.times === undefined ? 1 : f.times } : null;
      },
      faultArmed: function () { return !!(faultState && faultState.remaining > 0); }
    };
  }

  function browserBackend() {
    try {
      if (typeof localStorage === 'undefined') return memoryBackend();
      var probe = KEY + '.probe';
      localStorage.setItem(probe, '1');
      localStorage.removeItem(probe);
      return localBackend(localStorage, KEY);
    } catch (e) {
      /* Private mode / disabled storage: degrade to memory and say so rather than
         pretending the vault persists. */
      return memoryBackend();
    }
  }

  IW.STORE_KEY = KEY;
  IW.STORE_SCHEMA = SCHEMA;
  IW.encodeStoreDoc = encode;
  IW.decodeStoreDoc = decode;
  IW.memoryBackend = memoryBackend;
  IW.browserBackend = browserBackend;
  IW.createStore = createStore;
}(globalThis.IW = globalThis.IW || {}));
