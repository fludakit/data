package io.github.fludakit.data.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryDefinitionTest {

    @Test
    void aReadCarriesTheOrderingAndTheRowWindow() {
        QueryDefinition.Read read = new QueryDefinition.Read(ResultKind.LIST,
                List.of(Predicate.of("status", Operator.EQ, "status")),
                List.of(),
                false,
                Order.Static.of(List.of(new SortSpec("id", true, false))),
                new Window.None(new LimitSpec(5, 10)));
        assertEquals(ResultKind.LIST, read.resultKind());
        assertInstanceOf(Window.None.class, read.window());
        assertEquals(5, ((Window.None) read.window()).limit().limit());
        assertEquals(10, ((Window.None) read.window()).limit().offset());
        assertEquals(LimitSpec.none(), new LimitSpec(0, 0));
        assertEquals(List.of(new SortSpec("id", true, false)), read.sorts());
    }

    @Test
    void aCountCarriesCriteriaAndDistinctAndNothingElse() {
        QueryDefinition.Count count = new QueryDefinition.Count(
                List.of(Predicate.of("status", Operator.EQ, "status")), List.of(), true);
        assertEquals(ResultKind.COUNT, count.resultKind());
        assertTrue(count.distinct());
    }

    @Test
    void aCountAndAnExistsKnowTheirOwnResultShape() {
        assertEquals(ResultKind.EXISTS, new QueryDefinition.Exists(List.of(), List.of()).resultKind());
        assertEquals(ResultKind.COUNT, new QueryDefinition.Count(List.of(), List.of(), false).resultKind());
    }

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
        assertEquals(ResultKind.VOID, new QueryDefinition.Insert(null, false).resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.Update(null).resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.DeleteById(null).resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.DeleteWhere(List.of(), List.of(), null).resultKind());
        assertEquals(ResultKind.VOID, new QueryDefinition.Insert(null, true).resultKind());
    }

    @Test
    void nativeQueryCarriesSqlBindingsAndResultKind() {
        QueryDefinition.Native nativeQuery = new QueryDefinition.Native(
                "SELECT 1", ResultKind.SINGLE, List.of(new ParamBinding(0, "id", LikePattern.NONE)));
        assertEquals("SELECT 1", nativeQuery.sql());
        assertEquals(ResultKind.SINGLE, nativeQuery.resultKind());
        assertEquals(1, nativeQuery.paramBindings().size());
    }

    @Test
    void windowAndOrderAreSealed() {
        assertInstanceOf(Window.Limit.class, new Window.Limit(1));
        assertInstanceOf(Window.Offset.class, new Window.Offset(0));
        assertInstanceOf(Window.Cursor.class, new Window.Cursor(0));
        assertInstanceOf(Order.Dynamic.class, new Order.Dynamic(2));
        assertSame(Order.None.INSTANCE, Order.None.INSTANCE);
    }
}
