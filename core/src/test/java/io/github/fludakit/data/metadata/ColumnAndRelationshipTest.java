package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the physical-vs-query split: {@link Column}/{@link Relationship} are the pure schema view, while {@link ColumnRef}/{@link JoinRef} add the query-scoped aliases and join chain. */
class ColumnAndRelationshipTest {

    @Test
    void columnRefExposesItsPhysicalColumnWithoutAliasOrJoins() {
        ColumnRef ref = new ColumnRef("amount", "order_item", "orderItems",
                List.of(new JoinRef("order", null, "id", "order_item", "orderItems", "order_id")));
        assertThat(ref.physicalColumn()).isEqualTo(new Column("amount", "order_item"));
    }

    @Test
    void localColumnHasNoOwnerTable() {
        assertThat(new ColumnRef("status").physicalColumn()).isEqualTo(new Column("status", null));
    }

    @Test
    void joinRefExposesItsPhysicalRelationshipWithoutAliases() {
        JoinRef join = new JoinRef("order", null, "id", "order_item", "orderItems", "order_id");
        assertThat(join.relationship())
                .isEqualTo(new Relationship("order", "id", "order_item", "order_id"));
    }
}
