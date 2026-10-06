package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.EntityDescriptor;
import io.github.fludakit.data.metadata.EntitySchema;
import io.github.fludakit.data.metadata.JoinRef;
import io.github.fludakit.data.metamodel.EmbeddedAttributeRecord;
import io.github.fludakit.data.metamodel.IdAttributeRecord;
import io.github.fludakit.data.metamodel.TextColumnAttributeRecord;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;
import jakarta.data.metamodel.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlBuilderTest {

    static class Customer { }

    static class Profile { }

    static class Address { }

    static EntityDescriptor<Customer> customer() {
        return new EntityDescriptor<>() {
            @Override public Class<Customer> entityClass() { return Customer.class; }
            @Override public String tableName() { return "customer"; }
            @Override public IdAttributeRecord<Customer> id() {
                return new IdAttributeRecord<>("id", "id");
            }
            @Override public List<Attribute<Customer>> attributes() {
                return List.of(new TextColumnAttributeRecord<>("name", "name"));
            }
        };
    }

    /** Profile with an embedded Address: the flattened leaf is a direct attribute member. */
    static EntityDescriptor<Profile> profile() {
        EntityDescriptor<Address> address = new EntityDescriptor<>() {
            @Override public Class<Address> entityClass() { return Address.class; }
            @Override public String tableName() { return null; }
            @Override public io.github.fludakit.data.metamodel.IdAttribute<Address> id() { return null; }
            @Override public List<Attribute<Address>> attributes() {
                return List.of(new TextColumnAttributeRecord<>("city", "city"));
            }
        };
        return new EntityDescriptor<>() {
            @Override public Class<Profile> entityClass() { return Profile.class; }
            @Override public String tableName() { return "profile"; }
            @Override public IdAttributeRecord<Profile> id() { return new IdAttributeRecord<>("id", "id"); }
            @Override public List<Attribute<Profile>> attributes() {
                return List.of(
                        new EmbeddedAttributeRecord<Profile, Address>("address", address),
                        new TextColumnAttributeRecord<Profile>("address.city", "address_city"));
            }
        };
    }

    @Test
    void rendersSelectWithWhereAndOrder() {
        String sql = new SqlBuilder().selectBy(customer(),
                List.of(Predicate.of("name", Operator.EQ, "name")),
                List.of(SortSpec.of("id", true, false)), 0, null);
        assertThat(sql).isEqualTo("SELECT \"id\", \"name\" FROM \"customer\" WHERE \"name\" = :name ORDER BY \"id\" ASC");
    }

    @Test
    void orderByResolvesEmbeddedDotPathToFlattenedColumn() {
        String sql = new SqlBuilder().selectBy(profile(), List.of(),
                List.of(SortSpec.of("address.city", true, false)), 0, null);
        assertThat(sql).endsWith("ORDER BY \"address_city\" ASC");
        assertThat(SqlBuilder.resolveColumn(profile(), "address.city")).isEqualTo("address_city");
    }

    @Test
    void orderByRejectsUnknownProperty() {
        assertThatThrownBy(() -> new SqlBuilder().orderBy(customer(), List.of(SortSpec.of("nope", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown sort property: nope");
    }

    @Test
    void orderByRejectsEmbeddedContainerName() {
        // The container itself carries no column: sorting by "address" must not silently pass.
        assertThatThrownBy(() -> new SqlBuilder().orderBy(profile(), List.of(SortSpec.of("address", true, false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown sort property: address");
    }

    @Test
    void rendersInsert() {
        assertThat(new SqlBuilder().insert(customer()))
                .isEqualTo("INSERT INTO \"customer\" (\"id\", \"name\") VALUES (:id, :name)");
    }

    // ---- Task 3: SqlBuilder renders joins from ColumnRef join metadata ----

    /** Minimal engine-facing schema over a root table for join-clause rendering. */
    static EntitySchema rootSchema(String table, List<ColumnRef> columns) {
        return new EntitySchema() {
            @Override public String tableName() { return table; }
            @Override public String idColumn() { return "id"; }
            @Override public List<ColumnRef> columns() { return columns; }
        };
    }

    @Test
    void joinClauseEmitsSingleLeftJoinAndQualifiesLeafByLastAlias() {
        ColumnRef joined = new ColumnRef("amount", "orderItems.amount", null,
                List.of(new JoinRef("order_item", "orderItems", "id", "order_id")));
        EntitySchema rootMd = rootSchema("order", List.of(joined));
        assertEquals(" LEFT JOIN \"order_item\" AS \"orderItems\" ON \"order\".\"id\" = \"orderItems\".\"order_id\"",
                SqlBuilder.joinClause(rootMd, List.of(joined)));
        assertEquals("\"orderItems\".\"amount\"", SqlBuilder.qualify(joined, "order"));
    }

    @Test
    void joinClauseChainsQualifiersOverTwoHops() {
        // fk hop (root -> "primary") then a second hop (-> "players"): each qualifier is the
        // PREVIOUS join's alias, regardless of path grouping.
        ColumnRef twoHop = new ColumnRef("name", "primary.players.name", null, List.of(
                new JoinRef("player", "primary", "primary_player_id", "id"),
                new JoinRef("team", "players", "team_id", "id")));
        String clause = SqlBuilder.joinClause(rootSchema("game", List.of(twoHop)), List.of(twoHop));
        assertEquals(" LEFT JOIN \"player\" AS \"primary\" ON \"game\".\"primary_player_id\" = \"primary\".\"id\""
                        + " LEFT JOIN \"team\" AS \"players\" ON \"primary\".\"team_id\" = \"players\".\"id\"",
                clause);
        assertThat(clause).contains("\"primary\".\"team_id\" = \"players\".\"id\"");
        assertEquals("\"players\".\"name\"", SqlBuilder.qualify(twoHop, "game"));
    }

    @Test
    void joinClauseDedupesByAliasFirstOccurrenceWins() {
        JoinRef head = new JoinRef("order_item", "orderItems", "id", "order_id");
        ColumnRef a = new ColumnRef("amount", "orderItems.amount", null, List.of(head));
        ColumnRef b = new ColumnRef("qty", "orderItems.qty", null,
                List.of(head, new JoinRef("item", "item", "item_id", "id")));
        ColumnRef dup = new ColumnRef("price", "orderItems.price", null,
                List.of(new JoinRef("other_table", "orderItems", "x", "y")));
        String clause = SqlBuilder.joinClause(rootSchema("order", List.of(a, b, dup)), List.of(a, dup, b));
        assertEquals(" LEFT JOIN \"order_item\" AS \"orderItems\" ON \"order\".\"id\" = \"orderItems\".\"order_id\""
                        + " LEFT JOIN \"item\" AS \"item\" ON \"orderItems\".\"item_id\" = \"item\".\"id\"",
                clause);
    }

    @Test
    void joinClauseIsEmptyForJoinFreeRefs() {
        ColumnRef local = new ColumnRef("total", "total");
        assertEquals("", SqlBuilder.joinClause(rootSchema("order", List.of(local)), List.of(local)));
    }

    @Test
    void qualifyResolvesLocalRefsByTableThenRootTable() {
        assertEquals("\"order\".\"total\"", SqlBuilder.qualify(new ColumnRef("total", "total"), "order"));
        assertEquals("\"other\".\"total\"",
                SqlBuilder.qualify(new ColumnRef("total", "total", "other", List.of()), "order"));
        assertThatThrownBy(() -> SqlBuilder.qualify(new ColumnRef("total", "total")))
                .isInstanceOf(IllegalStateException.class);
        assertEquals("\"other\".\"total\"", SqlBuilder.qualify(new ColumnRef("total", "total", "other", List.of())));
    }

    @Test
    void renderOverloadQualifiesPredicateColumnWithRef() {
        ColumnRef joined = new ColumnRef("amount", "orderItems.amount", null,
                List.of(new JoinRef("order_item", "orderItems", "id", "order_id")));
        assertEquals("\"orderItems\".\"amount\" = :orderItems_amount",
                SqlBuilder.render(Predicate.of("orderItems.amount", Operator.EQ, "orderItems_amount"), joined));
        // Explicit non-root table, no joins.
        ColumnRef foreign = new ColumnRef("total", "other.total", "other", List.of());
        assertEquals("\"other\".\"total\" = :total",
                SqlBuilder.render(Predicate.of("other.total", Operator.EQ, "total"), foreign));
        // Fully-local ref renders unqualified, byte-identical to the legacy path.
        ColumnRef local = new ColumnRef("total", "total");
        assertEquals("\"total\" = :total",
                SqlBuilder.render(Predicate.of("total", Operator.EQ, "total"), local));
    }

    @Test
    void orderByOverloadQualifiesResolvedRefs() {
        ColumnRef joined = new ColumnRef("amount", "orderItems.amount", null,
                List.of(new JoinRef("order_item", "orderItems", "id", "order_id")));
        ColumnRef local = new ColumnRef("total", "total");
        assertEquals(" ORDER BY \"orderItems\".\"amount\"", new SqlBuilder().orderBy(List.of(joined)));
        assertEquals(" ORDER BY \"order\".\"total\"", new SqlBuilder().orderBy(List.of(local), "order"));
        assertEquals("", new SqlBuilder().orderBy(List.<ColumnRef>of()));
    }
}
