package io.github.fludakit.data.it.estore;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jakarta Data defines {@code save} as an <strong>upsert</strong>. This implementation used to route
 * any entity with a non-null id straight to an UPDATE, so saving an entity carrying an
 * application-assigned identifier into an empty table was a silent no-op — no row was ever written.
 *
 * <p>Caught by the Jakarta Data TCK ({@code NoSQLEntityTests.testNotRunOnPersistence}, which saves
 * entities with ids 1..5 and then counts them); this pins the behaviour without needing the TCK.
 */
@EnableAutoWeld
@AddPackages(Product.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class SaveUpsertTest {

    @Inject ProductRepository products;

    @Test
    void saveWithAnAssignedIdInsertsWhenNoRowExists() {
        Product pen = product(9001L, "Pen", "2.50");

        products.save(pen);

        // Before the fix this ran an UPDATE that matched nothing and the row never appeared.
        assertThat(products.findById(9001L)).isPresent();
        assertThat(products.findById(9001L).orElseThrow().getName()).isEqualTo("Pen");
    }

    @Test
    void saveWithAnAssignedIdUpdatesWhenTheRowExists() {
        Product marker = product(9002L, "Before", "1.00");
        products.save(marker);

        marker.setName("After");
        marker.setPrice(new BigDecimal("3.00"));
        products.save(marker);

        assertThat(products.findById(9002L).orElseThrow().getName()).isEqualTo("After");
        assertThat(products.findById(9002L).orElseThrow().getPrice())
                .isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(products.findByName("Before")).isEmpty();
    }

    @Test
    void saveWithANullIdStillLetsTheDatabaseAssignTheKey() {
        Product widget = product(null, "Widget", "5.00");

        products.save(widget);

        assertThat(widget.getId()).isNotNull();
        assertThat(products.findById(widget.getId())).isPresent();
    }

    private static Product product(Long id, String name, String price) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        return product;
    }
}
