package io.github.fludakit.data.it;

import io.github.fludakit.data.annotation.AggregateRoot;
import io.github.fludakit.data.annotation.Identity;

import java.util.ArrayList;
import java.util.List;

/**
 * One-to-many owner of the hydration IT: {@code players} is a bare {@code List<Player>} of entities, so
 * it classifies ONE_TO_MANY with the inverse resolved to {@code Player.team} (Plan B contract), and the
 * generated loader groups rows read from the player's OWN table by the {@code team_id} fk.
 */
@AggregateRoot
public class Team {
    @Identity Long id;
    String name;
    List<Player> players = new ArrayList<>();

    public Team() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Player> getPlayers() { return players; }
    public void setPlayers(List<Player> players) { this.players = players; }
}
