package io.github.fludakit.data.it.estore;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Repository
public interface ProductRepository extends CrudRepository<Product, Long> {
    List<Product> findByName(String name);
    List<Product> findByPriceGreaterThan(BigDecimal price);

    // A Set return materializes into a LinkedHashSet, preserving row order.
    Set<Product> findByNameLike(String pattern);

    // A range binds TWO values: BETWEEN renders :low_0 and :low_1, fed by the two arguments.
    List<Product> findByPriceBetween(BigDecimal low, BigDecimal high);
}
