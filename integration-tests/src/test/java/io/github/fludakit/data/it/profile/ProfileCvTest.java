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
 * (child table {@code profile_contacts}), and (c) navigable static-metamodel paths for sorting —
 * both through an embedded VO ({@code _Profile.address.city}) and through an entity collection
 * ({@code _Profile.workHistory.company}).
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
        // The child-repo + inverse-Reference write path stays supported alongside the new
        // aggregate cascade: this root carries NO in-memory entity children, only contacts.
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
    void aggregateSaveCascadesCompositionEntityCollections() {
        // The @AggregateRoot composition lifecycle is bound to the root: save() cascades the entity
        // collections, inserting each child row with the inverse fk filled from the root id, exactly
        // as element collections do. update() replaces all child rows (delete by fk + re-insert,
        // with fresh child ids), same replace-all parity as element collections.
        Profile p = new Profile();
        p.setFullName("Ada Lovelace");
        p.getWorkHistory().add(new WorkExperience("Analytical Engines", 1842, 1843));
        p.getEducation().add(new Education("University of London", 1840));
        Profile saved = profiles.save(p);

        Profile back = profiles.findById(saved.getId()).orElseThrow();
        assertThat(back.getWorkHistory()).hasSize(1);
        assertThat(back.getWorkHistory().get(0).getCompany()).isEqualTo("Analytical Engines");
        assertThat(back.getWorkHistory().get(0).getYearStart()).isEqualTo(1842);
        assertThat(back.getWorkHistory().get(0).getYearEnd()).isEqualTo(1843);
        assertThat(back.getWorkHistory().get(0).getId()).isNotNull();
        assertThat(back.getEducation()).hasSize(1);
        assertThat(back.getEducation().get(0).getInstitution()).isEqualTo("University of London");
        assertThat(back.getEducation().get(0).getYear()).isEqualTo(1840);
        assertThat(back.getEducation().get(0).getId()).isNotNull();

        // Cascaded children have workable ids: the flagship cross-check through their own repository.
        Long weId = back.getWorkHistory().get(0).getId();
        WorkExperience direct = workExperiences.findById(weId).orElseThrow();
        assertThat(direct.getId()).isEqualTo(weId);
        assertThat(direct.getCompany()).isEqualTo("Analytical Engines");

        // Root UPDATE cascades replace-all: the saved aggregate's children are deleted by fk and
        // re-inserted from the in-memory collection (fresh ids), never duplicated.
        profiles.save(back);
        Profile replaced = profiles.findById(saved.getId()).orElseThrow();
        assertThat(replaced.getWorkHistory()).hasSize(1);
        assertThat(replaced.getEducation()).hasSize(1);
    }

    @Test
    void sortThroughCollectionPathContributesJoin() {
        // A joined sort definition shares the "id" column name with the root, so the paged findAll keeps
        // TWO find constants — the plain SQL_FIND_ALL and a root-table-QUALIFIED join variant — and
        // picks the qualified one at runtime whenever the resolved sort refs contribute a LEFT JOIN.
        // Without the qualified variant the root select list is ambiguous and the read fails.
        // The children ride the aggregate: save() cascades them through the entity-collection write
        // path, so there are no explicit child-repo saves (those would double-insert).
        Profile ada = profile("Ada", "London");
        ada.getWorkHistory().add(new WorkExperience("Zeta Corp", 1842, 1843));
        Profile grace = profile("Grace", "New York");
        grace.getWorkHistory().add(new WorkExperience("Alpha Ltd", 1952, 1952));
        Profile rene = profile("Rene", "Paris"); // no work history at all
        profiles.save(ada);
        profiles.save(grace);
        profiles.save(rene);

        Page<Profile> page = profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.workHistory.company.asc()));

        // LEFT JOIN semantics: both work-history owners come back ordered by company ASC
        // ("Alpha Ltd" before "Zeta Corp") and the childless Rene is still present.
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
                PageRequest.ofPage(1).size(10), Order.by(_Profile.address.city.asc()));
        assertThat(page.content()).extracting(p -> p.getAddress().getCity())
                .containsExactly("Aarau", "Zürich");
    }

    @Test
    void toManySortAndPredicateReturnEachRootOnce() {
        // Ada's two companies straddle Grace's single one, so the MIN(company) aggregate sort keys
        // are "alpha" (Ada) and "Beta" (Grace), while the EXISTS predicate under the root list must
        // return each profile exactly ONCE. A LEFT JOIN here would fan out to three join rows
        // ([Grace, Ada, Ada] ordered by joined row) and duplicate the two-child root in both the
        // sorted read and the child-matched finder.
        Profile ada = profile("Ada", "London");
        ada.getWorkHistory().add(new WorkExperience("alpha", 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience("zeta", 1843, 1844));
        Profile grace = profile("Grace", "New York");
        grace.getWorkHistory().add(new WorkExperience("Beta", 1952, 1952));
        profiles.save(ada);
        profiles.save(grace);

        Page<Profile> page = profiles.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Profile.workHistory.company.asc()));
        assertThat(page.content()).extracting(Profile::getFullName)
                .containsExactly("Grace", "Ada"); // "Beta" < "alpha"; each root once
        // Second leg pins the HYDRATED collections (per-root MIN(company) recomputed in memory): fan-out
        // rows that partially overwrite workHistory (e.g. last-occurrence-wins -> Ada keeps only "zeta")
        // fail here even when the root sequence above still matches. A first-occurrence join dedup would
        // pass both legs — Grace's single child makes min=max — so this is hydration coverage, not a
        // MIN-vs-dedup sort discriminator.
        // Relies on H2 default collation: uppercase sorts before lowercase (BINARY), so "Beta" < "alpha".
        assertThat(page.content())
                .extracting(p -> p.getWorkHistory().stream().map(WorkExperience::getCompany)
                        .min(String::compareTo).orElseThrow())
                .containsExactly("Beta", "alpha");

        List<Profile> zetaHolders = profiles.findByWorkHistoryCompany("zeta");
        assertThat(zetaHolders).extracting(Profile::getFullName).containsExactly("Ada");
    }

    @Test
    void countThroughToManyPredicateIsDistinctRootTotal() {
        // Three matching CHILD rows across two roots ("alpha" twice on Ada, once on Grace), so the
        // COUNT must stay 2 distinct ROOTS and not the row fan-out of 3 that a joined WHERE would
        // produce. The total therefore runs unjoined, with the child match as an EXISTS semi-join.
        Profile ada = profile("Ada", "London");
        ada.getWorkHistory().add(new WorkExperience("alpha", 1842, 1843));
        ada.getWorkHistory().add(new WorkExperience("alpha", 1843, 1844));
        Profile grace = profile("Grace", "New York");
        grace.getWorkHistory().add(new WorkExperience("alpha", 1952, 1952));
        Profile rene = profile("Rene", "Paris");
        rene.getWorkHistory().add(new WorkExperience("zeta", 1900, 1901));
        profiles.save(ada);
        profiles.save(grace);
        profiles.save(rene);

        assertThat(profiles.countByWorkHistoryCompany("alpha")).isEqualTo(2L);
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
