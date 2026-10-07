package io.github.fludakit.data.it.profile;

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

    // Task 7: predicate THROUGH the composition entity collection (camel path head "workHistory"
    // (EntityList) + leaf "company"), rendering the Task 5 EXISTS semi-join on both the root list
    // and the unjoined COUNT(*) total — the root can never fan out with the children.
    @Find
    List<Profile> findByWorkHistoryCompany(String company);

    long countByWorkHistoryCompany(String company);
}
