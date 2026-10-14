package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.query.Connector;
import io.github.fludakit.data.query.LimitSpec;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The SQL rendering table: {@link SqlBuilder}'s structured-query terminals ({@link SqlBuilder#select()},
 * {@link SqlBuilder#count()}, {@link SqlBuilder#delete()}) are the one SQL layout renderer, so every
 * shape the processor used to assemble by hand is pinned here byte-for-byte against the strings the
 * processor pins already lock. The legacy delegation, the joined shell, the EXISTS conjunct, the
 * aggregate sort term, delete mode and the paged base constant are each a case; a change to any byte
 * of this table is a SQL change.
 */
class SqlBuilderBuildTest {

    private static final TableSchema ORDER = new TableSchema() {
        private final List<ColumnRef> columns = List.of(
                new ColumnRef.Simple("id", "order_table"),
                new ColumnRef.Simple("status", "order_table"),
                new ColumnRef.Simple("customer_id", "order_table"));

        @Override public String tableName() { return "order_table"; }
        @Override public String idColumn() { return "id"; }
        @Override public List<ColumnRef> columns() { return columns; }
    };

    /** The identifier — needed by the lifecycle statements, which name every column by its path. */
    private static final ColumnPlacement ID =
            new ColumnPlacement(new ColumnRef.Simple("id", "order_table"));
    /** A local (unjoined, non-existential) placement for {@code status}. */
    private static final ColumnPlacement STATUS =
            new ColumnPlacement(new ColumnRef.Simple("status", "order_table"));
    /** A to-one traversal for {@code customer.name}: joins through the owner's FK. */
    private static final ColumnPlacement CUSTOMER_NAME = new ColumnPlacement(
            new ColumnRef.Simple("name", "customer"),
            List.of(new JoinEdge.ToOne(new ColumnRef.ForeignKey("customer_id", "order_table"),
                            new ColumnRef.Simple("id", "customer"))));
    /** An existential element-collection traversal for {@code tags.label}. */
    private static final ColumnPlacement TAG_LABEL = new ColumnPlacement(
            new ColumnRef.Simple("label", "order_tags"),
            List.of(new JoinEdge.ToMany(new ColumnRef.Simple("id", "order_table"),
                            new ColumnRef.ForeignKey("order_id", "order_tags"))));
    /** An existential one-to-many traversal for {@code lines.product}. */
    private static final ColumnPlacement LINE_PRODUCT = new ColumnPlacement(
            new ColumnRef.Simple("product", "line"),
            List.of(new JoinEdge.ToMany(new ColumnRef.Simple("id", "order_table"),
                            new ColumnRef.ForeignKey("order_id", "line"))));

    /** The hand-written stand-in for a generated per-entity {@code resolve} switch. */
    private static final ColumnResolver ATTRIBUTES = new ColumnResolver() {
        @Override public Map<String, ColumnRef> columnRefs() {
            return Map.of("id", ID.leaf(), "status", STATUS.leaf(),
                    "customer_id", new ColumnRef.Simple("customer_id", "order_table"),
                    "customer.name", CUSTOMER_NAME.leaf(),
                    "tags.label", TAG_LABEL.leaf(), "lines.product", LINE_PRODUCT.leaf());
        }

        @Override public ColumnPlacement resolve(String path) {
            return switch (path) {
                case "status" -> STATUS;
                case "customer.name" -> CUSTOMER_NAME;
                case "tags.label" -> TAG_LABEL;
                case "lines.product" -> LINE_PRODUCT;
                default -> throw new UnknownAttributePathException(path, "Order");
            };
        }

        @Override public boolean isToManyPath(String attributePath) {
            int dot = attributePath.indexOf('.');
            String head = dot < 0 ? attributePath : attributePath.substring(0, dot);
            return head.equals("lines") || head.equals("tags");
        }
    };

    /** The rendering-under-test: the variant the operation names, carrying only what it can use. */
    private static QueryDefinition.Criteria model(Operation operation, List<Predicate> predicates,
                                                  List<SortSpec> sorts, PagingMode paging, int limit) {
        return model(operation, predicates, sorts, paging, limit, false);
    }

    private static QueryDefinition.Criteria model(Operation operation, List<Predicate> predicates,
                                                  List<SortSpec> sorts, PagingMode paging, int limit,
                                                  boolean distinct) {
        return switch (operation) {
            case COUNT -> new QueryDefinition.Count(predicates, List.of(), distinct);
            case EXISTS -> new QueryDefinition.Exists(predicates, List.of());
            case DELETE -> new QueryDefinition.DeleteWhere(predicates, List.of());
            default -> new QueryDefinition.Read(ResultKind.LIST, predicates, sorts, paging,
                    new LimitSpec(limit, 0), List.of(), List.of(), distinct);
        };
    }

    /**
     * {@code DISTINCT} is a fact only a read and a count carry: it is the one thing a method name can
     * state that changes the projection rather than the criteria, so it rides from the parse to the
     * renderer on those two variants.
     */
    @Test
    void distinctFindSelectsDistinctRows() {
        String sql = new SqlBuilder(ORDER, ATTRIBUTES, model(Operation.FIND,
                List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0, true)).select();
        assertEquals("SELECT DISTINCT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                + " WHERE \"status\" = :status", sql);
    }

    @Test
    void distinctCountCountsDistinctIdentifiers() {
        // COUNT(DISTINCT *) is not valid SQL, so the row identity column carries the DISTINCT.
        String sql = new SqlBuilder(ORDER, ATTRIBUTES, model(Operation.COUNT,
                List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0, true)).count();
        assertEquals("SELECT COUNT(DISTINCT \"id\") FROM \"order_table\" WHERE \"status\" = :status", sql);
    }

    @Test
    void lifecycleStatementsRenderStraightFromTheTableShape() {
        // Insert / update / delete-by-id are QueryDefinition variants too, so one render maps them —
        // but they carry no criteria, so they never reach the planner or the resolver.
        assertEquals("INSERT INTO \"order_table\" (\"id\", \"status\", \"customer_id\")"
                        + " VALUES (:id, :status, :customer_id)",
                new SqlBuilder(ORDER, ATTRIBUTES).render(new QueryDefinition.Insert()));
        assertEquals("INSERT INTO \"order_table\" (\"status\", \"customer_id\") VALUES (:status, :customer_id)",
                new SqlBuilder(ORDER, ATTRIBUTES).render(new QueryDefinition.Insert(false)));
        assertEquals("UPDATE \"order_table\" SET \"status\" = :status, \"customer_id\" = :customer_id"
                        + " WHERE \"id\" = :id",
                new SqlBuilder(ORDER, ATTRIBUTES).render(new QueryDefinition.Update()));
        assertEquals("DELETE FROM \"order_table\" WHERE \"id\" = :id",
                new SqlBuilder(ORDER).render(new QueryDefinition.DeleteById()));
    }

    @Test
    void nonDistinctDefinitionsRenderUnchanged() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(), PagingMode.NONE, 0));
        assertEquals("SELECT COUNT(*) FROM \"order_table\" WHERE \"status\" = :status",
                build(Operation.COUNT,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void theClassicHelpersTakeDistinctAsAnExplicitFlagToo() {
        // The no-join statements predate the model, so they keep the explicit flag the model now
        // supplies — both spellings must render the same SQL.
        List<Predicate> where = List.of(Predicate.of("status", Operator.EQ, "status"));
        assertEquals("SELECT DISTINCT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status",
                QueryCompiler.selectBy(H2Dialect.INSTANCE, ORDER, where, List.of(), 0, null, true));
        assertEquals("SELECT COUNT(DISTINCT \"id\") FROM \"order_table\" WHERE \"status\" = :status",
                QueryCompiler.countBy(H2Dialect.INSTANCE, ORDER, where, true));
    }

    private static Predicate pred(Predicate predicate) {
        return predicate;
    }

    @Test
    void orConnectorRendersOrBetweenLocalPredicates() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status OR \"customer_id\" = :customer_id",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status")),
                                pred(Predicate.of("customer_id", Operator.EQ, "customer_id", Connector.OR))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void mixedConnectorsKeepDeclarationOrder() {
        // a AND b OR c is (a AND b) OR c by SQL precedence, so the terms render in the declared order.
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :a AND \"customer_id\" = :b OR \"status\" = :c",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "a")),
                                pred(Predicate.of("customer_id", Operator.EQ, "b")),
                                pred(Predicate.of("status", Operator.EQ, "c", Connector.OR))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void orWithAnExistentialPredicateKeepsTheExistentialInPlace() {
        // The all-AND path reorders existential conjuncts to the end; under OR that would change the
        // meaning, so an OR-bearing query renders every conjunct in declaration order.
        String sql = build(Operation.FIND,
                List.of(pred(Predicate.of("tags.label", Operator.EQ, "label", Connector.OR)),
                        pred(Predicate.of("status", Operator.EQ, "status"))),
                List.of(), PagingMode.NONE, 0);
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                + " WHERE EXISTS (SELECT 1 FROM \"order_tags\" \"order_tags\""
                + " WHERE \"order_tags\".\"order_id\" = \"order_table\".\"id\" AND \"order_tags\".\"label\" = :label)"
                + " AND \"status\" = :status", sql);
    }

    private static String build(Operation operation, List<Predicate> predicates,
                                List<SortSpec> sorts, PagingMode paging, int limit) {
        SqlBuilder builder = new SqlBuilder(ORDER, ATTRIBUTES,
                model(operation, predicates, sorts, paging, limit));
        return switch (operation) {
            case COUNT, EXISTS -> builder.count();
            case DELETE -> builder.delete();
            default -> builder.select();
        };
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
        String sql = new SqlBuilder(ORDER, ATTRIBUTES,
                model(Operation.FIND,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0)).dialect(testDialect).select();
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
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void legacyCountAndDeleteDelegate() {
        List<Predicate> preds = List.of(pred(Predicate.of("status", Operator.EQ, "status")));
        assertEquals("SELECT COUNT(*) FROM \"order_table\" WHERE \"status\" = :status",
                build(Operation.COUNT, preds, List.of(), PagingMode.NONE, 0));
        assertEquals("SELECT COUNT(*) FROM \"order_table\" WHERE \"status\" = :status",
                build(Operation.EXISTS, preds, List.of(), PagingMode.NONE, 0));
        assertEquals("DELETE FROM \"order_table\" WHERE \"status\" = :status",
                build(Operation.DELETE, preds, List.of(), PagingMode.NONE, 0));
    }

    @Test
    void legacyLimitAndSortsFlowThroughTheClassicDelegate() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status ORDER BY \"status\" DESC LIMIT 5",
                build(Operation.FIND,
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
                build(Operation.FIND,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void existentialElementCollectionPredicateRendersExistsWithABareProjection() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE EXISTS (SELECT 1 FROM \"order_tags\" \"order_tags\""
                        + " WHERE \"order_tags\".\"order_id\" = \"order_table\".\"id\""
                        + " AND \"order_tags\".\"label\" = :label)",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("tags.label", Operator.EQ, "label"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void toManySortRendersTheAggregateTermAndContributesNoJoin() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status"
                        + " ORDER BY (SELECT MIN(\"line\".\"product\") FROM \"line\" \"line\""
                        + " WHERE \"line\".\"order_id\" = \"order_table\".\"id\") ASC",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"))),
                        List.of(new SortSpec("lines.product", true, false)),
                        PagingMode.NONE, 0));
    }

    @Test
    void deleteThroughAToOneRelationRendersExistsAndNoJoin() {
        assertEquals("DELETE FROM \"order_table\" WHERE EXISTS (SELECT 1 FROM \"customer\" \"customer\""
                        + " WHERE \"customer\".\"id\" = \"order_table\".\"customer_id\""
                        + " AND \"customer\".\"name\" = :customer_name)",
                build(Operation.DELETE,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void pagedModelRendersTheBaseConstantSortFreeAndLimitFree() {
        List<Predicate> preds = List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name")));
        List<SortSpec> sorts = List.of(new SortSpec("customer.name", false, false));
        String base = new SqlBuilder(ORDER, ATTRIBUTES, model(Operation.FIND, preds, sorts,
                PagingMode.OFFSET, 10)).select();
        assertEquals("SELECT \"order_table\".\"id\", \"order_table\".\"status\","
                        + " \"order_table\".\"customer_id\""
                        + " FROM \"order_table\""
                        + " LEFT JOIN \"customer\" AS \"customer\""
                        + " ON \"order_table\".\"customer_id\" = \"customer\".\"id\""
                        + " WHERE \"customer\".\"name\" = :customer_name", base);
    }

    @Test
    void extraProjectionRidesTheQualifiedFindShellOnly() {
        String sql = new SqlBuilder(ORDER, ATTRIBUTES,
                model(Operation.FIND,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"))),
                        List.of(), PagingMode.NONE, 0)).select("\"customer\".\"id\" AS \"customer_join_id\"");
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
                () -> new SqlBuilder(ORDER).qualifiedRootSelect(List.of(), false, "\"customer\".\"id\""));
        assertEquals("bare projection cannot carry extraProjection", failure.getMessage());
    }
}
