package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SegmentTest {

    private static List<Map.Entry<String, String>> docs(String... idTextPairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < idTextPairs.length; i += 2) {
            out.add(Map.entry(idTextPairs[i], idTextPairs[i + 1]));
        }
        return out;
    }

    private static Segment build(String... idTextPairs) {
        return Segment.build(1, docs(idTextPairs), Analyzer.standard());
    }

    @Test
    void recordsPositionsAndFrequencies() {
        Segment s = build("d0", "a b a");
        Posting a = s.postings("a").get(0);
        assertEquals(2, a.freq());
        assertArrayEquals(new int[] {0, 2}, a.positions());
        assertTrue(a.hasPosition(2));
        assertFalse(a.hasPosition(1));
        assertEquals(1, s.postings("b").get(0).freq());
    }

    @Test
    void recordsLengthsAndDocumentCounts() {
        Segment s = build("d0", "a b a", "d1", "c");
        assertEquals(2, s.maxDoc());
        assertEquals(2, s.liveDocs());
        assertEquals(3, s.length(0));
        assertEquals(1, s.length(1));
        assertEquals(4, s.liveLength());
        assertEquals("d1", s.docId(1));
        assertEquals("c", s.text(1));
    }

    @Test
    void postingsAreSortedByDocument() {
        Segment s = build("d0", "x", "d1", "y", "d2", "x");
        List<Posting> x = s.postings("x");
        assertEquals(List.of(0, 2), List.of(x.get(0).doc(), x.get(1).doc()));
    }

    @Test
    void unknownTermHasNoPostings() {
        assertTrue(build("d0", "a").postings("zzz").isEmpty());
    }

    @Test
    void postingLookupByBinarySearchFindsOnlyDocumentsThatContainTheTerm() {
        Segment s = build("d0", "q", "d1", "x", "d2", "q", "d3", "x", "d4", "q", "d5", "x", "d6", "q");
        for (int d = 0; d < 7; d++) {
            boolean expected = d % 2 == 0;
            assertEquals(expected, s.posting("q", d) != null, "doc " + d);
        }
        assertNull(s.posting("missing", 0));
    }

    @Test
    void termsAreExposedInSortedOrder() {
        Segment s = build("d0", "pear apple mango");
        assertEquals(List.of("apple", "mango", "pear"), new ArrayList<>(s.terms()));
    }

    @Test
    void deletingReturnsANewSegmentAndLeavesTheOriginalUntouched() {
        Segment s = build("d0", "a", "d1", "a");
        Segment t = s.withDeleted(0);
        assertTrue(s.isLive(0));
        assertEquals(2, s.liveDocs());
        assertFalse(t.isLive(0));
        assertEquals(1, t.liveDocs());
        assertEquals(1, t.deletedDocs());
        assertEquals(0.5, t.deleteRatio(), 1e-12);
        assertEquals(s.id(), t.id());
    }

    @Test
    void deletingTwiceIsIdempotent() {
        Segment t = build("d0", "a", "d1", "a").withDeleted(1).withDeleted(1);
        assertEquals(1, t.liveDocs());
    }

    @Test
    void deleteOutOfRangeIsRejected() {
        Segment s = build("d0", "a");
        assertThrows(IndexOutOfBoundsException.class, () -> s.withDeleted(1));
        assertThrows(IndexOutOfBoundsException.class, () -> s.withDeleted(-1));
    }

    @Test
    void liveStatisticsIgnoreTombstonedDocuments() {
        Segment s = build("d0", "a a", "d1", "a b c");
        Segment t = s.withDeleted(1);
        assertEquals(2, s.liveDocFreq("a"));
        assertEquals(1, t.liveDocFreq("a"));
        assertEquals(0, t.liveDocFreq("b"));
        assertEquals(2, t.liveLength());
    }

    @Test
    void deletedDocumentsCannotBeFoundByExternalId() {
        Segment s = build("d0", "a", "d1", "b");
        assertEquals(1, s.localDoc("d1"));
        assertEquals(-1, s.withDeleted(1).localDoc("d1"));
        assertEquals(-1, s.localDoc("nope"));
    }

    @Test
    void duplicateIdsInOneSegmentAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> build("d0", "a", "d0", "b"));
    }

    @Test
    void positionsAccessorReturnsACopy() {
        Segment s = build("d0", "a a");
        int[] copy = s.postings("a").get(0).positions();
        copy[0] = 99;
        assertArrayEquals(new int[] {0, 1}, s.postings("a").get(0).positions());
    }

    @Test
    void mergeDropsTombstonedDocumentsAndRenumbersTheRest() {
        Segment a = Segment.build(1, docs("x", "red fox", "y", "red dog"), Analyzer.standard()).withDeleted(1);
        Segment b = Segment.build(2, docs("z", "blue fox fox"), Analyzer.standard());
        Segment m = Segment.merge(3, List.of(a, b));
        assertEquals(2, m.maxDoc());
        assertEquals(0, m.deletedDocs());
        assertEquals("x", m.docId(0));
        assertEquals("z", m.docId(1));
        assertEquals("blue fox fox", m.text(1));
        assertEquals(3, m.length(1));
        assertTrue(m.postings("dog").isEmpty());
        assertEquals(2, m.postings("fox").size());
        assertEquals(2, m.postings("fox").get(1).freq());
        assertEquals(1, m.postings("fox").get(1).doc());
        assertNotNull(m.posting("red", 0));
    }

    @Test
    void mergeKeepsPositionsIntact() {
        Segment a = build("x", "a b c");
        Segment m = Segment.merge(9, List.of(a, build("y", "c b a")));
        assertArrayEquals(new int[] {2}, m.posting("a", 1).positions());
        assertArrayEquals(new int[] {0}, m.posting("a", 0).positions());
    }

    @Test
    void mergingOnlyDeletedDocumentsGivesAnEmptySegment() {
        Segment a = build("x", "a").withDeleted(0);
        Segment m = Segment.merge(2, List.of(a));
        assertEquals(0, m.maxDoc());
        assertTrue(m.terms().isEmpty());
    }

    @Test
    void emptySegmentHasZeroDeleteRatio() {
        assertEquals(0.0, Segment.build(1, List.of(), Analyzer.standard()).deleteRatio());
    }
}
