package com.github.sup3rmar10.searchindex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.sup3rmar10.searchindex.Query.AndQuery;
import com.github.sup3rmar10.searchindex.Query.NotQuery;
import com.github.sup3rmar10.searchindex.Query.OrQuery;
import com.github.sup3rmar10.searchindex.Query.PhraseQuery;
import com.github.sup3rmar10.searchindex.Query.TermQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryParserTest {

    private static Query t(String s) {
        return new TermQuery(s);
    }

    private static QueryParseException fails(String input) {
        return assertThrows(QueryParseException.class, () -> QueryParser.parse(input));
    }

    @Test
    void singleWordIsATermQuery() {
        assertEquals(t("fox"), QueryParser.parse("fox"));
    }

    @Test
    void quotedTextIsAPhraseQuery() {
        assertEquals(new PhraseQuery("quick brown fox"), QueryParser.parse("\"quick brown fox\""));
    }

    @Test
    void andOrNotParseToTheirNodes() {
        assertEquals(new AndQuery(List.of(t("a"), t("b"))), QueryParser.parse("a AND b"));
        assertEquals(new OrQuery(List.of(t("a"), t("b"))), QueryParser.parse("a OR b"));
        assertEquals(new NotQuery(t("a")), QueryParser.parse("NOT a"));
    }

    @Test
    void adjacencyMeansOr() {
        assertEquals(new OrQuery(List.of(t("cat"), t("dog"), t("bird"))), QueryParser.parse("cat dog bird"));
    }

    @Test
    void andBindsTighterThanOr() {
        assertEquals(new OrQuery(List.of(t("a"), new AndQuery(List.of(t("b"), t("c"))))), QueryParser.parse("a OR b AND c"));
        assertEquals(new OrQuery(List.of(t("a"), new AndQuery(List.of(t("b"), t("c"))))), QueryParser.parse("a b AND c"));
    }

    @Test
    void notBindsTighterThanAnd() {
        assertEquals(new AndQuery(List.of(t("a"), new NotQuery(t("b")))), QueryParser.parse("a AND NOT b"));
    }

    @Test
    void aBareNotAfterAClauseMeansAndNot() {
        assertEquals(new AndQuery(List.of(t("cat"), new NotQuery(t("dog")))), QueryParser.parse("cat NOT dog"));
    }

    @Test
    void parenthesesOverridePrecedence() {
        assertEquals(new AndQuery(List.of(new OrQuery(List.of(t("a"), t("b"))), t("c"))), QueryParser.parse("(a OR b) AND c"));
    }

    @Test
    void lowerCaseOperatorWordsAreOrdinaryTerms() {
        assertEquals(new OrQuery(List.of(t("salt"), t("and"), t("pepper"))), QueryParser.parse("salt and pepper"));
    }

    @Test
    void notCanBeNested() {
        assertEquals(new NotQuery(new NotQuery(t("a"))), QueryParser.parse("NOT NOT a"));
    }

    @Test
    void aQuoteEndsAWordSoNoWhitespaceIsNeeded() {
        assertEquals(new OrQuery(List.of(t("foo"), new PhraseQuery("bar baz"))), QueryParser.parse("foo\"bar baz\""));
    }

    @Test
    void toStringIsACanonicalParenthesisedForm() {
        assertEquals("(a OR (b AND NOT c))", QueryParser.parse("a b AND NOT c").toString());
        assertEquals("(\"x y\" AND z)", QueryParser.parse("\"x y\" AND z").toString());
    }

    @Test
    void canonicalFormParsesBackToTheSameTree() {
        Query q = QueryParser.parse("(a OR b) AND NOT \"c d\" e");
        assertEquals(q, QueryParser.parse(q.toString()));
    }

    @Test
    void extraWhitespaceIsIgnored() {
        assertEquals(QueryParser.parse("a AND b"), QueryParser.parse("   a \t AND\n b  "));
    }

    @Test
    void emptyQueryIsAnErrorAtPositionZero() {
        assertEquals(0, fails("").position());
        assertTrue(fails("   ").getMessage().contains("empty query"));
        assertThrows(QueryParseException.class, () -> QueryParser.parse(null));
    }

    @Test
    void unterminatedPhraseReportsWhereTheQuoteOpened() {
        QueryParseException e = fails("a \"b c");
        assertEquals(2, e.position());
        assertTrue(e.getMessage().contains("closing quote"));
    }

    @Test
    void emptyPhraseIsRejected() {
        QueryParseException e = fails("a \"  \"");
        assertEquals(2, e.position());
        assertTrue(e.getMessage().contains("empty phrase"));
    }

    @Test
    void missingCloseParenReportsTheOpeningParen() {
        QueryParseException e = fails("foo (bar OR baz");
        assertEquals(4, e.position());
        assertTrue(e.getMessage().contains("missing ')'"));
    }

    @Test
    void unmatchedCloseParenReportsItsPosition() {
        QueryParseException e = fails("a ) b");
        assertEquals(2, e.position());
        assertTrue(e.getMessage().contains("unmatched ')'"));
    }

    @Test
    void emptyParenthesesAreRejected() {
        assertTrue(fails("a ()").getMessage().contains("empty parentheses"));
    }

    @Test
    void trailingOperatorsAreRejectedAtTheOperator() {
        assertEquals(4, fails("fox AND").position());
        assertEquals(4, fails("fox OR").position());
        assertEquals(0, fails("NOT").position());
        assertTrue(fails("fox AND").getMessage().contains("no term after"));
    }

    @Test
    void leadingBinaryOperatorsAreRejected() {
        assertEquals(0, fails("AND fox").position());
        assertEquals(0, fails("OR fox").position());
    }

    @Test
    void operatorFollowedByOperatorPointsAtTheSecondOne() {
        QueryParseException e = fails("a AND OR b");
        assertEquals(6, e.position());
        assertTrue(e.getMessage().contains("must be followed by a term"));
    }

    @Test
    void closeParenAfterOperatorIsAnError() {
        assertEquals(7, fails("(a AND ) b").position());
    }

    @Test
    void moderateNestingIsAcceptedAndExtremeNestingIsRejectedCleanly() {
        String ok = "(".repeat(40) + "a" + ")".repeat(40);
        assertEquals(t("a"), QueryParser.parse(ok));
        String deep = "(".repeat(200) + "a" + ")".repeat(200);
        assertTrue(fails(deep).getMessage().contains("nested"));
    }
}
