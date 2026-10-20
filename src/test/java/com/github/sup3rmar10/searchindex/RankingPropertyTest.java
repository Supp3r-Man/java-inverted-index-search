package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Property-style tests: the same property is checked on seeded random inputs or over a sweep of
 * parameters, so a regression in length normalisation, saturation or merging cannot hide behind one example.
 */
class RankingPropertyTest {

    private static final String[] QUERIES = {
            "w1", "w1 w2", "w1 AND w2", "w3 OR w4", "w1 NOT w2", "\"w1 w2\"", "(w1 OR w2) AND NOT w3",
            "NOT w5", "w6 \"w2 w3\"", "w0 AND (w1 OR w7)"};

    private static Map<String, Double> scores(SearchIndex idx, String query) {
        Map<String, Double> m = new HashMap<>();
        for (Hit h : idx.search(query, 1000)) {
            m.put(h.id(), h.score());
        }
        return m;
    }

    private static String randomText(Random rnd) {
        int n = 4 + rnd.nextInt(12);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("w").append(rnd.nextInt(8)).append(' ');
        }
        return sb.toString().trim();
    }

    @Test
    void longerDocumentWithTheSameTermCountScoresLowerInTheIndex() {
        SearchIndex idx = SearchIndexTest.plain();
        int[] lengths = {5, 10, 20, 40, 80, 160};
        for (int len : lengths) {
            idx.addDocument("len" + String.format("%03d", len), "alpha " + "pad ".repeat(len - 1));
        }
        List<Hit> hits = idx.search("alpha", 10);
        assertEquals(lengths.length, hits.size());
        List<String> expectedOrder = new ArrayList<>();
        for (int len : lengths) {
            expectedOrder.add("len" + String.format("%03d", len));
        }
        assertEquals(expectedOrder, SearchIndexTest.ids(hits));
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).score() > hits.get(i).score(), "strictly lower for longer doc, step " + i);
        }
    }

    @Test
    void withBZeroTheSameSweepGivesIdenticalScores() {
        SearchIndex idx = new SearchIndex(Analyzer.standard(), new Bm25(1.2, 0.0), MergePolicy.NONE, 1000);
        for (int len : new int[] {5, 20, 80}) {
            idx.addDocument("len" + len, "alpha " + "pad ".repeat(len - 1));
        }
        List<Hit> hits = idx.search("alpha", 10);
        assertEquals(hits.get(0).score(), hits.get(2).score(), 1e-12);
    }

    @Test
    void repeatedTermsSaturateInTheIndex() {
        SearchIndex idx = SearchIndexTest.plain();
        int total = 30;
        for (int tf = 1; tf <= 10; tf++) {
            idx.addDocument(String.format("tf%02d", tf), "alpha ".repeat(tf) + "pad ".repeat(total - tf));
        }
        Map<String, Double> s = scores(idx, "alpha");
        double ceiling = (Bm25.DEFAULT_K1 + 1) * idx.bm25().idf(10, 10);
        double previousGain = Double.MAX_VALUE;
        for (int tf = 1; tf < 10; tf++) {
            double lo = s.get(String.format("tf%02d", tf));
            double hi = s.get(String.format("tf%02d", tf + 1));
            double gain = hi - lo;
            assertTrue(gain > 0, "more occurrences must still score higher, tf=" + tf);
            assertTrue(gain < previousGain, "marginal gain must shrink, tf=" + tf);
            previousGain = gain;
            assertTrue(hi < ceiling, "score must stay below idf * (k1 + 1)");
        }
    }

    @Test
    void fiveRepeatsOfOneTermDoNotBeatOneOccurrenceOfEachOfTwoTerms() {
        SearchIndex idx = SearchIndexTest.plain();
        idx.addDocument("stuffed", "alpha ".repeat(5) + "pad ".repeat(25));
        idx.addDocument("balanced", "alpha beta " + "pad ".repeat(28));
        idx.addDocument("other", "beta " + "pad ".repeat(29));
        List<Hit> hits = idx.search("alpha beta", 3);
        assertEquals("balanced", hits.get(0).id());
    }

    @Test
    void lengthNormalisationStopsALongDocumentWinningOnlyByHavingMoreChances() {
        // Same tf=1 in both; the long document is padded. Without normalisation (b=0) they tie; with it the short one wins.
        for (double b : new double[] {0.25, 0.5, 0.75, 1.0}) {
            SearchIndex idx = new SearchIndex(Analyzer.standard(), new Bm25(1.2, b), MergePolicy.NONE, 1000);
            idx.addDocument("short", "alpha pad pad");
            idx.addDocument("long", "alpha " + "pad ".repeat(60));
            List<Hit> hits = idx.search("alpha", 2);
            assertEquals("short", hits.get(0).id(), "b=" + b);
        }
    }

    @Test
    void largerBPenalisesTheLongDocumentMore() {
        double previousRatio = 1.0;
        for (double b : new double[] {0.0, 0.25, 0.5, 0.75, 1.0}) {
            SearchIndex idx = new SearchIndex(Analyzer.standard(), new Bm25(1.2, b), MergePolicy.NONE, 1000);
            idx.addDocument("short", "alpha pad pad");
            idx.addDocument("long", "alpha " + "pad ".repeat(60));
            Map<String, Double> s = scores(idx, "alpha");
            double ratio = s.get("long") / s.get("short");
            assertTrue(ratio <= previousRatio + 1e-12, "b=" + b);
            previousRatio = ratio;
        }
        assertTrue(previousRatio < 0.5);
    }

    @Test
    void topKIsAPrefixOfTheFullRanking() {
        Random rnd = new Random(77);
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 7);
        for (int i = 0; i < 80; i++) {
            idx.addDocument("d" + String.format("%02d", i), randomText(rnd));
        }
        for (String q : QUERIES) {
            List<Hit> all = idx.search(q, 1000);
            for (int k : new int[] {1, 3, 10}) {
                assertEquals(all.subList(0, Math.min(k, all.size())), idx.search(q, k), q + " k=" + k);
            }
        }
    }

    @Test
    void mergingAndPhysicalDeletionNeverChangeAnyScoreOrOrder() {
        for (long seed = 1; seed <= 5; seed++) {
            Random rnd = new Random(seed);
            SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 6);
            Map<String, String> survivors = new HashMap<>();
            for (int i = 0; i < 60; i++) {
                String id = String.format("d%02d", i);
                String text = randomText(rnd);
                idx.addDocument(id, text);
                survivors.put(id, text);
            }
            for (int i = 0; i < 60; i++) {
                if (rnd.nextInt(4) == 0) {
                    String id = String.format("d%02d", i);
                    idx.deleteDocument(id);
                    survivors.remove(id);
                }
            }
            assertTrue(idx.tombstoneCount() > 0);
            List<List<Hit>> before = new ArrayList<>();
            for (String q : QUERIES) {
                before.add(idx.search(q, 1000));
            }
            idx.forceMerge();
            assertEquals(0, idx.tombstoneCount());
            assertEquals(1, idx.segmentCount());
            // a brand-new single-segment index containing only the survivors must agree as well
            SearchIndex fresh = SearchIndexTest.plain();
            List<String> order = new ArrayList<>(survivors.keySet());
            Collections.sort(order);
            for (String id : order) {
                fresh.addDocument(id, survivors.get(id));
            }
            for (int q = 0; q < QUERIES.length; q++) {
                assertEquals(before.get(q), idx.search(QUERIES[q], 1000), "after merge: " + QUERIES[q] + " seed " + seed);
                assertEquals(before.get(q), fresh.search(QUERIES[q], 1000), "fresh index: " + QUERIES[q] + " seed " + seed);
            }
        }
    }

    @Test
    void insertionOrderAndSegmentLayoutDoNotChangeResults() {
        Random rnd = new Random(5);
        List<String> ids = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        for (int i = 0; i < 50; i++) {
            String id = String.format("d%02d", i);
            ids.add(id);
            texts.put(id, randomText(rnd));
        }
        SearchIndex a = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 1000);
        SearchIndex b = new SearchIndex(Analyzer.standard(), Bm25.defaults(), new SizeTieredMergePolicy(3, 0.5), 4);
        List<String> shuffled = new ArrayList<>(ids);
        Collections.shuffle(shuffled, new Random(99));
        for (String id : ids) {
            a.addDocument(id, texts.get(id));
        }
        for (String id : shuffled) {
            b.addDocument(id, texts.get(id));
        }
        for (String q : QUERIES) {
            List<Hit> ha = a.search(q, 1000);
            List<Hit> hb = b.search(q, 1000);
            assertEquals(SearchIndexTest.ids(ha), SearchIndexTest.ids(hb), q);
            for (int i = 0; i < ha.size(); i++) {
                assertEquals(ha.get(i).score(), hb.get(i).score(), 1e-12, q);
            }
        }
    }

    @Test
    void andResultsAreASubsetOfOrResultsAndPhraseResultsOfAnd() {
        Random rnd = new Random(31);
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 9);
        for (int i = 0; i < 70; i++) {
            idx.addDocument("d" + String.format("%02d", i), randomText(rnd));
        }
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                if (x == y) {
                    continue;
                }
                String a = "w" + x;
                String b = "w" + y;
                Set<String> and = new HashSet<>(SearchIndexTest.ids(idx.search(a + " AND " + b, 1000)));
                Set<String> or = new HashSet<>(SearchIndexTest.ids(idx.search(a + " OR " + b, 1000)));
                Set<String> phrase = new HashSet<>(SearchIndexTest.ids(idx.search("\"" + a + " " + b + "\"", 1000)));
                assertTrue(or.containsAll(and), a + "/" + b);
                assertTrue(and.containsAll(phrase), a + "/" + b);
            }
        }
    }

    @Test
    void everyScoreOfAPositiveQueryIsPositiveAndFinite() {
        Random rnd = new Random(3);
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 5);
        for (int i = 0; i < 40; i++) {
            idx.addDocument("d" + i, randomText(rnd));
        }
        for (String q : new String[] {"w1", "w1 w2", "w1 AND w2", "\"w1 w2\""}) {
            for (Hit h : idx.search(q, 100)) {
                assertTrue(h.score() > 0 && Double.isFinite(h.score()), q);
            }
        }
    }
}
