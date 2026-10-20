package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MergePolicyTest {

    private static Segment seg(long id, int docs) {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (int i = 0; i < docs; i++) {
            list.add(Map.entry("s" + id + "-" + i, "word" + i));
        }
        return Segment.build(id, list, Analyzer.standard());
    }

    @Test
    void noneNeverSelectsAnything() {
        assertTrue(MergePolicy.NONE.select(List.of(seg(1, 1), seg(2, 1), seg(3, 1))).isEmpty());
    }

    @Test
    void tiersGrowByTheMergeFactor() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(4, 0.5);
        assertEquals(0, p.tier(0));
        assertEquals(0, p.tier(3));
        assertEquals(1, p.tier(4));
        assertEquals(1, p.tier(15));
        assertEquals(2, p.tier(16));
        assertEquals(3, p.tier(64));
    }

    @Test
    void doesNothingBelowTheMergeFactor() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(3, 0.5);
        assertTrue(p.select(List.of(seg(1, 1), seg(2, 1))).isEmpty());
    }

    @Test
    void mergesTheFirstMergeFactorSegmentsOfTheSameTier() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(3, 0.5);
        Segment a = seg(1, 1);
        Segment b = seg(2, 3);   // tier 1 with mergeFactor 3, so it is skipped
        Segment c = seg(3, 1);
        Segment d = seg(4, 1);
        Segment e = seg(5, 1);
        List<Segment> picked = p.select(List.of(a, b, c, d, e));
        assertEquals(3, picked.size());
        assertSame(a, picked.get(0));
        assertSame(c, picked.get(1));
        assertSame(d, picked.get(2));
    }

    @Test
    void segmentsOfDifferentTiersAreNotMixed() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(2, 0.5);
        // sizes 1 (tier 0), 2 (tier 1), 4 (tier 2): one per tier
        assertTrue(p.select(List.of(seg(1, 1), seg(2, 2), seg(3, 4))).isEmpty());
    }

    @Test
    void aSegmentWithEnoughTombstonesIsRewrittenAlone() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(10, 0.5);
        Segment a = seg(1, 4);
        Segment dirty = seg(2, 4).withDeleted(0).withDeleted(1);
        List<Segment> picked = p.select(List.of(a, dirty));
        assertEquals(1, picked.size());
        assertSame(dirty, picked.get(0));
    }

    @Test
    void aSegmentBelowTheDeleteRatioIsLeftAlone() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(10, 0.5);
        assertTrue(p.select(List.of(seg(1, 4).withDeleted(0))).isEmpty());
    }

    @Test
    void deletesAreHandledBeforeTiers() {
        SizeTieredMergePolicy p = new SizeTieredMergePolicy(2, 0.5);
        Segment x = seg(1, 1);
        Segment y = seg(2, 1);
        Segment dirty = seg(3, 2).withDeleted(0).withDeleted(1);
        List<Segment> picked = p.select(List.of(x, y, dirty));
        assertEquals(List.of(dirty), picked);
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new SizeTieredMergePolicy(1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new SizeTieredMergePolicy(2, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new SizeTieredMergePolicy(2, 1.5));
    }
}
