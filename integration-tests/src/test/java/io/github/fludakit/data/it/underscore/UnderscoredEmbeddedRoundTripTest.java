package io.github.fludakit.data.it.underscore;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips an embedded value object whose property name contains an underscore. The compile-time
 * regression is pinned in the processor suite; this test exists because a compile-clean getter chain
 * is not enough — the bind PARAMETER has to line up with the SQL token the renderer produced, and
 * only a real database round trip proves that. An {@code update} is included because the flattened
 * bindings are emitted by a different helper path than {@code insert}.
 */
@EnableAutoWeld
@AddPackages(Site.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class UnderscoredEmbeddedRoundTripTest {

    @Inject SiteRepository sites;

    @Test
    void underscoredEmbeddedPropertyRoundTripsAndUpdates() {
        Site site = new Site();
        site.setName("HQ");
        Location location = new Location();
        location.setCity("Springfield");
        Coordinates coordinates = new Coordinates();
        coordinates.setLat(39.8);
        coordinates.setLng(-83.9);
        location.setGeo_point(coordinates);
        site.setHome_address(location);

        Site saved = sites.save(site);
        Site found = sites.findById(saved.getId()).orElseThrow();
        assertThat(found.getHome_address().getCity()).isEqualTo("Springfield");
        assertThat(found.getHome_address().getGeo_point().getLat()).isEqualTo(39.8);
        assertThat(found.getHome_address().getGeo_point().getLng()).isEqualTo(-83.9);

        found.getHome_address().setCity("Shelbyville");
        sites.save(found);

        Site updated = sites.findById(saved.getId()).orElseThrow();
        assertThat(updated.getHome_address().getCity()).isEqualTo("Shelbyville");
        assertThat(updated.getHome_address().getGeo_point().getLat()).isEqualTo(39.8);
    }
}
