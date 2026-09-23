/* InkWeft S1 sample — vault shape and the immutable revision chains.
   Rules taken from the plan's domain contract (docs/02), K00/K02 scope:
     - a card's body lives in immutable BlockRevision rows; the block head only
       points at the current one, so history can always be re-resolved;
     - CardRevision.blockSnapshotIds may only reference BlockRevision ids;
     - changing a map node's position or width must not touch card content;
     - deleting an occurrence must never cascade into card content. */
(function (IW) {
  'use strict';

  var fail = IW.fail;

  function emptyVault(vaultId) {
    return {
      schemaVersion: 'InkWeft.Vault/0.1',
      vaultId: vaultId,
      epoch: 0,
      settings: { autoAttachToMap: false, autoEnqueueReview: false },
      studySets: [],
      documents: [],
      documentVersions: [],
      cards: [],
      cardRevisions: [],
      blocks: [],
      blockRevisions: [],
      sourceAnchors: [],
      annotationPlacements: [],
      marginPlacements: [],
      maps: [],
      occurrences: [],
      reviewItems: [],
      reviewItemRevisions: [],
      reviewStates: [],
      reviewEvents: [],
      attempts: [],
      recallSessions: [],
      /* Explicitly out of S1 scope, kept as declared-but-empty so downstream
         readers cannot mistake "not implemented" for "forgotten". */
      dictionarySources: [],
      dictionaryEntries: [],
      cardRelations: []
    };
  }

  function clone(value) { return JSON.parse(JSON.stringify(value)); }

  /* --------------------------------------------------------------- lookups */
  function byId(rows, id) {
    for (var i = 0; i < rows.length; i++) if (rows[i].id === id) return rows[i];
    return null;
  }

  function cardOf(vault, cardId) {
    var card = byId(vault.cards, cardId);
    if (!card) fail('CARD_NOT_FOUND', 'no card ' + cardId);
    return card;
  }

  function blockOf(vault, blockId) {
    var block = byId(vault.blocks, blockId);
    if (!block) fail('BLOCK_NOT_FOUND', 'no block ' + blockId);
    return block;
  }

  function headBlockRevision(vault, blockId) {
    var block = blockOf(vault, blockId);
    var rev = byId(vault.blockRevisions, block.headRevisionId);
    if (!rev) fail('BLOCK_REVISION_UNRESOLVED', 'dangling head block revision');
    return rev;
  }

  function headCardRevision(vault, cardId) {
    var card = cardOf(vault, cardId);
    var rev = byId(vault.cardRevisions, card.headRevisionId);
    if (!rev) fail('CARD_REVISION_UNRESOLVED', 'dangling head card revision');
    return rev;
  }

  function blockRevisionChain(vault, blockId) {
    var chain = [];
    var rev = headBlockRevision(vault, blockId);
    var guard = 0;
    while (rev) {
      chain.push(rev);
      rev = rev.previousRevisionId ? byId(vault.blockRevisions, rev.previousRevisionId) : null;
      if (++guard > 1000) fail('REVISION_CHAIN_LOOP', 'block revision chain does not terminate');
    }
    return chain;
  }

  function cardRevisionChain(vault, cardId) {
    var chain = [];
    var rev = headCardRevision(vault, cardId);
    var guard = 0;
    while (rev) {
      chain.push(rev);
      rev = rev.previousRevisionId ? byId(vault.cardRevisions, rev.previousRevisionId) : null;
      if (++guard > 1000) fail('REVISION_CHAIN_LOOP', 'card revision chain does not terminate');
    }
    return chain;
  }

  /* Resolve a card revision into readable content. Old revisions keep resolving
     to the block versions that were current when they were committed.

     Exactness matters more than convenience here: an EMPTY text is a legitimate
     user edit ("I deleted my note"), so this must never fall back to an older
     revision. Only a genuinely malformed revision is rejected, loudly. */
  function readCardRevision(vault, cardRevisionId) {
    var rev = byId(vault.cardRevisions, cardRevisionId);
    if (!rev) fail('CARD_REVISION_UNRESOLVED', 'no card revision ' + cardRevisionId);
    var blocks = rev.blockSnapshotIds.map(function (snapshotId) {
      var blockRev = byId(vault.blockRevisions, snapshotId);
      if (!blockRev) fail('BLOCK_REVISION_UNRESOLVED', 'snapshot ' + snapshotId + ' is missing');
      if (!blockRev.payload || typeof blockRev.payload !== 'object') {
        fail('BLOCK_PAYLOAD_MALFORMED', 'block revision ' + blockRev.id + ' has no payload object', {
          blockRevisionId: blockRev.id, blockId: blockRev.blockId
        });
      }
      if (blockRev.kind === 'text' && typeof blockRev.payload.text !== 'string') {
        fail('BLOCK_PAYLOAD_MALFORMED',
          'text block revision ' + blockRev.id + ' has no text field; refusing to substitute an older revision',
          { blockRevisionId: blockRev.id, blockId: blockRev.blockId });
      }
      return {
        blockId: blockRev.blockId,
        blockRevisionId: blockRev.id,
        role: blockRev.role,
        kind: blockRev.kind,
        payload: clone(blockRev.payload),
        sourceAnchorId: blockRev.sourceAnchorId
      };
    });
    return {
      cardId: rev.cardId,
      cardRevisionId: rev.id,
      revision: rev.revision,
      title: rev.title,
      tags: rev.tags.slice(),
      blocks: blocks,
      sourceIds: rev.sourceIds.slice()
    };
  }

  /* The single "authoritative card content" every view reads. Views must render
     from this, never from their own copy. */
  function readCardHead(vault, cardId) {
    var card = cardOf(vault, cardId);
    return readCardRevision(vault, card.headRevisionId);
  }

  function makeBlock(vault, ids, cardId, role, kind, payload, sourceAnchorId) {
    var block = {
      id: ids.next(), cardId: cardId, kind: kind, role: role,
      revision: 1, headRevisionId: null
    };
    var revision = {
      id: ids.next(), blockId: block.id, cardId: cardId, revision: 1,
      previousRevisionId: null, kind: kind, role: role, payload: payload,
      sourceAnchorId: sourceAnchorId || null, createdAt: null
    };
    block.headRevisionId = revision.id;
    vault.blocks.push(block);
    vault.blockRevisions.push(revision);
    return block;
  }

  function appendBlockRevision(vault, ids, blockId, payload, createdAt) {
    var block = blockOf(vault, blockId);
    var previous = headBlockRevision(vault, blockId);
    var revision = {
      id: ids.next(), blockId: blockId, cardId: block.cardId,
      revision: block.revision + 1, previousRevisionId: previous.id,
      kind: block.kind, role: block.role, payload: payload,
      sourceAnchorId: previous.sourceAnchorId, createdAt: createdAt
    };
    block.revision = revision.revision;
    block.headRevisionId = revision.id;
    vault.blockRevisions.push(revision);
    return revision;
  }

  /* Commit a new card revision whose block snapshot is the current head of every
     ordered block. This is the only place card content advances. */
  function commitCardRevision(vault, ids, cardId, changes, createdAt) {
    var card = cardOf(vault, cardId);
    var previous = byId(vault.cardRevisions, card.headRevisionId);
    var snapshotIds = card.blockOrder.map(function (blockId) {
      return headBlockRevision(vault, blockId).id;
    });
    var revision = {
      id: ids.next(),
      cardId: cardId,
      revision: card.revision + 1,
      previousRevisionId: previous ? previous.id : null,
      title: changes.title !== undefined ? changes.title : (previous ? previous.title : ''),
      tags: changes.tags !== undefined ? changes.tags.slice() : (previous ? previous.tags.slice() : []),
      blockSnapshotIds: snapshotIds,
      sourceIds: changes.sourceIds !== undefined
        ? changes.sourceIds.slice()
        : (previous ? previous.sourceIds.slice() : [])
    };
    card.revision = revision.revision;
    card.headRevisionId = revision.id;
    vault.cardRevisions.push(revision);
    return revision;
  }

  /* ------------------------------------------------------------- invariants */
  /* Structural properties that must hold after every commit. runCommands checks
     these before acknowledging, so a half-built card can never be reported as
     saved. This is the sample's stand-in for AX28-A02. */
  function checkInvariants(vault) {
    var problems = [];
    var ids = {};
    function unique(kind, id) {
      if (ids[id]) problems.push('duplicate id across ' + kind + ': ' + id);
      ids[id] = true;
    }

    vault.cards.forEach(function (c) {
      unique('card', c.id);
      var head = byId(vault.cardRevisions, c.headRevisionId);
      if (!head) problems.push('card ' + c.id + ' head revision unresolved');
      else if (head.cardId !== c.id) problems.push('card ' + c.id + ' head revision belongs to another card');
      if (!c.blockOrder.length) problems.push('card ' + c.id + ' has no blocks');
      c.blockOrder.forEach(function (blockId) {
        var block = byId(vault.blocks, blockId);
        if (!block) return problems.push('card ' + c.id + ' references missing block ' + blockId);
        if (block.cardId !== c.id) problems.push('block ' + blockId + ' belongs to another card');
        if (!byId(vault.blockRevisions, block.headRevisionId)) {
          problems.push('block ' + blockId + ' head revision unresolved');
        }
      });
      /* A card with a source-quote block MUST have a resolvable source; a card
         without one (created as an independent note) legitimately has none. The
         earlier blanket rule contradicted createIndependentCard. */
      var hasQuote = false;
      c.blockOrder.forEach(function (blockId) {
        var block = byId(vault.blocks, blockId);
        if (block && block.role === 'source_quote') hasQuote = true;
      });
      if (hasQuote && !c.sourceIds.length) {
        problems.push('card ' + c.id + ' quotes a source but has no source anchor');
      }
      c.sourceIds.forEach(function (anchorId) {
        if (!byId(vault.sourceAnchors, anchorId)) problems.push('card ' + c.id + ' dangling anchor ' + anchorId);
      });
    });

    vault.blockRevisions.forEach(function (r) {
      unique('blockRevision', r.id);
      if (!byId(vault.blocks, r.blockId)) problems.push('blockRevision ' + r.id + ' orphaned');
      if (r.previousRevisionId && !byId(vault.blockRevisions, r.previousRevisionId)) {
        problems.push('blockRevision ' + r.id + ' previous revision unresolved');
      }
      /* Payload shape is checked per kind. An EMPTY string is valid content; a
         MISSING field is a malformed revision that must never reach a view. */
      if (!r.payload || typeof r.payload !== 'object') {
        problems.push('blockRevision ' + r.id + ' has no payload object');
      } else if (r.kind === 'text' && typeof r.payload.text !== 'string') {
        problems.push('blockRevision ' + r.id + ' is a text block without a text field');
      }
    });

    vault.cardRevisions.forEach(function (r) {
      unique('cardRevision', r.id);
      if (!byId(vault.cards, r.cardId)) problems.push('cardRevision ' + r.id + ' orphaned');
      r.blockSnapshotIds.forEach(function (snapshotId) {
        var blockRev = byId(vault.blockRevisions, snapshotId);
        if (!blockRev) return problems.push('cardRevision ' + r.id + ' dangling snapshot ' + snapshotId);
        if (blockRev.cardId !== r.cardId) problems.push('cardRevision ' + r.id + ' snapshots a foreign block');
        /* The v1 defect: a ContentBlock id must never stand in for a revision id. */
        if (byId(vault.blocks, snapshotId)) problems.push('cardRevision ' + r.id + ' snapshot is a block id, not a revision id');
      });
      if (r.previousRevisionId && !byId(vault.cardRevisions, r.previousRevisionId)) {
        problems.push('cardRevision ' + r.id + ' previous revision unresolved');
      }
    });

    vault.sourceAnchors.forEach(function (a) {
      unique('anchor', a.id);
      var doc = byId(vault.documents, a.documentId);
      if (!doc) problems.push('anchor ' + a.id + ' dangling document');
      else if (doc.headVersionId !== a.sourceVersion) {
        /* Expected after a source swap, as long as the old version is retained. */
        if (!byId(vault.documentVersions, a.sourceVersion)) {
          problems.push('anchor ' + a.id + ' lost its recorded source version');
        }
      }
    });

    vault.annotationPlacements.concat(vault.marginPlacements).forEach(function (p) {
      unique('placement', p.id);
      /* A plain highlighter placement legitimately has no card (cardId === null):
         it marks the source without creating knowledge. A non-null cardId must
         still resolve, which is what keeps a half-built card from shipping. */
      if (p.cardId !== null && p.cardId !== undefined && !byId(vault.cards, p.cardId)) {
        problems.push('placement ' + p.id + ' dangling card');
      }
      if (!byId(vault.sourceAnchors, p.anchorId)) problems.push('placement ' + p.id + ' dangling anchor');
    });

    vault.occurrences.forEach(function (o) {
      unique('occurrence', o.id);
      if (!byId(vault.maps, o.mapId)) problems.push('occurrence ' + o.id + ' dangling map');
      if (!byId(vault.cards, o.cardId)) problems.push('occurrence ' + o.id + ' dangling card');
      if (o.parentOccurrenceId) {
        var parent = byId(vault.occurrences, o.parentOccurrenceId);
        if (!parent) problems.push('occurrence ' + o.id + ' dangling parent');
        else if (parent.mapId !== o.mapId) problems.push('occurrence ' + o.id + ' parent is on another map');
      }
    });

    /* Occurrence forest must be acyclic. */
    var state = {};
    function visit(id) {
      if (state[id] === 1) return problems.push('occurrence cycle at ' + id);
      if (state[id] === 2) return;
      state[id] = 1;
      var node = byId(vault.occurrences, id);
      if (node && node.parentOccurrenceId) visit(node.parentOccurrenceId);
      state[id] = 2;
    }
    vault.occurrences.forEach(function (o) { visit(o.id); });

    vault.reviewItems.forEach(function (item) {
      unique('reviewItem', item.id);
      if (!byId(vault.cards, item.cardId)) problems.push('reviewItem ' + item.id + ' dangling card');
      if (!byId(vault.reviewItemRevisions, item.headPromptRevisionId)) {
        problems.push('reviewItem ' + item.id + ' prompt revision unresolved');
      }
      item.answerRefs.forEach(function (ref) {
        var block = byId(vault.blocks, ref.blockId);
        if (!block) return problems.push('reviewItem ' + item.id + ' dangling answer block');
        if (block.cardId !== item.cardId) problems.push('reviewItem ' + item.id + ' answer block on another card');
      });
      var states = vault.reviewStates.filter(function (s) { return s.reviewItemId === item.id; });
      if (!states.length) problems.push('reviewItem ' + item.id + ' has no ReviewState');
    });

    /* S1 scope guard: no scheduling was promised, so nothing may claim it. */
    if (vault.reviewEvents.length) problems.push('S1 sample must not fabricate review events');
    if (vault.attempts.length) problems.push('S1 sample must not fabricate attempts');

    return problems;
  }

  IW.emptyVault = emptyVault;
  IW.clone = clone;
  IW.byId = byId;
  IW.cardOf = cardOf;
  IW.blockOf = blockOf;
  IW.headBlockRevision = headBlockRevision;
  IW.headCardRevision = headCardRevision;
  IW.blockRevisionChain = blockRevisionChain;
  IW.cardRevisionChain = cardRevisionChain;
  IW.readCardRevision = readCardRevision;
  IW.readCardHead = readCardHead;
  IW.makeBlock = makeBlock;
  IW.appendBlockRevision = appendBlockRevision;
  IW.commitCardRevision = commitCardRevision;
  IW.checkInvariants = checkInvariants;
}(globalThis.IW = globalThis.IW || {}));
