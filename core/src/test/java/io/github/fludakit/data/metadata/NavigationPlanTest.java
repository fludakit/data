package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NavigationPlanTest {

    @Test
    void aliasForReturnsAliasWhenPresent() {
        var edge = new JoinEdge.ToOne(
            new ColumnRef.ForeignKey("customer_id", "orders"),
            new ColumnRef.Simple("id", "customers")
        );
        var spec = new NavigationPlan.JoinSpec(edge, "customers_1", LoadStrategy.EAGER, "customer", 1);
        var plan = new NavigationPlan("orders", "orders_0", List.of(spec), 1);

        assertEquals("customers_1", plan.aliasFor("customers"));
        assertNull(plan.aliasFor("products"));
    }

    @Test
    void forPathReturnsSpecWhenPresent() {
        var edge = new JoinEdge.ToOne(
            new ColumnRef.ForeignKey("customer_id", "orders"),
            new ColumnRef.Simple("id", "customers")
        );
        var spec = new NavigationPlan.JoinSpec(edge, "customers_1", LoadStrategy.EAGER, "customer", 1);
        var plan = new NavigationPlan("orders", "orders_0", List.of(spec), 1);

        assertNotNull(plan.forPath("customer"));
        assertNull(plan.forPath("product"));
    }

    @Test
    void specsForFiltersByStrategy() {
        var edge1 = new JoinEdge.ToOne(
            new ColumnRef.ForeignKey("customer_id", "orders"),
            new ColumnRef.Simple("id", "customers")
        );
        var edge2 = new JoinEdge.ToOne(
            new ColumnRef.ForeignKey("product_id", "orders"),
            new ColumnRef.Simple("id", "products")
        );
        var spec1 = new NavigationPlan.JoinSpec(edge1, "customers_1", LoadStrategy.EAGER, "customer", 1);
        var spec2 = new NavigationPlan.JoinSpec(edge2, "products_2", LoadStrategy.FK_ONLY, "product", 1);
        var plan = new NavigationPlan("orders", "orders_0", List.of(spec1, spec2), 1);

        assertEquals(1, plan.specsFor(LoadStrategy.EAGER).size());
        assertEquals(1, plan.specsFor(LoadStrategy.FK_ONLY).size());
        assertEquals(0, plan.specsFor(LoadStrategy.NOT_LOADED).size());
    }
}
