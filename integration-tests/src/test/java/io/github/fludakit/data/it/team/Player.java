package io.github.fludakit.data.it.team;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

/**
 * Many-to-one side of the hydration IT: the bare {@code Team team} field maps to the {@code team_id}
 * column; writes bind the fk from {@code team.getId()} either way.
 *
 * <p>Task 12: {@code @Entity} (not {@code @AggregateRoot}) — with an {@code @Entity} owner the bare
 * entity field is a NON-composing {@code Reference} (association), so direct row-mapper reads
 * (findById and friends) materialise it as an id-only partial (Task 9 {@code partial_<Target>} rule,
 * same shape as {@code WorkExperience.profile}), instead of the former two-phase eager hydration.</p>
 */
@Entity
public class Player {
    @Identity Long id;
    String name;
    Team team;

    public Player() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }
}
