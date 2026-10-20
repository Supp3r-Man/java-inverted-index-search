package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private static final double LOG2_3 = Math.log(3) / Math.log(2);

    @Test
    void perfectRankingHasNdcgOne() {
        Map<String, Integer> g = Map.of("a", 2, "b", 1);
        assertEquals(1.0, Metrics.ndcgAtK(List.of("a", "b", "x"), g, 10), 1e-12);
    }

    @Test
    void swappedRankingHasTheExpectedNdcg() {
        Map<String, Integer> g = Map.of("a", 2, "b", 1);
        double dcg = 1.0 + 3.0 / LOG2_3;
        double idcg = 3.0 + 1.0 / LOG2_3;
        assertEquals(dcg / idcg, Metrics.ndcgAtK(List.of("b", "a"), g, 10), 1e-12);
    }

    @Test
    void irrelevantResultsScoreZero() {
        assertEquals(0.0, Metrics.ndcgAtK(List.of("x", "y"), Map.of("a", 1), 10));
    }

    @Test
    void noRelevantDocumentsGivesZeroByConvention() {
        assertEquals(0.0, Metrics.ndcgAtK(List.of("x"), Map.of(), 10));
        assertEquals(0.0, Metrics.recallAtK(List.of("x"), Map.of(), 10));
    }

    @Test
    void ndcgOnlyLooksAtTheTopK() {
        Map<String, Integer> g = Map.of("a", 1);
        assertEquals(0.0, Metrics.ndcgAtK(List.of("x", "y", "a"), g, 2));
        assertEquals(1.0 / 2.0, Metrics.ndcgAtK(List.of("x", "y", "a"), g, 3), 1e-12);
    }

    @Test
    void idealDcgIsTruncatedToKToo() {
        Map<String, Integer> g = Map.of("a", 2, "b", 2, "c", 2);
        assertEquals(1.0, Metrics.ndcgAtK(List.of("a"), g, 1), 1e-12);
    }

    @Test
    void higherGradesAreWorthMoreThanLowerOnes() {
        Map<String, Integer> g = Map.of("hi", 2, "lo", 1);
        assertTrue(Metrics.ndcgAtK(List.of("hi"), g, 5) > Metrics.ndcgAtK(List.of("lo"), g, 5));
    }

    @Test
    void recallCountsRelevantDocumentsFoundInTheTopK() {
        Map<String, Integer> g = Map.of("a", 1, "b", 2, "c", 1, "d", 1);
        assertEquals(0.5, Metrics.recallAtK(List.of("a", "x", "b", "y"), g, 4), 1e-12);
        assertEquals(0.25, Metrics.recallAtK(List.of("a", "x", "b", "y"), g, 2), 1e-12);
        assertEquals(1.0, Metrics.recallAtK(List.of("a", "b", "c", "d"), g, 4), 1e-12);
    }

    @Test
    void gradeZeroDoesNotCountAsRelevant() {
        Map<String, Integer> g = Map.of("a", 0, "b", 1);
        assertEquals(0.0, Metrics.recallAtK(List.of("a"), g, 5));
        assertEquals(1.0, Metrics.recallAtK(List.of("a", "b"), g, 5), 1e-12);
    }

    @Test
    void invalidKIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Metrics.ndcgAtK(List.of(), Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> Metrics.recallAtK(List.of(), Map.of(), -1));
    }
}
