package io.github.fludakit.data.it;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips an {@code @ValueObject} embedded in an aggregate through the generated flattened
 * columns: writes bind each {@code address_*} leaf path, reads rebuild the VO tree leaf-by-leaf.
 * Presence rule (Plan C Decision 2): an embedded VO is reconstructed iff at least one flattened
 * boxed leaf is non-null — a NULL database row therefore maps back to a {@code null} property, not
 * an all-null VO. Note this fixture is all-boxed by design: a VO with a primitive leaf can never
 * be NULL-absent (the mapper throws on {@code wasNull()}), so its schema must supply a value.
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class EmbeddedRoundTripIT {

    @Inject ProfileRepository profiles;

    @Test
    void embeddedRoundTrip() {
        Profile p = new Profile();
        Address a = new Address();
        a.setCity("Springfield");
        a.setZip(4915);
        GeoPoint gp = new GeoPoint();
        gp.setLat(39.8);
        gp.setLng(-83.9);
        a.setPoint(gp);
        p.setAddress(a);

        Profile saved = profiles.save(p);
        Profile found = profiles.findById(saved.getId()).orElseThrow();
        assertThat(found.getAddress().getCity()).isEqualTo("Springfield");
        assertThat(found.getAddress().getZip()).isEqualTo(4915);
        assertThat(found.getAddress().getPoint().getLat()).isEqualTo(39.8);
    }

    @Test
    void fullyNullEmbeddedStaysNull() {
        Profile p = new Profile();
        Profile saved = profiles.save(p);
        Profile found = profiles.findById(saved.getId()).orElseThrow();
        assertThat(found.getAddress()).isNull();
    }
}
