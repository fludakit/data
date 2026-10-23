package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-side counterpart of the aggregate cascade: an <b>entity</b> collection has an identity, so
 * bringing its table in line with the in-memory collection is a <em>diff</em>, not a replace-all.
 *
 * <p>Each child is upserted — it keeps the identifier it already has — and the parent's rows whose
 * identifier the collection no longer names are deleted. Replace-all (delete by fk, re-insert) would
 * regenerate every child id on every save, so a caller holding a child id across a save lost it, and
 * nothing ever removed a child that had been dropped from the collection.</p>
 *
 * <p>An <em>element</em> collection ({@code Profile.contacts}) has no identity, so replace-all remains
 * the only sync it can have; that is unchanged here.</p>
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class AggregateChildSyncTest {

    @Inject ProfileRepository profiles;
    @Inject WorkExperienceRepository workExperiences;
    @Inject CompanyRepository companies;

    @Test
    void childIdentitiesSurviveASecondSave() {
        // The regression: save() routes a root with an id through the upsert path, which used to
        // delete the whole child set by fk and re-insert it, minting fresh ids every time.
        Company analyticalEngines = companies.save(new Company("Analytical Engines", "industry"));
        Company differenceEngines = companies.save(new Company("Difference Engines", "industry"));

        Profile ada = profile("Ada");
        ada.getWorkHistory().add(new WorkExperience(analyticalEngines, 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience(differenceEngines, 1844, 1845));
        Profile saved = profiles.save(ada);
        List<Long> assigned = ids(saved.getWorkHistory());
        assertThat(assigned).doesNotContainNull();

        saved.setFullName("Ada Byron");
        profiles.save(saved);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getFullName()).isEqualTo("Ada Byron");
        assertThat(back.getWorkHistory()).hasSize(2);
        assertThat(ids(back.getWorkHistory())).containsExactlyInAnyOrderElementsOf(assigned);
    }

    @Test
    void childIdentitiesSurviveAnUpdate() {
        // update() syncs the same way — it is the same diff, not a replace-all.
        Company analyticalEngines = companies.save(new Company("Analytical Engines", "industry"));
        Company analyticalEngineNo2 = companies.save(new Company("Analytical Engine, No. 2", "industry"));

        Profile ada = profile("Ada");
        ada.getWorkHistory().add(new WorkExperience(analyticalEngines, 1842, 1843));
        Profile saved = profiles.save(ada);
        Long childId = saved.getWorkHistory().get(0).getId();

        saved.getWorkHistory().get(0).setCompany(analyticalEngineNo2);
        profiles.update(saved);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).hasSize(1);
        assertThat(back.getWorkHistory().get(0).getId()).isEqualTo(childId);
        assertThat(back.getWorkHistory().get(0).getCompany().getId()).isEqualTo(analyticalEngineNo2.getId());
    }

    @Test
    void aChildDroppedFromTheCollectionLosesItsRow() {
        // The other half of a diff sync: an upsert-only sync would leave the dropped child's row
        // behind forever, because nothing in the collection names it any more.
        Company alpha = companies.save(new Company("alpha", "industry"));
        Company zeta = companies.save(new Company("zeta", "industry"));

        Profile ada = profile("Ada");
        ada.getWorkHistory().add(new WorkExperience(alpha, 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience(zeta, 1844, 1845));
        Profile saved = profiles.save(ada);
        WorkExperience keep = child(saved, alpha);
        WorkExperience drop = child(saved, zeta);
        Long droppedId = drop.getId();

        saved.getWorkHistory().remove(drop);
        profiles.save(saved);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).hasSize(1);
        assertThat(back.getWorkHistory().get(0).getId()).isEqualTo(keep.getId());
        assertThat(workExperiences.findById(droppedId))
                .as("a child the collection no longer names must be deleted, not left behind")
                .isEmpty();
        assertThat(workExperiences.findById(keep.getId())).isPresent();
    }

    @Test
    void aNewChildIsInsertedAlongsideTheExistingOnes() {
        // Mixed diff: the known child upserts, the new one inserts and gets an id.
        Company alpha = companies.save(new Company("alpha", "industry"));
        Company beta = companies.save(new Company("beta", "industry"));

        Profile ada = profile("Ada");
        ada.getWorkHistory().add(new WorkExperience(alpha, 1842, 1843));
        Profile saved = profiles.save(ada);
        Long firstId = saved.getWorkHistory().get(0).getId();

        saved.getWorkHistory().add(new WorkExperience(beta, 1846, 1847));
        profiles.save(saved);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).hasSize(2);
        assertThat(ids(back.getWorkHistory())).contains(firstId);
        assertThat(ids(back.getWorkHistory())).doesNotContainNull();
        assertThat(back.getWorkHistory()).extracting(we -> we.getCompany().getId())
                .containsExactlyInAnyOrder(alpha.getId(), beta.getId());
    }

    @Test
    void emptyingTheCollectionRemovesEveryChildRow() {
        // Degenerate diff: nothing to upsert, so the parent's whole child set is orphaned.
        Company alpha = companies.save(new Company("alpha", "industry"));
        Company zeta = companies.save(new Company("zeta", "industry"));

        Profile ada = profile("Ada");
        ada.getWorkHistory().add(new WorkExperience(alpha, 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience(zeta, 1844, 1845));
        Profile saved = profiles.save(ada);
        List<Long> assigned = ids(saved.getWorkHistory());

        saved.getWorkHistory().clear();
        profiles.save(saved);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).isEmpty();
        for (Long id : assigned) {
            assertThat(workExperiences.findById(id)).isEmpty();
        }
    }

    private static Profile profile(String fullName) {
        Profile p = new Profile();
        p.setFullName(fullName);
        return p;
    }

    private static WorkExperience child(Profile profile, Company company) {
        return profile.getWorkHistory().stream()
                .filter(w -> company.getId().equals(w.getCompany().getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no child with company id '" + company.getId() + "' in " + ids(profile.getWorkHistory())));
    }

    private static List<Long> ids(List<WorkExperience> children) {
        return children.stream().map(WorkExperience::getId).toList();
    }
}
