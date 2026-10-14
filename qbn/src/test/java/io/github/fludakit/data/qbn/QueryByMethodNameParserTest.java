package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class QueryByMethodNameParserTest {

    QueryByMethodNameParser parser = new QueryByMethodNameParser();

    @Test
    void parsesSimpleEquality() {
        var p = parser.parse("findByName");
        assertThat(p.action()).isEqualTo(Action.FIND);
        assertThat(p.predicates()).hasSize(1);
        assertThat(p.predicates().get(0).property()).isEqualTo("name");
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.EQ);
    }

    @Test
    void parsesAndWithOperatorAndOrder() {
        var p = parser.parse("findByCategoryNameAndPriceLessThanOrderByPriceDesc");
        assertThat(p.predicates()).hasSize(2);
        assertThat(p.predicates().get(0).property()).isEqualTo("categoryName");
        assertThat(p.predicates().get(1).property()).isEqualTo("price");
        assertThat(p.predicates().get(1).operator()).isEqualTo(Operator.LESS_THAN);
        assertThat(p.sorts()).hasSize(1);
        assertThat(p.sorts().get(0).property()).isEqualTo("price");
        assertThat(p.sorts().get(0).ascending()).isFalse();
    }

    @Test
    void parsesIgnoreCaseAndFirst() {
        var p = parser.parse("findFirst10ByNameIgnoreCase");
        assertThat(p.limit()).isEqualTo(10);
        // IgnoreCase is a predicate flag, not a distinct operator, so it composes with any operator.
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.EQ);
        assertThat(p.predicates().get(0).ignoreCase()).isTrue();
    }

    @Test
    void ignoreCaseComposesWithNonEqualityOperators() {
        // Spec example: countByStatusIgnoreCaseNotLike. IgnoreCase used to be dropped unless the
        // operator was plain equality.
        var negated = parser.parse("countByStatusIgnoreCaseNotLike");
        assertThat(negated.action()).isEqualTo(Action.COUNT);
        assertThat(negated.predicates().get(0).operator()).isEqualTo(Operator.NOT_LIKE);
        assertThat(negated.predicates().get(0).ignoreCase()).isTrue();

        assertThat(op("findByNameIgnoreCaseContains")).isEqualTo(Operator.CONTAINS);
        assertThat(parser.parse("findByNameIgnoreCaseContains").predicates().get(0).ignoreCase()).isTrue();
    }

    @Test
    void predicatesAreCaseSensitiveByDefault() {
        assertThat(parser.parse("findByName").predicates().get(0).ignoreCase()).isFalse();
    }

    @Test
    void parsesGreaterThan() {
        var p = parser.parse("findByPriceGreaterThan");
        assertThat(p.predicates()).hasSize(1);
        assertThat(p.predicates().get(0).property()).isEqualTo("price");
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.GREATER_THAN);
    }

    @Test
    void parsesSimplePropertyEquality() {
        var p = parser.parse("findByLogin");
        assertThat(p.predicates()).hasSize(1);
        assertThat(p.predicates().get(0).property()).isEqualTo("login");
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.EQ);
    }

    @Test
    void parsesInOperator() {
        var p = parser.parse("findByNameIn");
        assertThat(p.predicates()).hasSize(1);
        assertThat(p.predicates().get(0).property()).isEqualTo("name");
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.IN);
    }

    @Test
    void parsesOrCondition() {
        // The connector used to be discarded, so this rendered as AND. The original test asserted
        // only the property names and never noticed.
        var p = parser.parse("findByNameOrEmail");
        assertThat(p.predicates()).hasSize(2);
        assertThat(p.predicates().get(0).property()).isEqualTo("name");
        assertThat(p.predicates().get(1).property()).isEqualTo("email");
        assertThat(p.predicates().get(0).connector()).isEqualTo(Connector.AND);
        assertThat(p.predicates().get(1).connector()).isEqualTo(Connector.OR);
    }

    @Test
    void parsesMixedAndOrConnectorsInDeclarationOrder() {
        // Spec: And binds tighter than Or, and SQL has the same precedence, so the declared order is
        // rendered as written — a AND b OR c means (a AND b) OR c.
        var p = parser.parse("findByNameAndStatusOrEmail");
        assertThat(p.predicates()).extracting(Predicate::property)
                .containsExactly("name", "status", "email");
        assertThat(p.predicates()).extracting(Predicate::connector)
                .containsExactly(Connector.AND, Connector.AND, Connector.OR);
    }

    @Test
    void parsesAndChainAsAllAndConnectors() {
        var p = parser.parse("findByCategoryNameAndPriceLessThan");
        assertThat(p.predicates()).extracting(Predicate::connector)
                .containsExactly(Connector.AND, Connector.AND);
    }

    @Test
    void parsesNotLikeOperator() {
        var p = parser.parse("findByNameNotLike");
        assertThat(p.predicates()).hasSize(1);
        assertThat(p.predicates().get(0).property()).isEqualTo("name");
        assertThat(p.predicates().get(0).operator()).isEqualTo(Operator.NOT_LIKE);
    }

    @Test
    void composesNotWithEveryNegatableOperator() {
        // Not used to collapse everything except Like and Null into NOT_EQ, so NotIn rendered as <>.
        assertThat(op("findByNameNotIn")).isEqualTo(Operator.NOT_IN);
        assertThat(op("findByPriceNotBetween")).isEqualTo(Operator.NOT_BETWEEN);
        assertThat(op("findByNameNotContains")).isEqualTo(Operator.NOT_CONTAINS);
        assertThat(op("findByNameNotStartsWith")).isEqualTo(Operator.NOT_STARTS_WITH);
        assertThat(op("findByNameNotEndsWith")).isEqualTo(Operator.NOT_ENDS_WITH);
        assertThat(op("findByNameNotLike")).isEqualTo(Operator.NOT_LIKE);
        assertThat(op("findByNameNotNull")).isEqualTo(Operator.IS_NOT_NULL);
        assertThat(op("findByNameNot")).isEqualTo(Operator.NOT_EQ);
    }

    @Test
    void rejectsNotOnAnOperatorTheSpecDoesNotNegate() {
        // findByNameNotIn used to silently render <>. An unsupported composition now fails the parse,
        // which the validator turns into a compile error rather than a wrong query.
        //
        // hasMessage, not hasRootCauseMessage: the reason IS the message now. It used to be buried as
        // the cause of a generic "Not a query method name" wrapper, and every caller read only the
        // wrapper — so the one place the rejection was computed was the one place it was discarded.
        assertThatThrownBy(() -> parser.parse("findByPriceNotLessThan"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not cannot be combined with LESS_THAN");
    }

    @Test
    void parsesIgnoreCaseInTheOrderClause() {
        // Spec example: findByZipcodeOrderByStreetIgnoreCaseAscHouseNumAsc
        var p = parser.parse("findByZipcodeOrderByStreetIgnoreCaseAscHouseNumAsc");
        assertThat(p.sorts()).extracting(SortSpec::property).containsExactly("street", "houseNum");
        assertThat(p.sorts().get(0).ignoreCase()).isTrue();
        assertThat(p.sorts().get(0).ascending()).isTrue();
        assertThat(p.sorts().get(1).ignoreCase()).isFalse();
    }

    @Test
    void orderClauseDefaultsToCaseSensitiveAscending() {
        var p = parser.parse("findByNameOrderByTextDesc");
        assertThat(p.sorts().get(0).ignoreCase()).isFalse();
        assertThat(p.sorts().get(0).ascending()).isFalse();
    }

    @Test
    void discardsTheIgnoredTextBetweenTheActionAndBy() {
        // Jakarta Data's subject clause is "limit? ignoredText?", so findAllByName is "find" + the
        // ignored text "All" + "ByName" and parses as findByName. ("Distinct" is the one piece of
        // subject text we do NOT discard — it is a keyword here, see below.)
        var p = parser.parse("findAllByName");
        assertThat(p.action()).isEqualTo(Action.FIND);
        assertThat(p.distinct()).isFalse();
        assertThat(p.predicates()).extracting(Predicate::property).containsExactly("name");
        assertThat(p.limit()).isZero();

        // Discarded, not interpreted: a subject that LOOKS like a limit contributes no limit, because
        // only "First<n>" does. This is what the spec's ignoredText means — and the hazard it carries.
        var top = parser.parse("findTop5ByName");
        assertThat(top.predicates()).extracting(Predicate::property).containsExactly("name");
        assertThat(top.limit()).isZero();
    }

    @Test
    void parsesDistinctSubjectKeyword() {
        // A deliberate superset of the BNF: "Distinct" is a keyword, not ignored text, so the name
        // states the de-duplication instead of losing it.
        assertThat(parser.parse("findDistinctByName").distinct()).isTrue();
        assertThat(parser.parse("findFirst10DistinctByName").distinct()).isTrue();
        assertThat(parser.parse("findByName").distinct()).isFalse();
        assertDoesNotThrow(() -> parser.parse("countDistinctByStatus"));
    }

    @Test
    void limitPrecedesTheDistinctKeyword() {
        // BNF order with our keyword spliced in: find "First"<n> "Distinct"? ignoredText? ...
        var p = parser.parse("findFirst10DistinctByName");
        assertThat(p.limit()).isEqualTo(10);
        assertThat(p.distinct()).isTrue();
        assertThat(p.predicates()).extracting(Predicate::property).containsExactly("name");
    }

    @Test
    void rejectsDistinctOnAnActionThatNeitherSelectsNorCounts() {
        // De-duplicating rows a DELETE removes or an EXISTS tests has no meaning, so the name is
        // rejected rather than parsed with its Distinct quietly dropped.
        // The visitor's own reason, not the wrapper: the name parses, so "Not a query method name"
        // is the wrong thing to say about it — the query it states is what is wrong.
        assertThatThrownBy(() -> parser.parse("deleteDistinctByName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Distinct is only meaningful for find and count, not for delete");
        assertThatThrownBy(() -> parser.parse("existsDistinctByName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Distinct is only meaningful for find and count, not for exists");
    }

    @Test
    void ignoredTextAppliesToEveryAction() {
        // BNF: action : ("delete" | "count" | "exists") ignoredText? restriction?
        var p = parser.parse("countAllByStatus");
        assertThat(p.action()).isEqualTo(Action.COUNT);
        assertThat(p.predicates()).extracting(Predicate::property).containsExactly("status");
    }

    @Test
    void rejectsALimitOrAnOrderOnANonFindAction() {
        // BNF: only "find" carries a limit and an order. count/delete/exists take ignoredText? and a
        // restriction and nothing else — a First<n> or an OrderBy on those three is not a query
        // method name. The grammar used to allow both on every action and then drop the limit.
        assertThatThrownBy(() -> parser.parse("countFirst10ByName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a query method name");
        assertThatThrownBy(() -> parser.parse("deleteByNameOrderByNameDesc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a query method name");
        assertThatThrownBy(() -> parser.parse("existsByNameOrderByNameDesc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a query method name");
    }

    @Test
    void findKeepsItsLimitAndOrder() {
        // The same two clauses the other three actions lose.
        var p = parser.parse("findFirst3ByNameOrderByNameDesc");
        assertThat(p.limit()).isEqualTo(3);
        assertThat(p.predicates()).extracting(Predicate::property).containsExactly("name");
        assertThat(p.sorts()).extracting(SortSpec::property).containsExactly("name");
        assertThat(p.sorts().get(0).ascending()).isFalse();
    }

    private Operator op(String name) {
        return parser.parse(name).predicates().get(0).operator();
    }

    @Test
    void parsesPropertiesContainingDigits() {
        // DIGITS used to beat HUMP on the lexer length tie, and no parser rule referenced it —
        // a property with a digit run failed to lex as a property at all.
        var street = parser.parse("findByStreet2");
        assertThat(street.predicates().get(0).property()).isEqualTo("street2");

        var zip = parser.parse("findByZipCode4");
        assertThat(zip.predicates().get(0).property()).isEqualTo("zipCode4");
    }

    @Test
    void preservesUnderscoresInPropertyNames() {
        // The visitor used to concatenate humps and drop the UNDERSCORE tokens, turning
        // address_street into addressstreet.
        var p = parser.parse("findByAddress_street");
        assertThat(p.predicates().get(0).property()).isEqualTo("address_street");
    }

    @Test
    void parsesEveryActionPrefix() {
        assertThat(parser.parse("findByName").action()).isEqualTo(Action.FIND);
        assertThat(parser.parse("countByName").action()).isEqualTo(Action.COUNT);
        assertThat(parser.parse("deleteByName").action()).isEqualTo(Action.DELETE);
        assertThat(parser.parse("existsByName").action()).isEqualTo(Action.EXISTS);
    }

    @Test
    void actionPrefixIsCaseSensitive() {
        // The spec spells the prefixes lowercase; "Count…" is not a query-by-method name.
        assertThat(Action.of("CountByName")).isNull();
        assertThat(Action.of("FindByName")).isNull();
    }
}
