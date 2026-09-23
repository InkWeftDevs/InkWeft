/* InkWeft S1 sample — minimal native package export and isolated recovery.

   Scope is deliberately the S1 slice (AX28-S1 / AX28-A04):
     card, immutable block revisions, card revisions, source anchors, one map
     node, one manual question projection. No dictionary, no FSRS history, no
     advanced mind map, and the report says so instead of implying completeness.

   Asset bytes are never embedded; the package carries (path, sha256) and the
   importer verifies whatever bytes it is given, so a missing or substituted
   original is detected rather than trusted. */
(function (IW) {
  'use strict';

  var fail = IW.fail;
  var clone = IW.clone;
  var byId = IW.byId;

  var FORMAT = 'InkWeft.S1NativePackage/0.1';

  var OUT_OF_SCOPE = [
    'dictionarySources/dictionaryEntries (K03, S2)',
    'cardRelations (S3)',
    'reviewEvents/attempts — S1 ships no scheduling, so there is nothing to export',
    'recallSessions (K07, S3)',
    'embedded asset bytes — externals are referenced by hash, not duplicated',
    'expanded-layout PDF export (K05, S3)'
  ];

  function exportPackage(vault, state, options) {
    var opts = options || {};
    var assets = vault.documentVersions.map(function (v) {
      return {
        documentId: v.documentId,
        versionId: v.id,
        kind: v.kind,
        assetPath: v.assetPath,
        bytes: v.bytes,
        sha256: v.sha256,
        embedded: false
      };
    });

    var body = {
      format: FORMAT,
      exportedAt: opts.exportedAt || null,
      vaultEpoch: vault.epoch,
      vaultId: vault.vaultId,
      manifest: {
        cards: vault.cards.length,
        cardRevisions: vault.cardRevisions.length,
        blocks: vault.blocks.length,
        blockRevisions: vault.blockRevisions.length,
        sourceAnchors: vault.sourceAnchors.length,
        annotationPlacements: vault.annotationPlacements.length,
        marginPlacements: vault.marginPlacements.length,
        maps: vault.maps.length,
        occurrences: vault.occurrences.length,
        reviewItems: vault.reviewItems.length,
        reviewItemRevisions: vault.reviewItemRevisions.length,
        reviewStates: vault.reviewStates.length,
        documents: vault.documents.length,
        documentVersions: vault.documentVersions.length
      },
      assets: assets,
      /* History travels so an isolated restore can still resolve old revisions. */
      history: state ? {
        receipts: (state.receipts || []).map(function (r) {
          return {
            commandId: r.commandId, commandType: r.commandType, digest: r.digest,
            at: r.at, receiptRevision: r.receiptRevision, result: clone(r.result)
          };
        }),
        undone: clone(state.undone || {})
      } : { receipts: [], undone: {} },
      /* Deliberately absent, and named: an empty list would look like an oversight. */
      outOfScope: OUT_OF_SCOPE.slice(),
      data: {
        studySets: clone(vault.studySets),
        documents: clone(vault.documents),
        documentVersions: clone(vault.documentVersions),
        cards: clone(vault.cards),
        cardRevisions: clone(vault.cardRevisions),
        blocks: clone(vault.blocks),
        blockRevisions: clone(vault.blockRevisions),
        sourceAnchors: clone(vault.sourceAnchors),
        annotationPlacements: clone(vault.annotationPlacements),
        marginPlacements: clone(vault.marginPlacements),
        maps: clone(vault.maps),
        occurrences: clone(vault.occurrences),
        reviewItems: clone(vault.reviewItems),
        reviewItemRevisions: clone(vault.reviewItemRevisions),
        reviewStates: clone(vault.reviewStates)
      }
    };
    body.integrity = { algorithm: 'sha256', digest: IW.sha256Hex(IW.canonical(body)) };
    return body;
  }

  function verifyIntegrity(pkg) {
    var claimed = pkg.integrity ? pkg.integrity.digest : null;
    var copy = clone(pkg);
    delete copy.integrity;
    var actual = IW.sha256Hex(IW.canonical(copy));
    if (!claimed) fail('PACKAGE_NO_INTEGRITY', 'package carries no integrity digest');
    if (claimed !== actual) fail('PACKAGE_TAMPERED', 'package integrity digest does not match its content');
    return { verified: true, digest: actual };
  }

  /* assetBytes: { [versionId]: Uint8Array | string } — optional, verified when given. */
  function importPackage(pkg, options) {
    var opts = options || {};
    if (!pkg || pkg.format !== FORMAT) {
      fail('PACKAGE_FORMAT_UNSUPPORTED', 'not a ' + FORMAT + ' package', { format: pkg && pkg.format });
    }
    var integrity = verifyIntegrity(pkg);
    if (pkg.vaultEpoch !== 0 && opts.targetEpoch !== undefined && opts.targetEpoch !== pkg.vaultEpoch) {
      /* Recovering into a replaced vault is exactly the case the epoch guards. */
      if (opts.allowEpochReplace !== true) {
        fail('PACKAGE_EPOCH_MISMATCH', 'package belongs to vault epoch ' + pkg.vaultEpoch +
          ' but the target is at ' + opts.targetEpoch);
      }
    }

    var vault = IW.emptyVault(pkg.vaultId);
    vault.epoch = pkg.vaultEpoch;
    Object.keys(pkg.data).forEach(function (key) {
      vault[key] = clone(pkg.data[key]);
    });

    var report = { verified: true, integrityDigest: integrity.digest, checks: [], warnings: [] };

    function check(id, ok, message) {
      report.checks.push({ id: id, result: ok ? 'PASS' : 'FAIL', message: message });
      if (!ok) fail('IMPORT_CHECK_FAILED', message, { check: id });
    }

    check('vault-id', typeof vault.vaultId === 'string' && !!vault.vaultId, 'vault identity present');

    /* Every card must resolve to a committed card revision, and that revision may
       only snapshot block revisions (the v1 defect this sample guards against). */
    var snapshotRefs = 0;
    vault.cards.forEach(function (card) {
      check('card-head:' + card.id, !!byId(vault.cardRevisions, card.headRevisionId),
        'card ' + card.id + ' head revision resolves');
      var head = byId(vault.cardRevisions, card.headRevisionId);
      if (!head) return;
      head.blockSnapshotIds.forEach(function (sid) {
        snapshotRefs += 1;
        var rev = byId(vault.blockRevisions, sid);
        check('snapshot:' + sid, !!rev, 'snapshot ' + sid + ' resolves to a block revision');
        if (byId(vault.blocks, sid)) {
          check('snapshot-not-block-id:' + sid, false, 'snapshot id is a ContentBlock id');
        }
      });
    });

    /* Old history must still resolve after a round trip, else the export is not a backup. */
    var resolvable = 0;
    vault.cardRevisions.forEach(function (rev) {
      var ok = rev.blockSnapshotIds.every(function (sid) { return !!byId(vault.blockRevisions, sid); });
      if (ok) resolvable += 1;
    });
    check('history-resolves', resolvable === vault.cardRevisions.length,
      resolvable + '/' + vault.cardRevisions.length + ' card revisions resolve all snapshots');

    /* Anchors must keep pointing at a version the package retained. */
    vault.sourceAnchors.forEach(function (a) {
      check('anchor-version:' + a.id, !!byId(vault.documentVersions, a.sourceVersion),
        'anchor ' + a.id + ' keeps its recorded source version');
    });

    /* One projection per item, with the prompt revision present. */
    vault.reviewItems.forEach(function (item) {
      check('review-prompt:' + item.id, !!byId(vault.reviewItemRevisions, item.headPromptRevisionId),
        'review item ' + item.id + ' prompt revision resolves');
      var states = vault.reviewStates.filter(function (s) { return s.reviewItemId === item.id; });
      check('review-state:' + item.id, states.length === 1, 'review item has exactly one learner state');
    });

    var assetBytes = opts.assetBytes || {};
    vault.documentVersions.forEach(function (v) {
      var row = (pkg.assets || []).filter(function (a) { return a.versionId === v.id; })[0];
      check('asset-manifest:' + v.id, !!row, 'version ' + v.id + ' is declared in the package manifest');
      check('asset-declared-not-embedded:' + v.id, !row || row.embedded === false,
        'assets are referenced by hash, not duplicated into the package');
      var bytes = assetBytes[v.id];
      if (bytes === undefined || bytes === null) {
        report.warnings.push({
          code: 'ASSET_BYTES_NOT_PROVIDED', versionId: v.id, path: v.assetPath,
          detail: 'verification of the original bytes was not possible here'
        });
        return;
      }
      var text = typeof bytes === 'string' ? bytes : String.fromCharCode.apply(null, bytes);
      var actual = IW.sha256Hex(text);
      check('asset-bytes:' + v.id, actual === v.sha256,
        'provided bytes match the recorded sha256 for ' + v.assetPath);
    });

    var problems = IW.checkInvariants(vault);
    check('invariants', problems.length === 0, 'restored vault passes invariant checks: ' + (problems.join('; ') || 'none'));

    /* Rebuild the engine state from the historical receipts that came along. */
    var state = {
      receipts: (pkg.history && pkg.history.receipts ? pkg.history.receipts : []).map(function (r) {
        return {
          commandId: r.commandId, commandType: r.commandType, digest: r.digest, at: r.at,
          actorId: '(restored)', capabilityHandle: '(restored)',
          result: r.result, preImage: null, receiptRevision: r.receiptRevision,
          undoState: 'UNAVAILABLE_AFTER_RESTORE'
        };
      }),
      undone: clone((pkg.history && pkg.history.undone) || {}),
      sequence: pkg.history && pkg.history.receipts ? pkg.history.receipts.length : 0
    };

    report.restored = {
      vaultId: vault.vaultId,
      epoch: vault.epoch,
      cards: vault.cards.length,
      blockRevisions: vault.blockRevisions.length,
      cardRevisions: vault.cardRevisions.length,
      occurrences: vault.occurrences.length,
      reviewItems: vault.reviewItems.length,
      snapshotRefs: snapshotRefs,
      undoAvailableAfterRestore: false
    };
    report.warnings.push({
      code: 'UNDO_UNAVAILABLE_AFTER_RESTORE',
      detail: 'pre-images are not exported, so undo restarts empty after a recovery. ' +
              'The plan asks for honest capability reporting rather than fake reversibility.'
    });
    return { vault: vault, state: state, report: report };
  }

  IW.PACKAGE_FORMAT = FORMAT;
  IW.PACKAGE_OUT_OF_SCOPE = OUT_OF_SCOPE;
  IW.exportPackage = exportPackage;
  IW.verifyPackageIntegrity = verifyIntegrity;
  IW.importPackage = importPackage;
}(globalThis.IW = globalThis.IW || {}));
