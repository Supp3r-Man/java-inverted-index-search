package com.github.sup3rmar10.searchindex;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/** Scripted walk-through on synthetic data: boolean and phrase queries, deletes and merges, ranking, evaluation. */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        run(System.out);
    }

    static void run(PrintStream out) {
        out.println("== 1. a few hand-written SYNTHETIC sentences (stopwords removed, stemming on)");
        Analyzer analyzer = Analyzer.standard().withStopwords(Analyzer.ENGLISH_STOPWORDS).withStemming(true);
        SearchIndex small = new SearchIndex(analyzer, Bm25.defaults(), new SizeTieredMergePolicy(10, 0.5), 3);
        small.addDocument("s1", "The quick brown fox jumps over the lazy dog");
        small.addDocument("s2", "A lazy dog sleeps while the quick fox is running over hills");
        small.addDocument("s3", "Brown bears and brown foxes live in the hills");
        small.addDocument("s4", "The dog barks at the fox, the fox ignores the dog");
        small.addDocument("s5", "Quick quick quick: a very quick reply");
        small.addDocument("s6", "Hills, rivers and lazy afternoons");
        show(out, small, "fox dog", 3);
        show(out, small, "fox AND dog", 3);
        show(out, small, "hills NOT quick", 3);
        show(out, small, "\"lazy dog\"", 3);
        show(out, small, "\"quick fox\"", 3);
        show(out, small, "\"over the lazy\"", 3);
        show(out, small, "quick AND (brown OR hills)", 3);
        out.println("segments=" + small.segmentSizes() + " live=" + small.liveDocCount() + " tombstones=" + small.tombstoneCount());

        out.println();
        out.println("== 2. deletes are tombstones until a merge rewrites the segment");
        small.deleteDocument("s1");
        out.println("after deleting s1: segments=" + small.segmentSizes() + " live=" + small.liveDocCount()
                + " tombstones=" + small.tombstoneCount());
        show(out, small, "\"lazy dog\"", 3);
        small.forceMerge();
        out.println("after forceMerge:  segments=" + small.segmentSizes() + " live=" + small.liveDocCount()
                + " tombstones=" + small.tombstoneCount());
        show(out, small, "\"lazy dog\"", 3);

        out.println();
        out.println("== 3. query errors carry a position");
        for (String bad : List.of("fox AND", "\"lazy dog", "(fox OR dog", "fox ) dog", "AND fox")) {
            try {
                small.search(bad, 3);
            } catch (QueryParseException e) {
                out.println(String.format(Locale.ROOT, "%-14s -> %s", bad, e.getMessage()));
            }
        }

        out.println();
        out.println("== 4. generated SYNTHETIC corpus: 80 docs, 4 invented topics, seed 7");
        List<SyntheticCorpus.Doc> docs = SyntheticCorpus.generate(80, 7);
        SearchIndex index = new SearchIndex(Analyzer.standard(), Bm25.defaults(), new SizeTieredMergePolicy(4, 0.5), 10);
        for (SyntheticCorpus.Doc d : docs) {
            index.addDocument(d.id(), d.text());
        }
        index.flush();
        out.println("segments=" + index.segmentSizes() + " live=" + index.liveDocCount());
        for (String q : List.of("orbit telescope", "simmer whisk", "anchor AND tide")) {
            out.println("query: " + q);
            for (Hit h : index.search(q, 3)) {
                SyntheticCorpus.Doc d = docs.get(Integer.parseInt(h.id().substring(4)));
                out.println(String.format(Locale.ROOT, "  %s  score=%.3f  tokens=%d  (generator topic: %s, grade %d)",
                        h.id(), h.score(), index.analyzer().analyze(d.text()).size(),
                        SyntheticCorpus.TOPIC_NAMES[d.topic()], d.grade()));
            }
        }

        out.println();
        out.println("== 5. tiny evaluation on SYNTHETIC data (labels come from the generator, 120 docs, 12 queries, 30 relevant docs per query)");
        out.print(Evaluation.format(Evaluation.run(120, 7)));
        out.println("Synthetic: shows the harness works; says nothing about real-world relevance.");
    }

    private static void show(PrintStream out, SearchIndex index, String query, int k) {
        out.println("query: " + query);
        List<Hit> hits = index.search(query, k);
        if (hits.isEmpty()) {
            out.println("  (no results)");
        }
        for (Hit h : hits) {
            out.println(String.format(Locale.ROOT, "  %-4s %.3f  %s", h.id(), h.score(), index.document(h.id())));
        }
    }
}
