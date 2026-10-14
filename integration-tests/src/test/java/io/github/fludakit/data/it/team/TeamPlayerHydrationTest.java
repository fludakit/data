package io.github.fludakit.data.it.team;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips the two entity-relationship kinds through the generated hydration: the
 * one-to-many {@code Team.players} loader groups rows read from the player's own table by the
 * {@code team_id} inverse fk. Both fixture classes are {@code @Entity}, so the many-to-one
 * {@code Player.team} side is an association, materialised id-only by the player row mapper
 * rather than eagerly hydrated in a second phase.
 *
 * <p>Contract locks: hydration stays depth-1 — hydrated children carry their back-reference as an
 * id-only PARTIAL (a non-composing Reference reached from an {@code @Entity} owner materialises with
 * a non-null id, a null name and empty collections) — and a partial parent reference does NOT
 * cascade on update: replace-all applies to element collections only, and composition write
 * cascades require {@code @AggregateRoot}.</p>
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
        // as an id-only partial — no nested hydration beneath it.
        assertThat(found.getPlayers()).allSatisfy(p -> {
            assertThat(p.getTeam()).isNotNull();
            assertThat(p.getTeam().getId()).isEqualTo(savedTeam.getId());
            assertThat(p.getTeam().getName()).isNull();
            assertThat(p.getTeam().getPlayers()).isEmpty();
        });

        Player foundPlayer = players.findById(striker.getId()).orElseThrow();
        // Player is @Entity rather than @AggregateRoot, so the bare `team` field is an ASSOCIATION
        // (non-composing Reference), not a composition — direct reads materialise it id-only.
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
        //   applies to element collections only, and composition cascades require @AggregateRoot;
        //   this assertion locks that boundary.
    }
}
