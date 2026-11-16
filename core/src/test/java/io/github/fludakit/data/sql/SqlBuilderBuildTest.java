package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Order;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import io.github.fludakit.data.query.Window;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins {@link QueryCompiler#compile} → {@link SqlBuilder#sql(SqlFragment)} byte-for-byte.
 */
class SqlBuilderBuildTest {

    private enum Kind { FIND, COUNT, EXISTS, DELETE }

    private static final TypeDefinition.Entity CUSTOMER = new TypeDefinition.Entity(
            "demo.Customer", "customer",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            null,
            List.of(new TypeDefinition.Prop.Scalar("name", "String", "name", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.Entity LINE = new TypeDefinition.Entity(
            "demo.Line", "line",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            null,
            List.of(new TypeDefinition.Prop.Scalar("product", "java.lang.Long", "product", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.ValueObject TAG = new TypeDefinition.ValueObject(
            "demo.Tag",
            List.of(new TypeDefinition.Prop.Scalar("label", "String", "label", true)),
            List.of(), List.of(), List.of(), List.of());

    private static final TypeDefinition.Entity ATTRIBUTES = new TypeDefinition.Entity(
            "demo.Order", "order_table",
            new TypeDefinition.Prop.Id("id", "Long", "id", TypeDefinition.Prop.Id.GeneratedType.IDENTITY),
            null,
            List.of(new TypeDefinition.Prop.Scalar("status", "String", "status", false)),
            List.of(),
            List.of(new TypeDefinition.Prop.Association(
                    "customer", "demo.Customer", CUSTOMER, "customer_id", false, false, null)),
            List.of(new TypeDefinition.Prop.EntityCollection(
                    "lines", "java.util.List", LINE, "order_id", null)),
            List.of(new TypeDefinition.Prop.ElementCollection(
                    "tags", "java.util.List", TAG, "order_tags", "order_id")));

    private static QueryDefinition model(Kind operation, List<Predicate> predicates,
                                         List<SortSpec> sorts, PagingMode paging, int limit) {
        return model(operation, predicates, sorts, paging, limit, false);
    }

    private static QueryDefinition model(Kind operation, List<Predicate> predicates,
                                         List<SortSpec> sorts, PagingMode paging, int limit,
                                         boolean distinct) {
        return switch (operation) {
            case COUNT -> new QueryDefinition.Count(predicates, List.of(), distinct);
            case EXISTS -> new QueryDefinition.Exists(predicates, List.of());
            case DELETE -> new QueryDefinition.DeleteWhere(predicates, List.of());
            default -> {
                Order order = sorts == null || sorts.isEmpty() ? Order.None.INSTANCE : Order.Static.of(sorts);
                Window window = switch (paging == null ? PagingMode.NONE : paging) {
                    case NONE -> new Window.None(new LimitSpec(limit, 0));
                    case OFFSET -> new Window.Offset(0);
                    case CURSOR -> new Window.Cursor(0);
                };
                yield new QueryDefinition.Read(ResultKind.LIST, predicates, List.of(), distinct, order, window);
            }
        };
    }

    private static String build(Kind operation, List<Predicate> predicates,
                                List<SortSpec> sorts, PagingMode paging, int limit) {
        return new SqlBuilder(ATTRIBUTES).sql(model(operation, predicates, sorts, paging, limit));
    }

    private static Predicate pred(Predicate predicate) {
        return predicate;
    }

    @Test
    void distinctFindSelectsDistinctRows() {
        String sql = new SqlBuilder(ATTRIBUTES).sql(model(Kind.FIND,
                List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0, true));
        assertEquals("SELECT DISTINCT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                + " WHERE \"status\" = :status", sql);
    }

    @Test
    void distinctCountCountsDistinctIdentifiers() {
        String sql = new SqlBuilder(ATTRIBUTES).sql(model(Kind.COUNT,
                List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0, true));
        assertEquals("SELECT COUNT(DISTINCT \"id\") FROM \"order_table\" WHERE \"status\" = :status", sql);
    }

    @Test
    void lifecycleStatementsRenderStraightFromTheTableShape() {
        assertEquals("INSERT INTO \"order_table\" (\"id\", \"status\", \"customer_id\")"
                        + " VALUES (:id, :status, :customer)",
                new SqlBuilder(ATTRIBUTES).sql(new QueryDefinition.Insert()));
        assertEquals("INSERT INTO \"order_table\" (\"status\", \"customer_id\") VALUES (:status, :customer)",
                new SqlBuilder(ATTRIBUTES).sql(new QueryDefinition.Insert(false)));
        assertEquals("UPDATE \"order_table\" SET \"status\" = :status, \"customer_id\" = :customer"
                        + " WHERE \"id\" = :id",
                new SqlBuilder(ATTRIBUTES).sql(new QueryDefinition.Update()));
        assertEquals("DELETE FROM \"order_table\" WHERE \"id\" = :id",
                new SqlBuilder(ATTRIBUTES).sql(new QueryDefinition.DeleteById()));
    }

    @Test
    void nonDistinctDefinitionsRenderUnchanged() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(), PagingMode.NONE, 0));
        assertEquals("SELECT COUNT(*) FROM \"order_table\" WHERE \"status\" = :status",
                build(Kind.COUNT,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void theClassicHelpersTakeDistinctAsAnExplicitFlagToo() {
        List<Predicate> where = List.of(Predicate.of("status", Operator.EQ, "status"));
        assertEquals("SELECT DISTINCT \"order_table_0\".\"id\", \"order_table_0\".\"status\", \"order_table_0\".\"customer_id\" FROM \"order_table\""
                        + " AS \"order_table_0\" WHERE \"status\" = :status",
                QueryCompiler.selectBy(H2Dialect.INSTANCE, ATTRIBUTES, where, List.of(), 0, null, true));
        assertEquals("SELECT COUNT(DISTINCT \"id\") FROM \"order_table\" WHERE \"status\" = :status",
                QueryCompiler.countBy(H2Dialect.INSTANCE, ATTRIBUTES, where, true));
    }

    @Test
    void orConnectorRendersOrBetweenLocalPredicates() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status OR \"customer_id\" = :customer_id",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status")),
                                pred(Predicate.of("customer_id", Operator.EQ, "customer_id", Connector.OR))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void mixedConnectorsKeepDeclarationOrder() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :a AND \"customer_id\" = :b OR \"status\" = :c",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "a")),
                                pred(Predicate.of("customer_id", Operator.EQ, "b")),
                                pred(Predicate.of("status", Operator.EQ, "c", Connector.OR))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void orWithAnExistentialPredicateKeepsTheExistentialInPlace() {
        String sql = build(Kind.FIND,
                List.of(pred(Predicate.of("tags.label", Operator.EQ, "label", Connector.OR)),
                        pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0);
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                + " WHERE EXISTS (SELECT 1 FROM \"order_tags\" \"order_tags\""
                + " WHERE \"order_tags\".\"order_id\" = \"order_table\".\"id\" AND \"order_tags\".\"label\" = :label)"
                + " AND \"status\" = :status", sql);
    }

    @Test
    void configuredDialectFlowsThroughJoinedQueryRendering() {
        SqlDialect testDialect = new SqlDialect() {
            @Override
            public String quoteIdentifier(String identifier) {
                return "`" + identifier + "`";
            }

            @Override
            public String limitClause(String limitExpression) {
                return " FETCH FIRST " + limitExpression + " ROWS ONLY";
            }

            @Override
            public String offsetClause(String offsetExpression) {
                return " OFFSET " + offsetExpression;
            }
        };
        String sql = new SqlBuilder(ATTRIBUTES).dialect(testDialect).sql(model(Kind.FIND,
                List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                List.of(), PagingMode.NONE, 0));
        assertEquals("SELECT `order_table`.`id`, `order_table`.`status`, `order_table`.`customer_id`"
                        + " FROM `order_table` LEFT JOIN `customer` AS `customer`"
                        + " ON `order_table`.`customer_id` = `customer`.`id`"
                        + " WHERE `customer`.`name` = :customer_name",
                sql);
    }

    @Test
    void legacyFindDelegatesWhenNothingJoinsOrExistentials() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void legacyCountAndDeleteDelegate() {
        List<Predicate> preds = List.of(pred(Predicate.of("status", Operator.EQ, "status")));
        assertEquals("SELECT COUNT(*) FROM \"order_table\" WHERE \"status\" = :status",
                build(Kind.COUNT, preds, List.of(), PagingMode.NONE, 0));
        assertEquals("SELECT COUNT(*) FROM \"order_table\" WHERE \"status\" = :status",
                build(Kind.EXISTS, preds, List.of(), PagingMode.NONE, 0));
        assertEquals("DELETE FROM \"order_table\" WHERE \"status\" = :status",
                build(Kind.DELETE, preds, List.of(), PagingMode.NONE, 0));
    }

    @Test
    void legacyLimitAndSortsFlowThroughTheClassicDelegate() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status ORDER BY \"status\" DESC LIMIT 5",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(new SortSpec("status", false, false)),
                        PagingMode.NONE, 5));
    }

    @Test
    void toOnePredicateQualifiesEveryRootColumnAndSplicesTheJoin() {
        assertEquals("SELECT \"order_table\".\"id\", \"order_table\".\"status\","
                        + " \"order_table\".\"customer_id\""
                        + " FROM \"order_table\""
                        + " LEFT JOIN \"customer\" AS \"customer\""
                        + " ON \"order_table\".\"customer_id\" = \"customer\".\"id\""
                        + " WHERE \"customer\".\"name\" = :customer_name",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void existentialElementCollectionPredicateRendersExistsWithABareProjection() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE EXISTS (SELECT 1 FROM \"order_tags\" \"order_tags\""
                        + " WHERE \"order_tags\".\"order_id\" = \"order_table\".\"id\""
                        + " AND \"order_tags\".\"label\" = :label)",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("tags.label", Operator.EQ, "label"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void toManySortRendersTheAggregateTermAndContributesNoJoin() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status"
                        + " ORDER BY (SELECT MIN(\"line\".\"product\") FROM \"line\" \"line\""
                        + " WHERE \"line\".\"order_id\" = \"order_table\".\"id\") ASC",
                build(Kind.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(new SortSpec("lines.product", true, false)),
                        PagingMode.NONE, 0));
    }

    @Test
    void deleteThroughAToOneRelationRendersExistsAndNoJoin() {
        assertEquals("DELETE FROM \"order_table\" WHERE EXISTS (SELECT 1 FROM \"customer\" \"customer\""
                        + " WHERE \"customer\".\"id\" = \"order_table\".\"customer_id\""
                        + " AND \"customer\".\"name\" = :customer_name)",
                build(Kind.DELETE,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void pagedModelRendersTheBaseConstantSortFreeAndLimitFree() {
        List<Predicate> preds = List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name")));
        List<SortSpec> sorts = List.of(new SortSpec("customer.name", false, false));
        String base = new SqlBuilder(ATTRIBUTES).sql(model(Kind.FIND, preds, sorts,
                PagingMode.OFFSET, 10));
        assertEquals("SELECT \"order_table\".\"id\", \"order_table\".\"status\","
                        + " \"order_table\".\"customer_id\""
                        + " FROM \"order_table\""
                        + " LEFT JOIN \"customer\" AS \"customer\""
                        + " ON \"order_table\".\"customer_id\" = \"customer\".\"id\""
                        + " WHERE \"customer\".\"name\" = :customer_name", base);
    }

    @Test
    void extraProjectionRidesTheQualifiedFindShellOnly() {
        String sql = new SqlBuilder(ATTRIBUTES).sql(
                model(Kind.FIND,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0),
                List.of(),
                "\"customer\".\"id\" AS \"customer_join_id\"");
        assertEquals("SELECT \"order_table\".\"id\", \"order_table\".\"status\","
                        + " \"order_table\".\"customer_id\","
                        + " \"customer\".\"id\" AS \"customer_join_id\""
                        + " FROM \"order_table\""
                        + " LEFT JOIN \"customer\" AS \"customer\""
                        + " ON \"order_table\".\"customer_id\" = \"customer\".\"id\""
                        + " WHERE \"customer\".\"name\" = :customer_name", sql);
    }

    @Test
    void bareProjectionCannotCarryExtraColumns() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SqlBuilder(ATTRIBUTES).qualifiedRootSelect(List.of(), false, "\"customer\".\"id\""));
        assertEquals("bare projection cannot carry extraProjection", failure.getMessage());
    }

    @Test
    void prettyFormatRunsSqlFormatter() {
        String sql = new SqlBuilder(ATTRIBUTES).format(SqlFormat.PRETTY).sql(model(Kind.FIND,
                List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0));
        assertEquals(SqlFormatter.format(
                "SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\" WHERE \"status\" = :status"), sql);
    }
}
