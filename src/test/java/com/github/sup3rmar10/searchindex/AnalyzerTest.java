package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AnalyzerTest {

    private static List<String> terms(Analyzer a, String text) {
        return a.analyze(text).stream().map(Token::term).collect(Collectors.toList());
    }

    @Test
    void lowercasesTokens() {
        assertEquals(List.of("hello", "world"), terms(Analyzer.standard(), "HeLLo WORLD"));
    }

    @Test
    void splitsOnPunctuationAndWhitespace() {
        assertEquals(List.of("a", "b", "c", "d"), terms(Analyzer.standard(), "a,b;;c\t\nd!"));
    }

    @Test
    void keepsDigitsInsideAndAroundWords() {
        assertEquals(List.of("abc123", "42", "x9"), terms(Analyzer.standard(), "abc123 42 x9"));
    }

    @Test
    void positionsCountTokensFromZero() {
        List<Token> t = Analyzer.standard().analyze("one two three");
        assertEquals(List.of(0, 1, 2), t.stream().map(Token::position).collect(Collectors.toList()));
    }

    @Test
    void removedStopwordsLeaveAGapInPositions() {
        Analyzer a = Analyzer.standard().withStopwords(Set.of("the"));
        List<Token> t = a.analyze("over the lazy dog");
        assertEquals(List.of("over", "lazy", "dog"), t.stream().map(Token::term).collect(Collectors.toList()));
        assertEquals(List.of(0, 2, 3), t.stream().map(Token::position).collect(Collectors.toList()));
    }

    @Test
    void stopwordsAreComparedAfterLowercasing() {
        Analyzer a = Analyzer.standard().withStopwords(Analyzer.ENGLISH_STOPWORDS);
        assertEquals(List.of("fox"), terms(a, "THE Fox"));
    }

    @Test
    void stemmingIsOptionalAndOffByDefault() {
        assertEquals(List.of("dogs"), terms(Analyzer.standard(), "dogs"));
        assertEquals(List.of("dog"), terms(Analyzer.standard().withStemming(true), "dogs"));
    }

    @Test
    void stopwordsAreRemovedBeforeStemming() {
        Analyzer a = Analyzer.standard().withStopwords(Set.of("dogs")).withStemming(true);
        assertEquals(List.of(), terms(a, "dogs"));
    }

    @Test
    void unicodeLettersAreKeptAndLowercased() {
        assertEquals(List.of("école", "straße", "οδυσσευς"),
                terms(Analyzer.standard(), "ÉCOLE, Straße; ΟΔΥΣΣΕΥΣ"));
    }

    @Test
    void composedAndDecomposedAccentsGiveTheSameToken() {
        String composed = "café";
        String decomposed = "café";
        assertEquals(terms(Analyzer.standard(), composed), terms(Analyzer.standard(), decomposed));
        assertEquals(1, Analyzer.standard().analyze(decomposed).size());
    }

    @Test
    void combiningMarkWithoutAPrecomposedFormStaysInsideItsToken() {
        // x + U+0301 has no precomposed character, so NFC leaves the mark in place and the tokenizer must keep it
        List<Token> t = Analyzer.standard().analyze("x́y z");
        assertEquals(2, t.size());
        assertEquals("x́y", t.get(0).term());
        // a mark at the very start of a token is not allowed to start one
        assertEquals(List.of("a"), terms(Analyzer.standard(), "́a"));
    }

    @Test
    void supplementaryCodePointLettersAreNotSplit() {
        // U+1D49C is a mathematical script capital letter outside the BMP
        assertEquals(2, Analyzer.standard().analyze("a𝒜b c").size());
    }

    @Test
    void emptyAndPunctuationOnlyTextGiveNoTokens() {
        assertTrue(Analyzer.standard().analyze("").isEmpty());
        assertTrue(Analyzer.standard().analyze("  ... --- !!! ").isEmpty());
    }

    @Test
    void nullTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Analyzer.standard().analyze(null));
    }

    @Test
    void apostrophesSplitWords() {
        assertEquals(List.of("don", "t"), terms(Analyzer.standard(), "don't"));
    }

    @Test
    void cjkRunIsOneToken() {
        // documented limit: no word segmentation
        assertEquals(1, Analyzer.standard().analyze("你好世界").size());
    }

    @Test
    void withMethodsReturnNewAnalyzersAndLeaveTheOriginalAlone() {
        Analyzer base = Analyzer.standard();
        Analyzer configured = base.withStopwords(Set.of("x")).withStemming(true);
        assertTrue(base.stopwords().isEmpty());
        assertEquals(false, base.stemming());
        assertEquals(true, configured.stemming());
        assertEquals(Set.of("x"), configured.stopwords());
    }
}
