package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.data.Order;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Type-safe sorting through the generated {@code _Profile} static metamodel: runtime {@code Order}
 * criteria carry embedded dot paths ({@code "address.city"}) that the generated {@code column(String)}
 * switch must resolve to flattened columns, and a compile-time {@code @OrderBy} dot path must resolve
 * the same way through the descriptor.
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class MetamodelSortTest {

    @Inject ProfileRepository profiles;

    @Test
    void runtimeSortOnEmbeddedLeafOrdersByFlattenedColumn() {
        profiles.save(profile("Beta", 1.0));
        profiles.save(profile("Alpha", 3.0));
        profiles.save(profile("Gamma", 2.0));

        Page<Profile> byCity = profiles.findAll(
                PageRequest.ofSize(3), Order.by(_Profile.address.get(_Address.city).asc()));
        assertThat(byCity.content()).extracting(p -> p.getAddress().getCity())
                .containsExactly("Alpha", "Beta", "Gamma");

        Page<Profile> byLat = profiles.findAll(
                PageRequest.ofSize(3), Order.by(_Profile.address.get(_Address.point).get(_GeoPoint.lat).desc()));
        assertThat(byLat.content()).extracting(p -> p.getAddress().getPoint().getLat())
                .containsExactly(3.0, 2.0, 1.0);
    }

    @Test
    void orderByAnnotationResolvesEmbeddedDotPath() {
        profiles.save(profile("Beta", 1.0));
        profiles.save(profile("Alpha", 3.0));
        assertThat(profiles.orderedByCity()).extracting(p -> p.getAddress().getCity())
                .containsExactly("Alpha", "Beta");
    }

    private static Profile profile(String city, double lat) {
        Address a = new Address();
        a.setCity(city);
        GeoPoint gp = new GeoPoint();
        gp.setLat(lat);
        gp.setLng(0.0);
        a.setPoint(gp);
        Profile p = new Profile();
        p.setAddress(a);
        return p;
    }
}
