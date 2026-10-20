package com.github.sup3rmar10.searchindex;

import java.util.Arrays;

/** One document's occurrences of one term: segment-local doc number and sorted token positions. Immutable. */
public final class Posting {

    private final int doc;
    private final int[] positions;

    /** {@code positions} must be strictly increasing; the array is not copied, so callers inside the package must not reuse it. */
    Posting(int doc, int[] positions) {
        this.doc = doc;
        this.positions = positions;
    }

    public int doc() {
        return doc;
    }

    /** Term frequency in the document. */
    public int freq() {
        return positions.length;
    }

    public int position(int i) {
        return positions[i];
    }

    public boolean hasPosition(int position) {
        return Arrays.binarySearch(positions, position) >= 0;
    }

    public int[] positions() {
        return positions.clone();
    }

    Posting withDoc(int newDoc) {
        return new Posting(newDoc, positions);
    }
}
