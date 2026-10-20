package io.github.fludakit.data.it.estore;

import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface OrderRepository extends CrudRepository<Order, Long> {
    @Find
    List<Order> findByAmount(@By("amount") BigDecimal amount);

    // Predicate through the @Association reference {@code customer} and on to the definition's name:
    // @By declares the traversal and the resolver descends the relation head, contributing one join
    // (the mapRow_full_ path).
    @Find
    List<Order> findByCustomerName(@By("customer.name") String name);

    // Method-name DELETE through a relation: emitted as correlated EXISTS semi-joins, because a
    // joined DELETE ... LEFT JOIN is not valid SQL.
    void deleteByCustomerName(String name);

    void deleteByOrderItemsQuantityGreaterThan(int quantity);
}
