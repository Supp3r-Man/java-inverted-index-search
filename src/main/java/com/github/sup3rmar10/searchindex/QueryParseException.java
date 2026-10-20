package com.github.sup3rmar10.searchindex;

/** A query string could not be parsed. {@code position} is the 0-based character offset of the problem. */
public final class QueryParseException extends IllegalArgumentException {

    private final int position;

    public QueryParseException(String message, int position) {
        super(message + " (at position " + position + ")");
        this.position = position;
    }

    public int position() {
        return position;
    }
}
