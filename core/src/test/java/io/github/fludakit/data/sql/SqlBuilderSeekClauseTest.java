package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the keyset (cursor) seek expansion rendered by {@link SqlBuilder#pagedSeekStatement} — the
 * full statement a cursor-paged read assembles: the shell, the null-guarded seek predicate, the
 * shared ORDER BY, and (for a to-many sort) the derived-table wrap.
 */
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
        // The seek statement is assembled in core: shell + connector + null-guarded seek + ORDER BY,
        // ending at ORDER BY with NO limit clause (the runtime splices the literal LIMIT for the extra row).
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        String next = builder.pagedSeekStatement("SELECT \"id\", \"name\" FROM \"widget\"", false,
                List.of("\"name\""), List.of(true), true, " ORDER BY \"name\" ASC", null, null);
        assertThat(next).isEqualTo(
                "SELECT \"id\", \"name\" FROM \"widget\" WHERE (:cursor0 IS NULL OR ((\"name\" > :cursor0)))"
                        + " ORDER BY \"name\" ASC");
        // No baked limit fragment: the statement ends on a token, not whitespace, so it can format.
        assertThat(next).endsWith("ASC");
        assertThat(next).doesNotContain("LIMIT");
    }

    @Test
    void pagedSeekStatementAndsTheSeekOntoAShellThatAlreadyHasWhere() {
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        String prev = builder.pagedSeekStatement("SELECT \"id\" FROM \"widget\" WHERE \"id\" > 0", true,
                List.of("\"name\""), List.of(true), false, " ORDER BY \"name\" ASC", null, null);
        assertThat(prev).isEqualTo("SELECT \"id\" FROM \"widget\" WHERE \"id\" > 0"
                + " AND (:cursor0 IS NULL OR ((\"name\" < :cursor0))) ORDER BY \"name\" ASC");
    }

    @Test
    void pagedSeekStatementSeeksNothingWithoutTerms() {
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        assertThat(builder.pagedSeekStatement("SELECT \"id\", \"name\" FROM \"widget\"", false,
                List.of(), List.of(), true, " ORDER BY \"name\" ASC", null, null))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"widget\" ORDER BY \"name\" ASC");
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

    @Test
    void pagedSeekStatementWrapsToManyAggregateSeeksInADerivedTable() {
        // The to-many shape: the seek compares an aggregate subquery, so the statement wraps the
        // shell in a derived table and re-points the aggregate + root-qualified terms at the inner alias.
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        String shell = "SELECT \"id\", \"status\" FROM \"orders\""
                + " LEFT JOIN \"lines\" AS lines_ ON lines_.\"order_id\" = \"orders\".\"id\"";
        String aggregate = "(SELECT MIN(\"lines_.\"product\") FROM \"lines\" \"lines\""
                + " WHERE \"lines\".\"order_id\" = \"orders\".\"id\")";
        String stmt = builder.pagedSeekStatement(shell, false,
                List.of(aggregate), List.of(true), true,
                " ORDER BY \"agg_0\" ASC, \"orders\".\"id\" ASC", List.of(aggregate), "\"orders\"");
        assertThat(stmt).isEqualTo("SELECT inner_.* FROM (" + shell + ") AS inner_"
                + " WHERE (:cursor0 IS NULL OR ((inner_.\"agg_0\" > :cursor0)))"
                + " ORDER BY inner_.\"agg_0\" ASC, inner_.\"id\" ASC");
    }

    @Test
    void pagedSeekStatementSkipsTheWrapForLocalKeySeeks() {
        SqlBuilder builder = new SqlBuilder(SCHEMA).dialect(H2Dialect.INSTANCE);
        String stmt = builder.pagedSeekStatement("SELECT \"id\", \"name\" FROM \"widget\"", false,
                List.of("\"name\""), List.of(true), true, " ORDER BY \"name\" ASC", null, null);
        assertThat(stmt).doesNotContain("inner_");
        assertThat(stmt).doesNotContain("FROM (");
    }
}
