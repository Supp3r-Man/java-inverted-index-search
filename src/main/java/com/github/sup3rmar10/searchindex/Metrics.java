package com.github.sup3rmar10.searchindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Ranking metrics over a ranked list of document ids and graded relevance labels (missing id = grade 0). */
public final class Metrics {

    private Metrics() {
    }

    /**
     * nDCG@k with gain 2^grade - 1 and discount 1 / log2(rank + 1) (rank starting at 1).
     * Returns 0.0 when no document has a positive grade (the ideal DCG is 0), by convention.
     */
    public static double ndcgAtK(List<String> ranked, Map<String, Integer> grades, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, was " + k);
        }
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            dcg += gain(grades.getOrDefault(ranked.get(i), 0)) / discount(i);
        }
        List<Integer> ideal = new ArrayList<>(grades.values());
        ideal.sort(Collections.reverseOrder());
        double idcg = 0;
        for (int i = 0; i < Math.min(k, ideal.size()); i++) {
            idcg += gain(ideal.get(i)) / discount(i);
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /** Fraction of the relevant documents (grade > 0) that appear in the top k. 0.0 if there are none. */
    public static double recallAtK(List<String> ranked, Map<String, Integer> grades, int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be positive, was " + k);
        }
        long relevant = grades.values().stream().filter(g -> g > 0).count();
        if (relevant == 0) {
            return 0.0;
        }
        long found = ranked.stream().limit(k).filter(id -> grades.getOrDefault(id, 0) > 0).count();
        return (double) found / relevant;
    }

    private static double gain(int grade) {
        return grade <= 0 ? 0.0 : Math.pow(2, grade) - 1;
    }

    private static double discount(int zeroBasedRank) {
        return Math.log(zeroBasedRank + 2) / Math.log(2);
    }
}
