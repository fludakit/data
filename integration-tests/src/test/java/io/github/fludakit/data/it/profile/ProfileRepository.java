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
}
