package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlBuilderTest {

    static class Customer { }

    static class Profile { }

    static class Address { }

    static class Contact { }

    static class WorkExperience { }

    static class Ticket { }

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
        return TestMappings.aggregateRoot("Customer", "customer",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Scalar("name", "name", "java.lang.String")),
                List.of(new ColumnRef.Simple("id", "customer"), new ColumnRef.Simple("name", "customer")));
    }

    /** Profile with an embedded Address: the flattened leaves live in the explicit columns() view. */
    static TypeMapping<Profile> profile() {
        return TestMappings.aggregateRoot("Profile", "profile",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(new Prop.Embedded("address", null)),
                List.of(new ColumnRef.Simple("id", "profile"),
                        new ColumnRef.Simple("address_city", "profile")),
                Map.of("id", new ColumnRef.Simple("id", "profile"),
                        "address.city", new ColumnRef.Simple("address_city", "profile")));
    }

    @Test
    void rendersNegatedPredicateOperators() {
        assertThat(render(Predicate.of("name", Operator.NOT_IN, "name")))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" NOT IN (:name)");
        assertThat(render(Predicate.of("name", Operator.NOT_BETWEEN, "name")))
                .isEqualTo("SELECT \"id\", \"name\" FROM \"customer\""
                        + " WHERE \"name\" NOT BETWEEN :name_from AND :name_to");
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

    /** A mapped type whose only column is its identifier — the shape with nothing left to bind. */
    static TypeMapping<Ticket> identifierOnly() {
        return TestMappings.aggregateRoot("Ticket", "ticket",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(),
                List.of(new ColumnRef.Simple("id", "ticket")));
    }

    @Test
    void rendersAnAllDefaultsInsertForAnIdentifierOnlyEntity() {
        // A generated-key insert drops the identity from the column list, which for this shape leaves
        // it EMPTY — and `INSERT INTO t () VALUES ()` is not SQL. The row is still a real insert:
        // every column takes its default and the database generates the identity.
        assertThat(new SqlBuilder(identifierOnly()).insert(false))
                .isEqualTo("INSERT INTO \"ticket\" DEFAULT VALUES");
    }

    @Test
    void anIdentifierOnlyEntityStillInsertsItsAssignedIdentifier() {
        // The same shape carrying an application-assigned identifier has one column to bind, so it
        // keeps the ordinary form. The two differ only in whether the identity is generated.
        assertThat(new SqlBuilder(identifierOnly()).insert(true))
                .isEqualTo("INSERT INTO \"ticket\" (\"id\") VALUES (:id)");
    }

    @Test
    void anUpsertWithNothingToUpdateLeavesTheExistingRowAlone() {
        // There is no non-key column here, so `DO UPDATE SET ` followed by an empty list would not be
        // SQL. `DO NOTHING` is the same insert-or-update promise read at that size: insert the row,
        // or leave the existing one as it is when the identifier is already there.
        assertThat(new SqlBuilder(identifierOnly()).dialect(PostgreSqlDialect.INSTANCE).upsert())
                .isEqualTo("INSERT INTO \"ticket\" (\"id\") VALUES (:id) ON CONFLICT (\"id\") DO NOTHING");
    }

    @Test
    void anUpdateWithNothingToAssignStillReportsWhetherTheRowExists() {
        // `UPDATE t SET  WHERE ...` is not SQL. Assigning the identity to itself changes nothing in
        // the row while keeping the row-count contract the caller branches on — one when the row
        // exists, zero when it does not.
        assertThat(new SqlBuilder(identifierOnly()).update())
                .isEqualTo("UPDATE \"ticket\" SET \"id\" = \"id\" WHERE \"id\" = :id");
    }

    // ---- SqlBuilder renders joins from resolved ColumnPlacement edges ----

    /** The edge lists of several placements concatenated, preserving order — what {@code joinClause} takes. */
    private static List<JoinEdge> concat(ColumnPlacement... placements) {
        List<JoinEdge> edges = new java.util.ArrayList<>();
        for (ColumnPlacement placement : placements) {
            edges.addAll(placement.edges());
        }
        return edges;
    }

    /** One placement: a leaf column plus the edges that must be crossed to reach it. */
    private static ColumnPlacement placed(ColumnRef leaf, JoinEdge... edges) {
        return new ColumnPlacement(leaf, List.of(edges));
    }

    /** Minimal engine-facing schema over a root table for join-clause rendering. */
    static TableSchema rootSchema(String table, List<ColumnRef> columns) {
        return new TableSchema() {
            @Override public String tableName() { return table; }
            @Override public String idColumn() { return "id"; }
            @Override public List<ColumnRef> columns() { return columns; }
        };
    }

    @Test
    void joinClauseEmitsSingleLeftJoinAndQualifiesLeafByOwnerTable() {
        ColumnPlacement joined = placed(new ColumnRef.Simple("amount", "order_item"),
                new JoinEdge.ToMany(new ColumnRef.Simple("id", "order"),
                        new ColumnRef.ForeignKey("order_id", "order_item")));
        TableSchema rootMd = rootSchema("order", List.of(joined.leaf()));
        assertEquals(" LEFT JOIN \"order_item\" AS \"order_item\" ON \"order\".\"id\" = \"order_item\".\"order_id\"",
                new SqlBuilder(rootMd).joinClause(joined.edges()));
        assertEquals("\"order_item\".\"amount\"", new SqlBuilder(rootMd).qualify(joined.leaf()));
    }

    @Test
    void joinClauseChainsQualifiersOverTwoHops() {
        // Each hop aliases its child table as itself and qualifies the FK from the owner table
        // named on the edge — no per-path alias bookkeeping.
        ColumnPlacement twoHop = placed(new ColumnRef.Simple("name", "team"),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("primary_player_id", "game"),
                        new ColumnRef.Simple("id", "player")),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("team_id", "player"), new ColumnRef.Simple("id", "team")));
        TableSchema game = rootSchema("game", List.of(twoHop.leaf()));
        String clause = new SqlBuilder(game).joinClause(twoHop.edges());
        assertEquals(" LEFT JOIN \"player\" AS \"player\" ON \"game\".\"primary_player_id\" = \"player\".\"id\""
                        + " LEFT JOIN \"team\" AS \"team\" ON \"player\".\"team_id\" = \"team\".\"id\"",
                clause);
        assertThat(clause).contains("\"player\".\"team_id\" = \"team\".\"id\"");
        assertEquals("\"team\".\"name\"", new SqlBuilder(game).qualify(twoHop.leaf()));
    }

    @Test
    void joinClauseChainsQualifiersPerPathAcrossDivergingRefs() {
        // Placement A navigates player -> team; placement C navigates player -> squad. They share the
        // head edge onto "player" but diverge on the second hop. Each edge qualifies its FK from its
        // own owner table, so C's second hop reads "player"."squad_id" — never A's "team" table.
        JoinEdge primary = new JoinEdge.ToOne(new ColumnRef.ForeignKey("primary_player_id", "game"),
                                   new ColumnRef.Simple("id", "player"));
        ColumnPlacement a = placed(new ColumnRef.Simple("name", "team"),
                primary, new JoinEdge.ToOne(new ColumnRef.ForeignKey("team_id", "player"),
                                 new ColumnRef.Simple("id", "team")));
        ColumnPlacement c = placed(new ColumnRef.Simple("label", "squad"),
                primary, new JoinEdge.ToOne(new ColumnRef.ForeignKey("squad_id", "player"),
                                 new ColumnRef.Simple("id", "squad")));
        TableSchema game = rootSchema("game", List.of(a.leaf(), c.leaf()));
        String clause = new SqlBuilder(game).joinClause(concat(a, c));
        assertEquals(" LEFT JOIN \"player\" AS \"player\" ON \"game\".\"primary_player_id\" = \"player\".\"id\""
                        + " LEFT JOIN \"team\" AS \"team\" ON \"player\".\"team_id\" = \"team\".\"id\""
                        + " LEFT JOIN \"squad\" AS \"squad\" ON \"player\".\"squad_id\" = \"squad\".\"id\"",
                clause);
        // C's second hop is qualified by its own owner table "player", not by A's "team".
        assertThat(clause).contains("\"player\".\"squad_id\" = \"squad\".\"id\"");
        assertThat(clause).doesNotContain("\"team\".\"squad_id\"");
    }

    @Test
    void joinClauseDedupesByChildTableFirstOccurrenceWins() {
        // a and b both carry the head edge onto order_item; b's re-emission is suppressed. The
        // unrelated other_table edge stays, because dedupe is keyed by the child table name.
        JoinEdge head = new JoinEdge.ToMany(new ColumnRef.Simple("id", "order"),
                                new ColumnRef.ForeignKey("order_id", "order_item"));
        ColumnPlacement a = placed(new ColumnRef.Simple("amount", "order_item"), head);
        ColumnPlacement b = placed(new ColumnRef.Simple("qty", "item"),
                head, new JoinEdge.ToOne(new ColumnRef.ForeignKey("item_id", "order_item"),
                              new ColumnRef.Simple("id", "item")));
        ColumnPlacement other = placed(new ColumnRef.Simple("price", "other_table"),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("x", "order"), new ColumnRef.Simple("y", "other_table")));
        TableSchema order = rootSchema("order", List.of(a.leaf(), b.leaf(), other.leaf()));
        String clause = new SqlBuilder(order).joinClause(concat(a, other, b));
        assertEquals(" LEFT JOIN \"order_item\" AS \"order_item\" ON \"order\".\"id\" = \"order_item\".\"order_id\""
                        + " LEFT JOIN \"other_table\" AS \"other_table\" ON \"order\".\"x\" = \"other_table\".\"y\""
                        + " LEFT JOIN \"item\" AS \"item\" ON \"order_item\".\"item_id\" = \"item\".\"id\"",
                clause);
    }

    @Test
    void joinClauseIsEmptyForJoinFreePlacements() {
        ColumnPlacement local = placed(new ColumnRef.Simple("total", null));
        assertEquals("", new SqlBuilder(rootSchema("order", List.of(local.leaf()))).joinClause(local.edges()));
    }

    @Test
    void joinClauseDropsTablesTheBaseAlreadyJoined() {
        // A JOIN-projected base constant already carries LEFT JOIN "author". A runtime sort through
        // the same target resolves to another edge onto that table; splicing it repeats the alias and
        // makes every reference to it ambiguous, so the suppression set must drop it while leaving an
        // edge onto a table the base did NOT join untouched.
        ColumnPlacement author = placed(new ColumnRef.Simple("name", "author"),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("id", "book"), new ColumnRef.Simple("id", "author")));
        ColumnPlacement publisher = placed(new ColumnRef.Simple("name", "publisher"),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("publisher_id", "book"),
                        new ColumnRef.Simple("id", "publisher")));
        TableSchema book = rootSchema("book", List.of(author.leaf(), publisher.leaf()));
        assertEquals(" LEFT JOIN \"publisher\" AS \"publisher\" ON \"book\".\"publisher_id\" = \"publisher\".\"id\"",
                new SqlBuilder(book).joinClause(concat(author, publisher), List.of("author")));
    }

    @Test
    void joinClauseSuppressionIsByChildTableNotByEdgeShape() {
        // The base joined author through one edge; the runtime resolves the same table through a
        // differently-shaped edge. Suppression compares the child TABLE, so both must be dropped.
        ColumnPlacement byFk = placed(new ColumnRef.Simple("name", "author"),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("author_id", "book"), new ColumnRef.Simple("id", "author")));
        ColumnPlacement byJoin = placed(new ColumnRef.Simple("name", "author"),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("id", "book"), new ColumnRef.Simple("id", "author")));
        TableSchema book = rootSchema("book", List.of(byFk.leaf(), byJoin.leaf()));
        assertEquals("", new SqlBuilder(book).joinClause(concat(byFk, byJoin), List.of("author")));
        assertEquals(" LEFT JOIN \"author\" AS \"author\" ON \"book\".\"author_id\" = \"author\".\"id\"",
                new SqlBuilder(book).joinClause(concat(byFk, byJoin)));
    }

    @Test
    void qualifyResolvesLocalRefsByTableThenRootTable() {
        TableSchema order = rootSchema("order", List.of());
        assertEquals("\"order\".\"total\"", new SqlBuilder(order).qualify(new ColumnRef.Simple("total", null), "order"));
        assertEquals("\"other\".\"total\"",
                new SqlBuilder(order).qualify(new ColumnRef.Simple("total", "other"), "order"));
        assertThatThrownBy(() -> new SqlBuilder(order).qualify(new ColumnRef.Simple("total", null), null))
                .isInstanceOf(IllegalStateException.class);
        assertEquals("\"other\".\"total\"",
                new SqlBuilder(order).qualify(new ColumnRef.Simple("total", "other"), null));
    }

    @Test
    void renderQualifiesPredicateColumnByThePlacementLeaf() {
        ColumnPlacement joined = placed(new ColumnRef.Simple("amount", "order_item"),
                new JoinEdge.ToMany(new ColumnRef.Simple("id", "order"),
                        new ColumnRef.ForeignKey("order_id", "order_item")));
        TableSchema order = rootSchema("order", List.of());
        assertEquals("\"order_item\".\"amount\" = :orderItems_amount",
                new SqlBuilder(order).renderPredicate(
                        Predicate.of("orderItems.amount", Operator.EQ, "orderItems_amount"), joined));
        // Explicit non-root table, no edges.
        ColumnPlacement foreign = placed(new ColumnRef.Simple("total", "other"));
        assertEquals("\"other\".\"total\" = :total",
                new SqlBuilder(order).renderPredicate(Predicate.of("other.total", Operator.EQ, "total"), foreign));
        // A leaf owned by no table renders unqualified, byte-identical to the legacy path.
        ColumnPlacement local = placed(new ColumnRef.Simple("total", null));
        assertEquals("\"total\" = :total",
                new SqlBuilder(order).renderPredicate(Predicate.of("total", Operator.EQ, "total"), local));
    }

    // ---- A to-many sort is rendered as an aggregate subquery instead of a joined sort ----

    /** Profile descriptor carrying one element collection, one one-to-many and one to-one head. */
    static TypeMapping<Profile> mixedProfile() {
        return TestMappings.aggregateRoot("Profile", "profile",
                new Prop.Id("id", "id", "java.lang.Long"),
                List.of(
                        new Prop.EntityCollection("contacts", null, null, null, null),
                        new Prop.EntityCollection("workHistory", null, null, null, null),
                        new Prop.Reference("customer", "customer_id", null, null, false, false)),
                List.of(new ColumnRef.Simple("id", "profile"),
                        new ColumnRef.Simple("customer_id", "profile"),
                        new ColumnRef.Simple("full_name", "profile")));
    }

    @Test
    void isToManyPathDetectsCollectionAndEntityCollectionPaths() {
        TypeMapping md = mixedProfile();

        assertThat(new SqlBuilder(md).isToManyPath("contacts.value")).isTrue();
        assertThat(new SqlBuilder(md).isToManyPath("workHistory.company")).isTrue();
        assertThat(new SqlBuilder(md).isToManyPath("customer.name")).isFalse();
        // Join-free refs (local or embedded) never carry a relation head.
        assertThat(new SqlBuilder(md).isToManyPath("fullName")).isFalse();
        assertThat(new SqlBuilder(md).isToManyPath("address.city")).isFalse();
    }

    @Test
    void aggregateSortTermRendersCorrelatedMinMaxSubquery() {
        TableSchema md = rootSchema("profile", List.of(new ColumnRef.Simple("id", "profile")));
        ColumnPlacement asc = placed(new ColumnRef.Simple("company", "work_experience"),
                new JoinEdge.ToMany(new ColumnRef.Simple("id", "profile"),
                        new ColumnRef.ForeignKey("profile_id", "work_experience")));
        assertEquals("(SELECT MIN(\"work_experience\".\"company\") FROM \"work_experience\" \"work_experience\""
                        + " WHERE \"work_experience\".\"profile_id\" = \"profile\".\"id\")",
                new SqlBuilder(md).aggregateSortTerm(asc, true));
        assertEquals("(SELECT MAX(\"work_experience\".\"company\") FROM \"work_experience\" \"work_experience\""
                        + " WHERE \"work_experience\".\"profile_id\" = \"profile\".\"id\")",
                new SqlBuilder(md).aggregateSortTerm(asc, false));

        // Deeper edges chain INSIDE the subquery (LEFT JOIN AS shape); correlation stays on the head.
        ColumnPlacement deep = placed(new ColumnRef.Simple("name", "country"),
                new JoinEdge.ToMany(new ColumnRef.Simple("id", "profile"),
                        new ColumnRef.ForeignKey("profile_id", "work_experience")),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("country_id", "work_experience"),
                        new ColumnRef.Simple("id", "country")));
        assertEquals("(SELECT MIN(\"country\".\"name\") FROM \"work_experience\" \"work_experience\""
                        + " LEFT JOIN \"country\" AS \"country\" ON \"work_experience\".\"country_id\""
                        + " = \"country\".\"id\" WHERE \"work_experience\".\"profile_id\" = \"profile\".\"id\")",
                new SqlBuilder(md).aggregateSortTerm(deep, true));

        // A join-free placement has nothing to aggregate: fail fast rather than emit a correlated
        // nothing.
        assertThatThrownBy(() -> new SqlBuilder(md)
                .aggregateSortTerm(placed(new ColumnRef.Simple("id", "profile")), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate sort term requires a relation path");
    }

    /**
     * The reason {@link ColumnPlacement} exists: a renderer takes the resolved leaf AND its edges as
     * one value, so the two cannot be handed over mismatched. One placement drives all three
     * renderings — the aggregate sort term, the EXISTS conjunct and the plain qualified term — and
     * they agree on the column because they share the value.
     */
    @Test
    void onePlacementDrivesTheAggregateExistsAndQualifiedRenderings() {
        TableSchema md = rootSchema("profile", List.of(new ColumnRef.Simple("id", "profile")));
        ColumnPlacement placement = placed(new ColumnRef.Simple("company", "work_experience"),
                new JoinEdge.ToMany(new ColumnRef.Simple("id", "profile"),
                        new ColumnRef.ForeignKey("profile_id", "work_experience")));
        SqlBuilder sql = new SqlBuilder(md);

        assertEquals("(SELECT MIN(\"work_experience\".\"company\") FROM \"work_experience\""
                        + " \"work_experience\" WHERE \"work_experience\".\"profile_id\" = \"profile\".\"id\")",
                sql.aggregateSortTerm(placement, true));

        Predicate predicate = Predicate.of("workHistory.company", Operator.EQ, "workHistory_company");
        assertEquals("EXISTS (SELECT 1 FROM \"work_experience\" \"work_experience\""
                        + " WHERE \"work_experience\".\"profile_id\" = \"profile\".\"id\""
                        + " AND \"work_experience\".\"company\" = :workHistory_company)",
                sql.existsConjunct(predicate, placement));

        assertEquals("\"work_experience\".\"company\" = :workHistory_company",
                sql.renderPredicate(predicate, placement));
    }

    /** A placement the renderer cannot reach across: no edges means nothing to aggregate. */
    @Test
    void placementAggregateSortTermRejectsALocalPlacement() {
        TableSchema md = rootSchema("profile", List.of(new ColumnRef.Simple("id", "profile")));
        ColumnPlacement local = new ColumnPlacement(new ColumnRef.Simple("id", "profile"));
        assertThatThrownBy(() -> new SqlBuilder(md).aggregateSortTerm(local, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate sort term requires a relation path");
    }

    @Test
    void joinClauseRendersTheEdgesItIsGivenAndDoesNotFilterByEdgeKind() {
        // WHICH edges may be spliced is decided upstream: an existential (to-many head) renders an
        // EXISTS conjunct instead and never reaches here, so the planner drops it before the call.
        // joinClause is a pure rendering — it splices a ToOne edge and does not second-guess the
        // caller by inspecting kinds, and it splices a ToMany edge just as literally when a caller
        // hands one over.
        TypeMapping md = mixedProfile();
        JoinEdge toOne = new JoinEdge.ToOne(new ColumnRef.ForeignKey("customer_id", "profile"),
                                 new ColumnRef.Simple("id", "customer"));
        JoinEdge toMany = new JoinEdge.ToMany(new ColumnRef.Simple("id", "profile"),
                                  new ColumnRef.ForeignKey("profile_id", "work_experience"));

        String clause = new SqlBuilder(md).joinClause(List.of(toOne));
        assertThat(clause).isEqualTo(" LEFT JOIN \"customer\" AS \"customer\""
                + " ON \"profile\".\"customer_id\" = \"customer\".\"id\"");
        assertThat(clause).doesNotContain("\"work_experience\"");

        assertThat(new SqlBuilder(md).joinClause(List.of(toMany)))
                .contains("LEFT JOIN \"work_experience\"");
    }
}
