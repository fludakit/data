package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metadata.TypeMapping.Kind;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlBuilderTest {

    static class Customer { }

    static class Profile { }

    static class Address { }

    static class Contact { }

    static class WorkExperience { }

    @Test
    void h2DialectQuotesEscapedIdentifiersAndRendersPagination() {
        H2Dialect dialect = H2Dialect.INSTANCE;
        assertEquals("\"order\"\"details\"", dialect.quoteIdentifier("order\"details"));
        assertEquals(" LIMIT 10", dialect.limitClause("10"));
        assertEquals(" OFFSET 20", dialect.offsetClause("20"));
        assertEquals(" LIMIT 10 OFFSET 20", dialect.limitOffsetClause("10", "20"));
    }

    @Test
    void postgreSqlDialectQuotesAndPaginatesLikeStandardSql() {
        PostgreSqlDialect dialect = PostgreSqlDialect.INSTANCE;
        assertEquals("\"order\"\"details\"", dialect.quoteIdentifier("order\"details"));
        assertEquals(" LIMIT 10", dialect.limitClause("10"));
        assertEquals(" OFFSET 20", dialect.offsetClause("20"));
        assertEquals(" LIMIT 10 OFFSET 20", dialect.limitOffsetClause("10", "20"));
    }

    @Test
    void sqlBuilderUsesTheConfiguredDialect() {
        SqlDialect testDialect = new SqlDialect() {
            @Override
            public String quoteIdentifier(String identifier) {
                return "[" + identifier + "]";
            }

            @Override
            public String limitClause(String limitExpression) {
                return " FETCH FIRST " + limitExpression + " ROWS ONLY";
            }

            @Override
            public String offsetClause(String offsetExpression) {
                return " SKIP " + offsetExpression;
            }
        };
        String sql = new SqlBuilder(customer()).dialect(testDialect).selectBy(List.of(), List.of(), 5, null);
        assertEquals("SELECT [id], [name] FROM [customer] FETCH FIRST 5 ROWS ONLY", sql);
    }

    static TypeMapping<Customer> customer() {
        return new TypeMapping<Customer>() {
            @Override public String tableName() { return "customer"; }
            @Override public Kind kind() { return Kind.ENTITY; }
            @Override public String typeName() { return "Customer"; }
            @Override public Prop.Id id() { return new Prop.Id("id", "id", "java.lang.Long"); }
            @Override public List<Prop> props() {
                return List.of(new Prop.Scalar("name", "name", "java.lang.String"));
            }
            @Override public List<ColumnRef> columns() {
                return List.of(new ColumnRef("id"), new ColumnRef("name"));
            }
            @Override public java.util.Map<String, ColumnRef> columnRefs() {
                return java.util.Map.of("id", new ColumnRef("id"),
                        "name", new ColumnRef("name"));
            }
        };
    }

    /** Profile with an embedded Address: the flattened leaves live in the explicit columns() view. */
    static TypeMapping<Profile> profile() {
        return new TypeMapping<Profile>() {
            @Override public String tableName() { return "profile"; }
            @Override public Kind kind() { return Kind.ENTITY; }
            @Override public String typeName() { return "Profile"; }
            @Override public Prop.Id id() { return new Prop.Id("id", "id", "java.lang.Long"); }
            @Override public List<Prop> props() {
                return List.of(new Prop.Embedded("address", "address", null));
            }
            @Override public List<ColumnRef> columns() {
                return List.of(new ColumnRef("id", "profile"),
                        new ColumnRef("address_city", "profile"));
            }
            @Override public java.util.Map<String, ColumnRef> columnRefs() {
                return java.util.Map.of(
                        "id", new ColumnRef("id", "profile"),
                        "address.city", new ColumnRef("address_city", "profile"));
            }
        };
    }

    @Test
    void rendersNegatedPredicateOperators() {
        assertThat(render(Predicate.of("name", Operator.NOT_IN, "name")))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" NOT IN (:name)");
        assertThat(render(Predicate.of("name", Operator.NOT_BETWEEN, "name")))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\""
                        + " WHERE \"name\" NOT BETWEEN :name_0 AND :name_1");
        for (Operator negated : List.of(Operator.NOT_LIKE, Operator.NOT_CONTAINS,
                Operator.NOT_STARTS_WITH, Operator.NOT_ENDS_WITH)) {
            assertThat(render(Predicate.of("name", negated, "name")))
                    .describedAs("%s must render as NOT LIKE", negated)
                    .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" NOT LIKE :name");
        }
    }

    @Test
    void orderByFoldsCaseWhenTheSortAsksForIt() {
        assertThat(new SqlBuilder(customer()).selectBy(List.of(),
                List.of(SortSpec.of("name", true, true)), 0, null))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" ORDER BY LOWER(\"name\") ASC");
        assertThat(new SqlBuilder(customer()).selectBy(List.of(),
                List.of(SortSpec.of("name", true, false)), 0, null))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" ORDER BY \"name\" ASC");
    }

    private static String render(Predicate predicate) {
        return new SqlBuilder(customer()).selectBy(List.of(predicate), List.of(), 0, null);
    }

    @Test
    void rendersIgnoreCasePredicatesByFoldingBothSides() {
        assertThat(render(Predicate.of("name", Operator.EQ, "name", Connector.AND, true)))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE LOWER(\"name\") = LOWER(:name)");
        assertThat(render(Predicate.of("name", Operator.NOT_LIKE, "name", Connector.AND, true)))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE LOWER(\"name\") NOT LIKE LOWER(:name)");
        assertThat(render(Predicate.of("name", Operator.CONTAINS, "name", Connector.AND, true)))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE LOWER(\"name\") LIKE LOWER(:name)");
    }

    @Test
    void ignoreCaseLeavesOperatorsWhereCaseIsMeaninglessAlone() {
        // A range and the nullary operators are not case-sensitive comparisons; folding them would
        // produce nonsense, so they render unchanged.
        assertThat(render(Predicate.of("id", Operator.GREATER_THAN, "id", Connector.AND, true)))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"id\" > :id");
        assertThat(render(Predicate.of("name", Operator.IS_NULL, "name", Connector.AND, true)))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" IS NULL");
    }

    @Test
    void rendersSelectWithWhereAndOrder() {
        String sql = new SqlBuilder(customer()).selectBy(
                List.of(Predicate.of("name", Operator.EQ, "name")),
                List.of(SortSpec.of("id", true, false)), 0, null);
        assertThat(sql).isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name ORDER BY \"id\" ASC");
    }

    @Test
    void orderByResolvesEmbeddedDotPathToFlattenedColumn() {
        String sql = new SqlBuilder(profile()).selectBy(List.of(),
                List.of(SortSpec.of("address.city", true, false)), 0, null);
        assertThat(sql).endsWith("ORDER BY \"address_city\" ASC");
        assertThat(new SqlBuilder(profile()).resolveColumn("address.city")).isEqualTo("address_city");
    }

    @Test
    void orderByRejectsUnknownProperty() {
        assertThatThrownBy(() -> new SqlBuilder(customer()).orderBy(List.of(SortSpec.of("nope", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property 'nope'");
    }

    @Test
    void orderByRejectsEmbeddedContainerName() {
        // The container itself carries no column: sorting by "address" must not silently pass.
        assertThatThrownBy(() -> new SqlBuilder(profile()).orderBy(List.of(SortSpec.of("address", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property 'address' on Profile");
    }

    @Test
    void rendersInsert() {
        assertThat(new SqlBuilder(customer()).insert())
                .isEqualTo("INSERT INTO \"customer\" (\"id\", \"name\") VALUES (:id, :name)");
    }

    // ---- Task 3: SqlBuilder renders joins from ColumnRef join metadata ----

    /** Minimal engine-facing schema over a root table for join-clause rendering. */
    static TableSchema rootSchema(String table, List<ColumnRef> columns) {
        return new TableSchema() {
            @Override public String tableName() { return table; }
            @Override public String idColumn() { return "id"; }
            @Override public List<ColumnRef> columns() { return columns; }
        };
    }

    @Test
    void joinClauseEmitsSingleLeftJoinAndQualifiesLeafByLastAlias() {
        ColumnRef joined = new ColumnRef("amount", "order_item", "orderItems",
                List.of(new JoinRef("order", null, "id", "order_item", "orderItems", "order_id")));
        TableSchema rootMd = rootSchema("order", List.of(joined));
        assertEquals(" LEFT JOIN \"order_item\" AS \"orderItems\" ON \"order\".\"id\" = \"orderItems\".\"order_id\"",
                new SqlBuilder(rootMd).joinClause(List.of(joined)));
        assertEquals("\"orderItems\".\"amount\"", new SqlBuilder(rootMd).qualify(joined));
    }

    @Test
    void joinClauseChainsQualifiersOverTwoHops() {
        // fk hop (root -> "primary") then a second hop (-> "players"): each qualifier is the
        // PREVIOUS join's alias, regardless of path grouping.
        ColumnRef twoHop = new ColumnRef("name", "team", "players", List.of(
                new JoinRef("game", null, "primary_player_id", "player", "primary", "id"),
                new JoinRef("player", "primary", "team_id", "team", "players", "id")));
        TableSchema game = rootSchema("game", List.of(twoHop));
        String clause = new SqlBuilder(game).joinClause(List.of(twoHop));
        assertEquals(" LEFT JOIN \"player\" AS \"primary\" ON \"game\".\"primary_player_id\" = \"primary\".\"id\""
                        + " LEFT JOIN \"team\" AS \"players\" ON \"primary\".\"team_id\" = \"players\".\"id\"",
                clause);
        assertThat(clause).contains("\"primary\".\"team_id\" = \"players\".\"id\"");
        assertEquals("\"players\".\"name\"", new SqlBuilder(game).qualify(twoHop));
    }

    @Test
    void joinClauseChainsQualifiersPerPathAcrossDivergingRefs() {
        // Ref A navigates primary -> players; ref C navigates primary -> squads. They share the
        // head join "primary" but diverge on the second hop. Per-path chaining must qualify C's
        // second hop with ITS OWN previous alias ("primary"), not with the globally last-emitted
        // fragment alias ("players"), which the old global chaining would have produced.
        JoinRef primary = new JoinRef("game", null, "primary_player_id", "player", "primary", "id");
        ColumnRef a = new ColumnRef("name", "team", "players", List.of(
                primary,
                new JoinRef("player", "primary", "team_id", "team", "players", "id")));
        ColumnRef c = new ColumnRef("label", "squad", "squads", List.of(
                primary,
                new JoinRef("player", "primary", "squad_id", "squad", "squads", "id")));
        TableSchema game = rootSchema("game", List.of(a, c));
        String clause = new SqlBuilder(game).joinClause(List.of(a, c));
        assertEquals(" LEFT JOIN \"player\" AS \"primary\" ON \"game\".\"primary_player_id\" = \"primary\".\"id\""
                        + " LEFT JOIN \"team\" AS \"players\" ON \"primary\".\"team_id\" = \"players\".\"id\""
                        + " LEFT JOIN \"squad\" AS \"squads\" ON \"primary\".\"squad_id\" = \"squads\".\"id\"",
                clause);
        // C's second hop is qualified by the shared head "primary", not by A's "players".
        assertThat(clause).contains("\"primary\".\"squad_id\" = \"squads\".\"id\"");
        assertThat(clause).doesNotContain("\"players\".\"squad_id\"");
    }

    @Test
    void joinClauseDedupesByAliasFirstOccurrenceWins() {
        JoinRef head = new JoinRef("order", null, "id", "order_item", "orderItems", "order_id");
        ColumnRef a = new ColumnRef("amount", "order_item", "orderItems", List.of(head));
        ColumnRef b = new ColumnRef("qty", "item", "item",
                List.of(head, new JoinRef("order_item", "orderItems", "item_id", "item", "item", "id")));
        ColumnRef dup = new ColumnRef("price", "other_table", "orderItems",
                List.of(new JoinRef("order", null, "x", "other_table", "orderItems", "y")));
        TableSchema order = rootSchema("order", List.of(a, b, dup));
        String clause = new SqlBuilder(order).joinClause(List.of(a, dup, b));
        assertEquals(" LEFT JOIN \"order_item\" AS \"orderItems\" ON \"order\".\"id\" = \"orderItems\".\"order_id\""
                        + " LEFT JOIN \"item\" AS \"item\" ON \"orderItems\".\"item_id\" = \"item\".\"id\"",
                clause);
    }

    @Test
    void joinClauseIsEmptyForJoinFreeRefs() {
        ColumnRef local = new ColumnRef("total");
        assertEquals("", new SqlBuilder(rootSchema("order", List.of(local))).joinClause(List.of(local)));
    }

    @Test
    void qualifyResolvesLocalRefsByTableThenRootTable() {
        TableSchema order = rootSchema("order", List.of());
        assertEquals("\"order\".\"total\"", new SqlBuilder(order).qualify(new ColumnRef("total"), "order"));
        assertEquals("\"other\".\"total\"",
                new SqlBuilder(order).qualify(new ColumnRef("total", "other", null, List.of()), "order"));
        assertThatThrownBy(() -> new SqlBuilder(order).qualify(new ColumnRef("total"), null))
                .isInstanceOf(IllegalStateException.class);
        assertEquals("\"other\".\"total\"",
                new SqlBuilder(order).qualify(new ColumnRef("total", "other", null, List.of()), null));
    }

    @Test
    void renderOverloadQualifiesPredicateColumnWithRef() {
        ColumnRef joined = new ColumnRef("amount", "order_item", "orderItems",
                List.of(new JoinRef("order", null, "id", "order_item", "orderItems", "order_id")));
        TableSchema order = rootSchema("order", List.of());
        assertEquals("\"orderItems\".\"amount\" = :orderItems_amount",
                new SqlBuilder(order).render(Predicate.of("orderItems.amount", Operator.EQ, "orderItems_amount"), joined));
        // Explicit non-root table, no joins.
        ColumnRef foreign = new ColumnRef("total", "other", null, List.of());
        assertEquals("\"other\".\"total\" = :total",
                new SqlBuilder(order).render(Predicate.of("other.total", Operator.EQ, "total"), foreign));
        // Fully-local ref renders unqualified, byte-identical to the legacy path.
        ColumnRef local = new ColumnRef("total");
        assertEquals("\"total\" = :total",
                new SqlBuilder(order).render(Predicate.of("total", Operator.EQ, "total"), local));
    }

    // ---- Task 6: aggregate sort terms replace to-many joined sorts ----

    /** Profile descriptor carrying one element collection, one one-to-many and one to-one head. */
    static TypeMapping<Profile> mixedProfile() {
        return new TypeMapping<Profile>() {
            @Override public String tableName() { return "profile"; }
            @Override public Kind kind() { return Kind.ENTITY; }
            @Override public String typeName() { return "Profile"; }
            @Override public Prop.Id id() { return new Prop.Id("id", "id", "java.lang.Long"); }
            @Override public List<Prop> props() {
                return List.of(
                        new Prop.EntityList("contacts", null, null, null),
                        new Prop.EntityList("workHistory", null, null, null),
                        new Prop.Reference("customer", "customer_id", null, null, false));
            }
            @Override public List<ColumnRef> columns() {
                return List.of(new ColumnRef("id"),
                        new ColumnRef("customer_id"),
                        new ColumnRef("full_name"));
            }
        };
    }

    @Test
    void isToManyPathDetectsCollectionAndEntityListPaths() {
        TypeMapping md = mixedProfile();
        ColumnRef elementHead = new ColumnRef("value", "profile_contacts", "contacts",
                List.of(new JoinRef("profile", null, "id", "profile_contacts", "contacts", "profile_id")));
        ColumnRef entityHead = new ColumnRef("company", "work_experience", "workHistory",
                List.of(new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id")));
        ColumnRef toOneHead = new ColumnRef("name", "customer", "customer",
                List.of(new JoinRef("profile", null, "customer_id", "customer", "customer", "id")));
        ColumnRef local = new ColumnRef("full_name");
        ColumnRef embedded = new ColumnRef("address_city");

        assertThat(new SqlBuilder(md).isToManyPath("contacts.value")).isTrue();
        assertThat(new SqlBuilder(md).isToManyPath("workHistory.company")).isTrue();
        assertThat(new SqlBuilder(md).isToManyPath("customer.name")).isFalse();
        // Join-free refs (local or embedded) never carry a relation head.
        assertThat(new SqlBuilder(md).isToManyPath("fullName")).isFalse();
        assertThat(new SqlBuilder(md).isToManyPath("address.city")).isFalse();
    }

    @Test
    void aggregateSortTermRendersCorrelatedMinMaxSubquery() {
        TableSchema md = rootSchema("profile", List.of(new ColumnRef("id")));
        ColumnRef asc = new ColumnRef("company", "work_experience", "workHistory",
                List.of(new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id")));
        assertEquals("(SELECT MIN(\"workHistory\".\"company\") FROM \"work_experience\" \"workHistory\""
                        + " WHERE \"workHistory\".\"profile_id\" = \"profile\".\"id\")",
                new SqlBuilder(md).aggregateSortTerm(asc, true));
        assertEquals("(SELECT MAX(\"workHistory\".\"company\") FROM \"work_experience\" \"workHistory\""
                        + " WHERE \"workHistory\".\"profile_id\" = \"profile\".\"id\")",
                new SqlBuilder(md).aggregateSortTerm(asc, false));

        // Deeper edges chain INSIDE the subquery (LEFT JOIN AS shape); correlation stays on the head.
        ColumnRef deep = new ColumnRef("name", "country", "country", List.of(
                new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id"),
                new JoinRef("work_experience", "workHistory", "country_id", "country", "country", "id")));
        assertEquals("(SELECT MIN(\"country\".\"name\") FROM \"work_experience\" \"workHistory\""
                        + " LEFT JOIN \"country\" AS \"country\" ON \"workHistory\".\"country_id\""
                        + " = \"country\".\"id\" WHERE \"workHistory\".\"profile_id\" = \"profile\".\"id\")",
                new SqlBuilder(md).aggregateSortTerm(deep, true));

        // A join-free ref has nothing to aggregate: fail fast rather than emit a correlated nothing.
        assertThatThrownBy(() -> new SqlBuilder(md).aggregateSortTerm(new ColumnRef("id"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate sort term requires a relation path");
    }

    @Test
    void joinClauseSkipsToManyHeadRefsForRuntimeDescriptorsOnly() {
        TypeMapping md = mixedProfile();
        ColumnRef toMany = new ColumnRef("company", "work_experience", "workHistory",
                List.of(new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id")));
        ColumnRef toOne = new ColumnRef("name", "customer", "customer",
                List.of(new JoinRef("profile", null, "customer_id", "customer", "customer", "id")));

        String clause = new SqlBuilder(md).joinClause(List.of(toOne));
        assertThat(clause).doesNotContain("\"work_experience\"");
        assertThat(clause).isEqualTo(" LEFT JOIN \"customer\" AS \"customer\""
                + " ON \"profile\".\"customer_id\" = \"customer\".\"id\"");

        // Compile-time schemas (non-descriptors) are untouched: the processor filters existentials itself.
        TableSchema schema = rootSchema("profile", List.of(toMany));
        assertThat(new SqlBuilder(schema).joinClause(List.of(toMany))).contains("LEFT JOIN \"work_experience\"");
    }
}
