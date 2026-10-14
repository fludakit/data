package io.github.fludakit.data.it.profile;

import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface ProfileRepository extends CrudRepository<Profile, Long> {

    // Must stay a literal, not _Profile.ADDRESS_CITY: the processor proxies this annotation in the
    // round that still generates _Profile, so javac cannot inline the constant reference.
    @Find
    @OrderBy("address.city")
    List<Profile> orderedByCity();

    // Predicate THROUGH the composition entity collection: the camel path resolves its head
    // "workHistory" (the EntityCollection) and its leaf "company" (a plain column of
    // WorkExperience), and renders an EXISTS semi-join — never a LEFT JOIN — on both the root list
    // and the unjoined COUNT(*) total, so a root can never fan out with its children. The @Find
    // overload declares the path with @By; the COUNT below is unannotated, so it stays a
    // query-by-method name and is resolved from the method name.
    @Find
    List<Profile> findByWorkHistoryCompany(@By("workHistory.company") String company);

    long countByWorkHistoryCompany(String company);
}
