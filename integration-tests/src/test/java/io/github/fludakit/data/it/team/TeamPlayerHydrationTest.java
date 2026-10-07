package io.github.fludakit.data.it.team;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the two entity-relationship kinds through the generated hydration (Task 4): the
 * one-to-many {@code Team.players} loader groups rows read from the player's own table by the
 * {@code team_id} inverse fk; since Task 12 both classes are {@code @Entity}, so the many-to-one
 * {@code Player.team} side is an association materialised id-only by the player row mapper
 * (Task 9 partial rule) rather than the former two-phase fetch.
 *
 * <p>Contract locks (ruling 6, Task-12 @Entity amendment): hydration stays depth-1 — hydrated
 * children carry their back-reference as an id-only PARTIAL (non-composing Reference inside an
 * {@code @Entity} owner, Task 9 row-mapper rule: non-null id, null name, empty collections) — and a
 * partial parent reference does NOT cascade on update: replace-all applies to element collections
 * only (Decision 5), and composition write cascades are gated on {@code @AggregateRoot} (I-1).</p>
 */
@EnableAutoWeld
@AddPackages(Team.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class TeamPlayerHydrationTest {

    @Inject TeamRepository teams;
    @Inject PlayerRepository players;

    @Test
    void relationshipRoundTrip() {
        Team team = new Team();
        team.setName("Falcons");
        Team savedTeam = teams.save(team);

        Player striker = new Player();
        striker.setName("Ramos");
        striker.setTeam(savedTeam);                       // MANY_TO_ONE write binds team_id via getId
        Player keeper = new Player();
        keeper.setName("Navas");
        keeper.setTeam(savedTeam);
        players.save(striker);
        players.save(keeper);

        Team found = teams.findById(savedTeam.getId()).orElseThrow();
        assertThat(found.getPlayers()).extracting(Player::getName)
                .containsExactlyInAnyOrder("Ramos", "Navas");
        // depth-1 the one way: the child row mapper materialises the non-composing team Reference
        // as an id-only partial (Task 9 @Entity-owner rule) — no nested hydration beneath it.
        assertThat(found.getPlayers()).allSatisfy(p -> {
            assertThat(p.getTeam()).isNotNull();
            assertThat(p.getTeam().getId()).isEqualTo(savedTeam.getId());
            assertThat(p.getTeam().getName()).isNull();
            assertThat(p.getTeam().getPlayers()).isEmpty();
        });

        Player foundPlayer = players.findById(striker.getId()).orElseThrow();
        // Task 12: Player is @Entity, so the bare `team` field is an ASSOCIATION (non-composing
        // Reference), not composition — direct reads materialise it id-only, not fully hydrated.
        assertThat(foundPlayer.getTeam()).isNotNull();
        assertThat(foundPlayer.getTeam().getId()).isEqualTo(savedTeam.getId());
        assertThat(foundPlayer.getTeam().getName())
                .as("bare entity reference inside @Entity materialises as an association (id-only)")
                .isNull();
        assertThat(foundPlayer.getTeam().getPlayers()).isEmpty();      // depth-1 the other way

        foundPlayer.getTeam().setName("Falcons II");                   // write via partial ref
        players.update(foundPlayer);
        assertThat(teams.findById(savedTeam.getId()).orElseThrow().getName()).isEqualTo("Falcons");
        // ^ documents that a partial MANY_TO_ONE target is NOT cascaded on update — replace-all
        //   applies to element collections only (Decision 5) and composition cascades are gated on
        //   @AggregateRoot (I-1); this assertion locks that boundary.
    }
}
