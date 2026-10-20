package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TopKTest {

    private static List<String> ids(List<Hit> hits) {
        return hits.stream().map(Hit::id).collect(Collectors.toList());
    }

    @Test
    void keepsTheBestKInOrder() {
        TopK top = new TopK(3);
        top.offer("a", 1.0);
        top.offer("b", 5.0);
        top.offer("c", 3.0);
        top.offer("d", 4.0);
        top.offer("e", 0.5);
        assertEquals(List.of("b", "d", "c"), ids(top.toSortedList()));
    }

    @Test
    void returnsFewerThanKWhenFewerOffered() {
        TopK top = new TopK(10);
        top.offer("x", 2.0);
        top.offer("y", 3.0);
        assertEquals(List.of("y", "x"), ids(top.toSortedList()));
        assertEquals(2, top.size());
    }

    @Test
    void tiesBreakByIdAscendingRegardlessOfArrivalOrder() {
        TopK one = new TopK(2);
        for (String id : new String[] {"c", "a", "b"}) {
            one.offer(id, 1.0);
        }
        TopK two = new TopK(2);
        for (String id : new String[] {"b", "c", "a"}) {
            two.offer(id, 1.0);
        }
        assertEquals(List.of("a", "b"), ids(one.toSortedList()));
        assertEquals(ids(one.toSortedList()), ids(two.toSortedList()));
    }

    @Test
    void aTieForTheLastSlotKeepsTheSmallerId() {
        TopK top = new TopK(1);
        top.offer("m", 2.0);
        top.offer("b", 2.0);
        top.offer("z", 2.0);
        assertEquals(List.of("b"), ids(top.toSortedList()));
    }

    @Test
    void nonPositiveKIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TopK(0));
        assertThrows(IllegalArgumentException.class, () -> new TopK(-3));
    }

    @Test
    void emptyCollectorGivesAnEmptyList() {
        assertTrue(new TopK(5).toSortedList().isEmpty());
    }

    @Test
    void matchesAFullSortOnRandomScores() {
        Random rnd = new Random(1234);
        for (int round = 0; round < 50; round++) {
            int n = 1 + rnd.nextInt(200);
            int k = 1 + rnd.nextInt(20);
            List<Hit> all = new ArrayList<>();
            TopK top = new TopK(k);
            for (int i = 0; i < n; i++) {
                Hit h = new Hit("id" + rnd.nextInt(1000) + "-" + i, rnd.nextInt(30) / 4.0); // many ties
                all.add(h);
                top.offer(h.id(), h.score());
            }
            all.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id));
            assertEquals(all.subList(0, Math.min(k, n)), top.toSortedList(), "round " + round);
        }
    }

    @Test
    void returnedListCannotBeModified() {
        TopK top = new TopK(2);
        top.offer("a", 1);
        List<Hit> list = top.toSortedList();
        assertThrows(UnsupportedOperationException.class, () -> list.add(new Hit("z", 9)));
    }
}
