package io.github.fludakit.data.it.underscore;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface SiteRepository extends CrudRepository<Site, Long> {
}
