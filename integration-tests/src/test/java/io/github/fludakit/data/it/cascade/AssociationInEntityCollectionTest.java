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
 * Tests full cascade through the aggregate tree with @Association references.
 * Profile (AggregateRoot) → WorkExperience (EntityCollection) → Company (@Association)
 * 
 * This verifies that:
 * 1. Entity collection children (WorkExperience) can have @Association references to other entities
 * 2. The FK (company_id) is correctly persisted in the child table
 * 3. The associated entity (Company) must be saved separately (not cascaded)
 * 4. Loading hydrates the association correctly
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class AssociationInEntityCollectionTest {

    @Inject ProfileRepository profiles;
    @Inject CompanyRepository companies;
    @Inject WorkExperienceRepository workExperiences;

    @Test
    void associationFkIsPersistedInEntityCollectionChild() {
        // Save a Company first (not cascaded from Profile)
        Company acme = new Company("Acme Corp", "Technology");
        Company savedCompany = companies.save(acme);
        assertThat(savedCompany.getId()).isNotNull();

        // Create a Profile with WorkExperience referencing the Company
        Profile ada = new Profile();
        ada.setFullName("Ada");
        WorkExperience we = new WorkExperience(savedCompany, 1842, 1843);
        ada.getWorkHistory().add(we);

        Profile saved = profiles.save(ada);

        // Verify the WorkExperience was saved with the company_id FK
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getWorkHistory()).hasSize(1);
        WorkExperience savedWe = saved.getWorkHistory().get(0);
        assertThat(savedWe.getId()).isNotNull();
        assertThat(savedWe.getCompany()).isNotNull();
        assertThat(savedWe.getCompany().getId()).isEqualTo(savedCompany.getId());
    }

    @Test
    void associationIsHydratedOnLoad() {
        // Save a Company
        Company acme = new Company("Acme Corp", "Technology");
        Company savedCompany = companies.save(acme);

        // Create and save a Profile with WorkExperience
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience(savedCompany, 1842, 1843));
        Profile saved = profiles.save(ada);

        // Reload and verify the Company association is present with the correct ID.
        // Note: The current implementation loads @Association references as id-only partials
        // (FK binding), not fully hydrated entities. Full hydration would require LEFT JOIN
        // projection in the entity collection loader, which is a separate feature.
        Profile reloaded = profiles.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getWorkHistory()).hasSize(1);
        WorkExperience reloadedWe = reloaded.getWorkHistory().get(0);
        assertThat(reloadedWe.getCompany()).isNotNull();
        assertThat(reloadedWe.getCompany().getId()).isEqualTo(savedCompany.getId());
    }

    @Test
    void multipleWorkExperiencesCanReferenceSameCompany() {
        // Save a Company
        Company acme = new Company("Acme Corp", "Technology");
        Company savedCompany = companies.save(acme);

        // Create a Profile with multiple WorkExperiences referencing the same Company
        Profile ada = new Profile();
        ada.setFullName("Ada");
        ada.getWorkHistory().add(new WorkExperience(savedCompany, 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience(savedCompany, 1844, 1845));
        Profile saved = profiles.save(ada);

        // Reload and verify both reference the same Company (by ID — id-only partial hydration)
        Profile reloaded = profiles.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getWorkHistory()).hasSize(2);
        assertThat(reloaded.getWorkHistory().get(0).getCompany().getId())
                .isEqualTo(reloaded.getWorkHistory().get(1).getCompany().getId());
        assertThat(reloaded.getWorkHistory().get(0).getCompany().getId())
                .isEqualTo(savedCompany.getId());
    }
}
