package io.github.fludakit.data.it.profile;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

/** Makes {@link WorkExperience} a managed entity and serves the id-only-partial cross-check. */
@Repository
public interface WorkExperienceRepository extends CrudRepository<WorkExperience, Long> {
}
