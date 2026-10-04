package io.github.fludakit.data.mapping;

import io.github.fludakit.jdbc.JdbcClient;
import org.h2.jdbcx.JdbcDataSource;
import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves entity mapping and relationship resolution performed through {@link JdbcClient}.
 *
 * <p>The in-memory H2 database is created with {@code DATABASE_TO_LOWER=TRUE} so that the
 * lowercase, double-quoted identifiers emitted by {@code Names.quote} resolve against the
 * schema, matching PostgreSQL-style databases. {@code "order"} is quoted because it is a
 * reserved word.</p>
 */
class JdbcEntityMapperTest {

    @AggregateRoot
    public static class Customer {
        @Identity Long id;
        String name;
        List<Order> orders = new ArrayList<>();

        public Customer() {}

        public Long getId() { return id; }
        public String getName() { return name; }
        public List<Order> getOrders() { return orders; }
    }

    @Entity
    public static class Order {
        @Identity Long id;
        BigDecimal amount;
        Customer customer;

        public Order() {}

        public Long getId() { return id; }
        public BigDecimal getAmount() { return amount; }
        public Customer getCustomer() { return customer; }
    }

    private JdbcClient client;
    private JdbcEntityMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mapping-" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255))");
            statement.execute("CREATE TABLE \"order\" (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " amount DECIMAL(10,2), customer_id BIGINT)");
        }

        client = new JdbcClient(dataSource);
        mapper = new JdbcEntityMapper(client, new io.github.fludakit.data.metadata.EntityMetadataResolver());
    }

    private void seed() {
        client.sql("INSERT INTO customer (id, name) VALUES (:id, :name)")
                .params(Map.of("id", 1L, "name", "Ana")).update();
        client.sql("INSERT INTO \"order\" (id, amount, customer_id) VALUES (:id, :amount, :customer_id)")
                .params(Map.of("id", 10L, "amount", new BigDecimal("9.99"), "customer_id", 1L)).update();
        client.sql("INSERT INTO \"order\" (id, amount, customer_id) VALUES (:id, :amount, :customer_id)")
                .params(Map.of("id", 11L, "amount", new BigDecimal("19.50"), "customer_id", 1L)).update();
    }

    @Test
    void mapsFlatRowAndConvertsNumericTypes() {
        Customer customer = mapper.single(Customer.class, "SELECT 1 AS id, 'Ana' AS name", Map.of());

        // H2 returns an Integer for the literal 1; query(Class) must convert it to the Long field type.
        assertThat(customer.getId()).isInstanceOf(Long.class).isEqualTo(1L);
        assertThat(customer.getName()).isEqualTo("Ana");
        assertThat(customer.getOrders()).isEmpty();
    }

    @Test
    void resolvesManyToOne() {
        seed();

        Optional<Order> order = mapper.optional(Order.class,
                "SELECT * FROM \"order\" WHERE \"id\" = :id", Map.of("id", 10L));

        assertThat(order).isPresent();
        assertThat(order.get().getId()).isEqualTo(10L);
        assertThat(order.get().getAmount()).isEqualByComparingTo("9.99");
        assertThat(order.get().getCustomer()).isNotNull();
        assertThat(order.get().getCustomer().getId()).isEqualTo(1L);
        assertThat(order.get().getCustomer().getName()).isEqualTo("Ana");
    }

    @Test
    void resolvesManyToOneToNullWhenForeignKeyIsNull() {
        client.sql("INSERT INTO \"order\" (id, amount, customer_id) VALUES (:id, :amount, NULL)")
                .params(Map.of("id", 12L, "amount", new BigDecimal("1.00"))).update();

        Order order = mapper.single(Order.class,
                "SELECT * FROM \"order\" WHERE \"id\" = :id", Map.of("id", 12L));

        assertThat(order.getCustomer()).isNull();
    }

    @Test
    void resolvesOneToMany() {
        seed();

        List<Customer> customers = mapper.list(Customer.class, "SELECT * FROM customer", Map.of());

        assertThat(customers).hasSize(1);
        Customer customer = customers.get(0);
        assertThat(customer.getId()).isEqualTo(1L);
        assertThat(customer.getName()).isEqualTo("Ana");
        assertThat(customer.getOrders()).hasSize(2);
        assertThat(customer.getOrders()).extracting(Order::getId).containsExactlyInAnyOrder(10L, 11L);
        assertThat(customer.getOrders()).extracting(Order::getAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("9.99"), new BigDecimal("19.50"));
    }

    @Test
    void resolvesOneToManyToEmptyListWhenNoChildren() {
        client.sql("INSERT INTO customer (id, name) VALUES (:id, :name)")
                .params(Map.of("id", 2L, "name", "Bob")).update();

        List<Customer> customers = mapper.list(Customer.class,
                "SELECT * FROM customer WHERE \"id\" = :id", Map.of("id", 2L));

        assertThat(customers).singleElement()
                .satisfies(c -> assertThat(c.getOrders()).isEmpty());
    }
}
