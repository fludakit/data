package io.github.fludakit.data.query;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryDefinitionTest {

    @Test
    void queryPlanCarriesLogicalTermsWithoutPhysicalPlacement() {
        ColumnRef joined = new ColumnRef("name", "customer", List.of(new JoinRef("order", "customer_id", "customer", "id")));
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
    void queryDefinitionRecordCarriesTheLimitSpec() {
        QueryDefinition definition = new QueryDefinition(
                Operation.COUNT, ResultKind.COUNT, List.of(), List.of(),
                PagingMode.NONE, new LimitSpec(5, 10), List.of(), List.of(), false);
        assertEquals(Operation.COUNT, definition.operation());
        assertEquals(PagingMode.NONE, definition.paging());
        assertEquals(5, definition.limit().limit());
        assertEquals(10, definition.limit().offset());
        assertEquals(LimitSpec.none(), new LimitSpec(0, 0));
    }
}
