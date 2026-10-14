package io.github.fludakit.data.metadata;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ColumnPlacement} is the answer to "where is this attribute's column, and how do I reach
 * it" — the two halves that {@link ColumnRef} used to carry inside one type. These tests pin the
 * properties that make it safe to hand to a renderer: the leaf and its edges arrive as one value so
 * they cannot be mismatched, the edges are already {@link JoinEdge}s, and the whole thing is
 * immutable.
 */
class ColumnPlacementTest {

    private static final ColumnRef LEAF = new ColumnRef.Simple("name", "author");
    private static final JoinEdge EDGE = new JoinEdge.ToOne(new ColumnRef.ForeignKey("author_id", "book"),
                                                 new ColumnRef.Simple("id", "author"));

    @Test
    void aRootColumnHasNoEdges() {
        ColumnPlacement placement = new ColumnPlacement(new ColumnRef.Simple("title", "book"));
        assertThat(placement.local()).isTrue();
        assertThat(placement.edges()).isEmpty();
        assertThat(placement.leaf().column()).isEqualTo("title");
    }

    @Test
    void aTraversedColumnCarriesTheEdgeThatReachesIt() {
        ColumnPlacement placement = new ColumnPlacement(LEAF, List.of(EDGE));
        assertThat(placement.local()).isFalse();
        assertThat(placement.edges()).containsExactly(EDGE);
        assertThat(placement.leaf()).isEqualTo(LEAF);
    }

    @Test
    void edgesKeepTheirCardinalityAndWalkOrder() {
        ColumnPlacement placement = new ColumnPlacement(new ColumnRef.Simple("name", "country"), List.of(
                new JoinEdge.ToMany(new ColumnRef.Simple("id", "profile"),
                        new ColumnRef.ForeignKey("profile_id", "work_experience")),
                new JoinEdge.ToOne(new ColumnRef.ForeignKey("country_id", "work_experience"),
                        new ColumnRef.Simple("id", "country"))));
        assertThat(placement.edges().get(0)).isInstanceOf(JoinEdge.ToMany.class);
        assertThat(placement.edges().get(1)).isInstanceOf(JoinEdge.ToOne.class);
    }

    @Test
    void edgesAreCopiedSoTheValueIsImmutable() {
        List<JoinEdge> edges = new ArrayList<>();
        edges.add(EDGE);
        ColumnPlacement placement = new ColumnPlacement(LEAF, edges);
        edges.clear();
        assertThat(placement.edges()).hasSize(1);

        assertThatThrownBy(() -> placement.edges().add(new JoinEdge.ToOne(new ColumnRef.ForeignKey("b", "a"),
                                                               new ColumnRef.Simple("d", "c"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNullEdgeListIsTreatedAsLocalAndANullLeafIsRejected() {
        assertThat(new ColumnPlacement(LEAF, null).local()).isTrue();
        assertThatThrownBy(() -> new ColumnPlacement(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("leaf");
    }

    @Test
    void theGenericWalkOnATypeMappingReturnsTheSameShape() {
        TypeMapping<?> order = new AggregateTypeMapping<>("test.Order", "orders",
                new TypeMapping.Prop.Id("id", "id", "java.lang.Long"),
                List.of(new TypeMapping.Prop.Scalar("status", "status", "java.lang.String"),
                        new TypeMapping.Prop.Reference("customer", "customer_id", "test.Customer",
                                new AggregateTypeMapping<>("test.Customer", "customer",
                                        new TypeMapping.Prop.Id("id", "id", "java.lang.Long"),
                                        List.of(new TypeMapping.Prop.Scalar("name", "name", "java.lang.String"))),
                                false, false)));
        assertEquals(new ColumnPlacement(new ColumnRef.Simple("status", "orders")),
                order.resolve("status"));
        assertEquals(new ColumnPlacement(new ColumnRef.ForeignKey("customer_id", "orders")),
                order.resolve("customer"));
        assertEquals(new ColumnPlacement(new ColumnRef.Simple("name", "customer"),
                        List.of(new JoinEdge.ToOne(new ColumnRef.ForeignKey("customer_id", "orders"),
                                        new ColumnRef.Simple("id", "customer")))),
                order.resolve("customer.name"));
    }

    @Test
    void anUnknownPathIsRejectedRatherThanDegradedToAColumn() {
        TypeMapping<?> book = new AggregateTypeMapping<>("test.Book", "book",
                new TypeMapping.Prop.Id("id", "id", "java.lang.Long"), List.of());
        assertThatThrownBy(() -> book.resolve("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown property 'nope'");
    }
}
