package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.sup3rmar10.searchindex.Query.AndQuery;
import com.github.sup3rmar10.searchindex.Query.TermQuery;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SearchIndexTest {

    static SearchIndex plain() {
        return new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 1000);
    }

    static SearchIndex with(Analyzer analyzer) {
        return new SearchIndex(analyzer, Bm25.defaults(), MergePolicy.NONE, 1000);
    }

    static List<String> ids(List<Hit> hits) {
        return hits.stream().map(Hit::id).collect(Collectors.toList());
    }

    static Set<String> idSet(List<Hit> hits) {
        return hits.stream().map(Hit::id).collect(Collectors.toSet());
    }

    @Test
    void emptyIndexReturnsNothing() {
        assertTrue(plain().search("anything", 10).isEmpty());
    }

    @Test
    void aSingleTermFindsOnlyDocumentsContainingIt() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "apple pie");
        idx.addDocument("d2", "banana split");
        assertEquals(List.of("d1"), ids(idx.search("apple", 10)));
        assertTrue(idx.search("cherry", 10).isEmpty());
    }

    @Test
    void scoreOfASingleMatchEqualsTheHandComputedBm25Value() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "apple pie");
        // N=1, df=1, tf=1, dl == avgdl so tfNorm = tf*(k1+1)/(tf+k1) = 1
        double expected = Math.log(1 + 0.5 / 1.5);
        assertEquals(expected, idx.search("apple", 1).get(0).score(), 1e-12);
    }

    @Test
    void aRarerTermOutweighsACommonOne() {
        SearchIndex idx = plain();
        idx.addDocument("a", "common rare");
        idx.addDocument("b", "common filler");
        idx.addDocument("c", "common padding");
        idx.addDocument("d", "common stuffing");
        List<Hit> hits = idx.search("common rare", 4);
        assertEquals("a", hits.get(0).id());
        assertTrue(hits.get(0).score() > hits.get(1).score());
    }

    @Test
    void andRequiresEveryClause() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "red apple");
        idx.addDocument("d2", "red car");
        idx.addDocument("d3", "green apple");
        assertEquals(List.of("d1"), ids(idx.search("red AND apple", 10)));
    }

    @Test
    void orMatchesEitherAndRanksDocsMatchingBothFirst() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "red apple");
        idx.addDocument("d2", "red car");
        idx.addDocument("d3", "green apple");
        List<Hit> hits = idx.search("red OR apple", 10);
        assertEquals(Set.of("d1", "d2", "d3"), idSet(hits));
        assertEquals("d1", hits.get(0).id());
    }

    @Test
    void notExcludesDocumentsAndBareNotMeansAndNot() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "red apple");
        idx.addDocument("d2", "red car");
        idx.addDocument("d3", "green apple");
        assertEquals(List.of("d2"), ids(idx.search("red AND NOT apple", 10)));
        assertEquals(List.of("d2"), ids(idx.search("red NOT apple", 10)));
    }

    @Test
    void orAndAndScoresAreTheSumOfTheClauseScores() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "red apple");
        idx.addDocument("d2", "red car");
        idx.addDocument("d3", "green apple");
        double red = idx.search("red", 10).stream().filter(h -> h.id().equals("d1")).findFirst().get().score();
        double apple = idx.search("apple", 10).stream().filter(h -> h.id().equals("d1")).findFirst().get().score();
        assertEquals(red + apple, idx.search("red OR apple", 10).get(0).score(), 1e-12);
        assertEquals(red + apple, idx.search("red AND apple", 10).get(0).score(), 1e-12);
    }

    @Test
    void adjacentTermsAreAnOrQuery() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "red apple");
        idx.addDocument("d2", "blue car");
        idx.addDocument("d3", "green apple");
        assertEquals(idSet(idx.search("red OR apple", 10)), idSet(idx.search("red apple", 10)));
    }

    @Test
    void aPurelyNegativeQueryReturnsEverythingElseWithScoreZero() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a");
        idx.addDocument("d2", "b");
        idx.addDocument("d3", "c");
        List<Hit> hits = idx.search("NOT a", 10);
        assertEquals(List.of("d2", "d3"), ids(hits));
        assertEquals(0.0, hits.get(0).score());
    }

    @Test
    void doubleNegationBringsBackThePositiveSet() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a");
        idx.addDocument("d2", "b");
        assertEquals(List.of("d1"), ids(idx.search("NOT NOT a", 10)));
    }

    @Test
    void deMorganHolds() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a");
        idx.addDocument("d2", "b");
        idx.addDocument("d3", "c");
        idx.addDocument("d4", "a b");
        idx.addDocument("d5", "d");
        assertEquals(List.of("d3", "d5"), ids(idx.search("NOT (a OR b)", 10)));
        assertEquals(ids(idx.search("NOT (a OR b)", 10)), ids(idx.search("NOT a AND NOT b", 10)));
        assertEquals(List.of("d1", "d2", "d3", "d5"), ids(idx.search("NOT (a AND b)", 10)));
        assertEquals(ids(idx.search("NOT (a AND b)", 10)), ids(idx.search("NOT a OR NOT b", 10)));
    }

    @Test
    void orWithANegatedClauseIsTheUnionWithTheComplement() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a b");
        idx.addDocument("d2", "b");
        idx.addDocument("d3", "c");
        assertEquals(List.of("d1", "d3"), ids(idx.search("a OR NOT b", 10)));
    }

    @Test
    void phraseRequiresAdjacencyAndOrder() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "the quick brown fox");
        idx.addDocument("d2", "brown quick fox");
        idx.addDocument("d3", "quick red brown fox");
        assertEquals(List.of("d1"), ids(idx.search("\"quick brown\"", 10)));
        assertTrue(idx.search("\"brown quick brown\"", 10).isEmpty());
    }

    @Test
    void longPhraseMatchesOnlyTheExactRun() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a b c d");
        idx.addDocument("d2", "a b x d");
        assertEquals(List.of("d1"), ids(idx.search("\"a b c d\"", 10)));
    }

    @Test
    void morePhraseOccurrencesScoreHigherAtEqualLength() {
        SearchIndex idx = plain();
        idx.addDocument("twice", "a b x a b y");
        idx.addDocument("once", "a b x y z w");
        List<Hit> hits = idx.search("\"a b\"", 10);
        assertEquals(List.of("twice", "once"), ids(hits));
    }

    @Test
    void phraseWithBothWordsPresentButNotAdjacentDoesNotMatch() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a x b");
        assertTrue(idx.search("\"a b\"", 10).isEmpty());
        assertEquals(List.of("d1"), ids(idx.search("a AND b", 10)));
    }

    @Test
    void phraseGapsFromStopwordsAreRespected() {
        SearchIndex idx = with(Analyzer.standard().withStopwords(Analyzer.ENGLISH_STOPWORDS));
        idx.addDocument("gap", "over the lazy dog");
        idx.addDocument("tight", "over lazy dog");
        assertEquals(List.of("gap"), ids(idx.search("\"over the lazy\"", 10)));
        assertEquals(List.of("tight"), ids(idx.search("\"over lazy\"", 10)));
    }

    @Test
    void hyphenatedQueryWordIsTreatedAsAPhrase() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a state-of-art system");
        idx.addDocument("d2", "state art of");
        assertEquals(List.of("d1"), ids(idx.search("state-of-art", 10)));
    }

    @Test
    void singleWordPhraseBehavesLikeATerm() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "apple");
        idx.addDocument("d2", "pear");
        assertEquals(idx.search("apple", 5), idx.search("\"apple\"", 5));
    }

    @Test
    void stemmingAppliesToDocumentsAndQueriesAlike() {
        SearchIndex idx = with(Analyzer.standard().withStemming(true));
        idx.addDocument("d1", "running dogs");
        idx.addDocument("d2", "sleeping cat");
        assertEquals(List.of("d1"), ids(idx.search("dog", 10)));
        assertEquals(List.of("d1"), ids(idx.search("dogs", 10)));
        assertEquals(List.of("d1"), ids(idx.search("run", 10)));
        assertEquals(List.of("d1"), ids(idx.search("\"running dog\"", 10)));
    }

    @Test
    void clausesThatAnalyseToNothingAreDropped() {
        SearchIndex idx = with(Analyzer.standard().withStopwords(Analyzer.ENGLISH_STOPWORDS));
        idx.addDocument("d1", "the fox");
        idx.addDocument("d2", "the dog");
        assertEquals(List.of("d1"), ids(idx.search("the AND fox", 10)));
        assertTrue(idx.search("the", 10).isEmpty());
        assertTrue(idx.search("\"the of\"", 10).isEmpty());
        assertTrue(idx.search("NOT the", 10).isEmpty());
    }

    @Test
    void unicodeTextIsSearchableCaseInsensitively() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "Café MÜLLER");
        assertEquals(List.of("d1"), ids(idx.search("CAFÉ", 10)));
        assertEquals(List.of("d1"), ids(idx.search("müller", 10)));
    }

    @Test
    void programmaticQueriesWork() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a b");
        idx.addDocument("d2", "a");
        Query q = new AndQuery(List.of(new TermQuery("a"), new TermQuery("b")));
        assertEquals(List.of("d1"), ids(idx.search(q, 5)));
    }

    @Test
    void parseErrorsSurfaceFromSearch() {
        assertThrows(QueryParseException.class, () -> plain().search("a AND", 5));
    }

    @Test
    void kMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> plain().search("a", 0));
    }

    @Test
    void topKLimitsResultsToTheBestScores() {
        SearchIndex idx = plain();
        for (int i = 1; i <= 5; i++) {
            idx.addDocument("d" + i, "x ".repeat(i) + "pad ".repeat(6 - i));   // same length, tf = i
        }
        assertEquals(List.of("d5", "d4"), ids(idx.search("x", 2)));
    }

    @Test
    void equalScoresAreOrderedByIdWhateverTheInsertionOrder() {
        SearchIndex idx = plain();
        idx.addDocument("z1", "same text");
        idx.addDocument("a1", "same text");
        idx.addDocument("m1", "same text");
        assertEquals(List.of("a1", "m1", "z1"), ids(idx.search("same", 10)));
    }

    @Test
    void deletedDocumentsDisappearImmediately() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "apple");
        idx.addDocument("d2", "apple");
        idx.flush();
        assertTrue(idx.deleteDocument("d1"));
        assertEquals(List.of("d2"), ids(idx.search("apple", 10)));
        assertNull(idx.document("d1"));
        assertEquals(1, idx.liveDocCount());
    }

    @Test
    void deletingABufferedDocumentNeverCreatesATombstone() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "apple");
        assertTrue(idx.deleteDocument("d1"));
        assertEquals(0, idx.tombstoneCount());
        assertEquals(0, idx.bufferedDocCount());
        assertTrue(idx.search("apple", 10).isEmpty());
    }

    @Test
    void deletingAnUnknownIdReturnsFalse() {
        assertFalse(plain().deleteDocument("nope"));
    }

    @Test
    void reAddingAnIdReplacesTheDocument() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "apple");
        idx.flush();
        idx.addDocument("d1", "banana");
        assertTrue(idx.search("apple", 10).isEmpty());
        assertEquals(List.of("d1"), ids(idx.search("banana", 10)));
        assertEquals("banana", idx.document("d1"));
        assertEquals(1, idx.liveDocCount());
    }

    @Test
    void deletedDocumentsDoNotInfluenceScoresOfTheRest() {
        SearchIndex withGhost = plain();
        SearchIndex clean = plain();
        for (SearchIndex idx : List.of(withGhost, clean)) {
            idx.addDocument("d1", "alpha beta beta");
            idx.addDocument("d2", "alpha gamma");
            idx.addDocument("d3", "delta");
        }
        withGhost.flush();   // the ghost lands in a different segment from the real documents
        withGhost.addDocument("ghost", "alpha alpha alpha alpha beta gamma delta delta delta");
        withGhost.flush();
        withGhost.deleteDocument("ghost");
        assertEquals(clean.search("alpha OR beta", 10), withGhost.search("alpha OR beta", 10));
    }

    @Test
    void flushThresholdCreatesSegmentsAndSearchFlushesTheRemainder() {
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 2);
        for (int i = 0; i < 5; i++) {
            idx.addDocument("d" + i, "word");
        }
        assertEquals(List.of(2, 2), idx.segmentSizes());
        assertEquals(1, idx.bufferedDocCount());
        assertEquals(5, idx.search("word", 10).size());
        assertEquals(List.of(2, 2, 1), idx.segmentSizes());
        assertEquals(0, idx.bufferedDocCount());
    }

    @Test
    void theMergePolicyRunsAfterEveryFlush() {
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), new SizeTieredMergePolicy(2, 0.5), 1);
        idx.addDocument("d1", "a");
        assertEquals(List.of(1), idx.segmentSizes());
        idx.addDocument("d2", "a");
        assertEquals(List.of(2), idx.segmentSizes());
        idx.addDocument("d3", "a");
        assertEquals(List.of(2, 1), idx.segmentSizes());
        idx.addDocument("d4", "a");
        assertEquals(List.of(4), idx.segmentSizes());
    }

    @Test
    void tombstonesAccumulateUntilAMergeReclaimsThem() {
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 3);
        idx.addDocument("d1", "a");
        idx.addDocument("d2", "a");
        idx.addDocument("d3", "a");
        idx.deleteDocument("d1");
        assertEquals(1, idx.tombstoneCount());
        assertEquals(List.of(3), idx.segmentSizes());
        idx.forceMerge();
        assertEquals(0, idx.tombstoneCount());
        assertEquals(List.of(2), idx.segmentSizes());
    }

    @Test
    void theDeleteRatioTriggersARewriteOfTheDirtySegment() {
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), new SizeTieredMergePolicy(10, 0.5), 2);
        idx.addDocument("d1", "a");
        idx.addDocument("d2", "a");
        idx.deleteDocument("d1");
        assertEquals(0, idx.tombstoneCount());
        assertEquals(List.of(1), idx.segmentSizes());
        assertEquals(List.of("d2"), ids(idx.search("a", 5)));
    }

    @Test
    void aFullyDeletedSegmentDisappearsOnForceMerge() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "a");
        idx.flush();
        idx.deleteDocument("d1");
        idx.forceMerge();
        assertEquals(0, idx.segmentCount());
        assertEquals(0, idx.liveDocCount());
        assertTrue(idx.search("a", 5).isEmpty());
    }

    @Test
    void forceMergeLeavesASingleSegment() {
        SearchIndex idx = new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 2);
        for (int i = 0; i < 7; i++) {
            idx.addDocument("d" + i, "w" + i + " common");
        }
        idx.forceMerge();
        assertEquals(List.of(7), idx.segmentSizes());
        assertEquals(7, idx.search("common", 10).size());
    }

    @Test
    void aBrokenMergePolicyIsDetected() {
        SearchIndex foreign = new SearchIndex(Analyzer.standard(), Bm25.defaults(),
                segs -> List.of(Segment.build(99, List.of(), Analyzer.standard())), 1);
        assertThrows(IllegalStateException.class, () -> foreign.addDocument("d1", "a"));
        SearchIndex twice = new SearchIndex(Analyzer.standard(), Bm25.defaults(),
                segs -> segs.isEmpty() ? List.of() : List.of(segs.get(0), segs.get(0)), 1);
        assertThrows(IllegalStateException.class, () -> twice.addDocument("d1", "a"));
    }

    @Test
    void documentsReturnStoredText() {
        SearchIndex idx = plain();
        idx.addDocument("d1", "Original TEXT");
        assertEquals("Original TEXT", idx.document("d1"));
        idx.flush();
        assertEquals("Original TEXT", idx.document("d1"));
        assertNull(idx.document("missing"));
    }

    @Test
    void invalidArgumentsAreRejected() {
        SearchIndex idx = plain();
        assertThrows(IllegalArgumentException.class, () -> idx.addDocument(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> idx.addDocument("", "x"));
        assertThrows(IllegalArgumentException.class, () -> idx.addDocument("id", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchIndex(Analyzer.standard(), Bm25.defaults(), MergePolicy.NONE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SearchIndex(null, Bm25.defaults(), MergePolicy.NONE, 1));
    }

    @Test
    void bm25ParametersChangeTheRanking() {
        SearchIndex normal = new SearchIndex(Analyzer.standard(), new Bm25(1.2, 0.75), MergePolicy.NONE, 100);
        SearchIndex flat = new SearchIndex(Analyzer.standard(), new Bm25(1.2, 0.0), MergePolicy.NONE, 100);
        for (SearchIndex idx : List.of(normal, flat)) {
            idx.addDocument("short", "alpha filler");
            idx.addDocument("long", "alpha " + "filler ".repeat(40));
        }
        List<Hit> n = normal.search("alpha", 2);
        List<Hit> f = flat.search("alpha", 2);
        assertEquals("short", n.get(0).id());
        assertTrue(n.get(0).score() > n.get(1).score());
        assertEquals(f.get(0).score(), f.get(1).score(), 1e-12);
    }
}
