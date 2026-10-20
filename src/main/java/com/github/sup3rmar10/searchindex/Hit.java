package com.github.sup3rmar10.searchindex;

/** One search result: external document id and its score. */
public record Hit(String id, double score) {
}
