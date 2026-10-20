package com.github.sup3rmar10.searchindex;

import java.util.List;

/**
 * Decides which segments to merge next. Returns a non-empty subset of the given segments to merge
 * into one, or an empty list when nothing should be merged. The index asks again after every merge
 * until the answer is empty, so a policy must make progress (merging fewer segments, or removing
 * tombstones, every time it returns something).
 */
@FunctionalInterface
public interface MergePolicy {

    List<Segment> select(List<Segment> segments);

    /** Never merges. Segments and tombstones accumulate until an explicit forceMerge. */
    MergePolicy NONE = segments -> List.of();
}
