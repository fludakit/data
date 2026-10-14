package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shapes a repository method may declare, end-to-end. Jakarta Data's own table names
 * {@code E}, {@code Optional<E>}, {@code List<E>}, {@code E[]} and {@code Stream<E>}; {@code Set<E>}
 * is this implementation's extension.
 *
 * <p>The one worth pinning here is {@code E[]}: it is a spec return type that publishes its own
 * {@code ResultKind.ARRAY}, and it used to be flattened to {@code LIST} — so the array-ness survived
 * only as a re-check of the return type at the emission site rather than as a fact on the model.</p>
 */
@EnableAutoWeld
@AddPackages(Product.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ReturnShapeTest {

    @Inject ProductRepository products;

    @BeforeEach
    void seed() {
        products.save(product("Pen", "2.50"));
        products.save(product("Pencil", "1.50"));
        products.save(product("Notebook", "3.00"));
    }

    @Test
    void anArrayReturnComesBackAsAnArray() {
        Product[] found = products.findByNameContains("Pen");

        assertThat(found).hasSize(2);
        assertThat(found).extracting(Product::getName).containsExactlyInAnyOrder("Pen", "Pencil");
        assertThat(found).allSatisfy(p -> assertThat(p.getId()).isNotNull());
    }

    @Test
    void anArrayReturnIsEmptyWhenNothingMatches() {
        // An empty result must be a zero-length array, not null — the caller indexes it directly.
        assertThat(products.findByNameContains("nothing")).isEmpty();
    }

    @Test
    void aSetReturnComesBackAsASetInRowOrder() {
        // Extension, not spec: materialized into a LinkedHashSet so row order survives.
        assertThat(products.findByNameLike("%Pen%"))
                .extracting(Product::getName)
                .containsExactlyInAnyOrder("Pen", "Pencil");
    }

    @Test
    void aListReturnComesBackAsAList() {
        assertThat(products.findByName("Pen")).extracting(Product::getName).containsExactly("Pen");
    }

    private static Product product(String name, String price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        return product;
    }
}
