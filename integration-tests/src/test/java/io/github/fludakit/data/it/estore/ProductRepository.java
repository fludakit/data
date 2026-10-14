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

    // Jakarta Data names E[] as a return type in its own right, so it is its own result kind (ARRAY)
    // rather than a List with a toArray tacked on by re-inspecting the return type at the call site.
    Product[] findByNameContains(String text);

    // A range binds TWO values: BETWEEN renders :low_from and :low_to, fed by the two arguments.
    List<Product> findByPriceBetween(BigDecimal low, BigDecimal high);
}
