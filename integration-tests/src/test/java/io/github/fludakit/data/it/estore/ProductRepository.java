package io.github.fludakit.data.it.estore;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ProductRepository extends CrudRepository<Product, Long> {
    List<Product> findByName(String name);
    List<Product> findByPriceGreaterThan(BigDecimal price);
}
