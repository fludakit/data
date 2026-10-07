package io.github.fludakit.data.it.team;

import io.github.fludakit.data.annotation.Entity;
import io.github.fludakit.data.annotation.Identity;

import java.util.ArrayList;
import java.util.List;

/**
 * One-to-many owner of the hydration IT: {@code players} is a bare {@code List<Player>} of entities, so
 * it classifies ONE_TO_MANY ({@code Prop.EntityList}) with the inverse resolved to {@code Player.team},
 * and the generated read loader groups rows from the player's OWN table by the {@code team_id} fk.
 *
 * <p>Task 12: {@code @Entity} (not {@code @AggregateRoot}) — the read loader is stereotype-independent,
 * but the composition WRITE cascade is gated on {@code @AggregateRoot} (I-1): children are managed
 * explicitly through {@link PlayerRepository} and {@code teams.save/update/delete} never insert, replace
 * or delete player rows. This is the directive's "collections of associated objects" case.</p>
 */
@Entity
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
