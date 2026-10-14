package io.github.fludakit.data.it.team;

import io.github.fludakit.data.it.DataSourceProducer;

import jakarta.data.Order;
import jakarta.data.page.CursoredPage;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddPackages;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The team fixture classes are annotated {@code @Entity} rather than {@code @AggregateRoot} — this
 * test pins what that stereotype means for reads and writes end-to-end:
 *
 * <ul>
 *   <li>the bare entity field {@code Player.team} is a NON-composing Reference (an association):
 *       direct reads materialise it through the generated {@code PlayerRowMapper.partial_Team}
 *       helper as an id-only partial, with every other column left null;</li>
 *   <li>the entity collection {@code Team.players} keeps its stereotype-independent read loader —
 *       pinned to the ACTUAL materialisation the framework returns;</li>
 *   <li>{@code @Entity} owners get NO composition write cascade, because that cascade is gated on
 *       {@code @AggregateRoot}: {@code teams.update} with an empty in-memory collection must leave
 *       player rows untouched;</li>
 *   <li>runtime {@code Order} through the collection ({@code _Team.players.name}) sorts by an
 *       aggregate subquery over the relation rather than by a plain joined column, a method-name
 *       finder predicate traverses the same relation as an EXISTS semi-join so the root row cannot
 *       fan out, a CURSOR-mode paged read executes the join-QUALIFIED seek predicate, and the cursor
 *       WALK completes: page 2 is requested from page 1's {@code nextPageRequest()} over the
 *       MIN(player.name)+root-id keyset.</li>
 * </ul>
 */
@EnableAutoWeld
@AddPackages(Team.class)
@AddBeanClasses({DataSourceProducer.class,
                 io.github.fludakit.jdbc.cdi.JdbcClientProducer.class,
                 io.github.fludakit.jdbc.cdi.ConverterRegistryProducer.class})
class TeamEntityTest {

    @Inject TeamRepository teams;
    @Inject PlayerRepository players;

    @Test
    void bareReferenceInsideEntityMaterialisesIdOnlyPartial() {
        Team saved = teams.save(team("Falcons"));
        Player striker = players.save(player("Ramos", saved));

        Player back = players.findById(striker.getId()).orElseThrow();
        assertThat(back.getTeam()).isNotNull();
        assertThat(back.getTeam().getId()).isEqualTo(saved.getId());
        assertThat(back.getTeam().getName())
                .as("bare entity reference inside @Entity materialises as an association (id-only)")
                .isNull();
    }

    @Test
    void entityCollectionOnEntityOwnerStillHydratesOnRead() {
        Team saved = teams.save(team("Falcons"));
        players.save(player("Ramos", saved));
        players.save(player("Navas", saved));

        Team back = teams.findById(saved.getId()).orElseThrow();
        // Pin to ACTUAL behavior: the read loader is stereotype-independent and materialises each
        // child through PlayerRowMapper — the child's OWN columns come back FULL (id, name), while
        // its non-composing Reference rides along as an id-only partial (non-null id, every other
        // column null), with NO nested children beneath it (depth-1).
        assertThat(back.getPlayers()).extracting(Player::getName)
                .containsExactlyInAnyOrder("Ramos", "Navas");
        assertThat(back.getPlayers()).allSatisfy(p -> {
            assertThat(p.getTeam()).isNotNull();
            assertThat(p.getTeam().getId()).isEqualTo(saved.getId());
            assertThat(p.getTeam().getName()).isNull();
            assertThat(p.getTeam().getPlayers()).isEmpty();
        });
    }

    @Test
    void updateOnEntityOwnerDoesNotCascadeEntityCollectionWrites() {
        Team saved = teams.save(team("Falcons"));
        Player striker = players.save(player("Ramos", saved));

        // Stereotype gate: composition write cascade is reserved for @AggregateRoot owners, and
        // Team is @Entity, so it NEVER cascades its entity collection on write.
        // Fresh root, same id, EMPTY in-memory collection → update must not delete player rows.
        Team fresh = new Team();
        fresh.setId(saved.getId());
        fresh.setName("Falcons II");
        teams.update(fresh);

        assertThat(players.findById(striker.getId())).isPresent();
        Team back = teams.findById(saved.getId()).orElseThrow();
        assertThat(back.getName()).isEqualTo("Falcons II");
        assertThat(back.getPlayers()).extracting(Player::getName).containsExactly("Ramos");
    }

    @Test
    void runtimeOrderThroughEntityCollectionContributesJoin() {
        Team borussia = teams.save(team("Borussia"));
        Team ajax = teams.save(team("Ajax"));
        Team empty = teams.save(team("Empty"));
        players.save(player("Zeta", borussia));
        players.save(player("Alpha", ajax));

        Page<Team> page = teams.findAll(
                PageRequest.ofPage(1).size(10), Order.by(_Team.players.name.asc()));

        // LEFT JOIN semantics: the childless team survives; join owners come back ordered by the
        // joined player's name ("Alpha" before "Zeta").
        List<String> names = page.content().stream().map(Team::getName).toList();
        assertThat(names).contains(empty.getName());
        int ajaxAt = names.indexOf("Ajax");
        int borussiaAt = names.indexOf("Borussia");
        assertThat(ajaxAt).isNotNegative();
        assertThat(borussiaAt).isNotNegative();
        assertThat(ajaxAt).isLessThan(borussiaAt);
    }

    @Test
    void methodNameFinderThroughEntityCollectionFiltersByJoinedPredicate() {
        Team falcons = teams.save(team("Falcons"));
        Team eagles = teams.save(team("Eagles"));
        players.save(player("Ramos", falcons));
        players.save(player("Silva", eagles));

        List<Team> found = teams.findByPlayersName("Ramos");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(falcons.getId());
        assertThat(found.get(0).getName()).isEqualTo("Falcons");
    }

    @Test
    void cursorModeRelationSortSeeksOnJoinQualifiedPredicate() {
        // End-to-end proof that the seek predicate follows the same qualification rule as the select
        // list: in CURSOR mode both team and player carry a "name" column, so a BARE seek term on
        // the joined path would be an ambiguous-column SQL failure at parse time — the request
        // below must EXECUTE.
        Team falcons = teams.save(team("Falcons"));
        Team eagles = teams.save(team("Eagles"));
        players.save(player("Navas", falcons));
        players.save(player("Ramos", eagles));

        // The keyset is the two-term [aggregate sort key, appended root-id tiebreak] shape, so the
        // cursor must carry two keys; a one-key cursor is a keyset/request mismatch, not a short read.
        PageRequest beyond = PageRequest.ofPage(1).size(10)
                .afterCursor(PageRequest.Cursor.forKey("Zzz", 0L));
        CursoredPage<Team> page = teams.orderedByPlayerName(beyond);
        // Cursor beyond the whole keyset: the qualified ("player"."name" > :cursor0) conjunct ran
        // without the ambiguity error and matched nothing.
        assertThat(page.content()).isEmpty();
    }

    @Test
    void cursorModeRelationSortWalksTwoPages() {
        // The REAL cursor walk (a reworked placeholder Pin earlier stood in for it): cursor-mode
        // page 1 materialises, its cursor is built from the aggregate MIN(player.name) key plus the
        // root-id tiebreak, and page 2 CONTINUES from that cursor (nextPageRequest is a CURSOR_NEXT
        // request, not an offset).
        Team falcons = teams.save(team("Falcons"));
        players.save(player("zeta", falcons));
        players.save(player("alpha", falcons));   // MIN = "alpha"
        Team hawks = teams.save(team("Hawks"));
        players.save(player("beta", hawks));      // MIN = "beta"

        // Jakarta Data 1.0.1 has no withCursor()/mode(CURSOR) setter — cursor mode is entered via
        // afterCursor, mirroring cursorModeRelationSortSeeksOnJoinQualifiedPredicate. The key is
        // the two-term [aggregate sort key, appended root-id tiebreak] shape, seeded below every
        // MIN ("A" < "alpha", id > 0) so page 1 starts at the head of the keyset.
        CursoredPage<Team> first = teams.orderedByPlayerName(
                PageRequest.ofPage(1).size(1).afterCursor(PageRequest.Cursor.forKey("A", 0L)));
        assertThat(first.content()).extracting(Team::getName).containsExactly("Falcons");

        CursoredPage<Team> second = teams.orderedByPlayerName(first.nextPageRequest());
        assertThat(second.content()).extracting(Team::getName).containsExactly("Hawks");
    }

    private static Team team(String name) {
        Team t = new Team();
        t.setName(name);
        return t;
    }

    private static Player player(String name, Team team) {
        Player p = new Player();
        p.setName(name);
        p.setTeam(team);
        return p;
    }
}
