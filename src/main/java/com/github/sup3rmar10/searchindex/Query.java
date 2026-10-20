package com.github.sup3rmar10.searchindex;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Query AST. Term and phrase nodes keep the raw text; the index analyses it with its own
 * {@link Analyzer} at search time, so the same query behaves consistently with the indexed text.
 */
public sealed interface Query {

    /** One word. If analysis splits it into several tokens ("state-of-art") it is treated as a phrase. */
    record TermQuery(String text) implements Query {
        public TermQuery {
            requireText(text);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** Tokens that must appear next to each other, in order (stopword gaps are respected). */
    record PhraseQuery(String text) implements Query {
        public PhraseQuery {
            requireText(text);
        }

        @Override
        public String toString() {
            return '"' + text + '"';
        }
    }

    record AndQuery(List<Query> clauses) implements Query {
        public AndQuery {
            clauses = List.copyOf(clauses);
            if (clauses.isEmpty()) {
                throw new IllegalArgumentException("AND needs at least one clause");
            }
        }

        @Override
        public String toString() {
            return clauses.stream().map(Object::toString).collect(Collectors.joining(" AND ", "(", ")"));
        }
    }

    record OrQuery(List<Query> clauses) implements Query {
        public OrQuery {
            clauses = List.copyOf(clauses);
            if (clauses.isEmpty()) {
                throw new IllegalArgumentException("OR needs at least one clause");
            }
        }

        @Override
        public String toString() {
            return clauses.stream().map(Object::toString).collect(Collectors.joining(" OR ", "(", ")"));
        }
    }

    record NotQuery(Query clause) implements Query {
        public NotQuery {
            if (clause == null) {
                throw new IllegalArgumentException("NOT needs a clause");
            }
        }

        @Override
        public String toString() {
            return "NOT " + clause;
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("query text must not be blank");
        }
    }
}
