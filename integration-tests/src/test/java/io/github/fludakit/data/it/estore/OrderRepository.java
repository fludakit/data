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
}
