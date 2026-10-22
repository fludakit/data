package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.it.DataSourceProducer;
import io.github.fludakit.jdbc.JdbcClient;

import jakarta.data.page.CursoredPage;
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
class CustomerRepositoryTest {

    @Inject CustomerRepository customers;
    @Inject ProductRepository products;
    @Inject OrderRepository orders;

    @Test
    void crudAndQueryByMethodName() {
        Customer ana = customers.insert(customer("Ana", "ana@example.com"));
        assertThat(ana.getId()).isNotNull();
        assertThat(customers.findById(ana.getId())).isPresent();
        assertThat(customers.findByName("Ana")).hasSize(1);
        assertThat(customers.findByEmail("ana@example.com")).hasSize(1);

        Product book = products.insert(product("DDD", new BigDecimal("39.99")));
        assertThat(products.findByName("DDD")).hasSize(1);
        assertThat(products.findByPriceGreaterThan(new BigDecimal("10"))).hasSize(1);
        // BETWEEN binds both bounds — before the fix only :low_from was bound and the query failed at
        // execution time.
        assertThat(products.findByPriceBetween(new BigDecimal("30"), new BigDecimal("50")))
                .extracting(Product::getName).containsExactly("DDD");
        assertThat(products.findByPriceBetween(new BigDecimal("40"), new BigDecimal("50"))).isEmpty();
        assertThat(products.findByNameLike("%DD%")).extracting(Product::getName).containsExactly("DDD");
    }

    @Test
    void rereadPopulatesAllScalarColumnsViaGeneratedMapper() {
        Customer ana = customers.insert(customer("Ana", "ana@example.com"));
        Product book = products.insert(product("DDD", new BigDecimal("39.99")));

        Customer reloadedCustomer = customers.findById(ana.getId()).orElseThrow();
        assertThat(reloadedCustomer.getName()).isEqualTo("Ana");
        assertThat(reloadedCustomer.getEmail()).isEqualTo("ana@example.com");

        Product reloadedProduct = products.findById(book.getId()).orElseThrow();
        assertThat(reloadedProduct.getName()).isEqualTo("DDD");
        assertThat(reloadedProduct.getPrice()).isEqualByComparingTo("39.99");
    }

    @Test
    void findAllPageHonoursOrder() {
        customers.insert(customer("Charlie", "charlie@example.com"));
        customers.insert(customer("Alice", "alice@example.com"));
        customers.insert(customer("Bob", "bob@example.com"));

        Page<Customer> page = customers.findAll(
                PageRequest.ofSize(2),
                jakarta.data.Order.by(_Customer.name.asc()));

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

    @Test
    void associationMaterializesAsIdOnlyPartialOnReread() {
        Customer ana = customers.save(customer("Ana", "ana@example.com"));
        Order order = new Order(ana);
        order.setAmount(new BigDecimal("79.98"));
        Order saved = orders.save(order);
        assertThat(saved.getId()).isNotNull();

        Order reloaded = orders.findById(saved.getId()).orElseThrow();
        // An @Association reference definition materializes as an id-only partial in the row mapper.
        assertThat(reloaded.getCustomer()).isNotNull();
        assertThat(reloaded.getCustomer().getId()).isEqualTo(ana.getId());
        assertThat(reloaded.getCustomer().getName()).isNull();
        assertThat(reloaded.getAmount()).isEqualByComparingTo("79.98");
    }

    @Test
    void cursoredPagePagesById() {
        customers.insert(customer("Charlie", "charlie@example.com"));
        customers.insert(customer("Alice", "alice@example.com"));
        customers.insert(customer("Bob", "bob@example.com"));

        CursoredPage<Customer> first = customers.paged(PageRequest.ofSize(2));
        assertThat(first).isInstanceOf(CursoredPage.class);
        assertThat(first.content()).hasSize(2);
        if (first.hasNext()) {
            CursoredPage<Customer> second = (CursoredPage<Customer>) customers.paged(first.nextPageRequest());
            assertThat(second.content()).isNotEmpty();
        }
    }

    @Test
    void exposesTheInjectedJdbcClientThroughADeclaredAccessor() {
        // CustomerRepository declares `JdbcClient getJdbcClient();` like any other method, and the
        // generated CustomerRepositoryImpl answers it with the client its own generated methods run
        // through. An ad-hoc statement proves it is the SAME connection, not a second one: the rows
        // counted are the rows this repository just inserted.
        customers.insert(customer("Ana", "ana@example.com"));
        customers.insert(customer("Bo", "bo@example.com"));

        JdbcClient client = customers.getJdbcClient();
        assertThat(client).isNotNull();
        assertThat(client.sql("SELECT COUNT(*) FROM \"customers\"").singleValue(Long.class)).isEqualTo(2L);
        // A statement the repository would never generate itself — which is the point of the hatch.
        assertThat(client.sql("SELECT name FROM \"customers\" ORDER BY name").query(String.class).list())
                .containsExactly("Ana", "Bo");
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
