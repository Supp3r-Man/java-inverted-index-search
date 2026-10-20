package com.github.sup3rmar10.searchindex;

/**
 * One analysed token. {@code position} is the index of the raw token in the text, counting tokens
 * that were later dropped as stopwords, so removed stopwords leave a gap in the positions.
 */
public record Token(String term, int position) {
}
