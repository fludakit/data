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
 * The {@code First} keyword of a query by method name, end-to-end on H2. Jakarta Data: {@code First<n>}
 * keeps the first n rows and a bare {@code First} keeps one, applied AFTER the sort.
 *
 * <p>The keyword used to be parsed and then dropped, so both finders below read every matching row: the
 * list came back with all three products and the single-entity finder failed on its second row.</p>
 */
@EnableAutoWeld
@AddPackages(Product.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class FirstKeywordTest {

    @Inject ProductRepository products;

    @BeforeEach
    void seed() {
        products.save(product("Pen", "2.50"));
        products.save(product("Pencil", "1.50"));
        products.save(product("Notebook", "3.00"));
    }

    @Test
    void firstNKeepsTheFirstNRowsInSortOrder() {
        assertThat(products.findFirst2ByPriceGreaterThanOrderByPriceDesc(BigDecimal.ZERO))
                .extracting(Product::getName)
                .containsExactly("Notebook", "Pen");
    }

    @Test
    void aBareFirstReturnsTheFirstRowInSortOrder() {
        // "Pen" and "Pencil" both match; the cheaper one comes first.
        assertThat(products.findFirstByNameContainsOrderByPriceAsc("Pen").getName()).isEqualTo("Pencil");
    }

    private static Product product(String name, String price) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        return product;
    }
}