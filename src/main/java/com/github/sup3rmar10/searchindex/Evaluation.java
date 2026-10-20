package com.github.sup3rmar10.searchindex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tiny evaluation harness: runs the synthetic labelled queries against several scoring configurations
 * and reports mean nDCG@10 and recall@30 (30 because each query has 30 relevant documents in the 120-document corpus, so recall@10 could never exceed 0.33). SYNTHETIC data with generator-made labels: it checks the
 * harness and the relative behaviour of the settings on this generator, nothing more.
 */
public final class Evaluation {

    public record Result(String name, double ndcg10, double recall30) {
    }

    private Evaluation() {
    }

    public static List<Result> run(int docCount, long seed) {
        List<SyntheticCorpus.Doc> docs = SyntheticCorpus.generate(docCount, seed);
        List<SyntheticCorpus.LabelledQuery> queries = SyntheticCorpus.queries(3, seed + 1);
        List<Result> results = new ArrayList<>();
        results.add(evaluate("BM25 k1=1.2 b=0.75", new Bm25(1.2, 0.75), docs, queries));
        results.add(evaluate("BM25 k1=1.2 b=0 (no length normalisation)", new Bm25(1.2, 0.0), docs, queries));
        results.add(evaluate("BM25 k1=0 (term present or not)", new Bm25(0.0, 0.75), docs, queries));
        results.add(evaluate("near-raw tf (k1=1e6, b=0)", new Bm25(1e6, 0.0), docs, queries));
        results.add(idOrderBaseline(docs, queries));
        return results;
    }

    private static Result evaluate(String name, Bm25 params, List<SyntheticCorpus.Doc> docs,
                                   List<SyntheticCorpus.LabelledQuery> queries) {
        SearchIndex index = new SearchIndex(Analyzer.standard(), params, new SizeTieredMergePolicy(4, 0.5), 25);
        for (SyntheticCorpus.Doc d : docs) {
            index.addDocument(d.id(), d.text());
        }
        double ndcg = 0;
        double recall = 0;
        for (SyntheticCorpus.LabelledQuery q : queries) {
            List<String> ranked = index.search(q.text(), 30).stream().map(Hit::id).collect(Collectors.toList());
            Map<String, Integer> grades = SyntheticCorpus.grades(docs, q);
            ndcg += Metrics.ndcgAtK(ranked, grades, 10);
            recall += Metrics.recallAtK(ranked, grades, 30);
        }
        return new Result(name, ndcg / queries.size(), recall / queries.size());
    }

    /** No-skill floor: always returns the first thirty ids in id order, whatever the query. */
    private static Result idOrderBaseline(List<SyntheticCorpus.Doc> docs, List<SyntheticCorpus.LabelledQuery> queries) {
        List<String> ranked = docs.stream().map(SyntheticCorpus.Doc::id).sorted().limit(30).collect(Collectors.toList());
        double ndcg = 0;
        double recall = 0;
        for (SyntheticCorpus.LabelledQuery q : queries) {
            Map<String, Integer> grades = SyntheticCorpus.grades(docs, q);
            ndcg += Metrics.ndcgAtK(ranked, grades, 10);
            recall += Metrics.recallAtK(ranked, grades, 30);
        }
        return new Result("baseline: first 30 ids, ignores the query", ndcg / queries.size(), recall / queries.size());
    }

    public static String format(List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-46s %8s %10s%n", "configuration", "nDCG@10", "recall@30"));
        for (Result r : results) {
            sb.append(String.format(Locale.ROOT, "%-46s %8.3f %10.3f%n", r.name(), r.ndcg10(), r.recall30()));
        }
        return sb.toString();
    }
}
