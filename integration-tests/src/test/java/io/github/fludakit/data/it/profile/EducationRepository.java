package io.github.fludakit.data.it.profile;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

/** Makes {@link Education} a managed entity; the aggregate's EntityCollection loader walks profile_id. */
@Repository
public interface EducationRepository extends CrudRepository<Education, Long> {
}
