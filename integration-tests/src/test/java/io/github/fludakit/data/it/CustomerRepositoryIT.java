package io.github.fludakit.data.it;

import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@EnableAutoWeld
@AddPackages(Customer.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class CustomerRepositoryIT {

    @Inject CustomerRepository customers;
    @Inject ProductRepository products;
    @Inject OrderRepository orders;

    @Test
    void crudAndQueryByMethodName() {
        Customer ana = customers.insert(customer("Ana", "ana@example.com"));
        assertThat(ana.getId()).isNotNull();
        assertThat(customers.findById(ana.getId())).isPresent();
        assertThat(customers.findByName("Ana")).hasSize(1);
        assertThat(customers.findByEmailLike("%example.com")).hasSize(1);

        Product book = products.insert(product("DDD", new BigDecimal("39.99")));
        assertThat(products.findByName("DDD")).hasSize(1);
        assertThat(products.findByPriceGreaterThan(new BigDecimal("10"))).hasSize(1);
    }

    @Test
    void findAllPageHonoursOrder() {
        customers.insert(customer("Charlie", "charlie@example.com"));
        customers.insert(customer("Alice", "alice@example.com"));
        customers.insert(customer("Bob", "bob@example.com"));

        Page<Customer> page = customers.findAll(
                PageRequest.ofSize(2),
                jakarta.data.Order.by(jakarta.data.Sort.asc("name")));

        assertThat(page.numberOfElements()).isEqualTo(2);
        assertThat(page.content()).extracting(Customer::getName).containsExactly("Alice", "Bob");
    }

    @Test
    void savesEntityGraphWithRelationships() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Product book = products.save(product("DDD", new BigDecimal("39.99")));
        Order order = new Order(ana);
        order.addItem(book, 2);
        order.setAmount(new BigDecimal("79.98"));
        Order saved = orders.save(order);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCustomer().getId()).isEqualTo(ana.getId());
        assertThat(saved.getOrderItems()).hasSize(1);
        assertThat(saved.getOrderItems().get(0).getProduct().getId()).isEqualTo(book.getId());
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
