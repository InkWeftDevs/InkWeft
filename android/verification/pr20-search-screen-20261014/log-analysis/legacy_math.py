# Exact quantiles/classify functions extracted from the immutable previous analyzer.
import math
import statistics


def quantiles(values):
    assert values and all(isinstance(x, (int, float)) and math.isfinite(x) and x >= 0 for x in values)
    ordered = sorted(values)
    return {'p50': ordered[math.ceil(len(values) * .5) - 1],
            'p95': ordered[math.ceil(len(values) * .95) - 1], 'max': ordered[-1]}


def classify(pairs, band):
    if len(pairs) != 4 or any(p['relative_change'] is None for p in pairs):
        return 'INCOMPLETE'
    if (sum(p['delta_ms'] > 0 for p in pairs) >= 3
            and statistics.median(p['relative_change'] for p in pairs) > band['relative']
            and statistics.median(p['delta_ms'] for p in pairs) > band['absolute_ms']):
        return 'REGRESSION_SIGNAL'
    return 'INCONCLUSIVE' if any(p['over_band'] for p in pairs) else 'WITHIN_PROPOSED_BAND'
