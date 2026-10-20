package com.github.sup3rmar10.searchindex;

/**
 * BM25 scoring with configurable {@code k1} and {@code b}. Pure functions of statistics, so the
 * ranking properties (saturation, length normalisation) can be tested without an index.
 *
 * <pre>
 *   idf(N, df)         = ln(1 + (N - df + 0.5) / (df + 0.5))            (the non-negative "Lucene" form)
 *   tfNorm(tf, dl, av) = tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / av))
 *   score              = idf * tfNorm
 * </pre>
 *
 * <ul>
 *   <li><b>Saturation (k1):</b> tfNorm grows with tf but is bounded above by k1 + 1. The tenth
 *       occurrence of a word adds far less than the second, so keyword stuffing has diminishing
 *       returns. {@code k1 = 0} makes the term binary (present or not).</li>
 *   <li><b>Length normalisation (b):</b> the denominator contains {@code dl / av}. For the same tf, a
 *       document longer than average scores lower, a shorter one higher. {@code b = 0} switches this
 *       off, {@code b = 1} applies it fully. Without it, long documents win simply by having more
 *       chances to contain the term.</li>
 * </ul>
 */
public final class Bm25 {

    public static final double DEFAULT_K1 = 1.2;
    public static final double DEFAULT_B = 0.75;

    private final double k1;
    private final double b;

    public Bm25(double k1, double b) {
        if (!(k1 >= 0) || Double.isInfinite(k1)) {
            throw new IllegalArgumentException("k1 must be a finite number >= 0, was " + k1);
        }
        if (!(b >= 0 && b <= 1)) {
            throw new IllegalArgumentException("b must be in [0, 1], was " + b);
        }
        this.k1 = k1;
        this.b = b;
    }

    public static Bm25 defaults() {
        return new Bm25(DEFAULT_K1, DEFAULT_B);
    }

    public double k1() {
        return k1;
    }

    public double b() {
        return b;
    }

    public double idf(long docCount, long docFreq) {
        if (docCount < 0 || docFreq < 0 || docFreq > docCount) {
            throw new IllegalArgumentException("need 0 <= docFreq <= docCount, got df=" + docFreq + " N=" + docCount);
        }
        return Math.log(1.0 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
    }

    public double tfNorm(double tf, double docLength, double avgDocLength) {
        if (tf <= 0) {
            return 0.0;
        }
        double ratio = avgDocLength > 0 ? docLength / avgDocLength : 1.0;
        double norm = 1.0 - b + b * ratio;
        return tf * (k1 + 1.0) / (tf + k1 * norm);
    }

    public double score(double idf, double tf, double docLength, double avgDocLength) {
        return idf * tfNorm(tf, docLength, avgDocLength);
    }
}
