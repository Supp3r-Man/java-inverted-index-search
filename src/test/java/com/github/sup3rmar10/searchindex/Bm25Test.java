package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Bm25Test {

    private final Bm25 bm25 = Bm25.defaults();

    @Test
    void defaultsAreK1Of1Point2AndBOf0Point75() {
        assertEquals(1.2, bm25.k1());
        assertEquals(0.75, bm25.b());
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Bm25(-0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Bm25(Double.NaN, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Bm25(Double.POSITIVE_INFINITY, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new Bm25(1.2, -0.01));
        assertThrows(IllegalArgumentException.class, () -> new Bm25(1.2, 1.01));
        assertThrows(IllegalArgumentException.class, () -> new Bm25(1.2, Double.NaN));
    }

    @Test
    void idfMatchesTheFormulaForKnownValues() {
        assertEquals(Math.log(1 + 99.5 / 1.5), bm25.idf(100, 1), 1e-12);
        assertEquals(Math.log(1 + 0.5 / 100.5), bm25.idf(100, 100), 1e-12);
    }

    @Test
    void idfIsStrictlyDecreasingInDocumentFrequencyAndNeverNegative() {
        double previous = Double.MAX_VALUE;
        for (long df = 0; df <= 50; df++) {
            double idf = bm25.idf(50, df);
            assertTrue(idf > 0, "idf must stay positive, df=" + df);
            assertTrue(idf < previous, "idf must decrease, df=" + df);
            previous = idf;
        }
    }

    @Test
    void idfRejectsImpossibleStatistics() {
        assertThrows(IllegalArgumentException.class, () -> bm25.idf(5, 6));
        assertThrows(IllegalArgumentException.class, () -> bm25.idf(5, -1));
    }

    @Test
    void zeroTermFrequencyScoresZero() {
        assertEquals(0.0, bm25.tfNorm(0, 10, 10));
        assertEquals(0.0, bm25.score(3.0, 0, 10, 10));
    }

    @Test
    void averageLengthDocumentReducesToTheClassicFormula() {
        double tf = 3;
        assertEquals(tf * 2.2 / (tf + 1.2), bm25.tfNorm(tf, 50, 50), 1e-12);
    }

    @Test
    void termFrequencyContributionIsStrictlyIncreasing() {
        double previous = 0;
        for (int tf = 1; tf <= 100; tf++) {
            double v = bm25.tfNorm(tf, 50, 50);
            assertTrue(v > previous);
            previous = v;
        }
    }

    @Test
    void termFrequencySaturatesBelowKOnePlusOne() {
        for (int tf : new int[] {1, 2, 5, 20, 1000, 1_000_000}) {
            assertTrue(bm25.tfNorm(tf, 50, 50) < bm25.k1() + 1, "tf=" + tf);
        }
        assertEquals(2.2, bm25.tfNorm(1e12, 50, 50), 1e-6);
    }

    @Test
    void marginalGainOfEachExtraOccurrenceShrinks() {
        double previousGain = Double.MAX_VALUE;
        for (int tf = 1; tf <= 30; tf++) {
            double gain = bm25.tfNorm(tf + 1, 50, 50) - bm25.tfNorm(tf, 50, 50);
            assertTrue(gain < previousGain, "gain must shrink at tf=" + tf);
            previousGain = gain;
        }
    }

    @Test
    void largerK1SaturatesMoreSlowly() {
        Bm25 slow = new Bm25(3.0, 0.75);
        Bm25 fast = new Bm25(0.5, 0.75);
        // relative to each model's own ceiling (k1 + 1), tf=5 is closer to the ceiling with small k1
        assertTrue(fast.tfNorm(5, 50, 50) / 1.5 > slow.tfNorm(5, 50, 50) / 4.0);
    }

    @Test
    void k1ZeroMakesTheTermBinary() {
        Bm25 binary = new Bm25(0.0, 0.75);
        assertEquals(binary.tfNorm(1, 50, 50), binary.tfNorm(40, 50, 50), 1e-12);
        assertEquals(1.0, binary.tfNorm(7, 80, 50), 1e-12);
    }

    @Test
    void longerDocumentWithTheSameTermFrequencyScoresLower() {
        double previous = Double.MAX_VALUE;
        for (int len = 10; len <= 640; len *= 2) {
            double v = bm25.tfNorm(2, len, 100);
            assertTrue(v < previous, "length " + len);
            previous = v;
        }
    }

    @Test
    void shorterThanAverageDocumentIsBoosted() {
        assertTrue(bm25.tfNorm(2, 25, 100) > bm25.tfNorm(2, 100, 100));
        assertTrue(bm25.tfNorm(2, 200, 100) < bm25.tfNorm(2, 100, 100));
    }

    @Test
    void bZeroSwitchesLengthNormalisationOff() {
        Bm25 noNorm = new Bm25(1.2, 0.0);
        assertEquals(noNorm.tfNorm(3, 10, 100), noNorm.tfNorm(3, 1000, 100), 1e-12);
    }

    @Test
    void bOneNormalisesFullyAndLargerBPenalisesLongDocsMore() {
        Bm25 full = new Bm25(1.2, 1.0);
        assertEquals(3 * 2.2 / (3 + 1.2 * 4.0), full.tfNorm(3, 400, 100), 1e-12);
        Bm25 mild = new Bm25(1.2, 0.25);
        assertTrue(full.tfNorm(3, 400, 100) < mild.tfNorm(3, 400, 100));
    }

    @Test
    void scoreIsIdfTimesTfNorm() {
        assertEquals(2.5 * bm25.tfNorm(2, 30, 40), bm25.score(2.5, 2, 30, 40), 1e-12);
    }

    @Test
    void zeroAverageLengthDoesNotProduceNaN() {
        assertTrue(Double.isFinite(bm25.tfNorm(1, 0, 0)));
    }
}
