package io.github.fludakit.data.it.projection;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;

@Repository
public interface AuthorRepository extends CrudRepository<Author, Long> { }
