package com.github.sup3rmar10.searchindex;

import com.github.sup3rmar10.searchindex.Query.AndQuery;
import com.github.sup3rmar10.searchindex.Query.NotQuery;
import com.github.sup3rmar10.searchindex.Query.OrQuery;
import com.github.sup3rmar10.searchindex.Query.PhraseQuery;
import com.github.sup3rmar10.searchindex.Query.TermQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for the query language.
 *
 * <pre>
 *   query   := or
 *   or      := and ( [OR] and )*        adjacency means OR ("cat dog" = "cat OR dog")
 *   and     := unary ( [AND] NOT unary | AND unary )*      a bare NOT after a clause means AND NOT
 *   unary   := NOT unary | primary
 *   primary := WORD | "quoted phrase" | ( or )
 * </pre>
 *
 * Operators are the upper-case words AND, OR, NOT; lower-case "and" is an ordinary word. Precedence
 * is NOT, then AND, then OR/adjacency, so {@code a b AND c} means {@code a OR (b AND c)}, and
 * {@code cat NOT dog} means {@code cat AND NOT dog}. A word ends at
 * whitespace, a parenthesis or a double quote. Every error carries the character position.
 */
public final class QueryParser {

    static final int MAX_DEPTH = 64;

    private enum Kind { WORD, PHRASE, LPAREN, RPAREN, AND, OR, NOT }

    private record Tok(Kind kind, String text, int pos) {
        String describe() {
            return switch (kind) {
                case WORD -> "word '" + text + "'";
                case PHRASE -> "phrase";
                case LPAREN -> "'('";
                case RPAREN -> "')'";
                default -> kind.name();
            };
        }
    }

    private final List<Tok> toks;
    private int i = 0;
    private int depth = 0;

    private QueryParser(List<Tok> toks) {
        this.toks = toks;
    }

    public static Query parse(String input) {
        if (input == null) {
            throw new QueryParseException("query is null", 0);
        }
        List<Tok> toks = lex(input);
        if (toks.isEmpty()) {
            throw new QueryParseException("empty query", 0);
        }
        QueryParser p = new QueryParser(toks);
        Query q = p.parseOr();
        if (p.i < toks.size()) {
            Tok t = toks.get(p.i);
            if (t.kind() == Kind.RPAREN) {
                throw new QueryParseException("unmatched ')'", t.pos());
            }
            throw new QueryParseException("unexpected " + t.describe(), t.pos());
        }
        return q;
    }

    private static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int n = s.length();
        int k = 0;
        while (k < n) {
            char c = s.charAt(k);
            if (Character.isWhitespace(c)) {
                k++;
            } else if (c == '(') {
                out.add(new Tok(Kind.LPAREN, "(", k++));
            } else if (c == ')') {
                out.add(new Tok(Kind.RPAREN, ")", k++));
            } else if (c == '"') {
                int close = s.indexOf('"', k + 1);
                if (close < 0) {
                    throw new QueryParseException("unterminated phrase: missing closing quote", k);
                }
                out.add(new Tok(Kind.PHRASE, s.substring(k + 1, close), k));
                k = close + 1;
            } else {
                int start = k;
                while (k < n && !Character.isWhitespace(s.charAt(k)) && "()\"".indexOf(s.charAt(k)) < 0) {
                    k++;
                }
                String word = s.substring(start, k);
                Kind kind = switch (word) {
                    case "AND" -> Kind.AND;
                    case "OR" -> Kind.OR;
                    case "NOT" -> Kind.NOT;
                    default -> Kind.WORD;
                };
                out.add(new Tok(kind, word, start));
            }
        }
        return out;
    }

    private Tok peek() {
        return i < toks.size() ? toks.get(i) : null;
    }

    private static boolean startsOperand(Tok t) {
        return t != null && (t.kind() == Kind.WORD || t.kind() == Kind.PHRASE
                || t.kind() == Kind.LPAREN || t.kind() == Kind.NOT);
    }

    private Query parseOr() {
        if (++depth > MAX_DEPTH) {
            throw new QueryParseException("query nested deeper than " + MAX_DEPTH + " levels", peekPos());
        }
        List<Query> parts = new ArrayList<>();
        parts.add(parseAnd());
        while (true) {
            Tok t = peek();
            if (t != null && t.kind() == Kind.OR) {
                i++;
                requireOperand(t);
                parts.add(parseAnd());
            } else if (startsOperand(t)) {
                parts.add(parseAnd());
            } else {
                break;
            }
        }
        depth--;
        return parts.size() == 1 ? parts.get(0) : new OrQuery(parts);
    }

    private Query parseAnd() {
        List<Query> parts = new ArrayList<>();
        parts.add(parseUnary());
        while (peek() != null && (peek().kind() == Kind.AND || peek().kind() == Kind.NOT)) {
            if (peek().kind() == Kind.AND) {
                Tok op = toks.get(i++);
                requireOperand(op);
            }
            // a bare NOT after a clause means AND NOT ("cat NOT dog")
            parts.add(parseUnary());
        }
        return parts.size() == 1 ? parts.get(0) : new AndQuery(parts);
    }

    private Query parseUnary() {
        Tok t = peek();
        if (t != null && t.kind() == Kind.NOT) {
            i++;
            requireOperand(t);
            return new NotQuery(parseUnary());
        }
        return parsePrimary();
    }

    private Query parsePrimary() {
        Tok t = peek();
        if (t == null) {
            throw new QueryParseException("unexpected end of query", peekPos());
        }
        switch (t.kind()) {
            case WORD:
                i++;
                return new TermQuery(t.text());
            case PHRASE:
                i++;
                if (t.text().isBlank()) {
                    throw new QueryParseException("empty phrase", t.pos());
                }
                return new PhraseQuery(t.text());
            case LPAREN: {
                i++;
                Tok next = peek();
                if (next != null && next.kind() == Kind.RPAREN) {
                    throw new QueryParseException("empty parentheses", t.pos());
                }
                Query inner = parseOr();
                Tok close = peek();
                if (close == null || close.kind() != Kind.RPAREN) {
                    throw new QueryParseException("missing ')' to close the '(' opened here", t.pos());
                }
                i++;
                return inner;
            }
            default:
                throw new QueryParseException("unexpected " + t.describe()
                        + ": operators need a term on both sides", t.pos());
        }
    }

    private void requireOperand(Tok op) {
        Tok next = peek();
        if (next == null) {
            throw new QueryParseException(op.kind().name() + " at the end of the query has no term after it", op.pos());
        }
        if (!startsOperand(next)) {
            throw new QueryParseException(op.kind().name() + " must be followed by a term, found " + next.describe(), next.pos());
        }
    }

    private int peekPos() {
        Tok t = peek();
        return t != null ? t.pos() : (toks.isEmpty() ? 0 : toks.get(toks.size() - 1).pos());
    }
}
