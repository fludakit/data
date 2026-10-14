package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryDefinitionTest {

    @Test
    void queryPlanCarriesLogicalTermsWithoutPhysicalPlacement() {
        ColumnRef joined = new ColumnRef.ForeignKey("customer_id", "order");
        Predicate p = Predicate.of("customer.name", Operator.EQ, "customer_name");
        SortSpec sort = new SortSpec("customer.name", true, false);
        assertEquals("customer.name", p.property());
        assertEquals("customer.name", sort.property());

        SeekTerm seek = new SeekTerm(joined, true);
        assertSame(joined, seek.column());
        assertTrue(seek.ascending());

        ParamBinding binding = new ParamBinding(1, "customer_name");
        assertEquals(1, binding.argIndex());
        assertEquals("customer_name", binding.paramName());
    }

    @Test
    void recordsHaveValueEqualityAcrossWrapperTypes() {
        ColumnRef ref = new ColumnRef.Simple("id", "orders");
        assertEquals(new SeekTerm(ref, true), new SeekTerm(ref, true));
        assertNotEquals(new SeekTerm(ref, true), new SeekTerm(ref, false));
        assertEquals(new ParamBinding(0, "a"), new ParamBinding(0, "a"));
        Predicate p = Predicate.of("id", Operator.EQ, "id");
        SortSpec s = new SortSpec("id", true, false);
        assertEquals(Predicate.of("id", Operator.EQ, "id"), Predicate.of("id", Operator.EQ, "id"));
        assertEquals(new SortSpec("id", true, false), new SortSpec("id", true, false));
    }

    @Test
    void aReadCarriesTheOrderingAndTheRowWindow() {
        // The only variant with an ordering, a paging mode, a row window and keyset terms — and the
        // only one whose result shape is not fixed by the kind of statement it is.
        QueryDefinition.Read read = new QueryDefinition.Read(ResultKind.LIST,
                List.of(Predicate.of("status", Operator.EQ, "status")),
                List.of(new SortSpec("id", true, false)),
                PagingMode.NONE, new LimitSpec(5, 10), List.of(), List.of(), false);
        assertEquals(Operation.FIND, read.operation());
        assertEquals(ResultKind.LIST, read.resultKind());
        assertEquals(PagingMode.NONE, read.paging());
        assertEquals(5, read.limit().limit());
        assertEquals(10, read.limit().offset());
        assertEquals(LimitSpec.none(), new LimitSpec(0, 0));
    }

    @Test
    void aCountCarriesCriteriaAndDistinctAndNothingElse() {
        // No ordering, no paging, no row window, no seek terms — they are not empty here, they are
        // absent, because a count cannot use them.
        QueryDefinition.Count count = new QueryDefinition.Count(
                List.of(Predicate.of("status", Operator.EQ, "status")), List.of(), true);
        assertEquals(Operation.COUNT, count.operation());
        assertEquals(ResultKind.COUNT, count.resultKind());
        assertTrue(count.distinct());
    }

    @Test
    void aCountAndAnExistsKnowTheirOwnResultShape() {
        assertEquals(Operation.EXISTS, new QueryDefinition.Exists(List.of(), List.of()).operation());
        assertEquals(ResultKind.EXISTS, new QueryDefinition.Exists(List.of(), List.of()).resultKind());
        assertEquals(Operation.COUNT, new QueryDefinition.Count(List.of(), List.of(), false).operation());
        assertEquals(ResultKind.COUNT, new QueryDefinition.Count(List.of(), List.of(), false).resultKind());
    }

    /**
     * A write is not always fire-and-forget: it may hand the entity back, or report the number of
     * rows it changed. That is a fact about the repository method, so it rides on the statement —
     * the alternative, a constant VOID, published a delete that returns {@code long} as one that
     * returns nothing.
     */
    @Test
    void everyWriteCarriesTheResultShapeItDeclares() {
        assertEquals(ResultKind.SINGLE,
                new QueryDefinition.Insert(ResultKind.SINGLE, false).resultKind());
        assertEquals(ResultKind.AFFECTED,
                new QueryDefinition.Update(ResultKind.AFFECTED).resultKind());
        assertEquals(ResultKind.AFFECTED,
                new QueryDefinition.DeleteById(ResultKind.AFFECTED).resultKind());
        assertEquals(ResultKind.AFFECTED,
                new QueryDefinition.DeleteWhere(List.of(), List.of(), ResultKind.AFFECTED).resultKind());
    }

    @Test
    void aWriteWithNoDeclaredShapeReportsNothing() {
        assertEquals(ResultKind.VOID, new QueryDefinition.Insert(false).resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.Update().resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.DeleteById().resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.DeleteWhere(List.of(), List.of()).resultKind());
        // A null shape is no shape, not a crash: the convenience constructors all land here.
        assertEquals(ResultKind.VOID, new QueryDefinition.Insert(null, true).resultKind());
    }

    @Test
    void everyWriteKnowsItsOwnOperation() {
        assertEquals(Operation.INSERT, new QueryDefinition.Insert(false).operation());
        assertEquals(Operation.UPDATE, new QueryDefinition.Update().operation());
        assertEquals(Operation.DELETE, new QueryDefinition.DeleteById().operation());
        assertEquals(Operation.DELETE, new QueryDefinition.DeleteWhere(List.of(), List.of()).operation());
    }

    @Test
    void anInsertDefaultsToIncludingTheIdentifier() {
        assertTrue(new QueryDefinition.Insert().includeId());
        assertFalse(new QueryDefinition.Insert(false).includeId());
        assertFalse(new QueryDefinition.Insert(ResultKind.SINGLE, false).includeId());
    }
}
