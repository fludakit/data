package io.github.fludakit.data.it;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface TeamRepository extends CrudRepository<Team, Long> {}
