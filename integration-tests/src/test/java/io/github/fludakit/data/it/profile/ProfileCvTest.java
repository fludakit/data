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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flagship end-to-end pin for the navigable-stereotype directive: the profile fixture is a CV
 * aggregate mixing (a) composition entity collections ({@code workHistory}, {@code education} —
 * targets are managed {@code @Entity} classes with their own repositories and a bare inverse
 * {@code Profile profile} field), (b) an element collection of {@code @ValueObject} contacts
 * (child table {@code contact_points}), (c) navigable static-metamodel paths for sorting —
 * both through an embedded VO ({@code _Profile.address.city}) and through an entity collection
 * ({@code _Profile.workHistory.yearStart}), and (d) an {@code @Association} reference from
 * WorkExperience to Company, verifying that non-composing to-one associations work within a
 * cascaded entity collection.
 *
 * <p>Two framework gaps this flagship IT surfaced are closed: aggregate {@code save}/
 * {@code update}/{@code deleteById} now cascade composition entity collections (insert children with
 * the inverse fk taken from the root id; update replaces all child rows like element collections),
 * and the paged {@code findAll} join chain is spliced onto a root-table-QUALIFIED find constant so a
 * joined definition sharing the {@code id} column name is no longer ambiguous. The supported child-repo +
 * inverse-fk write path stays covered by {@link #aggregateRoundTripsAllCollections}.</p>
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ProfileCvTest {

    @Inject ProfileRepository profiles;
    @Inject CompanyRepository companies;
    @Inject WorkExperienceRepository workExperiences;
    @Inject EducationRepository educations;

    @Test
    void aggregateRoundTripsAllCollections() {
        Profile p = new Profile();
        p.setFullName("Ada Lovelace");
        Address address = new Address();
        address.setCity("London");
        address.setZip(12345);
        p.setAddress(address);
        p.getContacts().add(new ContactPoint("EMAIL", "ada@example.com"));
        p.getContacts().add(new ContactPoint("PHONE", "+44-20-7946-0000"));
        Profile saved = profiles.save(p);

        // The @Association Company must be saved independently before it can be referenced.
        Company ae = companies.save(new Company("Analytical Engines", "computing"));
        workExperiences.save(withProfile(new WorkExperience(ae, 1842, 1843), saved));
        Education edu = new Education("University of London", 1840);
        edu.setProfile(saved);
        educations.save(edu);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getFullName()).isEqualTo("Ada Lovelace");

        assertThat(back.getWorkHistory()).hasSize(1);
        assertThat(back.getWorkHistory().get(0).getCompany()).isNotNull();
        assertThat(back.getWorkHistory().get(0).getCompany().getId()).isEqualTo(ae.getId());
        assertThat(back.getWorkHistory().get(0).getYearStart()).isEqualTo(1842);
        assertThat(back.getEducation()).hasSize(1);
        assertThat(back.getEducation().get(0).getInstitution()).isEqualTo("University of London");
        assertThat(back.getEducation().get(0).getYear()).isEqualTo(1840);

        assertThat(back.getContacts()).hasSize(2);
        assertThat(back.getContacts()).extracting(ContactPoint::getKind)
                .containsExactlyInAnyOrder("EMAIL", "PHONE");

        WorkExperience member = back.getWorkHistory().get(0);
        assertThat(member.getId()).isNotNull();
        WorkExperience direct = workExperiences.findById(member.getId()).orElseThrow();
        assertThat(direct.getId()).isEqualTo(member.getId());
        assertThat(direct.getCompany()).isNotNull();
        assertThat(direct.getCompany().getId()).isEqualTo(ae.getId());
        assertThat(direct.getProfile()).isNotNull();
        assertThat(direct.getProfile().getId()).isEqualTo(saved.getId());
        assertThat(direct.getProfile().getFullName()).isNull();
    }

    @Test
    void aggregateSaveCascadesCompositionEntityCollections() {
        // The @Association Company must be saved independently — @Association is non-composing,
        // so the aggregate cascade binds the FK (company_id) but does not persist the Company.
        Company ae = companies.save(new Company("Analytical Engines", "computing"));

        Profile p = new Profile();
        p.setFullName("Ada Lovelace");
        p.getWorkHistory().add(new WorkExperience(ae, 1842, 1843));
        p.getEducation().add(new Education("University of London", 1840));
        Profile saved = profiles.save(p);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).hasSize(1);
        assertThat(back.getWorkHistory().get(0).getCompany()).isNotNull();
        assertThat(back.getWorkHistory().get(0).getCompany().getId()).isEqualTo(ae.getId());
        assertThat(back.getWorkHistory().get(0).getYearStart()).isEqualTo(1842);
        assertThat(back.getWorkHistory().get(0).getYearEnd()).isEqualTo(1843);
        assertThat(back.getWorkHistory().get(0).getId()).isNotNull();
        assertThat(back.getEducation()).hasSize(1);
        assertThat(back.getEducation().get(0).getInstitution()).isEqualTo("University of London");
        assertThat(back.getEducation().get(0).getYear()).isEqualTo(1840);
        assertThat(back.getEducation().get(0).getId()).isNotNull();

        Long weId = back.getWorkHistory().get(0).getId();
        WorkExperience direct = workExperiences.findById(weId).orElseThrow();
        assertThat(direct.getId()).isEqualTo(weId);
        assertThat(direct.getCompany()).isNotNull();
        assertThat(direct.getCompany().getId()).isEqualTo(ae.getId());

        profiles.save(back);
        Profile replaced = profiles.findById(saved.getId()).orElseThrow();
        assertThat(replaced.getWorkHistory()).hasSize(1);
        assertThat(replaced.getEducation()).hasSize(1);
    }

    @Test
    void sortThroughCollectionPathContributesJoin() {
        // The @Association Company must be saved independently before referencing.
        Company zeta = companies.save(new Company("Zeta Corp", "industry"));
        Company alpha = companies.save(new Company("Alpha Ltd", "industry"));

        Profile ada = profile("Ada", "London");
        ada.getWorkHistory().add(new WorkExperience(zeta, 1952, 1953));
        Profile grace = profile("Grace", "New York");
        grace.getWorkHistory().add(new WorkExperience(alpha, 1842, 1843));
        Profile rene = profile("Rene", "Paris");
        profiles.save(ada);
        profiles.save(grace);
        profiles.save(rene);

        Page<Profile> page = profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.workHistory.get(_WorkExperience.yearStart).asc()));

        // LEFT JOIN semantics: both work-history owners come back ordered by yearStart ASC
        // (Grace 1842 before Ada 1952) and the childless Rene is still present.
        assertThat(page.content()).isNotEmpty();
        List<String> names = page.content().stream().map(Profile::getFullName).toList();
        assertThat(names).contains("Rene");
        int graceAt = names.indexOf("Grace");
        int adaAt = names.indexOf("Ada");
        assertThat(graceAt).isNotNegative();
        assertThat(adaAt).isNotNegative();
        assertThat(graceAt).isLessThan(adaAt);
    }

    @Test
    void navigableEmbeddedPathSortStillWorks() {
        profiles.save(profile("Beta", "Zürich"));
        profiles.save(profile("Alpha", "Aarau"));

        Page<Profile> page = profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.address.get(_Address.city).asc()));
        assertThat(page.content()).extracting(p -> p.getAddress().getCity())
                .containsExactly("Aarau", "Zürich");
    }

    @Test
    void toManySortReturnsEachRootOnce() {
        // Ada's two work experiences have yearStart 1842 and 1850, so the MIN(yearStart) aggregate
        // sort key is 1842 (Ada) and 1952 (Grace). The sort must return each profile exactly ONCE.
        // A LEFT JOIN here would fan out to three join rows and duplicate the two-child root.
        Company alpha = companies.save(new Company("alpha", "industry"));
        Company zeta = companies.save(new Company("zeta", "industry"));
        Company beta = companies.save(new Company("Beta", "industry"));

        Profile ada = profile("Ada", "London");
        ada.getWorkHistory().add(new WorkExperience(alpha, 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience(zeta, 1850, 1851));
        Profile grace = profile("Grace", "New York");
        grace.getWorkHistory().add(new WorkExperience(beta, 1952, 1952));
        profiles.save(ada);
        profiles.save(grace);

        Page<Profile> page = profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.workHistory.get(_WorkExperience.yearStart).asc()));
        assertThat(page.content()).extracting(Profile::getFullName)
                .containsExactly("Ada", "Grace");
        assertThat(page.content())
                .extracting(p -> p.getWorkHistory().stream().map(WorkExperience::getYearStart)
                        .min(Integer::compareTo).orElseThrow())
                .containsExactly(1842, 1952);
    }

    private static Profile profile(String fullName, String city) {
        Profile p = new Profile();
        p.setFullName(fullName);
        Address a = new Address();
        a.setCity(city);
        p.setAddress(a);
        return p;
    }

    private static WorkExperience withProfile(WorkExperience we, Profile profile) {
        we.setProfile(profile);
        return we;
    }
}
