package com.github.sup3rmar10.searchindex;

/**
 * A deliberately crude English suffix stripper (NOT the Porter stemmer). Rules, applied in order
 * to lowercase terms:
 * <ol>
 *   <li>Terms of 3 characters or fewer are left alone.</li>
 *   <li>Plurals: {@code sses -> ss}; {@code ies -> y} (only when longer than 4 characters);
 *       a trailing {@code s} is dropped unless the term ends in {@code ss}, {@code us} or {@code is}.</li>
 *   <li>{@code ing} is removed if what remains has at least 3 characters and contains a vowel;
 *       then a doubled final consonant is reduced to one unless it is {@code l}, {@code s} or {@code z}.</li>
 *   <li>Otherwise {@code ed} gets the same treatment.</li>
 * </ol>
 * It is deterministic and cheap, and it is wrong in known ways: {@code hoping -> hop} collides with
 * {@code hop}, and irregular forms ({@code mice}, {@code ran}) are untouched. The point is to show
 * where stemming plugs into analysis (the same function must run at index time and query time).
 */
public final class SimpleStemmer {

    private SimpleStemmer() {
    }

    public static String stem(String term) {
        if (term == null) {
            throw new IllegalArgumentException("term must not be null");
        }
        String t = term;
        if (t.length() <= 3) {
            return t;
        }
        if (t.endsWith("sses")) {
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("ies") && t.length() > 4) {
            t = t.substring(0, t.length() - 3) + "y";
        } else if (t.endsWith("s") && !t.endsWith("ss") && !t.endsWith("us") && !t.endsWith("is")) {
            t = t.substring(0, t.length() - 1);
        }
        if (t.endsWith("ing") && canStrip(t, 3)) {
            return undouble(t.substring(0, t.length() - 3));
        }
        if (t.endsWith("ed") && canStrip(t, 2)) {
            return undouble(t.substring(0, t.length() - 2));
        }
        return t;
    }

    private static boolean canStrip(String t, int suffixLength) {
        String rest = t.substring(0, t.length() - suffixLength);
        return rest.length() >= 3 && containsVowel(rest);
    }

    private static boolean containsVowel(String s) {
        for (int i = 0; i < s.length(); i++) {
            if ("aeiouy".indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String undouble(String s) {
        int n = s.length();
        if (n >= 2) {
            char last = s.charAt(n - 1);
            if (last == s.charAt(n - 2) && "aeiou".indexOf(last) < 0 && last != 'l' && last != 's' && last != 'z') {
                return s.substring(0, n - 1);
            }
        }
        return s;
    }
}
