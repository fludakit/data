package io.github.fludakit.data.it.cascade;

import io.github.fludakit.data.it.DataSourceProducer;
import io.github.fludakit.data.it.profile.*;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class AggregateCascadeTest {

    @Inject ProfileRepository profiles;
    @Inject WorkExperienceRepository workExperiences;
    @Inject EducationRepository educations;

    @Test
    void insertCascadesCompositionsBeforeRootAndCollectionsAfter() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience("Alpha", 1842, 1843));
        ada.getEducation().add(new Education("Oxford", 1840));
        ada.getContacts().add(new ContactPoint("EMAIL", "ada@example.com"));

        Profile saved = profiles.save(ada);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getWorkHistory()).hasSize(1);
        assertThat(saved.getWorkHistory().get(0).getId()).isNotNull();
        assertThat(saved.getEducation()).hasSize(1);
        assertThat(saved.getEducation().get(0).getId()).isNotNull();
        assertThat(workExperiences.findById(saved.getWorkHistory().get(0).getId())).isPresent();
    }

    @Test
    void updateCascadesToAllChildren() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience("Alpha", 1842, 1843));
        Profile saved = profiles.save(ada);

        saved.setFullName("Ada Lovelace");
        saved.getWorkHistory().get(0).setCompany("Analytical Society");
        profiles.save(saved);

        Profile reloaded = profiles.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(reloaded.getWorkHistory().get(0).getCompany()).isEqualTo("Analytical Society");
    }

    @Test
    void deleteCascadesToAllChildren() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience("Alpha", 1842, 1843));
        ada.getContacts().add(new ContactPoint("EMAIL", "ada@example.com"));
        Profile saved = profiles.save(ada);
        Long weId = saved.getWorkHistory().get(0).getId();

        profiles.deleteById(saved.getId());

        assertThat(profiles.findById(saved.getId())).isEmpty();
        assertThat(workExperiences.findById(weId)).isEmpty();
    }

    @Test
    void entityCollectionDiffSyncUpsertsAndDeletesOrphans() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience("Alpha", 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience("Beta", 1844, 1845));
        Profile saved = profiles.save(ada);
        Long firstId = saved.getWorkHistory().get(0).getId();

        // Replace the collection: keep first, modify second, add third
        saved.getWorkHistory().get(1).setCompany("Beta Updated");
        saved.getWorkHistory().add(new WorkExperience("Gamma", 1846, 1847));
        profiles.save(saved);

        Profile reloaded = profiles.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getWorkHistory()).hasSize(3);
        assertThat(workExperiences.findById(firstId)).isPresent();
    }
}
