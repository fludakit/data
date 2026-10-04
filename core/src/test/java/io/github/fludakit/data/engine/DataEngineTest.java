package io.github.fludakit.data.engine;

import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryModel;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.jdbc.JdbcClient;
import org.h2.jdbcx.JdbcDataSource;
import org.jmolecules.ddd.annotation.AggregateRoot;
import org.jmolecules.ddd.annotation.Entity;
import org.jmolecules.ddd.annotation.Identity;
import org.jmolecules.ddd.annotation.ValueObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@link DataEngine} executes the {@link QueryModel} contract through {@link JdbcClient}:
 * generated-id inserts, reads with predicates, many-to-one FK extraction and element-collection
 * child-table writes.
 *
 * <p>The in-memory H2 database is created with {@code DATABASE_TO_LOWER=TRUE} so the lowercase,
 * double-quoted identifiers emitted by the SQL builder resolve, matching PostgreSQL-style databases.
 * {@code "order"} is quoted because it is a reserved word. Mapped entities are {@code public static}
 * with public no-arg constructors, as required by {@link JdbcClient}'s row mapper.</p>
 */
class DataEngineTest {

    @AggregateRoot
    public static class Customer {
        @Identity Long id;
        String name;

        public Customer() {}

        public Customer(Long id, String name) { this.id = id; this.name = name; }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity
    public static class Product {
        @Identity Long id;
        String name;
        BigDecimal price;

        public Product() {}

        public Product(Long id, String name, BigDecimal price) {
            this.id = id;
            this.name = name;
            this.price = price;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
    }

    @ValueObject
    public record OrderItem(Product product, int quantity) {}

    @Entity
    public static class Order {
        @Identity Long id;
        BigDecimal amount;
        Customer customer;
        List<OrderItem> orderItems = new ArrayList<>();

        public Order() {}

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public Customer getCustomer() { return customer; }
        public void setCustomer(Customer customer) { this.customer = customer; }
        public List<OrderItem> getOrderItems() { return orderItems; }
        public void setOrderItems(List<OrderItem> orderItems) { this.orderItems = orderItems; }
    }

    private JdbcClient client;
    private DataEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = dataSource();
        client = new JdbcClient(dataSource);
        engine = new DataEngine(client);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM order_order_items");
            statement.execute("DELETE FROM \"order\"");
            statement.execute("DELETE FROM customer");
            statement.execute("DELETE FROM product");
        }
    }

    @Test
    void insertsAndFindsById() {
        Customer saved = (Customer) engine.invoke(
                QueryModel.builder(Customer.class, Operation.INSERT)
                        .resultKind(ResultKind.SINGLE).build(),
                new Object[] { new Customer(null, "Ana") });

        assertThat(saved.getId()).isNotNull();

        Object found = engine.invoke(
                QueryModel.builder(Customer.class, Operation.FIND)
                        .resultKind(ResultKind.OPTIONAL)
                        .predicate(Predicate.of("id", Operator.EQ, "arg0")).build(),
                new Object[] { saved.getId() });

        assertThat(found).isInstanceOf(java.util.Optional.class);
        @SuppressWarnings("unchecked")
        java.util.Optional<Customer> optional = (java.util.Optional<Customer>) found;
        assertThat(optional).isPresent();
        assertThat(optional.get().getId()).isEqualTo(saved.getId());
        assertThat(optional.get().getName()).isEqualTo("Ana");
    }

    @Test
    void savesOrderWithManyToOneAndElementCollection() {
        Customer customer = (Customer) engine.invoke(
                QueryModel.builder(Customer.class, Operation.INSERT)
                        .resultKind(ResultKind.SINGLE).build(),
                new Object[] { new Customer(null, "Ana") });
        Product product = (Product) engine.invoke(
                QueryModel.builder(Product.class, Operation.INSERT)
                        .resultKind(ResultKind.SINGLE).build(),
                new Object[] { new Product(null, "Widget", new BigDecimal("9.99")) });

        Order order = new Order();
        order.setAmount(new BigDecimal("29.97"));
        order.setCustomer(customer);
        order.setOrderItems(List.of(new OrderItem(product, 2), new OrderItem(product, 3)));

        Order saved = (Order) engine.invoke(
                QueryModel.builder(Order.class, Operation.SAVE)
                        .resultKind(ResultKind.SINGLE).build(),
                new Object[] { order });

        assertThat(saved.getId()).isNotNull();

        Long customerId = client.sql("SELECT customer_id FROM \"order\" WHERE id = :id")
                .param("id", saved.getId()).singleValue(Long.class);
        assertThat(customerId).isEqualTo(customer.getId());

        Long itemCount = client.sql("SELECT COUNT(*) FROM order_order_items WHERE order_id = :id")
                .param("id", saved.getId()).singleValue(Long.class);
        assertThat(itemCount).isEqualTo(2L);

        Long itemProductId = client.sql("SELECT product_id FROM order_order_items WHERE order_id = :id")
                .param("id", saved.getId()).singleValue(Long.class);
        assertThat(itemProductId).isEqualTo(product.getId());
    }

    @Test
    void findsByNamePredicate() {
        engine.invoke(
                QueryModel.builder(Customer.class, Operation.INSERT)
                        .resultKind(ResultKind.SINGLE).build(),
                new Object[] { new Customer(null, "Ana") });
        engine.invoke(
                QueryModel.builder(Customer.class, Operation.INSERT)
                        .resultKind(ResultKind.SINGLE).build(),
                new Object[] { new Customer(null, "Bob") });

        Object result = engine.invoke(
                QueryModel.builder(Customer.class, Operation.FIND)
                        .resultKind(ResultKind.LIST)
                        .predicate(Predicate.of("name", Operator.EQ, "arg0")).build(),
                new Object[] { "Bob" });

        assertThat(result).isInstanceOf(List.class);
        List<?> customers = (List<?>) result;
        assertThat(customers).hasSize(1);
        assertThat(((Customer) customers.get(0)).getName()).isEqualTo("Bob");
    }

    private static DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:engine;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS customer (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255))");
            statement.execute("CREATE TABLE IF NOT EXISTS \"order\" (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " amount DECIMAL(10,2), customer_id BIGINT)");
            statement.execute("CREATE TABLE IF NOT EXISTS order_order_items (order_id BIGINT,"
                    + " product_id BIGINT, quantity INT)");
            statement.execute("CREATE TABLE IF NOT EXISTS product (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + " name VARCHAR(255), price DECIMAL(10,2))");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return dataSource;
    }
}
