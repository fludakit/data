package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the compile-side EXISTS rendering against H2. A {@code deleteBy} whose
 * predicate traverses one relation is emitted as a correlated EXISTS semi-join (never a joined
 * {@code DELETE ... LEFT JOIN}, which is invalid SQL), in both head directions: the many-to-one
 * ({@code customer.name}) and the element-collection ({@code orderItems.quantity}).
 *
 * <p>Semantics pinned here are the semi-join's, i.e. the same rows the matching find/count read
 * (LEFT JOIN drop semantics): only roots with a child row satisfying the predicate are removed; a
 * root whose fk is NULL survives ({@code NULL = ...} is never true) and so does an orphan — a root
 * whose fk dangles at a customer row that no longer exists, because EXISTS finds no matching child.</p>
 */
@EnableAutoWeld
@AddPackages(Order.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class OrderDeleteByRelationTest {

    @Inject CustomerRepository customers;
    @Inject ProductRepository products;
    @Inject OrderRepository orders;

    @Test
    void deleteByCustomerNameRemovesMatchingRootsOnly() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Customer bob = customers.save(customer("Bob", "bob@example.com"));
        Order anaOrder = orders.save(order(ana));
        Order bobOrder = orders.save(order(bob));
        // NULL-fk root: the semi-join edge "customers"."id" = "orders"."customer_id" can never be true,
        // exactly the LEFT JOIN drop semantics of the matching read.
        Order nullFkOrder = orders.save(order(null));

        orders.deleteByCustomerName("Ana");

        assertThat(orders.findById(anaOrder.getId())).isEmpty();
        assertThat(orders.findById(bobOrder.getId())).isPresent();
        assertThat(orders.findById(nullFkOrder.getId())).isPresent();
    }

    @Test
    void deleteByCustomerNameNeverTouchesOrphanFkRows() {
        Customer temp = customers.save(customer("Temp", "temp@example.com"));
        Order orphaned = orders.save(order(temp));
        // The schema has no FK constraint, so the order row may keep a dangling customer_id.
        customers.deleteById(temp.getId());

        orders.deleteByCustomerName("Temp");

        assertThat(orders.findById(orphaned.getId())).isPresent();
    }

    @Test
    void deleteByOrderItemsQuantityGreaterThanMatchesTheToManyRead() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Product widget = products.save(product("Widget", new BigDecimal("1.00")));
        Order qty5 = orders.save(orderWithItem(ana, widget, 5));
        Order qty1 = orders.save(orderWithItem(ana, widget, 1));
        Order emptyItems = orders.save(order(ana));

        orders.deleteByOrderItemsQuantityGreaterThan(3);

        assertThat(orders.findById(qty5.getId())).isEmpty();
        assertThat(orders.findById(qty1.getId())).isPresent();
        assertThat(orders.findById(emptyItems.getId())).isPresent();
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

    private static Order order(Customer customer) {
        Order order = new Order(customer);
        order.setAmount(new BigDecimal("10.00"));
        return order;
    }

    private static Order orderWithItem(Customer customer, Product product, int quantity) {
        Order order = order(customer);
        order.addItem(product, quantity);
        return order;
    }
}
