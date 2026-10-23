package io.github.fludakit.data.it.cascade;

import io.github.fludakit.data.it.DataSourceProducer;
import io.github.fludakit.data.it.profile.*;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests composing to-one cascade behavior (Rule 1 for to-one associations).
 * Profile.preference is a composing to-one — the Preference's lifecycle is owned by the Profile.
 * The FK column (preference_id) is on the Profile table.
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ComposingToOneCascadeTest {

    @Inject ProfileRepository profiles;
    @Inject PreferenceRepository preferences;

    @Test
    void insertCascadesComposingToOneBeforeRoot() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.setPreference(new Preference(Preference.Visibility.PUBLIC, "dark"));

        Profile saved = profiles.save(ada);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPreference()).isNotNull();
        assertThat(saved.getPreference().getId()).isNotNull();
        assertThat(preferences.findById(saved.getPreference().getId())).isPresent();
    }

    @Test
    void updateCascadesToComposingToOne() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.setPreference(new Preference(Preference.Visibility.PUBLIC, "dark"));
        Profile saved = profiles.save(ada);
        Long prefId = saved.getPreference().getId();

        saved.getPreference().setVisibility(Preference.Visibility.PRIVATE);
        saved.getPreference().setDefaultTheme("light");
        profiles.save(saved);

        Profile reloaded = profiles.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPreference().getVisibility()).isEqualTo(Preference.Visibility.PRIVATE);
        assertThat(reloaded.getPreference().getDefaultTheme()).isEqualTo("light");
        assertThat(reloaded.getPreference().getId()).isEqualTo(prefId);
    }

    @Test
    void deleteCascadesToComposingToOne() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.setPreference(new Preference(Preference.Visibility.PUBLIC, "dark"));
        Profile saved = profiles.save(ada);
        Long prefId = saved.getPreference().getId();

        profiles.deleteById(saved.getId());

        assertThat(profiles.findById(saved.getId())).isEmpty();
        assertThat(preferences.findById(prefId)).isEmpty();
    }
}
