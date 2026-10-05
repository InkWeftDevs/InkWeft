"""Disjoint deterministic slices for large Android UI runs."""
def shard_count(expected_app):
    return 2 if expected_app > 40 else 1


def partition(cases, index, count):
    if count < 1 or not 0 <= index < count:
        raise ValueError("Invalid Android shard")
    return cases[index::count]


def diagnostic_cases(cases, mode, rounds):
    """Repeat observations, never retry a failed result; callers stop on first failure."""
    if rounds not in (1, 3) or (rounds != 1 and mode != "focused"):
        raise ValueError("Diagnostic rounds require explicit focused scope and a bound of three")
    return [(round_id, selection, expected) for round_id in range(1, rounds + 1)
            for selection, expected in cases]
