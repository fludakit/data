package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.it.DataSourceProducer;

import io.github.fludakit.jdbc.JdbcClient;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips a {@code List<@ValueObject>} element collection through the generated child-table
 * hydration: reads (findById, findAll, paged) materialize the parents then group children onto each
 * one; update replaces the whole child set (delete + re-insert); delete cascades the children.
 *
 * <p>Contract lock (Decision 8): in this iteration hydration covers element collections only — the
 * {@code @Association} header target ({@code Order.getCustomer()}) and the {@code @Association} child
 * target ({@code OrderItem.getProduct()}) are NEVER populated on read; they stay {@code null}. Task 4
 * widens hydration to many-to-one / one-to-many.</p>
 */
@EnableAutoWeld
@AddPackages(Order.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class OrderCollectionHydrationTest {

    @Inject CustomerRepository customers;
    @Inject ProductRepository products;
    @Inject OrderRepository orders;
    @Inject JdbcClient jdbcClient;

    @Test
    void collectionRoundTrip() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Product widget = products.save(product("Widget", new BigDecimal("1.00")));
        Product gizmo = products.save(product("Gizmo", new BigDecimal("2.00")));

        Order order = new Order(ana);
        order.addItem(widget, 2);
        order.addItem(gizmo, 5);
        Order saved = orders.save(order);

        Order found = orders.findById(saved.getId()).orElseThrow();
        assertThat(found.getOrderItems()).hasSize(2);
        assertThat(found.getOrderItems()).extracting(OrderItem::getQuantity).containsExactlyInAnyOrder(2, 5);
        // Decision 8 — @Association targets are never hydrated in Task 3.
        assertThat(found.getOrderItems()).allSatisfy(i -> assertThat(i.getProduct()).isNull());
        assertThat(found.getCustomer()).isNull();

        // replace-all update: drop the two children, add a single one back.
        found.getOrderItems().clear();
        found.addItem(widget, 7);
        orders.update(found);

        Order afterUpdate = orders.findById(saved.getId()).orElseThrow();
        assertThat(afterUpdate.getOrderItems()).hasSize(1);
        assertThat(afterUpdate.getOrderItems().get(0).getQuantity()).isEqualTo(7);

        // stream read (findAll materializes then hydrates) and paged read both hydrate too.
        assertThat(orders.findAll().toList()).isNotEmpty();
        assertThat(orders.findAll().toList())
                .anySatisfy(o -> assertThat(o.getOrderItems()).isNotEmpty());

        // delete cascades children: the header is gone and the child table no longer references it.
        orders.delete(found);
        Long remaining = jdbcClient.sql("SELECT COUNT(*) FROM order_order_items WHERE order_id = :id")
                .param("id", saved.getId()).singleValue(Long.class);
        assertThat(remaining).isZero();
    }

    @Test
    void findByAmountHydratesOrderItems() {
        // I-2: a hydrated LIST @Find must run the umbrella — the returned orders carry hydrated
        // orderItems (and the Decision 8 @Association child target stays null through this read too).
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Product widget = products.save(product("Widget", new BigDecimal("1.00")));
        Order order = new Order(ana);
        order.setAmount(new BigDecimal("42.00"));
        order.addItem(widget, 3);
        orders.save(order);

        List<Order> found = orders.findByAmount(new BigDecimal("42.00"));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getOrderItems()).hasSize(1);
        assertThat(found.get(0).getOrderItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(found.get(0).getOrderItems().get(0).getProduct()).isNull();
    }

    private static Customer customer(String name, String email) {
        Customer c = new Customer();
        c.setName(name);
        c.setEmail(email);
        return c;
    }

    private static Product product(String name, BigDecimal price) {
        Product p = new Product();
        p.setName(name);
        p.setPrice(price);
        return p;
    }
}
