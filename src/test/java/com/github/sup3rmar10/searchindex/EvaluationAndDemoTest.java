package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationAndDemoTest {

    private static String demoOutput() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Demo.run(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void corpusGenerationIsDeterministicForASeed() {
        assertEquals(SyntheticCorpus.generate(30, 5), SyntheticCorpus.generate(30, 5));
        assertNotEquals(SyntheticCorpus.generate(30, 5), SyntheticCorpus.generate(30, 6));
    }

    @Test
    void corpusTopicsAreBalancedAndGradesAreOneOrTwo() {
        List<SyntheticCorpus.Doc> docs = SyntheticCorpus.generate(120, 7);
        for (int topic = 0; topic < SyntheticCorpus.TOPICS.length; topic++) {
            int t = topic;
            assertEquals(30, docs.stream().filter(d -> d.topic() == t).count());
        }
        assertTrue(docs.stream().allMatch(d -> d.grade() == 1 || d.grade() == 2));
        assertEquals(120, docs.stream().map(SyntheticCorpus.Doc::id).distinct().count());
    }

    @Test
    void queriesHaveTwoDistinctWordsFromTheirTopic() {
        List<SyntheticCorpus.LabelledQuery> queries = SyntheticCorpus.queries(3, 8);
        assertEquals(12, queries.size());
        for (SyntheticCorpus.LabelledQuery q : queries) {
            String[] words = q.text().split(" ");
            assertEquals(2, words.length);
            assertTrue(!words[0].equals(words[1]));
            List<String> vocab = List.of(SyntheticCorpus.TOPICS[q.topic()]);
            assertTrue(vocab.contains(words[0]) && vocab.contains(words[1]));
        }
    }

    @Test
    void gradesCoverOnlyDocumentsOfTheQueryTopic() {
        List<SyntheticCorpus.Doc> docs = SyntheticCorpus.generate(40, 1);
        SyntheticCorpus.LabelledQuery q = new SyntheticCorpus.LabelledQuery("orbit comet", 1);
        Map<String, Integer> grades = SyntheticCorpus.grades(docs, q);
        assertEquals(10, grades.size());
        for (SyntheticCorpus.Doc d : docs) {
            assertEquals(d.topic() == 1, grades.containsKey(d.id()));
        }
    }

    @Test
    void evaluationIsDeterministicAndWithinRange() {
        List<Evaluation.Result> a = Evaluation.run(120, 7);
        assertEquals(a, Evaluation.run(120, 7));
        assertEquals(5, a.size());
        for (Evaluation.Result r : a) {
            assertTrue(r.ndcg10() >= 0 && r.ndcg10() <= 1, r.name());
            assertTrue(r.recall30() >= 0 && r.recall30() <= 1, r.name());
        }
    }

    @Test
    void bm25BeatsTheNoSkillBaselineOnTheSyntheticSet() {
        List<Evaluation.Result> r = Evaluation.run(120, 7);
        Evaluation.Result bm25 = r.get(0);
        Evaluation.Result baseline = r.get(r.size() - 1);
        assertTrue(bm25.name().startsWith("BM25"));
        assertTrue(baseline.name().startsWith("baseline"));
        assertTrue(bm25.ndcg10() > baseline.ndcg10() + 0.3, bm25 + " vs " + baseline);
        assertTrue(bm25.recall30() > baseline.recall30() + 0.2, bm25 + " vs " + baseline);
    }

    @Test
    void thePlainBinaryTermModelDoesNotBeatTheSaturatingOneHere() {
        // an observation on this synthetic data, not a general claim
        List<Evaluation.Result> r = Evaluation.run(120, 7);
        assertTrue(r.get(0).ndcg10() >= r.get(2).ndcg10());
    }

    @Test
    void formattedReportListsEveryConfiguration() {
        String text = Evaluation.format(Evaluation.run(60, 3));
        assertTrue(text.contains("nDCG@10"));
        assertTrue(text.contains("recall@30"));
        assertTrue(text.contains("BM25 k1=1.2 b=0.75"));
        assertEquals(6, text.lines().count());
    }

    @Test
    void demoRunsAndIsDeterministic() {
        String first = demoOutput();
        assertEquals(first, demoOutput());
        assertTrue(first.contains("== 1."));
        assertTrue(first.contains("== 5."));
        assertTrue(first.contains("SYNTHETIC"));
        assertTrue(first.contains("Synthetic: shows the harness works"));
    }

    @Test
    void demoShowsTombstonesBeingReclaimedAndPositionedErrors() {
        String out = demoOutput();
        assertTrue(out.contains("tombstones=1"));
        assertTrue(out.contains("after forceMerge:  segments=[5] live=5 tombstones=0"));
        assertTrue(out.contains("(at position"));
    }
}
