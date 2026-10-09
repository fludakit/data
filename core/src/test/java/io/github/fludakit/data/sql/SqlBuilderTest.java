package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntityMetadata;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metamodel.EmbeddableAttributeRecord;
import io.github.fludakit.data.metamodel.EntityAttributeRecord;
import io.github.fludakit.data.metamodel.IdAttributeRecord;
import io.github.fludakit.data.metamodel.ListAttributeRecord;
import io.github.fludakit.data.metamodel.PathAttribute;
import io.github.fludakit.data.metamodel.PathAttributeRecord;
import io.github.fludakit.data.metamodel.TextScalarAttributeRecord;
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
        String sql = new SqlBuilder(testDialect).selectBy(customer(), List.of(), List.of(), 5, null);
        assertEquals("SELECT [id], [name] FROM [customer] FETCH FIRST 5 ROWS ONLY", sql);
    }

    static EntityMetadata<Customer> customer() {
        return new EntityMetadata<>() {
            @Override public Class<Customer> entityClass() { return Customer.class; }
            @Override public String tableName() { return "customer"; }
            @Override public IdAttributeRecord<Customer> id() {
                return new IdAttributeRecord<>("id");
            }
            @Override public List<PathAttribute<Customer>> attributes() {
                return List.of(new TextScalarAttributeRecord<>("name"));
            }
            @Override public List<ColumnRef> columns() {
                return List.of(new ColumnRef("id"), new ColumnRef("name"));
            }
            @Override public java.util.Map<PathAttribute<Customer>, ColumnRef> columnRefs() {
                return java.util.Map.of(new PathAttributeRecord<Customer>("id", "id"), new ColumnRef("id"),
                        new PathAttributeRecord<Customer>("name", "name"), new ColumnRef("name"));
            }
        };
    }

    /** Profile with an embedded Address: the flattened leaves live in the explicit columns() view. */
    static EntityMetadata<Profile> profile() {
        return new EntityMetadata<>() {
            @Override public Class<Profile> entityClass() { return Profile.class; }
            @Override public String tableName() { return "profile"; }
            @Override public IdAttributeRecord<Profile> id() { return new IdAttributeRecord<>("id"); }
            @Override public List<PathAttribute<Profile>> attributes() {
                return List.of(
                        new EmbeddableAttributeRecord<Profile, Address>("address"));
            }
            @Override public List<ColumnRef> columns() {
                return List.of(new ColumnRef("id", "profile"),
                        new ColumnRef("address_city", "profile"));
            }
            @Override public java.util.Map<PathAttribute<Profile>, ColumnRef> columnRefs() {
                return java.util.Map.of(
                        new PathAttributeRecord<Profile>("id", "id"), new ColumnRef("id", "profile"),
                        new PathAttributeRecord<Profile>("city", "address.city"),
                        new ColumnRef("address_city", "profile"));
            }
        };
    }

    @Test
    void rendersSelectWithWhereAndOrder() {
        String sql = new SqlBuilder().selectBy(customer(),
                List.of(Predicate.of("name", Operator.EQ, "name")),
                List.of(SortSpec.of("id", true, false)), 0, null);
        assertThat(sql).isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name ORDER BY \"id\" ASC");
    }

    @Test
    void orderByResolvesEmbeddedDotPathToFlattenedColumn() {
        String sql = new SqlBuilder().selectBy(profile(), List.of(),
                List.of(SortSpec.of("address.city", true, false)), 0, null);
        assertThat(sql).endsWith("ORDER BY \"address_city\" ASC");
        assertThat(SqlBuilder.resolveColumn(profile(), "address.city")).isEqualTo("address_city");
    }

    @Test
    void orderByRejectsUnknownProperty() {
        assertThatThrownBy(() -> new SqlBuilder().orderBy(customer(), List.of(SortSpec.of("nope", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property 'nope'");
    }

    @Test
    void orderByRejectsEmbeddedContainerName() {
        // The container itself carries no column: sorting by "address" must not silently pass.
        assertThatThrownBy(() -> new SqlBuilder().orderBy(profile(), List.of(SortSpec.of("address", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown property 'address' on Profile");
    }

    @Test
    void rendersInsert() {
        assertThat(new SqlBuilder().insert(customer(), customer()))
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
                SqlBuilder.joinClause(rootMd, List.of(joined)));
        assertEquals("\"orderItems\".\"amount\"", SqlBuilder.qualify(joined, "order"));
    }

    @Test
    void joinClauseChainsQualifiersOverTwoHops() {
        // fk hop (root -> "primary") then a second hop (-> "players"): each qualifier is the
        // PREVIOUS join's alias, regardless of path grouping.
        ColumnRef twoHop = new ColumnRef("name", "team", "players", List.of(
                new JoinRef("game", null, "primary_player_id", "player", "primary", "id"),
                new JoinRef("player", "primary", "team_id", "team", "players", "id")));
        String clause = SqlBuilder.joinClause(rootSchema("game", List.of(twoHop)), List.of(twoHop));
        assertEquals(" LEFT JOIN \"player\" AS \"primary\" ON \"game\".\"primary_player_id\" = \"primary\".\"id\""
                        + " LEFT JOIN \"team\" AS \"players\" ON \"primary\".\"team_id\" = \"players\".\"id\"",
                clause);
        assertThat(clause).contains("\"primary\".\"team_id\" = \"players\".\"id\"");
        assertEquals("\"players\".\"name\"", SqlBuilder.qualify(twoHop, "game"));
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
        String clause = SqlBuilder.joinClause(rootSchema("game", List.of(a, c)), List.of(a, c));
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
        String clause = SqlBuilder.joinClause(rootSchema("order", List.of(a, b, dup)), List.of(a, dup, b));
        assertEquals(" LEFT JOIN \"order_item\" AS \"orderItems\" ON \"order\".\"id\" = \"orderItems\".\"order_id\""
                        + " LEFT JOIN \"item\" AS \"item\" ON \"orderItems\".\"item_id\" = \"item\".\"id\"",
                clause);
    }

    @Test
    void joinClauseIsEmptyForJoinFreeRefs() {
        ColumnRef local = new ColumnRef("total");
        assertEquals("", SqlBuilder.joinClause(rootSchema("order", List.of(local)), List.of(local)));
    }

    @Test
    void qualifyResolvesLocalRefsByTableThenRootTable() {
        assertEquals("\"order\".\"total\"", SqlBuilder.qualify(new ColumnRef("total"), "order"));
        assertEquals("\"other\".\"total\"",
                SqlBuilder.qualify(new ColumnRef("total", "other", null, List.of()), "order"));
        assertThatThrownBy(() -> SqlBuilder.qualify(new ColumnRef("total"), null))
                .isInstanceOf(IllegalStateException.class);
        assertEquals("\"other\".\"total\"",
                SqlBuilder.qualify(new ColumnRef("total", "other", null, List.of()), null));
    }

    @Test
    void renderOverloadQualifiesPredicateColumnWithRef() {
        ColumnRef joined = new ColumnRef("amount", "order_item", "orderItems",
                List.of(new JoinRef("order", null, "id", "order_item", "orderItems", "order_id")));
        assertEquals("\"orderItems\".\"amount\" = :orderItems_amount",
                SqlBuilder.render(Predicate.of("orderItems.amount", Operator.EQ, "orderItems_amount"), joined));
        // Explicit non-root table, no joins.
        ColumnRef foreign = new ColumnRef("total", "other", null, List.of());
        assertEquals("\"other\".\"total\" = :total",
                SqlBuilder.render(Predicate.of("other.total", Operator.EQ, "total"), foreign));
        // Fully-local ref renders unqualified, byte-identical to the legacy path.
        ColumnRef local = new ColumnRef("total");
        assertEquals("\"total\" = :total",
                SqlBuilder.render(Predicate.of("total", Operator.EQ, "total"), local));
    }

    // ---- Task 6: aggregate sort terms replace to-many joined sorts ----

    /** Profile descriptor carrying one element collection, one one-to-many and one to-one head. */
    static EntityMetadata<Profile> mixedProfile() {
        return new EntityMetadata<>() {
            @Override public Class<Profile> entityClass() { return Profile.class; }
            @Override public String tableName() { return "profile"; }
            @Override public IdAttributeRecord<Profile> id() { return new IdAttributeRecord<>("id"); }
            @Override public List<PathAttribute<Profile>> attributes() {
                return List.of(
                        new ListAttributeRecord<Profile, Contact>("contacts"),
                        new ListAttributeRecord<Profile, WorkExperience>("workHistory"),
                        new EntityAttributeRecord<Profile, Customer>("customer"));
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
        EntityMetadata<Profile> md = mixedProfile();
        ColumnRef elementHead = new ColumnRef("value", "profile_contacts", "contacts",
                List.of(new JoinRef("profile", null, "id", "profile_contacts", "contacts", "profile_id")));
        ColumnRef entityHead = new ColumnRef("company", "work_experience", "workHistory",
                List.of(new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id")));
        ColumnRef toOneHead = new ColumnRef("name", "customer", "customer",
                List.of(new JoinRef("profile", null, "customer_id", "customer", "customer", "id")));
        ColumnRef local = new ColumnRef("full_name");
        ColumnRef embedded = new ColumnRef("address_city");

        assertThat(SqlBuilder.isToManyPath(md, "contacts.value")).isTrue();
        assertThat(SqlBuilder.isToManyPath(md, "workHistory.company")).isTrue();
        assertThat(SqlBuilder.isToManyPath(md, "customer.name")).isFalse();
        // Join-free refs (local or embedded) never carry a relation head.
        assertThat(SqlBuilder.isToManyPath(md, "fullName")).isFalse();
        assertThat(SqlBuilder.isToManyPath(md, "address.city")).isFalse();
    }

    @Test
    void aggregateSortTermRendersCorrelatedMinMaxSubquery() {
        TableSchema md = rootSchema("profile", List.of(new ColumnRef("id")));
        ColumnRef asc = new ColumnRef("company", "work_experience", "workHistory",
                List.of(new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id")));
        assertEquals("(SELECT MIN(\"workHistory\".\"company\") FROM \"work_experience\" \"workHistory\""
                        + " WHERE \"workHistory\".\"profile_id\" = \"profile\".\"id\")",
                SqlBuilder.aggregateSortTerm(md, asc, true));
        assertEquals("(SELECT MAX(\"workHistory\".\"company\") FROM \"work_experience\" \"workHistory\""
                        + " WHERE \"workHistory\".\"profile_id\" = \"profile\".\"id\")",
                SqlBuilder.aggregateSortTerm(md, asc, false));

        // Deeper edges chain INSIDE the subquery (LEFT JOIN AS shape); correlation stays on the head.
        ColumnRef deep = new ColumnRef("name", "country", "country", List.of(
                new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id"),
                new JoinRef("work_experience", "workHistory", "country_id", "country", "country", "id")));
        assertEquals("(SELECT MIN(\"country\".\"name\") FROM \"work_experience\" \"workHistory\""
                        + " LEFT JOIN \"country\" AS \"country\" ON \"workHistory\".\"country_id\""
                        + " = \"country\".\"id\" WHERE \"workHistory\".\"profile_id\" = \"profile\".\"id\")",
                SqlBuilder.aggregateSortTerm(md, deep, true));

        // A join-free ref has nothing to aggregate: fail fast rather than emit a correlated nothing.
        assertThatThrownBy(() -> SqlBuilder.aggregateSortTerm(md, new ColumnRef("id"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate sort term requires a relation path");
    }

    @Test
    void joinClauseSkipsToManyHeadRefsForRuntimeDescriptorsOnly() {
        EntityMetadata<Profile> md = mixedProfile();
        ColumnRef toMany = new ColumnRef("company", "work_experience", "workHistory",
                List.of(new JoinRef("profile", null, "id", "work_experience", "workHistory", "profile_id")));
        ColumnRef toOne = new ColumnRef("name", "customer", "customer",
                List.of(new JoinRef("profile", null, "customer_id", "customer", "customer", "id")));

        String clause = SqlBuilder.joinClause(md, List.of(toOne));
        assertThat(clause).doesNotContain("\"work_experience\"");
        assertThat(clause).isEqualTo(" LEFT JOIN \"customer\" AS \"customer\""
                + " ON \"profile\".\"customer_id\" = \"customer\".\"id\"");

        // Compile-time schemas (non-descriptors) are untouched: the processor filters existentials itself.
        TableSchema schema = rootSchema("profile", List.of(toMany));
        assertThat(SqlBuilder.joinClause(schema, List.of(toMany))).contains("LEFT JOIN \"work_experience\"");
    }
}
