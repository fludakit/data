package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryDefinitionTest {

    record Sample(QueryDefinition delegate) implements QueryDefinition {
        @Override public Operation operation() { return Operation.FIND; }
        @Override public ResultKind resultKind() { return ResultKind.LIST; }
        @Override public List<Predicate> predicates() { return delegate.predicates(); }
        @Override public List<SortSpec> sorts() { return delegate.sorts(); }
        @Override public PagingMode paging() { return PagingMode.CURSOR; }
        @Override public int limit() { return 10; }
        @Override public List<SeekTerm> seekTerms() { return delegate.seekTerms(); }
        @Override public List<ParamBinding> paramBindings() { return delegate.paramBindings(); }
    }

    @Test
    void queryPlanCarriesLogicalTermsWithoutPhysicalPlacement() {
        ColumnRef joined = new ColumnRef("name", "customer", "customer",
                List.of(new JoinRef("order", null, "customer_id", "customer", "customer", "id")));
        Predicate p = Predicate.of("customer.name", Operator.EQ, "customer_name");
        SortSpec sort = new SortSpec("customer.name", true, false);
        assertEquals("customer.name", p.property());
        assertEquals("customer.name", sort.property());

        SeekTerm seek = new SeekTerm(joined, true);
        assertSame(joined, seek.ref());
        assertTrue(seek.ascending());

        ParamBinding binding = new ParamBinding(1, "customer_name");
        assertEquals(1, binding.argIndex());
        assertEquals("customer_name", binding.paramName());
    }

    @Test
    void recordsHaveValueEqualityAcrossWrapperTypes() {
        ColumnRef ref = new ColumnRef("id");
        assertEquals(new SeekTerm(ref, true), new SeekTerm(ref, true));
        assertNotEquals(new SeekTerm(ref, true), new SeekTerm(ref, false));
        assertEquals(new ParamBinding(0, "a"), new ParamBinding(0, "a"));
        Predicate p = Predicate.of("id", Operator.EQ, "id");
        SortSpec s = new SortSpec("id", true, false);
        assertEquals(Predicate.of("id", Operator.EQ, "id"), Predicate.of("id", Operator.EQ, "id"));
        assertEquals(new SortSpec("id", true, false), new SortSpec("id", true, false));
    }

    @Test
    void interfaceSurface() {
        QueryDefinition legacyShape = new QueryDefinition() {
            @Override public Operation operation() { return Operation.COUNT; }
            @Override public ResultKind resultKind() { return ResultKind.COUNT; }
            @Override public List<Predicate> predicates() { return List.of(); }
            @Override public List<SortSpec> sorts() { return List.of(); }
            @Override public PagingMode paging() { return PagingMode.NONE; }
            @Override public int limit() { return 0; }
            @Override public List<SeekTerm> seekTerms() { return List.of(); }
            @Override public List<ParamBinding> paramBindings() { return List.of(); }
        };
        assertEquals(Operation.COUNT, legacyShape.operation());
        assertEquals(PagingMode.NONE, legacyShape.paging());
    }
}
