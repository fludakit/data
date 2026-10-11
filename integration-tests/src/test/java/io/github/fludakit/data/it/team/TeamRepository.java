package io.github.fludakit.data.it.team;

import jakarta.data.page.CursoredPage;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Find;
import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;

import java.util.List;

@Repository
public interface TeamRepository extends CrudRepository<Team, Long> {

    // Task 12: predicate THROUGH the entity collection (Task 8 camel-path resolution:
    // head "players" (EntityList) + leaf "name" in Player), rendering an EXISTS semi-join (Task 5),
    // not a LEFT JOIN, so the root row cannot fan out. The traversal is declared by @By: @Find is
    // annotation-driven, so the method name is not analyzed.
    @Find
    List<Team> findByPlayersName(@By("players.name") String name);

    // Cursored paging through a to-many relation: the runtime sort is baked in via @OrderBy, and
    // the aggregate MIN(player.name) key plus the root-id tiebreak drives the keyset seek. The name
    // must not parse as a query-by-name finder (no "find…By" shape), so it stays a pure @Find.
    @Find
    @OrderBy("players.name")
    CursoredPage<Team> orderedByPlayerName(PageRequest pageRequest);
}
