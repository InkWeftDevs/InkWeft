"""Offline, consented handwriting evaluation. Never uploads samples or includes text in reports."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import unicodedata


def edits(reference, hypothesis):
    """Return deterministic character edit operations, preserving spaces and punctuation."""
    a, b = list(unicodedata.normalize('NFC', reference)), list(unicodedata.normalize('NFC', hypothesis))
    previous = [(j, [('insert', '', c) for c in b[:j]]) for j in range(len(b) + 1)]
    for i, x in enumerate(a, 1):
        current = [(i, [('delete', c, '') for c in a[:i]])]
        for j, y in enumerate(b, 1):
            diagonal = previous[j-1]
            choices = [(diagonal[0] + (x != y), diagonal[1] + ([] if x == y else [('substitute', x, y)])),
                       (previous[j][0] + 1, previous[j][1] + [('delete', x, '')]),
                       (current[j-1][0] + 1, current[j-1][1] + [('insert', '', y)])]
            current.append(min(choices, key=lambda v: v[0]))
        previous = current
    return len(a), previous[-1][1]


def evaluate(rows):
    seen, writer_splits, hashes = set(), collections.defaultdict(set), collections.defaultdict(set)
    for r in rows:
        required = {'id', 'writer', 'split', 'source_sha256', 'consent', 'reference', 'hypothesis', 'automatic'}
        if not required <= r.keys():
            raise ValueError('Missing fields: ' + ', '.join(sorted(required-r.keys())))
        if r['id'] in seen or not r['id'] or not r['writer']:
            raise ValueError('Duplicate/empty sample identity')
        seen.add(r['id'])
        if r['consent'] is not True or type(r['automatic']) is not bool:
            raise ValueError('Consent and explicit automatic decision required')
        if r['split'] not in ('development', 'calibration', 'evaluation'):
            raise ValueError('Unknown split')
        digest = r['source_sha256']
        if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
            raise ValueError('Source SHA-256 required')
        if not isinstance(r['reference'], str) or not r['reference'] or not isinstance(r['hypothesis'], str):
            raise ValueError('Verified non-empty reference required')
        if max(len(r['reference']), len(r['hypothesis'])) > 1000:
            raise ValueError('Split samples into at most 1000 characters')
        writer_splits[r['writer']].add(r['split'])
        hashes[digest].add(r['split'])
    if any('evaluation' in s and len(s) > 1 for s in [*writer_splits.values(), *hashes.values()]):
        raise ValueError('Evaluation writer/source overlaps development or calibration')
    report = {'normalization': 'NFC; spaces, case and punctuation retained', 'splits': {}}
    for split in ('development', 'calibration', 'evaluation'):
        group = [r for r in rows if r['split'] == split]
        counts = collections.Counter(); confusions = collections.Counter()
        for r in group:
            n, operations = edits(r['reference'], r['hypothesis'])
            counts.update(segments=1, characters=n, edits=len(operations))
            counts.update(op[0] for op in operations)
            for kind, a, b in operations:
                # Only symbol/number confusion pairs; no private word fragments in output.
                if all(not c or c.isdecimal() or unicodedata.category(c)[0] in 'PSZ' for c in (a, b)):
                    confusions[f'{kind}:{a!r}→{b!r}'] += 1
            if r['automatic']:
                counts.update(accepted_segments=1, accepted_characters=n, accepted_edits=len(operations),
                              accepted_wrong_segments=int(bool(operations)))
            else:
                counts.update(review_segments=1, review_minimum_edits=len(operations))
        def ratio(a, b): return counts[a] / counts[b] if counts[b] else None
        report['splits'][split] = {'writers': len({r['writer'] for r in group}), **dict(counts),
            'cer': ratio('edits', 'characters'), 'automatic_segment_coverage': ratio('accepted_segments', 'segments'),
            'automatic_character_coverage': ratio('accepted_characters', 'characters'),
            'accepted_cer': ratio('accepted_edits', 'accepted_characters'),
            'symbol_confusions': dict(confusions), 'status': 'MEASURED' if group else 'NOT_RUN'}
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path, help='Private JSONL; keep outside Git repository')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    raw = args.input.read_bytes()
    result = evaluate([json.loads(line) for line in raw.decode('utf-8-sig').splitlines() if line.strip()])
    result['input_sha256'] = hashlib.sha256(raw).hexdigest()
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
