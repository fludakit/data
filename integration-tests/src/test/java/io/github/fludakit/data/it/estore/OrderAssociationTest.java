package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10: the estore models are {@code @Entity}. Pins the read semantics of the
 * {@code @Association} header target {@code Order.customer} in that world:
 *
 * <ul>
 *   <li>a plain entity read (findById) materialises the association as an id-only partial
 *       (Task 9 rule; the stereotype swap keeps it because {@code @Association} is explicit);</li>
 *   <li>a method-name finder whose predicate traverses the association
 *       ({@code findByCustomerName}) is the join-aware read ({@code mapRow_full_} path) and
 *       returns the traversed association FULLY populated — first end-to-end IT of that path.</li>
 * </ul>
 */
@EnableAutoWeld
@AddPackages(Order.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class OrderAssociationTest {

    @Inject CustomerRepository customers;
    @Inject OrderRepository orders;

    @Test
    void findByIdMaterialisesAssociationAsIdOnlyPartial() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Order order = new Order(ana);
        order.setAmount(new BigDecimal("10.00"));
        Order saved = orders.save(order);

        Order reloaded = orders.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCustomer()).isNotNull();
        assertThat(reloaded.getCustomer().getId()).isEqualTo(ana.getId());
        assertThat(reloaded.getCustomer().getName()).isNull(); // association materialises id only
    }

    @Test
    void methodNameFinderThroughAssociationMaterialisesTargetFully() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Customer bob = customers.save(customer("Bob", "bob@example.com"));
        Order anaOrder = new Order(ana);
        anaOrder.setAmount(new BigDecimal("20.00"));
        orders.save(anaOrder);
        Order bobOrder = new Order(bob);
        bobOrder.setAmount(new BigDecimal("30.00"));
        orders.save(bobOrder);

        List<Order> found = orders.findByCustomerName("Ana");
        assertThat(found).hasSize(1);
        Order order = found.get(0);
        // Joined read: the traversed association is fully materialised, not an id-only partial.
        assertThat(order.getCustomer()).isNotNull();
        assertThat(order.getCustomer().getId()).isEqualTo(ana.getId());
        assertThat(order.getCustomer().getName()).isEqualTo("Ana");
        assertThat(order.getCustomer().getEmail()).isEqualTo("ana@example.com");
        assertThat(order.getAmount()).isEqualByComparingTo("20.00");
    }

    private static Customer customer(String name, String email) {
        Customer c = new Customer();
        c.setName(name);
        c.setEmail(email);
        return c;
    }
}
