"""Disjoint deterministic slices for large Android UI runs."""
def shard_count(expected_app):
    return 2 if expected_app > 40 else 1


def partition(cases, index, count):
    if count < 1 or not 0 <= index < count:
        raise ValueError("Invalid Android shard")
    return cases[index::count]
