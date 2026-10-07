package io.github.fludakit.data.it.team;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface TeamRepository extends CrudRepository<Team, Long> {

    // Task 12: predicate THROUGH the entity collection (Task 8 camel-path resolution:
    // head "players" (EntityList) + leaf "name" in Player), rendering an EXISTS semi-join (Task 5),
    // not a LEFT JOIN, so the root row cannot fan out.
    @Find
    List<Team> findByPlayersName(String name);
}
