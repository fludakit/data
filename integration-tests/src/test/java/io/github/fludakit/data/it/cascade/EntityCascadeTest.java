package io.github.fludakit.data.it.cascade;

import io.github.fludakit.data.it.DataSourceProducer;
import io.github.fludakit.data.it.estore.*;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@EnableAutoWeld
@AddPackages(Order.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class EntityCascadeTest {

    @Inject OrderRepository orders;
    @Inject CustomerRepository customers;
    @Inject ProductRepository products;

    @Test
    void entityInsertOnlyCascadesElementCollections() {
        Customer cust = new Customer();
        cust.setName("Test");
        customers.save(cust);

        Order order = new Order(cust);
        order.setAmount(java.math.BigDecimal.TEN);
        Order saved = orders.save(order);

        assertThat(saved.getId()).isNotNull();
        // Order is @Entity, not @Aggregate: entity collections are NOT cascaded
        // Element collections (if any) ARE cascaded
    }

    @Test
    void entityDeleteOnlyCascadesElementCollections() {
        Customer cust = new Customer();
        cust.setName("Test");
        customers.save(cust);

        Order order = new Order(cust);
        order.setAmount(java.math.BigDecimal.TEN);
        Order saved = orders.save(order);
        Long orderId = saved.getId();

        orders.deleteById(orderId);

        assertThat(orders.findById(orderId)).isEmpty();
        // Customer should still exist (not cascade-deleted via @Association)
        assertThat(cust.getId()).isNotNull();
        assertThat(customers.findById(cust.getId())).isPresent();
    }

    @Test
    void associationTargetIsNeverWrittenByOwner() {
        Customer cust = new Customer();
        cust.setName("Original");
        customers.save(cust);

        Order order = new Order(cust);
        order.setAmount(java.math.BigDecimal.TEN);
        orders.save(order);

        // Modify the customer through the order's association
        cust.setName("Modified");
        // Saving the order should NOT update the customer (composition=false)
        orders.save(order);

        Customer reloaded = customers.findById(cust.getId()).orElseThrow();
        // Customer name should remain "Original" since Order doesn't cascade to Customer
        assertThat(reloaded.getName()).isEqualTo("Original");
    }
}
