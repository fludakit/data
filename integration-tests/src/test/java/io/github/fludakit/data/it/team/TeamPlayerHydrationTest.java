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
 * {@code team_id} inverse fk, and the many-to-one {@code Player.team} loader re-reads that fk from the
 * player row and then fetches the team with its generated row mapper.
 *
 * <p>Contract locks (ruling 6): hydrated children keep their back-reference NULL and a hydrated parent
 * reference carries EMPTY collections — hydration is depth-1, never recursive — and updating through a
 * hydrated many-to-one reference does NOT cascade into the target: replace-all applies to element
 * collections only (Decision 5).</p>
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
        // depth-1: hydrated children carry no nested hydration (team ref is null, not "filled then empty")
        assertThat(found.getPlayers()).allSatisfy(p -> assertThat(p.getTeam()).isNull());

        Player foundPlayer = players.findById(striker.getId()).orElseThrow();
        assertThat(foundPlayer.getTeam()).isNotNull();
        assertThat(foundPlayer.getTeam().getName()).isEqualTo("Falcons");
        assertThat(foundPlayer.getTeam().getPlayers()).isEmpty();      // depth-1 the other way

        foundPlayer.getTeam().setName("Falcons II");                   // update via hydrated ref
        players.update(foundPlayer);
        assertThat(teams.findById(savedTeam.getId()).orElseThrow().getName()).isEqualTo("Falcons");
        // ^ documents that a hydrated MANY_TO_ONE target is NOT cascaded on update — replace-all
        //   applies to element collections only (Decision 5); this assertion locks that boundary.
    }
}
