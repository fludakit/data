package io.github.fludakit.data.it.profile;

import io.github.fludakit.data.it.DataSourceProducer;

import io.github.fludakit.jdbc.JdbcClientException;
import jakarta.data.Order;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flagship end-to-end pin for the navigable-stereotype directive: the profile fixture is a CV
 * aggregate mixing (a) composition entity collections ({@code workHistory}, {@code education} —
 * targets are managed {@code @Entity} classes with their own repositories and a bare inverse
 * {@code Profile profile} field), (b) an element collection of {@code @ValueObject} contacts
 * (child table {@code profile_contacts}), and (c) navigable static-metamodel paths for sorting —
 * both through an embedded VO ({@code _Profile.address.city}) and through an entity collection
 * ({@code _Profile.workHistory.company}).
 *
 * <p>Two framework gaps surfaced while writing this flagship IT and pinned in current-behaviour
 * form (Task 9 precedent {@code OrderCollectionHydrationTest}). GAP-1: aggregate {@code save}
 * cascades element collections only — {@code ProfileRepositoryImpl} emits {@code SQL_INSERT_CONTACTS}
 * but no {@code SQL_INSERT_WORK_HISTORY}/{@code SQL_INSERT_EDUCATION}, so entity-collection members
 * must be persisted through their own repository with the inverse field set (the supported write
 * path, same shape as the team corpus). GAP-2: the paged {@code findAll} join chain is spliced onto
 * the UNQUALIFIED {@code SQL_FIND_ALL} constant, so an entity-collection sort — whose target table
 * always shares the {@code id} column — fails with H2 "Ambiguous column name"; the join and the
 * qualified ORDER BY themselves ARE emitted correctly.</p>
 */
@EnableAutoWeld
@AddPackages(Profile.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class ProfileCvTest {

    @Inject ProfileRepository profiles;
    @Inject WorkExperienceRepository workExperiences;
    @Inject EducationRepository educations;

    @Test
    void aggregateRoundTripsAllCollections() {
        Profile p = new Profile();
        p.setFullName("Ada Lovelace");
        Address address = new Address();
        address.setCity("London");
        address.setZip(12345);           // zip is Integer in this fixture — numeric pin, not "E1"
        p.setAddress(address);
        p.getContacts().add(new ContactPoint("EMAIL", "ada@example.com"));
        p.getContacts().add(new ContactPoint("PHONE", "+44-20-7946-0000"));
        // GAP-1: workHistory/education are NOT cascaded by save; element collections (contacts) are.
        Profile saved = profiles.save(p);

        // Supported entity-collection write path: child repo + inverse Reference (fk profile_id).
        workExperiences.save(withProfile(new WorkExperience("Analytical Engines", 1842, 1843), saved));
        Education edu = new Education("University of London", 1840);
        edu.setProfile(saved);
        educations.save(edu);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getFullName()).isEqualTo("Ada Lovelace");

        // Composition entity collections hydrate eagerly with full fields on aggregate reads.
        assertThat(back.getWorkHistory()).hasSize(1);
        assertThat(back.getWorkHistory().get(0).getCompany()).isEqualTo("Analytical Engines");
        assertThat(back.getWorkHistory().get(0).getYearStart()).isEqualTo(1842);
        assertThat(back.getEducation()).hasSize(1);
        assertThat(back.getEducation().get(0).getInstitution()).isEqualTo("University of London");
        assertThat(back.getEducation().get(0).getYear()).isEqualTo(1840);

        // Element collection of value objects round-trips through profile_contacts.
        assertThat(back.getContacts()).hasSize(2);
        assertThat(back.getContacts()).extracting(ContactPoint::getKind)
                .containsExactlyInAnyOrder("EMAIL", "PHONE");

        // Entity members carry their ids: cross-check through their own repository.
        WorkExperience member = back.getWorkHistory().get(0);
        assertThat(member.getId()).isNotNull();
        WorkExperience direct = workExperiences.findById(member.getId()).orElseThrow();
        assertThat(direct.getId()).isEqualTo(member.getId());
        assertThat(direct.getCompany()).isEqualTo("Analytical Engines");
        // The bare inverse field is a non-composing Reference: id-only partial on direct reads.
        assertThat(direct.getProfile()).isNotNull();
        assertThat(direct.getProfile().getId()).isEqualTo(saved.getId());
        assertThat(direct.getProfile().getFullName()).isNull();
    }

    @Test
    void aggregateSaveDoesNotCascadeEntityCollectionsGapPin() {
        // GAP-1 pin of CURRENT behaviour: save inserts the aggregate root + element-collection rows
        // only; entity-collection members carried on the aggregate are silently dropped (no
        // SQL_INSERT_WORK_HISTORY exists in ProfileRepositoryImpl). The read-side loader DOES walk
        // profile_id — proven by aggregateRoundTripsAllCollections via the child-repo write path.
        // Expected once aggregate writes cascade composition entity collections: hasSize(1).
        Profile p = new Profile();
        p.setFullName("Ada Lovelace");
        p.getWorkHistory().add(new WorkExperience("Analytical Engines", 1842, 1843));
        Profile saved = profiles.save(p);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).isEmpty();
    }

    @Test
    void sortThroughCollectionPathContributesJoin() {
        Profile ada = profile("Ada", "London");
        ada.getWorkHistory().add(new WorkExperience("Zeta Corp", 1842, 1843));
        Profile grace = profile("Grace", "New York");
        grace.getWorkHistory().add(new WorkExperience("Alpha Ltd", 1952, 1952));
        Profile rene = profile("Rene", "Paris"); // no work history at all
        profiles.save(ada);
        profiles.save(grace);
        profiles.save(rene);
        workExperiences.save(withProfile(new WorkExperience("Zeta Corp", 1842, 1843), ada));
        workExperiences.save(withProfile(new WorkExperience("Alpha Ltd", 1952, 1952), grace));

        // GAP-2 pin of CURRENT behaviour: the runtime path DOES resolve "workHistory.company" to a
        // LEFT JOIN plus a qualified ORDER BY, but the join is spliced onto the unqualified
        // SQL_FIND_ALL select list, and "id" is then ambiguous between profile and work_experience:
        //   SELECT "id", ... FROM "profile" LEFT JOIN "work_experience" AS "workHistory"
        //     ON "profile"."id" = "workHistory"."profile_id" ORDER BY "workHistory"."company" ASC
        // Expected once the joined branch qualifies root columns (Task 9 find-constant precedent):
        // both work-history owners ordered Grace->Ada and the childless Rene still present (LEFT JOIN).
        assertThatThrownBy(() -> profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.workHistory.company.asc())))
                .isInstanceOf(JdbcClientException.class)
                .hasMessageContaining("Ambiguous column name");
    }

    @Test
    void navigableEmbeddedPathSortStillWorks() {
        profiles.save(profile("Beta", "Zürich"));
        profiles.save(profile("Alpha", "Aarau"));

        Page<Profile> page = profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.address.city.asc()));
        assertThat(page.content()).extracting(p -> p.getAddress().getCity())
                .containsExactly("Aarau", "Zürich");
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
