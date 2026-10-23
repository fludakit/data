package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.it.DataSourceProducer;

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
class AggregateDeleteCascadeTest {

    @Inject ProfileRepository profiles;
    @Inject WorkExperienceRepository workExperiences;
    @Inject EducationRepository educations;

    @Test
    void deleteRemovesEntityCollectionChildren() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience("Alpha", 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience("Beta", 1844, 1845));
        ada.getEducation().add(new Education("Oxford", 1840));
        Profile saved = profiles.save(ada);

        Long weId1 = saved.getWorkHistory().get(0).getId();
        Long weId2 = saved.getWorkHistory().get(1).getId();
        Long eduId = saved.getEducation().get(0).getId();

        profiles.deleteById(saved.getId());

        assertThat(profiles.findById(saved.getId())).isEmpty();
        assertThat(workExperiences.findById(weId1)).isEmpty();
        assertThat(workExperiences.findById(weId2)).isEmpty();
        assertThat(educations.findById(eduId)).isEmpty();
    }

    @Test
    void deleteRemovesElementCollectionChildren() {
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getContacts().add(new ContactPoint("EMAIL", "ada@example.com"));
        ada.getContacts().add(new ContactPoint("PHONE", "+44-20-7946-0000"));
        Profile saved = profiles.save(ada);

        profiles.deleteById(saved.getId());

        assertThat(profiles.findById(saved.getId())).isEmpty();
    }
}
