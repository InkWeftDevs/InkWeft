/* InkWeft S1 sample — the single write entry point (K00).

   Contract implemented here (docs/02 §5, interface-contract.json):
     - every write is a command with a stable commandId and a semantic digest;
     - same commandId + same digest  -> the ORIGINAL receipt, no new revision;
     - same commandId + other digest -> COMMAND_ID_REUSE, receipt untouched;
     - authorization is re-checked before an old receipt may be returned;
     - the whole command runs against a copy: it either lands completely with all
       references closed, or nothing is published and no receipt exists;
     - `expectedVersions` mismatches are refused instead of silently overwriting.

   Nothing in the UI writes to the vault directly; the UI only calls run(). */
(function (IW) {
  'use strict';

  var fail = IW.fail;
  var clone = IW.clone;
  var byId = IW.byId;
  var digest = IW.semanticDigest;

  var COMMAND_HEADER = ['commandId', 'vaultId', 'actorId', 'expectedVersions', 'capabilityHandle'];

  function emptyState() {
    return { receipts: [], undone: {}, sequence: 0 };
  }

  /* --------------------------------------------------------------- helpers */
  function nextMapOrder(vault, mapId) {
    var max = -1;
    vault.occurrences.forEach(function (o) {
      if (o.mapId === mapId && o.siblingOrder > max) max = o.siblingOrder;
    });
    return max + 1;
  }

  function ensureDefaultMap(vault, ids, studySetId, createdAt) {
    var existing = vault.maps.filter(function (m) { return m.studySetId === studySetId; })[0];
    if (existing) return existing;
    var map = {
      id: ids.next(),
      studySetId: studySetId,
      title: '主脑图',
      revision: 1,
      layoutProfile: { kind: 'manual-tree', version: 1 },
      createdAt: createdAt
    };
    vault.maps.push(map);
    return map;
  }

  /* Capability model for the sample: '*' grants every source (the default for a
     local single-user vault); revoking a specific version drops that entry while
     the wildcard stays, so setGrant(v,false) really does deny v. */
  function granted(grants, sourceVersion) {
    if (Object.prototype.hasOwnProperty.call(grants, sourceVersion)) return !!grants[sourceVersion];
    return !!grants['*'];
  }

  function assertGranted(engine, sourceVersion) {
    if (!granted(engine.grants, sourceVersion)) {
      fail('SOURCE_RESTRICTED', 'the current grant does not allow reading this source version', {
        sourceVersion: sourceVersion
      });
    }
  }

  /* -------------------------------------------------------- command table */
  /* `grants` is passed in so authorization is a pure lookup with no hidden
     global: the same object the engine mutates via setGrant. */
  function buildCommands(grants) {
    return {
      bootstrap: {
        description: 'Create the study set that owns the sample vault.',
        apply: function (vault, ids, p, ts) {
          var set = {
            id: ids.next(),
            title: p.title || '概率基础',
            documentRefs: [],
            mapRefs: [],
            cardRefs: [],
            createdAt: ts
          };
          vault.studySets.push(set);
          return { studySetId: set.id };
        }
      },

      registerDocumentVersion: {
        description: 'Record one immutable source version plus the grant that makes it readable.',
        apply: function (vault, ids, p, ts) {
          var doc = byId(vault.documents, p.documentId);
          if (!doc) {
            doc = { id: p.documentId, title: p.title, headVersionId: null, createdAt: ts };
            vault.documents.push(doc);
          }
          if (byId(vault.documentVersions, p.versionId)) {
            fail('VERSION_EXISTS', 'document version already registered', { versionId: p.versionId });
          }
          var version = {
            id: p.versionId,
            documentId: p.documentId,
            kind: p.kind,
            assetPath: p.assetPath,
            bytes: p.bytes,
            sha256: p.sha256,
            textSha256: p.textSha256 || null,
            retention: 'RETAINED',
            createdAt: ts
          };
          vault.documentVersions.push(version);
          doc.headVersionId = p.versionId;
          return { documentId: doc.id, versionId: version.id, sha256: version.sha256 };
        }
      },

      /* Stands in for the capability layer: revoking access must make an old
         commandId unusable even when its receipt still exists (AX34-A03). */
      setSourceAccess: {
        description: 'Grant or revoke the right to read one source version.',
        apply: function (vault, ids, p) {
          if (!byId(vault.documentVersions, p.versionId)) {
            fail('VERSION_NOT_FOUND', 'unknown version ' + p.versionId);
          }
          return { versionId: p.versionId, granted: !!p.granted };
        }
      },

      /* Ordinary highlighter: an annotation only. It must never create a card,
         a review item or a map node (AX33-A01). */
      markHighlighter: {
        description: 'Store a plain highlighter mark on the source. No knowledge card is created.',
        apply: function (vault, ids, p, ts) {
          var built = buildAnchors(vault, ids, p, ts);
          vault.sourceAnchors.push(built.anchors[0]);
          var mark = {
            id: ids.next(),
            cardId: null,
            anchorId: built.anchors[0].id,
            style: 'highlighter',
            presentationRevision: 1,
            createdAt: ts
          };
          vault.annotationPlacements.push(mark);
          return { anchorId: built.anchors[0].id, annotationId: mark.id, cardId: null };
        }
      },

      /* Excerpt: create the shared card and its source marker in one transaction
         so a card can never exist without a resolvable source (AX33-A02).
         A selection crossing a logical page break yields ONE ANCHOR PER PAGE —
         never one oversized rectangle spanning pages (AX03-A02). */
      createCardFromSelection: {
        description: 'Create a knowledge card from a source selection, atomically with its anchor(s) and marker.',
        apply: function (vault, ids, p, ts) {
          if (!p.studySetId || !byId(vault.studySets, p.studySetId)) {
            fail('STUDY_SET_REQUIRED', 'a study set is required before creating a card');
          }
          var built = buildAnchors(vault, ids, p, ts);
          built.anchors.forEach(function (a) { vault.sourceAnchors.push(a); });

          var card = {
            id: ids.next(),
            studySetId: p.studySetId,
            revision: 0,
            headRevisionId: null,
            blockOrder: [],
            sourceIds: built.anchors.map(function (a) { return a.id; }),
            tags: p.tags ? p.tags.slice() : [],
            trashedAt: null,
            createdAt: ts
          };
          vault.cards.push(card);

          var quoteBlock = IW.makeBlock(vault, ids, card.id, 'source_quote', 'text',
            { text: built.quote }, built.anchors[0].id);
          var commentBlock = IW.makeBlock(vault, ids, card.id, 'comment', 'text',
            { text: p.comment || '' }, null);
          vault.blockRevisions.forEach(function (r) {
            if (r.blockId === quoteBlock.id || r.blockId === commentBlock.id) r.createdAt = ts;
          });
          card.blockOrder = [quoteBlock.id, commentBlock.id];

          var revision = IW.commitCardRevision(vault, ids, card.id, {
            title: p.title || built.quote.slice(0, 40),
            tags: card.tags,
            sourceIds: card.sourceIds
          }, ts);

          var mark = {
            id: ids.next(),
            cardId: card.id,
            anchorId: built.anchors[0].id,
            style: 'excerpt-highlight',
            presentationRevision: 1,
            createdAt: ts
          };
          vault.annotationPlacements.push(mark);

          /* Auto-attach / auto-enqueue stay OFF unless the vault asks for them;
             the plain default is "card only". */
          var attached = null;
          if (vault.settings.autoAttachToMap) {
            var map = ensureDefaultMap(vault, ids, p.studySetId, ts);
            attached = attach(vault, ids, map.id, card.id, null, ts);
          }
          return {
            cardId: card.id,
            cardRevisionId: revision.id,
            anchorId: built.anchors[0].id,
            anchorIds: card.sourceIds.slice(),
            annotationId: mark.id,
            occurrenceId: attached ? attached.id : null,
            quoteBlockId: quoteBlock.id,
            commentBlockId: commentBlock.id
          };
        }
      },

      createIndependentCard: {
        description: 'Create a card that deliberately has no source anchor yet.',
        apply: function (vault, ids, p, ts) {
          var card = {
            id: ids.next(), studySetId: p.studySetId, revision: 0, headRevisionId: null,
            blockOrder: [], sourceIds: [], tags: p.tags ? p.tags.slice() : [],
            trashedAt: null, createdAt: ts
          };
          vault.cards.push(card);
          var block = IW.makeBlock(vault, ids, card.id, 'comment', 'text', { text: p.text || '' }, null);
          vault.blockRevisions.forEach(function (r) { if (r.blockId === block.id) r.createdAt = ts; });
          card.blockOrder = [block.id];
          var revision = IW.commitCardRevision(vault, ids, card.id, {
            title: p.title || '未命名卡片', tags: card.tags, sourceIds: []
          }, ts);
          return { cardId: card.id, cardRevisionId: revision.id, blockId: block.id };
        }
      },

      /* Content lives in immutable block revisions; editing appends a new one and
         commits a new card revision that snapshots it. */
      patchCard: {
        description: 'Append a new comment block revision and commit a new card revision.',
        pre: function (vault, p) {
          var card = byId(vault.cards, p.cardId);
          if (!card) return { code: 'CARD_NOT_FOUND', message: 'no card ' + p.cardId };
          if (card.trashedAt) return { code: 'CARD_TRASHED', message: 'card is in the trash' };
          var blocked = null;
          card.sourceIds.forEach(function (anchorId) {
            var anchor = byId(vault.sourceAnchors, anchorId);
            if (anchor && !granted(grants, anchor.sourceVersion)) {
              blocked = { code: 'SOURCE_RESTRICTED', message: 'the card source is no longer readable' };
            }
          });
          return blocked;
        },
        apply: function (vault, ids, p, ts) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          var updated = [];
          if (p.text !== undefined) {
            var commentBlock = null;
            card.blockOrder.forEach(function (blockId) {
              var block = byId(vault.blocks, blockId);
              if (block && block.role === 'comment') commentBlock = block;
            });
            if (!commentBlock) {
              commentBlock = IW.makeBlock(vault, ids, card.id, 'comment', 'text', { text: p.text }, null);
              card.blockOrder.push(commentBlock.id);
            } else {
              IW.appendBlockRevision(vault, ids, commentBlock.id, { text: p.text }, ts);
            }
            updated.push(commentBlock.id);
          }
          var changes = {};
          if (p.title !== undefined) changes.title = p.title;
          if (p.tags !== undefined) changes.tags = p.tags;
          var revision = IW.commitCardRevision(vault, ids, card.id, changes, ts);
          return { cardId: card.id, cardRevisionId: revision.id, revision: revision.revision, blocks: updated };
        }
      },

      createMap: {
        description: 'Create an explicit mind map. Occurrences are only ever placed on a named map; ' +
                     'passing no mapId means "the study set default", not "a second map".',
        apply: function (vault, ids, p, ts) {
          if (!p.studySetId || !byId(vault.studySets, p.studySetId)) {
            fail('STUDY_SET_REQUIRED', 'a map needs a study set');
          }
          var map = {
            id: ids.next(),
            studySetId: p.studySetId,
            title: p.title || '新脑图',
            revision: 1,
            layoutProfile: { kind: 'manual-tree', version: 1 },
            createdAt: ts
          };
          vault.maps.push(map);
          return { mapId: map.id, title: map.title };
        }
      },

      attachOccurrence: {
        description: 'Place a card on a mind map as a node; layout only, content untouched.',
        apply: function (vault, ids, p, ts) {
          var mapId = p.mapId;
          if (!mapId) {
            var map = ensureDefaultMap(vault, ids, p.studySetId, ts);
            mapId = map.id;
          } else if (!byId(vault.maps, mapId)) {
            fail('MAP_NOT_FOUND', 'no map ' + mapId);
          }
          var node = attach(vault, ids, mapId, p.cardId, p.parentOccurrenceId || null, ts);
          if (typeof p.x === 'number') node.x = p.x;
          if (typeof p.y === 'number') node.y = p.y;
          return { occurrenceId: node.id, mapId: mapId };
        }
      },

      attachSourceAnchor: {
        description: 'Append one more resolvable source span to an existing card and commit a new revision.',
        apply: function (vault, ids, p, ts) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          var built = buildAnchors(vault, ids, {
            documentId: p.documentId,
            pageId: p.pageId,
            sourceVersion: p.sourceVersion,
            startOffset: p.startOffset,
            endOffset: p.endOffset,
            surface: p.surface
          }, ts);
          built.anchors.forEach(function (a) { vault.sourceAnchors.push(a); });
          card.sourceIds = card.sourceIds.concat(built.anchors.map(function (a) { return a.id; }));
          var revision = IW.commitCardRevision(vault, ids, card.id, {
            sourceIds: card.sourceIds
          }, ts);
          return {
            cardId: card.id,
            cardRevisionId: revision.id,
            revision: revision.revision,
            addedAnchorIds: built.anchors.map(function (a) { return a.id; }),
            sourceCount: card.sourceIds.length
          };
        }
      },

      moveOccurrence: {
        description: 'Move or resize ONE node. Must not touch card content or other maps (AX02-A03).',
        pre: function (vault, p) {
          var node = byId(vault.occurrences, p.occurrenceId);
          if (!node) return { code: 'OCCURRENCE_NOT_FOUND', message: 'no occurrence ' + p.occurrenceId };
          return null;
        },
        apply: function (vault, ids, p) {
          var node = byId(vault.occurrences, p.occurrenceId);
          if (!node) fail('OCCURRENCE_NOT_FOUND', 'no occurrence ' + p.occurrenceId);
          /* Per-occurrence presentation lives in layoutOverride, as the fixture
             contract says; `collapsed` is view state and stays on the node.
             Guard on the VALUE so a present-but-undefined key cannot wipe the
             current layout — that is what turned a resize into a silent reset. */
          node.layoutOverride = node.layoutOverride || { position: { x: 0, y: 0 }, pinned: false, width: 220 };
          ['x', 'y', 'width'].forEach(function (key) {
            if (p[key] !== undefined) node.layoutOverride[key] = p[key];
          });
          if (p.collapsed !== undefined) node.collapsed = p.collapsed;
          var map = byId(vault.maps, node.mapId);
          map.revision += 1;
          return { occurrenceId: node.id, mapId: map.id, mapRevision: map.revision };
        }
      },

      reparentOccurrence: {
        description: 'Re-parent a node inside one map. Cross-map parents and cycles are refused.',
        apply: function (vault, ids, p) {
          var node = byId(vault.occurrences, p.occurrenceId);
          if (!node) fail('OCCURRENCE_NOT_FOUND', 'no occurrence ' + p.occurrenceId);
          if (p.parentOccurrenceId) {
            var parent = byId(vault.occurrences, p.parentOccurrenceId);
            if (!parent) fail('PARENT_NOT_FOUND', 'no parent occurrence');
            if (parent.mapId !== node.mapId) fail('CROSS_MAP_PARENT', 'parent is on another map');
            var cursor = parent;
            var guard = 0;
            while (cursor) {
              if (cursor.id === node.id) fail('OCCURRENCE_CYCLE', 're-parenting would create a cycle');
              cursor = cursor.parentOccurrenceId ? byId(vault.occurrences, cursor.parentOccurrenceId) : null;
              if (++guard > 500) fail('OCCURRENCE_CYCLE', 'occurrence chain does not terminate');
            }
          }
          node.parentOccurrenceId = p.parentOccurrenceId || null;
          var map = byId(vault.maps, node.mapId);
          map.revision += 1;
          return { occurrenceId: node.id, parentOccurrenceId: node.parentOccurrenceId };
        }
      },

      /* Removing a node must not cascade into the card (AX06 semantics). */
      detachOccurrence: {
        description: 'Remove one map position only. The card, its content and other nodes survive.',
        apply: function (vault, ids, p) {
          var node = byId(vault.occurrences, p.occurrenceId);
          if (!node) fail('OCCURRENCE_NOT_FOUND', 'no occurrence ' + p.occurrenceId);
          var children = vault.occurrences.filter(function (o) {
            return o.parentOccurrenceId === node.id;
          });
          children.forEach(function (child) { child.parentOccurrenceId = node.parentOccurrenceId; });
          vault.occurrences = vault.occurrences.filter(function (o) { return o.id !== node.id; });
          var map = byId(vault.maps, node.mapId);
          map.revision += 1;
          return {
            occurrenceId: node.id, cardId: node.cardId, promotedChildren: children.map(function (c) { return c.id; })
          };
        }
      },

      /* "remove the source marker only" vs "delete the card" are different
         entries on purpose (docs/02 §6). */
      detachPlacement: {
        description: 'Remove only the margin placement; the card and its anchor are kept.',
        apply: function (vault, ids, p) {
          var before = vault.marginPlacements.length;
          var row = byId(vault.marginPlacements, p.placementId);
          if (!row) fail('PLACEMENT_NOT_FOUND', 'no margin placement ' + p.placementId);
          vault.marginPlacements = vault.marginPlacements.filter(function (x) { return x.id !== p.placementId; });
          return { placementId: p.placementId, removed: before - vault.marginPlacements.length, cardId: row.cardId };
        }
      },

      upsertMargin: {
        description: 'Give a card a margin placement bound to its anchor.',
        apply: function (vault, ids, p, ts) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          var anchorId = p.anchorId || card.sourceIds[0];
          if (!anchorId || !byId(vault.sourceAnchors, anchorId)) {
            fail('ANCHOR_REQUIRED', 'a margin placement needs a resolvable source anchor');
          }
          var rows = vault.marginPlacements.filter(function (m) { return m.cardId === p.cardId; });
          if (rows.length) {
            rows[0].layoutRevision += 1;
            if (p.preferredMode) rows[0].preferredMode = p.preferredMode;
            if (typeof p.preferredHeight === 'number') rows[0].insertionSpec.preferredHeight = p.preferredHeight;
            return { placementId: rows[0].id, layoutRevision: rows[0].layoutRevision };
          }
          var row = {
            id: ids.next(), cardId: p.cardId, blockIds: card.blockOrder.slice(),
            anchorId: anchorId, preferredMode: p.preferredMode || 'EDGE',
            insertionSpec: {
              kind: 'edge', side: 'right',
              preferredHeight: typeof p.preferredHeight === 'number' ? p.preferredHeight : 180,
              coordinateSpace: 'margin_local'
            },
            layoutRevision: 1, createdAt: ts
          };
          vault.marginPlacements.push(row);
          return { placementId: row.id, layoutRevision: 1 };
        }
      },

      /* One ReviewItem = one question identity. No rating, no due date, no FSRS
         in S1; the projection must not carry its own copy of the card body. */
      createReviewItem: {
        description: 'Create a manual question projection over the shared card content.',
        apply: function (vault, ids, p, ts) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          var refs = (p.answerBlockIds || card.blockOrder).map(function (blockId) {
            var block = byId(vault.blocks, blockId);
            if (!block || block.cardId !== card.id) {
              fail('ANSWER_BLOCK_FOREIGN', 'answer block does not belong to this card', { blockId: blockId });
            }
            return { cardId: card.id, blockId: blockId };
          });
          var item = {
            id: ids.next(), cardId: card.id, promptRevision: 1, headPromptRevisionId: null,
            promptSpec: { kind: 'text', question: p.question },
            answerRefs: refs,
            maskSpec: { kind: 'none', blockIds: [] },
            sourcePolicy: { kind: 'FOLLOW_HEAD_ON_OPEN_PIN_DURING_SESSION' },
            lifecycle: 'READY',
            createdAt: ts
          };
          var revision = {
            id: ids.next(), reviewItemId: item.id, cardId: card.id, promptRevision: 1,
            promptSpec: clone(item.promptSpec), answerRefs: clone(refs),
            maskSpec: clone(item.maskSpec), sourcePolicy: clone(item.sourcePolicy), createdAt: ts
          };
          item.headPromptRevisionId = revision.id;
          vault.reviewItems.push(item);
          vault.reviewItemRevisions.push(revision);
          vault.reviewStates.push({
            reviewItemId: item.id,
            learnerScope: p.learnerScope || 'local-learner',
            schedulerVersion: 'UNASSIGNED',
            scheduleGeneration: 1,
            statePayload: { dueAt: null },
            createdAt: ts
          });
          return { reviewItemId: item.id, promptRevisionId: revision.id };
        }
      },

      /* Correction path for an expected-versions conflict: reads the freshest
         head, keeps prior history, and never edits a revision in place.
         The edit lands in a SEPARATE comment block so two views' committed text
         both survive instead of one silently overwriting the other. */
      commitResolveConflict: {
        description: 'Re-read the current head and re-apply the edit against it.',
        apply: function (vault, ids, p, ts) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          var block = IW.makeBlock(vault, ids, card.id, 'comment', 'text', { text: p.text }, null);
          vault.blockRevisions.forEach(function (r) { if (r.blockId === block.id) r.createdAt = ts; });
          card.blockOrder.push(block.id);
          var revision = IW.commitCardRevision(vault, ids, card.id, {}, ts);
          return {
            cardId: card.id,
            cardRevisionId: revision.id,
            revision: revision.revision,
            resolvedFrom: p.observedCardRevisionId
          };
        }
      },

      trashCard: {
        description: 'Move the card to the trash by tombstone; placements and nodes are suspended, not deleted.',
        apply: function (vault, ids, p, ts) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          if (card.trashedAt) fail('CARD_ALREADY_TRASHED', 'card is already in the trash');
          card.trashedAt = ts;
          return {
            cardId: card.id, trashedAt: ts,
            suspended: {
              occurrences: vault.occurrences.filter(function (o) { return o.cardId === card.id; }).map(function (o) { return o.id; }),
              marginPlacements: vault.marginPlacements.filter(function (m) { return m.cardId === card.id; }).map(function (m) { return m.id; }),
              reviewItems: vault.reviewItems.filter(function (r) { return r.cardId === card.id; }).map(function (r) { return r.id; })
            }
          };
        }
      },

      restoreCard: {
        description: 'Clear the tombstone. Same identity, same history.',
        apply: function (vault, ids, p) {
          var card = byId(vault.cards, p.cardId);
          if (!card) fail('CARD_NOT_FOUND', 'no card ' + p.cardId);
          if (!card.trashedAt) fail('CARD_NOT_TRASHED', 'card is not in the trash');
          card.trashedAt = null;
          return { cardId: card.id, trashedAt: null };
        }
      },

      /* Vault epoch: an old session must never write back over replaced data. */
      resetVaultEpoch: {
        description: 'Bump the vault epoch. Commands carrying the old epoch are refused.',
        apply: function (vault, ids, p, ts) {
          vault.epoch += 1;
          return { epoch: vault.epoch, at: ts };
        }
      }
    };
  }

  /* Shared by markHighlighter / createCardFromSelection.

     A selection is resolved through the surface into per-page line groups and
     exactly one anchor is emitted per page. The UI therefore never hands the
     engine a hand-built rectangle it could get wrong, and a cross-page drag
     cannot be flattened into one giant box (AX03-A02). */
  function buildAnchors(vault, ids, p, ts) {
    var version = byId(vault.documentVersions, p.sourceVersion);
    if (!version) fail('SOURCE_VERSION_NOT_FOUND', 'no source version ' + p.sourceVersion);
    if (p.resolutionState === 'RESTRICTED') fail('SOURCE_RESTRICTED', 'source is not readable');
    var surface = p.surface;
    if (!surface || typeof surface.offsetsToLines !== 'function') {
      fail('SURFACE_REQUIRED', 'a document surface is required to resolve a selection');
    }
    var start = p.startOffset;
    var end = p.endOffset;
    if (typeof start !== 'number' || typeof end !== 'number' || !(end > start)) {
      fail('EMPTY_SELECTION', 'an excerpt needs a non-empty selection');
    }
    var pageIds = surface.listPageIds();
    var grouped = [];
    pageIds.forEach(function (pageId) {
      var lines = surface.offsetsToLines(pageId, start, end);
      /* Keep only the lines the selection really covers, and clip each one to the
         selection. Without the clip, a drag ending on the second line of a page
         would drag the rest of that page into the card. */
      var kept = lines.map(function (l) {
        return {
          id: l.id,
          text: l.text.slice(Math.max(0, start - l.startOffset), Math.min(l.text.length, end - l.startOffset)),
          rect: l.rect,
          startOffset: Math.max(l.startOffset, start),
          endOffset: Math.min(l.endOffset, end)
        };
      }).filter(function (l) { return l.endOffset > l.startOffset; });
      if (kept.length) grouped.push({ pageId: pageId, lines: kept });
    });
    if (!grouped.length) fail('EMPTY_SELECTION', 'the selection did not intersect any text line');

    var quote = grouped.map(function (g) {
      return g.lines.map(function (l) { return l.text; }).join('\n');
    }).join('\n');

    return {
      quote: quote,
      anchors: grouped.map(function (g) {
        var first = g.lines[0];
        var last = g.lines[g.lines.length - 1];
        return {
          id: ids.next(),
          documentId: p.documentId,
          pageId: g.pageId,
          sourceVersion: p.sourceVersion,
          selector: {
            kind: 'text_flow_region',
            frame: 'synthetic-normalized-top-left-v1',
            lines: g.lines.map(function (l) { return l.id; }),
            rectConvention: 'xywh',
            rects: g.lines.map(function (l) { return l.rect; }),
            textQuote: { exact: g.lines.map(function (l) { return l.text; }).join('\n') },
            textOffsets: {
              start: Math.max(first.startOffset, start),
              end: Math.min(last.endOffset, end),
              unit: 'utf16_code_unit',
              textRevision: 't1'
            }
          },
          resolutionState: 'EXACT_SYNTHETIC',
          createdAt: ts
        };
      })
    };
  }

  function anchorFromSelection(vault, ids, p, ts) {
    return buildAnchors(vault, ids, p, ts).anchors[0];
  }

  function attach(vault, ids, mapId, cardId, parentOccurrenceId, ts) {
    if (!byId(vault.cards, cardId)) fail('CARD_NOT_FOUND', 'no card ' + cardId);
    var node = {
      id: ids.next(), mapId: mapId, cardId: cardId,
      parentOccurrenceId: parentOccurrenceId || null,
      siblingOrder: nextMapOrder(vault, mapId),
      layoutOverride: {
        position: { x: 80 + 160 * nextMapOrder(vault, mapId), y: 60 },
        pinned: true,
        /* Per-occurrence presentation. Deliberately a constant, NOT inherited from
           the source node, so a resize can never leak between map positions. */
        width: 220
      },
      collapsed: false,
      createdAt: ts
    };
    vault.occurrences.push(node);
    var map = byId(vault.maps, mapId);
    map.revision += 1;
    return node;
  }

  /* ------------------------------------------------------------- versioning */
  function snapshotVersions(vault, cardId) {
    var versions = {
      vault: { epoch: vault.epoch },
      card: {}, block: {}, reviewItem: {}, map: {}, occurrence: {}, margin: {}, document: {}
    };
    vault.cards.forEach(function (c) { versions.card[c.id] = c.revision; });
    vault.blocks.forEach(function (b) { versions.block[b.id] = b.revision; });
    vault.reviewItems.forEach(function (r) { versions.reviewItem[r.id] = r.promptRevision; });
    vault.maps.forEach(function (m) { versions.map[m.id] = m.revision; });
    vault.occurrences.forEach(function (o) { versions.occurrence[o.id] = o.siblingOrder; });
    vault.marginPlacements.forEach(function (m) { versions.margin[m.id] = m.layoutRevision; });
    vault.documents.forEach(function (d) { versions.document[d.id] = d.headVersionId; });
    if (cardId) versions.cardId = cardId;
    return versions;
  }

  /* Every identity-bearing collection, so id reservation covers the whole vault. */
  var ID_COLLECTIONS = [
    'studySets', 'documents', 'documentVersions', 'cards', 'cardRevisions', 'blocks',
    'blockRevisions', 'sourceAnchors', 'annotationPlacements', 'marginPlacements',
    'maps', 'occurrences', 'reviewItems', 'reviewItemRevisions', 'reviewStates',
    'recallSessions'
  ];

  function collectIds(vault) {
    var rows = [];
    ID_COLLECTIONS.forEach(function (name) {
      (vault[name] || []).forEach(function (row) { rows.push(row); });
    });
    return rows;
  }

  /* Advance the generator until `ids.peek()` is not already used. `ids.peek()`
     alone is enough: a command mints ids consecutively, so making the first one
     fresh makes the rest fresh too. This is what lets a reloaded engine replay a
     command instead of colliding with ids it restored from storage. */
  function advancePastCollisions(ids, rows) {
    var used = {};
    rows.forEach(function (row) {
      if (!row) return;
      if (typeof row.id === 'string') used[row.id] = true;
      if (typeof row.reviewItemId === 'string') used[row.reviewItemId] = true;
    });
    for (var i = 0; i < 4096; i++) {
      if (!used[ids.peek()]) return true;
      ids.setCounter(ids.counter() + 1);
    }
    return false;
  }

  function checkExpectedVersions(vault, expected) {    if (!expected) return;
    var live = snapshotVersions(vault);
    var groups = ['card', 'block', 'reviewItem', 'map', 'occurrence', 'margin'];
    for (var g = 0; g < groups.length; g++) {
      var group = groups[g];
      var want = expected[group] || {};
      var keys = Object.keys(want);
      for (var i = 0; i < keys.length; i++) {
        var liveValue = live[group][keys[i]];
        if (liveValue === undefined) {
          return { code: 'EXPECTED_TARGET_MISSING', message: group + ' ' + keys[i] + ' no longer exists' };
        }
        if (liveValue !== want[keys[i]]) {
          return {
            code: 'EXPECTED_VERSION_MISMATCH',
            message: group + ' ' + keys[i] + ' expected ' + want[keys[i]] + ' but head is ' + liveValue,
            details: { group: group, id: keys[i], expected: want[keys[i]], actual: liveValue }
          };
        }
      }
    }
    if (expected.epoch !== undefined && expected.epoch !== vault.epoch) {
      return {
        code: 'VAULT_EPOCH_MISMATCH',
        message: 'this command belongs to vault epoch ' + expected.epoch + ' but the vault is at ' + vault.epoch
      };
    }
    return null;
  }

  /* ---------------------------------------------------------------- engine */
  function createEngine(options) {
    var opts = options || {};
    var ids = opts.ids || IW.ids('inkweft-sample');
    var clock = opts.clock || IW.clock(Date.parse('2026-09-23T00:00:00Z'), 1000);
    var vault = opts.vault ? clone(opts.vault) : IW.emptyVault(opts.vaultId || ids.next());
    var state = opts.state ? clone(opts.state) : emptyState();
    var persistHook = opts.persist || null;
    /* capabilityHandle -> { grants: { versionId: true | false } }
       An explicit false is a denial; an absent key falls back to the '*'
       wildcard, which is the local single-user default. */
    var grants = { '*': true };
    (opts.grants || []).forEach(function (v) { grants[v] = true; });
    if (opts.revoked) opts.revoked.forEach(function (v) { grants[v] = false; });
    var commands = buildCommands(grants);

    var listeners = [];

    function notify(event) {
      listeners.forEach(function (fn) { try { fn(event); } catch (e) { /* a view must not break a commit */ } });
    }

    function receiptFor(commandId) {
      for (var i = 0; i < state.receipts.length; i++) {
        if (state.receipts[i].commandId === commandId) return state.receipts[i];
      }
      return null;
    }

    function authFor(command, params) {
      var version = params.sourceVersion;
      if (!version) return null;
      if (!granted(grants, version)) {
        return { code: 'SOURCE_RESTRICTED', message: 'the current grant does not allow reading this source version' };
      }
      return null;
    }

    function run(commandType, params, header) {
      var h = header || {};
      COMMAND_HEADER.forEach(function (key) {
        if (!h[key]) fail('MALFORMED_HEADER', 'command header is missing ' + key, { command: commandType });
      });
      var command = commands[commandType];
      if (!command) fail('UNKNOWN_COMMAND', 'no such command: ' + commandType);

      /* 1. Authorization is evaluated against the live grant, every time. */
      var authError = authFor(command, params);
      if (authError) fail(authError.code, authError.message);

      /* 1b. Command-specific preconditions also run before any replay may be
             answered. The plan requires the CURRENT permission to be re-checked
             before an old receipt is returned (AX34-A03), so a revocation must
             be able to invalidate a previously successful commandId. */
      if (command.pre) {
        var early = command.pre(vault, params);
        if (early) fail(early.code, early.message, early.details);
      }

      /* 2. Digest the semantic payload. Refreshable secrets, retry timestamps and
            trace ids are deliberately excluded, so a retry after token rotation
            still matches while a changed payload never does. */
      var digestInput = {
        commandType: commandType,
        vaultId: vault.vaultId,
        actorId: h.actorId,
        payload: params,
        expectedVersions: h.expectedVersions || null,
        epoch: (h.expectedVersions && h.expectedVersions.epoch !== undefined)
          ? h.expectedVersions.epoch : vault.epoch
      };
      var semantic = digest(digestInput);
      var capabilityDigest = digest({ handle: String(h.capabilityHandle).replace(/[0-9]{4}$/, '****') });
      semantic = digest({ semantic: semantic, capability: capabilityDigest });

      /* 3. Replay or refuse. An existing receipt is authoritative. */
      var existing = receiptFor(h.commandId);
      if (existing) {
        if (existing.digest === semantic) {
          return {
            status: 'REPLAYED', code: 'RECEIPT_REPLAYED', replayed: true,
            receipt: clone(existing),
            message: 'same commandId and semantic payload: original receipt returned, nothing re-applied'
          };
        }
        fail('COMMAND_ID_REUSE',
          'commandId ' + h.commandId + ' was used with a different payload or expected versions',
          { commandId: h.commandId, originalDigest: existing.digest, incomingDigest: semantic });
      }

      /* 4. Preconditions against the moving parts are checked against the live
            head (the semantic preconditions already ran in step 1b). */
      var preError = checkExpectedVersions(vault, h.expectedVersions);
      if (preError) fail(preError.code, preError.message, preError.details);

      /* 5. Apply to a copy, verify the whole reference closure, then publish.
            If a minted id collides with one already in the vault (only possible
            after a reload), advance the generator and apply again — never repair
            the vault by hand, or the transaction guarantee becomes a lie. */
      var preImage = clone(vault);
      var draft, result, problems;
      var attempts = 0;
      for (;;) {
        draft = clone(vault);
        try {
          result = command.apply(draft, ids, params, clock.iso());
        } catch (err) {
          /* Nothing was published: the vault is byte-identical to the pre-image. */
          throw err;
        }
        problems = IW.checkInvariants(draft);
        if (problems.length) {
          /* Only treat a DUPLICATE-ID problem as retryable: every other invariant
             failure is a real refusal and must not be retried away. */
          var idProblems = problems.filter(function (p) { return /duplicate id/.test(p); });
          if (idProblems.length && ++attempts < 8) {
            if (advancePastCollisions(ids, collectIds(draft))) continue;
          }
        }
        break;
      }
      if (problems.length) {
        fail('INVARIANT_VIOLATION', 'the command would leave an incomplete reference closure', {
          problems: problems, commandType: commandType
        });
      }

      var receipt = {
        commandId: h.commandId,
        commandType: commandType,
        digest: semantic,
        actorId: h.actorId,
        capabilityHandle: String(h.capabilityHandle),
        at: clock.iso(),
        result: result,
        preImage: preImage,
        receiptRevision: state.sequence + 1,
        undoState: 'AVAILABLE'
      };

      /* 6. Persist before acknowledging. A failing store must leave no receipt. */
      var nextState = {
        receipts: state.receipts.concat([receipt]),
        undone: clone(state.undone),
        sequence: state.sequence + 1
      };
      if (persistHook) persistHook({ vault: draft, state: nextState, receipt: receipt });

      vault = draft;
      state = nextState;
      notify({ type: 'commit', commandType: commandType, receipt: receipt, vault: vault });
      return { status: 'ACK', code: 'COMMITTED', replayed: false, receipt: clone(receipt) };
    }

    function undo(header) {
      COMMAND_HEADER.forEach(function (key) {
        if (!header || !header[key]) fail('MALFORMED_HEADER', 'undo needs a full command header');
      });
      var targetId = header.targetCommandId;
      if (!targetId) fail('UNDO_TARGET_REQUIRED', 'undo needs targetCommandId');
      var target = receiptFor(targetId);
      if (!target) fail('UNDO_TARGET_NOT_FOUND', 'no receipt ' + targetId);
      if (state.undone[targetId]) fail('ALREADY_UNDONE', 'that command was already undone');
      /* Only the newest reversible command may be undone, so an old restore can
         never overwrite newer work from another view. */
      var live = state.receipts.filter(function (r) { return !state.undone[r.commandId]; });
      var newest = live[live.length - 1];
      if (!newest || newest.commandId !== targetId) {
        fail('UNDO_NOT_LATEST', 'only the most recent command can be undone in this sample', {
          newest: newest ? newest.commandId : null
        });
      }
      var restored = clone(target.preImage);
      var preImage = clone(vault);
      var receipt = {
        commandId: header.commandId,
        commandType: 'undo',
        digest: digest({ commandType: 'undo', target: targetId, epoch: vault.epoch }),
        actorId: header.actorId,
        capabilityHandle: String(header.capabilityHandle),
        at: clock.iso(),
        result: { undoneCommandId: targetId, invertedCommandType: target.commandType },
        preImage: preImage,
        receiptRevision: state.sequence + 1,
        undoState: 'AVAILABLE'
      };
      var nextState = {
        receipts: state.receipts.concat([receipt]),
        undone: clone(state.undone),
        sequence: state.sequence + 1
      };
      nextState.undone[targetId] = receipt.commandId;
      if (persistHook) persistHook({ vault: restored, state: nextState, receipt: receipt });
      vault = restored;
      state = nextState;
      notify({ type: 'undo', receipt: receipt, vault: vault });
      return { status: 'ACK', code: 'UNDONE', receipt: clone(receipt), undoneCommandId: targetId };
    }

    function history() {
      return state.receipts.map(function (r) {
        return {
          commandId: r.commandId,
          commandType: r.commandType,
          at: r.at,
          digest: r.digest,
          undone: !!state.undone[r.commandId] || r.commandType === 'undo',
          result: clone(r.result),
          undoable: !state.undone[r.commandId] && r.commandType !== 'undo'
        };
      });
    }

    function read(fn) { return fn({ vault: vault, state: state, grants: grants }); }

    return {
      vaultId: vault.vaultId,
      ids: ids,
      clock: clock,
      commands: commands,
      run: run,
      undo: undo,
      history: history,
      receiptFor: receiptFor,
      receiptRevision: function () { return state.sequence; },
      getVault: function () { return vault; },
      getState: function () { return state; },
      setVault: function (nextVault, nextState) { vault = nextVault; state = nextState || emptyState(); },
      grants: grants,
      /* Revoking must leave an explicit denial behind: deleting the key would let
         the '*' default silently re-grant access. */
      setGrant: function (versionId, isGranted) {
        grants[versionId] = !!isGranted;
      },
      read: read,
      subscribe: function (fn) { listeners.push(fn); return function () { listeners = listeners.filter(function (x) { return x !== fn; }); }; },
      snapshotVersions: function (cardId) { return snapshotVersions(vault, cardId); },
      checkInvariants: function () { return IW.checkInvariants(vault); },
      exportPackage: function () { return IW.exportPackage(vault, state); }
    };
  }

  /* `grants` is handed to buildCommands at engine creation, so authorization has
     no hidden global and the same object is what setGrant mutates. */
  IW.createEngine = createEngine;
  IW.COMMAND_HEADER = COMMAND_HEADER;
}(globalThis.IW = globalThis.IW || {}));
