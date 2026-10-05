package io.github.fludakit.data.it;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

/**
 * Many-to-one side of the hydration IT: the bare {@code Team team} field classifies MANY_TO_ONE (Plan B
 * contract) with fk column {@code team_id}, and the generated two-phase loader re-reads that fk from the
 * player's own row before fetching the team.
 */
@AggregateRoot
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
