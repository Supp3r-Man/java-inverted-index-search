package com.github.sup3rmar10.searchindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Keeps the best {@code k} hits using a bounded min-heap: O(n log k) time, O(k) memory.
 * Ordering is score descending, then id ascending, so results do not depend on arrival order
 * or on how documents are spread across segments.
 */
public final class TopK {

    /** Best first. */
    static final Comparator<Hit> BEST_FIRST = (a, b) -> {
        int c = Double.compare(b.score(), a.score());
        return c != 0 ? c : a.id().compareTo(b.id());
    };

    private final int k;
    private final PriorityQueue<Hit> heap;   // head = the worst hit currently kept

    public TopK(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, was " + k);
        }
        this.k = k;
        this.heap = new PriorityQueue<>(k, BEST_FIRST.reversed());
    }

    public void offer(String id, double score) {
        Hit h = new Hit(id, score);
        if (heap.size() < k) {
            heap.add(h);
        } else if (BEST_FIRST.compare(h, heap.peek()) < 0) {
            heap.poll();
            heap.add(h);
        }
    }

    public int size() {
        return heap.size();
    }

    /** The kept hits, best first. */
    public List<Hit> toSortedList() {
        List<Hit> out = new ArrayList<>(heap);
        out.sort(BEST_FIRST);
        return Collections.unmodifiableList(out);
    }
}
