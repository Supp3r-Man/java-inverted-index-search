package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SimpleStemmerTest {

    @Test
    void shortTermsAreUntouched() {
        assertEquals("bus", SimpleStemmer.stem("bus"));
        assertEquals("is", SimpleStemmer.stem("is"));
        assertEquals("ing", SimpleStemmer.stem("ing"));
    }

    @Test
    void simplePluralsLoseTheS() {
        assertEquals("cat", SimpleStemmer.stem("cats"));
        assertEquals("hill", SimpleStemmer.stem("hills"));
    }

    @Test
    void ssEndingsAreKeptAndSsesBecomeSs() {
        assertEquals("glass", SimpleStemmer.stem("glass"));
        assertEquals("caress", SimpleStemmer.stem("caresses"));
    }

    @Test
    void usAndIsEndingsAreKept() {
        assertEquals("status", SimpleStemmer.stem("status"));
        assertEquals("analysis", SimpleStemmer.stem("analysis"));
    }

    @Test
    void iesBecomesYWhenLongEnough() {
        assertEquals("query", SimpleStemmer.stem("queries"));
        assertEquals("pony", SimpleStemmer.stem("ponies"));
        assertEquals("tie", SimpleStemmer.stem("ties"));
    }

    @Test
    void ingIsRemovedAndDoubledConsonantReduced() {
        assertEquals("jump", SimpleStemmer.stem("jumping"));
        assertEquals("run", SimpleStemmer.stem("running"));
        assertEquals("fill", SimpleStemmer.stem("filling"));
    }

    @Test
    void ingIsKeptWhenTheStemWouldBeTooShortOrVowelless() {
        assertEquals("sing", SimpleStemmer.stem("sing"));
        assertEquals("string", SimpleStemmer.stem("string"));
    }

    @Test
    void edIsRemovedWithTheSameGuards() {
        assertEquals("jump", SimpleStemmer.stem("jumped"));
        assertEquals("stop", SimpleStemmer.stem("stopped"));
        assertEquals("need", SimpleStemmer.stem("need"));
    }

    @Test
    void knownOverStemmingIsPinned() {
        // documented weakness: "hoping" collides with "hop"
        assertEquals(SimpleStemmer.stem("hop"), SimpleStemmer.stem("hoping"));
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SimpleStemmer.stem(null));
    }
}
