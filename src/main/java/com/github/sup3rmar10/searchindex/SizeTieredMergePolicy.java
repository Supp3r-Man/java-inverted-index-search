package com.github.sup3rmar10.searchindex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small size-tiered policy.
 * <ol>
 *   <li><b>Deletes first:</b> if a segment has tombstones making up at least {@code maxDeleteRatio} of
 *       its documents, rewrite that segment alone (this reclaims the space and drops the tombstones;
 *       a fully deleted segment simply disappears).</li>
 *   <li><b>Tiers:</b> a segment's tier is how many times its live document count can be divided by
 *       {@code mergeFactor} (integer division) before it drops below it. When the lowest tier holds
 *       {@code mergeFactor} or more segments, the first {@code mergeFactor} of them (index order)
 *       are merged. Merging small segments together this way keeps the number of segments around
 *       {@code mergeFactor * log_mergeFactor(N)} while each document is rewritten only O(log N) times.</li>
 * </ol>
 */
public final class SizeTieredMergePolicy implements MergePolicy {

    private final int mergeFactor;
    private final double maxDeleteRatio;

    public SizeTieredMergePolicy(int mergeFactor, double maxDeleteRatio) {
        if (mergeFactor < 2) {
            throw new IllegalArgumentException("mergeFactor must be >= 2, was " + mergeFactor);
        }
        if (!(maxDeleteRatio > 0 && maxDeleteRatio <= 1)) {
            throw new IllegalArgumentException("maxDeleteRatio must be in (0, 1], was " + maxDeleteRatio);
        }
        this.mergeFactor = mergeFactor;
        this.maxDeleteRatio = maxDeleteRatio;
    }

    @Override
    public List<Segment> select(List<Segment> segments) {
        for (Segment s : segments) {
            if (s.deletedDocs() > 0 && s.deleteRatio() >= maxDeleteRatio) {
                return List.of(s);
            }
        }
        Map<Integer, List<Segment>> tiers = new TreeMap<>();
        for (Segment s : segments) {
            tiers.computeIfAbsent(tier(s.liveDocs()), t -> new ArrayList<>()).add(s);
        }
        for (List<Segment> group : tiers.values()) {
            if (group.size() >= mergeFactor) {
                return List.copyOf(group.subList(0, mergeFactor));
            }
        }
        return List.of();
    }

    int tier(int liveDocs) {
        int tier = 0;
        int size = liveDocs;
        while (size >= mergeFactor) {
            size /= mergeFactor;
            tier++;
        }
        return tier;
    }
}
