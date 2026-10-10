package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.AttributePathResolver;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The SQL rendering table: {@link SqlBuilder#build(TableSchema, AttributePathResolver, QueryDefinition, String)} is the one
 * SQL layout renderer, so every shape {@code RepositoryProcessor.methodSql} used to assemble by hand
 * is pinned here byte-for-byte against the strings the processor pins already lock. The legacy
 * delegation, the joined shell, the EXISTS conjunct, the aggregate sort term, delete mode and the
 * paged base constant are each a case; a change to any byte of this table is a SQL change.
 */
class SqlBuilderBuildTest {

    private static final TableSchema ORDER = new TableSchema() {
        private final List<ColumnRef> columns = List.of(
                new ColumnRef("id", "order_table"),
                new ColumnRef("status", "order_table"),
                new ColumnRef("customer_id", "order_table"));

        @Override public String tableName() { return "order_table"; }
        @Override public String idColumn() { return "id"; }
        @Override public List<ColumnRef> columns() { return columns; }
    };

    /** A local (unjoined, non-existential) ref for {@code status}. */
    private static final ColumnRef STATUS = new ColumnRef("status");
    /** A to-one traversed ref for {@code customer.name}: joins through the owner's FK. */
    private static final ColumnRef CUSTOMER_NAME = new ColumnRef("name", "customer", "customer",
            List.of(new JoinRef("order_table", null, "customer_id", "customer", "customer", "id")));
    /** An existential element-collection ref for {@code tags.label} (head = ElementList). */
    private static final ColumnRef TAG_LABEL = new ColumnRef("label", "order_tags", "tags",
            List.of(new JoinRef("order_table", null, "id", "order_tags", "tags", "order_id")));
    /** An existential one-to-many ref for {@code lines.product} (head = EntityList). */
    private static final ColumnRef LINE_PRODUCT = new ColumnRef("product", "line", "lines",
            List.of(new JoinRef("order_table", null, "id", "line", "lines", "order_id")));

    private static final AttributePathResolver<String> ATTRIBUTES = new AttributePathResolver<>() {
        @Override public Map<String, ColumnRef> columnRefs() {
            return Map.of("status", STATUS, "customer.name", CUSTOMER_NAME,
                    "tags.label", TAG_LABEL, "lines.product", LINE_PRODUCT);
        }

        @Override public String attributePath(String path) {
            return path;
        }

        @Override public ColumnRef columnRef(String path) {
            return switch (path) {
                case "status" -> STATUS;
                case "customer.name" -> CUSTOMER_NAME;
                case "tags.label" -> TAG_LABEL;
                case "lines.product" -> LINE_PRODUCT;
                default -> throw new IllegalArgumentException("Unknown property '" + path + "'");
            };
        }

        @Override public boolean isToManyPath(String attributePath) {
            int dot = attributePath.indexOf('.');
            String head = dot < 0 ? attributePath : attributePath.substring(0, dot);
            return head.equals("lines") || head.equals("tags");
        }
    };

    /** The rendering-under-test as a QueryDefinition view: the interface, not a record. */
    private record Model(Operation operation, List<Predicate> predicates, List<SortSpec> sorts,
                         PagingMode paging, int limit) implements QueryDefinition {
        Model {
            predicates = List.copyOf(predicates);
            sorts = List.copyOf(sorts);
        }
        @Override public ResultKind resultKind() { return ResultKind.LIST; }
        @Override public List<SeekTerm> seekTerms() { return List.of(); }
        @Override public List<ParamBinding> paramBindings() { return List.of(); }
    }

    private static Predicate pred(Predicate predicate) {
        return predicate;
    }

    private static String build(Operation operation, List<Predicate> predicates,
                                List<SortSpec> sorts, PagingMode paging, int limit) {
        SqlBuilder builder = new SqlBuilder(ORDER, ATTRIBUTES,
                new Model(operation, predicates, sorts, paging, limit));
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
                new Model(Operation.FIND,
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
                        + " WHERE EXISTS (SELECT 1 FROM \"order_tags\" \"tags\""
                        + " WHERE \"tags\".\"order_id\" = \"order_table\".\"id\""
                        + " AND \"tags\".\"label\" = :label)",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("tags.label", Operator.EQ, "label"))),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void toManySortRendersTheAggregateTermAndContributesNoJoin() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status"
                        + " ORDER BY (SELECT MIN(\"lines\".\"product\") FROM \"line\" \"lines\""
                        + " WHERE \"lines\".\"order_id\" = \"order_table\".\"id\") ASC",
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
        String base = new SqlBuilder(ORDER, ATTRIBUTES, new Model(Operation.FIND, preds, sorts,
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
                new Model(Operation.FIND,
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
