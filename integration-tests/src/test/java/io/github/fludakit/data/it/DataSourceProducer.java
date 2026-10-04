package io.github.fludakit.data.it;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.sql.Connection;

@ApplicationScoped
public class DataSourceProducer {

    @Produces
    @ApplicationScoped
    public DataSource dataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:it;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection c = ds.getConnection()) {
            for (String stmt : new String[]{
                    // Weld (re)starts a container per test method; the named in-memory database survives
                    // across containers (DB_CLOSE_DELAY=-1), so start each one from a clean schema.
                    "DROP ALL OBJECTS",
                    "CREATE TABLE customer (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), email VARCHAR(255))",
                    "CREATE TABLE product (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), price DECIMAL(10,2))",
                    "CREATE TABLE \"order\" (id BIGINT AUTO_INCREMENT PRIMARY KEY, amount DECIMAL(10,2), customer_id BIGINT)",
                    "CREATE TABLE order_order_items (order_id BIGINT, product_id BIGINT, quantity INT)"
            }) {
                c.createStatement().execute(stmt);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return ds;
    }
}
