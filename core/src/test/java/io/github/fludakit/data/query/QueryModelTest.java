package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryModelTest {

    record Sample(QueryModel<String> delegate) implements QueryModel<String> {
        @Override public Operation operation() { return Operation.FIND; }
        @Override public ResultKind resultKind() { return ResultKind.LIST; }
        @Override public List<ResolvedPredicate> predicates() { return delegate.predicates(); }
        @Override public List<ResolvedSort> sorts() { return delegate.sorts(); }
        @Override public PagingMode paging() { return PagingMode.CURSOR; }
        @Override public int limit() { return 10; }
        @Override public List<SeekTerm> seekTerms() { return delegate.seekTerms(); }
        @Override public List<ParamBinding> paramBindings() { return delegate.paramBindings(); }
    }

    @Test
    void resolvedWrappersCarryRefAndExistentialFlag() {
        ColumnRef joined = new ColumnRef("name", "customer.name", null,
                List.of(new JoinRef("customer", "customer", "customer_id", "id")));
        Predicate p = new Predicate("customer.name", Operator.EQ, "customer_name");
        ResolvedPredicate rp = new ResolvedPredicate(p, joined, false);
        assertSame(joined, rp.ref());
        assertFalse(rp.existential());
        assertEquals(joined, rp.ref());

        ResolvedSort rs = new ResolvedSort(new SortSpec("customer.name", true, false), joined, false);
        assertEquals("customer.name", rs.sort().property());
        assertSame(joined, rs.ref());

        SeekTerm seek = new SeekTerm(joined, true);
        assertSame(joined, seek.ref());
        assertTrue(seek.ascending());

        ParamBinding binding = new ParamBinding(1, "customer_name");
        assertEquals(1, binding.argIndex());
        assertEquals("customer_name", binding.paramName());
    }

    @Test
    void recordsHaveValueEqualityAcrossWrapperTypes() {
        ColumnRef ref = new ColumnRef("id", "id");
        assertEquals(new SeekTerm(ref, true), new SeekTerm(ref, true));
        assertNotEquals(new SeekTerm(ref, true), new SeekTerm(ref, false));
        assertEquals(new ParamBinding(0, "a"), new ParamBinding(0, "a"));
        Predicate p = new Predicate("id", Operator.EQ, "id");
        SortSpec s = new SortSpec("id", true, false);
        assertEquals(new ResolvedPredicate(p, ref, false), new ResolvedPredicate(p, ref, false));
        assertNotEquals(new ResolvedPredicate(p, ref, false), new ResolvedPredicate(p, ref, true));
        assertEquals(new ResolvedSort(s, ref, true), new ResolvedSort(s, ref, true));
    }

    @Test
    void interfaceSurface() {
        QueryModel<String> legacyShape = new QueryModel<>() {
            @Override public Operation operation() { return Operation.COUNT; }
            @Override public ResultKind resultKind() { return ResultKind.COUNT; }
            @Override public List<ResolvedPredicate> predicates() { return List.of(); }
            @Override public List<ResolvedSort> sorts() { return List.of(); }
            @Override public PagingMode paging() { return PagingMode.NONE; }
            @Override public int limit() { return 0; }
            @Override public List<SeekTerm> seekTerms() { return List.of(); }
            @Override public List<ParamBinding> paramBindings() { return List.of(); }
        };
        assertEquals(Operation.COUNT, legacyShape.operation());
        assertEquals(PagingMode.NONE, legacyShape.paging());
    }
}
