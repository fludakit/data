package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Locks the keyset (cursor) seek expansion rendered by {@link SqlBuilder#seekClause}. */
class SqlBuilderSeekClauseTest {

    private static final TableSchema SCHEMA = new TableSchema() {
        @Override
        public String tableName() {
            return "widget";
        }

        @Override
        public String idColumn() {
            return "id";
        }

        @Override
        public List<ColumnRef> columns() {
            return List.of(new ColumnRef.Simple("id", "widget"), new ColumnRef.Simple("name", "widget"));
        }
    };

    private static String seek(List<String> terms, List<Boolean> ascending, boolean forward) {
        return new SqlBuilder(SCHEMA).seekClause(terms, ascending, forward);
    }

    @Test
    void pagedSeekStatementJoinsShellSeekAndOrderByWithNoLimit() {
        // The seek statement is assembled in core: shell + connector + seek + ORDER BY, ending at
        // ORDER BY with NO limit clause (the runtime splices the literal LIMIT for the extra row).
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        String next = builder.pagedSeekStatement("SELECT \"id\", \"name\" FROM \"widget\"", false,
                List.of("\"name\""), List.of(true), true, " ORDER BY \"name\" ASC");
        assertThat(next).isEqualTo(
                "SELECT \"id\", \"name\" FROM \"widget\" WHERE ((\"name\" > :cursor0)) ORDER BY \"name\" ASC");
        // No baked limit fragment: the statement ends on a token, not whitespace, so it can format.
        assertThat(next).endsWith("ASC");
        assertThat(next).doesNotContain("LIMIT");
    }

    @Test
    void pagedSeekStatementAndsTheSeekOntoAShellThatAlreadyHasWhere() {
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        String prev = builder.pagedSeekStatement("SELECT \"id\" FROM \"widget\" WHERE \"id\" > 0", true,
                List.of("\"name\""), List.of(true), false, " ORDER BY \"name\" ASC");
        assertThat(prev).isEqualTo("SELECT \"id\" FROM \"widget\" WHERE \"id\" > 0"
                + " AND ((\"name\" < :cursor0)) ORDER BY \"name\" ASC");
    }

    @Test
    void singleKeyUsesTheDirectionOperator() {
        assertThat(seek(List.of("\"name\""), List.of(true), true))
                .isEqualTo("((\"name\" > :cursor0))");
        assertThat(seek(List.of("\"name\""), List.of(true), false))
                .isEqualTo("((\"name\" < :cursor0))");
        assertThat(seek(List.of("\"name\""), List.of(false), true))
                .isEqualTo("((\"name\" < :cursor0))");
    }

    @Test
    void precedingKeysCompareWithEqualityNotTheDirectionOperator() {
        // Regression: every key used to take the direction operator, so the second disjunct
        // ("name" > :c0 AND ...) was a subset of the first ("name" > :c0) and the predicate
        // degenerated to a single-key seek — every row sharing the leading key was unreachable
        // from page two onward.
        assertThat(seek(List.of("\"name\"", "\"id\""), List.of(true, false), true))
                .isEqualTo("((\"name\" > :cursor0)"
                        + " OR ((\"name\" = :cursor0 OR (\"name\" IS NULL AND :cursor0 IS NULL))"
                        + " AND \"id\" < :cursor1))");
    }

    @Test
    void backwardPagingInvertsOnlyTheTrailingKey() {
        assertThat(seek(List.of("\"name\"", "\"id\""), List.of(true, true), false))
                .isEqualTo("((\"name\" < :cursor0)"
                        + " OR ((\"name\" = :cursor0 OR (\"name\" IS NULL AND :cursor0 IS NULL))"
                        + " AND \"id\" < :cursor1))");
    }

    @Test
    void compositeKeyExpandsOneDisjunctPerKey() {
        // Each disjunct pins the preceding keys with = and advances on its own key, so the trailing
        // key of disjunct i is the only term carrying the direction operator.
        assertThat(seek(List.of("\"a\"", "\"b\"", "\"c\""), List.of(true, true, true), true))
                .isEqualTo("((\"a\" > :cursor0)"
                        + " OR ((\"a\" = :cursor0 OR (\"a\" IS NULL AND :cursor0 IS NULL))"
                        + " AND \"b\" > :cursor1)"
                        + " OR ((\"a\" = :cursor0 OR (\"a\" IS NULL AND :cursor0 IS NULL))"
                        + " AND (\"b\" = :cursor1 OR (\"b\" IS NULL AND :cursor1 IS NULL))"
                        + " AND \"c\" > :cursor2))");
    }

    @Test
    void nullSafeEqualityGuardsNullCursorKeys() {
        // A cursor key is legitimately null (an empty or all-null to-many list reduces to a null
        // edge); "t = NULL" is UNKNOWN, so a preceding key must also match on IS NULL.
        assertThat(seek(List.of("\"name\"", "\"id\""), List.of(true, true), true))
                .contains("\"name\" = :cursor0 OR (\"name\" IS NULL AND :cursor0 IS NULL)");
    }

    @Test
    void relationSortedTermsAreSplicedVerbatim() {
        String aggregate = "(SELECT MAX(\"i\".\"quantity\") FROM \"order_item\" \"i\""
                + " WHERE \"i\".\"order_id\" = \"order\".\"id\")";
        assertThat(seek(List.of(aggregate, "\"order\".\"id\""), List.of(true, true), true))
                .isEqualTo("((" + aggregate + " > :cursor0)"
                        + " OR ((" + aggregate + " = :cursor0 OR (" + aggregate
                        + " IS NULL AND :cursor0 IS NULL))"
                        + " AND \"order\".\"id\" > :cursor1))");
    }

    @Test
    void mismatchedDirectionsAndEmptyKeysAreRejected() {
        assertThatThrownBy(() -> seek(List.of("\"name\"", "\"id\""), List.of(true), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one direction per key");

        assertThatThrownBy(() -> seek(List.of(), List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one cursor key");
    }
}
