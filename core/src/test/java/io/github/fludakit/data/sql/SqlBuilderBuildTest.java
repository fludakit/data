package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.model.DataModel.Prop;
import io.github.fludakit.data.model.DataModel.Stereotype;
import io.github.fludakit.data.model.EntityDataModel;
import io.github.fludakit.data.model.ExternalValueObjectModel;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryModel;
import io.github.fludakit.data.query.ResolvedPredicate;
import io.github.fludakit.data.query.ResolvedSort;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Plan E rendering table: {@link SqlBuilder#build(EntitySchema, QueryModel, String)} is the ONE
 * SQL layout renderer, so every shape {@code RepositoryProcessor.methodSql} used to assemble by hand
 * is pinned here byte-for-byte against the strings the processor pins already lock. The legacy
 * delegation, the joined shell, the EXISTS conjunct, the aggregate sort term, delete mode and the
 * paged base constant are each a case; a change to any byte of this table is a SQL change.
 */
class SqlBuilderBuildTest {

    private static final EntityDataModel CUSTOMER = new EntityDataModel(
            Stereotype.AGGREGATE_ROOT, "test.Customer", "customer",
            new Prop.Id("id", "id", "java.lang.Long"),
            List.of(new Prop.Scalar("name", "name", "java.lang.String")));

    private static final EntityDataModel LINE = new EntityDataModel(
            Stereotype.ENTITY, "test.Line", "line",
            new Prop.Id("id", "id", "java.lang.Long"),
            List.of(new Prop.Scalar("product", "product", "java.lang.String")));

    private static final ExternalValueObjectModel TAGS = new ExternalValueObjectModel(
            "test.Tag", "order_tags", "order_id",
            List.of(new Prop.Scalar("label", "label", "java.lang.String")));

    private static final EntityDataModel ORDER = new EntityDataModel(
            Stereotype.AGGREGATE_ROOT, "test.Order", "order_table",
            new Prop.Id("id", "id", "java.lang.Long"),
            List.of(
                    new Prop.Scalar("status", "status", "java.lang.String"),
                    new Prop.Reference("customer", "customer_id", "test.Customer", CUSTOMER, true),
                    new Prop.EntityList("lines", LINE, "order", "order_id"),
                    new Prop.ElementList("tags", TAGS, TAGS.columns())));

    /** A local (unjoined, non-existential) ref for {@code status}. */
    private static final ColumnRef STATUS = new ColumnRef("status", "status");
    /** A to-one traversed ref for {@code customer.name}: joins through the owner's FK. */
    private static final ColumnRef CUSTOMER_NAME = new ColumnRef("name", "customer.name", null,
            List.of(new JoinRef("customer", "customer", "customer_id", "id")));
    /** An existential element-collection ref for {@code tags.label} (head = ElementList). */
    private static final ColumnRef TAG_LABEL = new ColumnRef("label", "tags.label", null,
            List.of(new JoinRef("order_tags", "tags", "id", "order_id")));
    /** An existential one-to-many ref for {@code lines.product} (head = EntityList). */
    private static final ColumnRef LINE_PRODUCT = new ColumnRef("product", "lines.product", null,
            List.of(new JoinRef("line", "lines", "id", "order_id")));

    /** The rendering-under-test as a QueryModel view: the interface, not a record. */
    private record Model(Operation operation, List<ResolvedPredicate> predicates, List<ResolvedSort> sorts,
                         PagingMode paging, int limit) implements QueryModel<Object> {
        Model {
            predicates = List.copyOf(predicates);
            sorts = List.copyOf(sorts);
        }
        @Override public ResultKind resultKind() { return ResultKind.LIST; }
        @Override public List<SeekTerm> seekTerms() { return List.of(); }
        @Override public List<ParamBinding> paramBindings() { return List.of(); }
    }

    private static ResolvedPredicate pred(Predicate p, ColumnRef ref, boolean existential) {
        return new ResolvedPredicate(p, ref, existential);
    }

    private static String build(Operation operation, List<ResolvedPredicate> predicates,
                                List<ResolvedSort> sorts, PagingMode paging, int limit) {
        return new SqlBuilder().build(ORDER, new Model(operation, predicates, sorts, paging, limit));
    }

    @Test
    void legacyFindDelegatesWhenNothingJoinsOrExistentials() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"), STATUS, false)),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void legacyCountAndDeleteDelegate() {
        List<ResolvedPredicate> preds =
                List.of(pred(Predicate.of("status", Operator.EQ, "status"), STATUS, false));
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
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"), STATUS, false)),
                        List.of(new ResolvedSort(new SortSpec("status", false, false), STATUS, false)),
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
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"),
                                CUSTOMER_NAME, false)),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void existentialElementCollectionPredicateRendersExistsWithABareProjection() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE EXISTS (SELECT 1 FROM \"order_tags\" \"tags\""
                        + " WHERE \"tags\".\"order_id\" = \"order_table\".\"id\""
                        + " AND \"tags\".\"label\" = :label)",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("tags.label", Operator.EQ, "label"), TAG_LABEL, true)),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void toManySortRendersTheAggregateTermAndContributesNoJoin() {
        assertEquals("SELECT \"id\", \"status\", \"customer_id\" FROM \"order_table\""
                        + " WHERE \"status\" = :status"
                        + " ORDER BY (SELECT MIN(\"lines\".\"product\") FROM \"line\" \"lines\""
                        + " WHERE \"lines\".\"order_id\" = \"order_table\".\"id\") ASC",
                build(Operation.FIND,
                        List.of(pred(Predicate.of("status", Operator.EQ, "status"), STATUS, false)),
                        List.of(new ResolvedSort(new SortSpec("lines.product", true, false),
                                LINE_PRODUCT, true)),
                        PagingMode.NONE, 0));
    }

    @Test
    void deleteThroughAToOneRelationRendersExistsAndNoJoin() {
        assertEquals("DELETE FROM \"order_table\" WHERE EXISTS (SELECT 1 FROM \"customer\" \"customer\""
                        + " WHERE \"customer\".\"id\" = \"order_table\".\"customer_id\""
                        + " AND \"customer\".\"name\" = :customer_name)",
                build(Operation.DELETE,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"),
                                CUSTOMER_NAME, false)),
                        List.of(), PagingMode.NONE, 0));
    }

    @Test
    void pagedModelRendersTheBaseConstantSortFreeAndLimitFree() {
        List<ResolvedPredicate> preds =
                List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"),
                        CUSTOMER_NAME, false));
        List<ResolvedSort> sorts =
                List.of(new ResolvedSort(new SortSpec("customer.name", false, false), CUSTOMER_NAME, false));
        String base = new SqlBuilder().build(ORDER, new Model(Operation.FIND, preds, sorts,
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
        String sql = new SqlBuilder().build(ORDER,
                new Model(Operation.FIND,
                        List.of(pred(Predicate.of("customer.name", Operator.EQ, "customer_name"),
                                CUSTOMER_NAME, false)),
                        List.of(), PagingMode.NONE, 0),
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
                () -> SqlBuilder.qualifiedRootSelect(ORDER, List.of(), false, "\"customer\".\"id\""));
        assertEquals("bare projection cannot carry extraProjection", failure.getMessage());
    }
}
