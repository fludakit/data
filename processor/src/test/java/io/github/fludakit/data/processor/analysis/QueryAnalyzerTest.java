package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.*;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.Operator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueryAnalyzerTest {

    @Test
    void analyzesPredicateWithNavigation() {
        // Build a simple TypeDefinition with an association
        var customerId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        var customerName = new TypeDefinition.Prop.Scalar("name", "java.lang.String", "name", false);
        var customerType = new TypeDefinition.Entity(
            "com.example.Customer", "customers", customerId, null,
            List.of(customerName), List.of(), List.of(), List.of(), List.of()
        );

        var orderId = new TypeDefinition.Prop.Id("id", "java.lang.Long", "id",
            TypeDefinition.Prop.Id.GeneratedType.IDENTITY);
        var orderStatus = new TypeDefinition.Prop.Scalar("status", "java.lang.String", "status", true);
        var customerAssoc = new TypeDefinition.Prop.Association(
            "customer", "com.example.Customer", customerType, "customer_id",
            false, false, null, LoadStrategy.FK_ONLY
        );
        var orderType = new TypeDefinition.Entity(
            "com.example.Order", "orders", orderId, null,
            List.of(orderStatus), List.of(), List.of(customerAssoc), List.of(), List.of()
        );

        // Build a MethodContext with a predicate on customer.name
        var method = new MethodContext(
            null, "findByCustomerName", null, null, false,
            List.of(), List.of(),
            List.of(Predicate.of("customer.name", Operator.EQ, "name")),
            List.of(), null, null
        );

        NavigationPlan plan = QueryAnalyzer.analyze(method, orderType);

        assertEquals("orders", plan.rootTable());
        assertEquals("orders_0", plan.rootAlias());
        assertEquals(1, plan.joinSpecs().size());
        assertEquals("customers_1", plan.joinSpecs().get(0).alias());
        assertEquals(LoadStrategy.EAGER, plan.joinSpecs().get(0).strategy());
        assertEquals(1, plan.maxDepth());
    }
}
