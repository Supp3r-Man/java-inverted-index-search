package com.github.sup3rmar10.searchindex;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns text into tokens: Unicode NFC normalisation, lowercase ({@link Locale#ROOT}), split on every
 * code point that is not a letter, digit or combining mark, then optional stopword removal and
 * optional {@link SimpleStemmer} stemming. Instances are immutable.
 *
 * <p>Positions count raw tokens, so a removed stopword leaves a gap ("over the lazy" keeps
 * positions 0 and 2). Phrase queries are analysed with the same rule, so a phrase containing a
 * stopword still requires the right distance.
 *
 * <p>Not done: accent folding, CJK word segmentation (a run of CJK characters is one token),
 * apostrophe handling ("don't" becomes "don" and "t").
 */
public final class Analyzer {

    public static final Set<String> ENGLISH_STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is", "it",
            "no", "not", "of", "on", "or", "such", "that", "the", "their", "then", "there", "these",
            "they", "this", "to", "was", "will", "with");

    private final Set<String> stopwords;
    private final boolean stemming;

    private Analyzer(Set<String> stopwords, boolean stemming) {
        this.stopwords = stopwords;
        this.stemming = stemming;
    }

    /** Lowercase and split only. */
    public static Analyzer standard() {
        return new Analyzer(Set.of(), false);
    }

    /** Copy of this analyzer with the given stopwords (compared after lowercasing, before stemming). */
    public Analyzer withStopwords(Set<String> words) {
        return new Analyzer(Set.copyOf(words), stemming);
    }

    public Analyzer withStemming(boolean enabled) {
        return new Analyzer(stopwords, enabled);
    }

    public boolean stemming() {
        return stemming;
    }

    public Set<String> stopwords() {
        return stopwords;
    }

    public List<Token> analyze(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        List<Token> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int position = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isTokenChar(cp, cur.length() > 0)) {
                cur.appendCodePoint(cp);
            } else if (cur.length() > 0) {
                emit(cur.toString(), position++, out);
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            emit(cur.toString(), position, out);
        }
        return out;
    }

    private void emit(String raw, int position, List<Token> out) {
        if (stopwords.contains(raw)) {
            return;
        }
        out.add(new Token(stemming ? SimpleStemmer.stem(raw) : raw, position));
    }

    private static boolean isTokenChar(int cp, boolean insideToken) {
        if (Character.isLetterOrDigit(cp)) {
            return true;
        }
        if (!insideToken) {
            return false;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }
}
