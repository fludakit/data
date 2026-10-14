package io.github.fludakit.data.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The edge cases are {@link JoinEdge.ToOne} and {@link JoinEdge.ToMany}, built from two
 * {@link ColumnRef} endpoints. What is pinned here is the pairing: which endpoint is the
 * {@link ColumnRef.ForeignKey} — a to-one reference crosses its OWN FK, a collection is reached
 * through the FK stored on the child — and that the four derived names come from that pairing
 * instead of being a second copy of it.
 */
class JoinEdgeTest {

    private static final JoinEdge.ToOne AUTHOR =
            new JoinEdge.ToOne(new ColumnRef.ForeignKey("author_id", "book"),
                    new ColumnRef.Simple("id", "author"));

    private static final JoinEdge.ToMany LINES =
            new JoinEdge.ToMany(new ColumnRef.Simple("id", "order"),
                    new ColumnRef.ForeignKey("order_id", "line"));

    @Test
    void aToOneEdgeHoldsTheForeignKeyOnItsOwnerEndpoint() {
        assertEquals(new ColumnRef.ForeignKey("author_id", "book"), AUTHOR.owner());
        assertEquals(new ColumnRef.Simple("id", "author"), AUTHOR.child());
    }

    @Test
    void aToManyEdgeHoldsTheForeignKeyOnItsChildEndpoint() {
        assertEquals(new ColumnRef.Simple("id", "order"), LINES.owner());
        assertEquals(new ColumnRef.ForeignKey("order_id", "line"), LINES.child());
    }

    @Test
    void theFourNamesAreDerivedFromTheEndpointsNotStoredBesideThem() {
        assertEquals("book", AUTHOR.ownerTable());
        assertEquals("author_id", AUTHOR.ownerColumn());
        assertEquals("author", AUTHOR.childTable());
        assertEquals("id", AUTHOR.childColumn());

        assertEquals("order", LINES.ownerTable());
        assertEquals("id", LINES.ownerColumn());
        assertEquals("line", LINES.childTable());
        assertEquals("order_id", LINES.childColumn());
    }

    @Test
    void anEdgeIsEqualToAnotherBuiltFromTheSameEndpoints() {
        assertEquals(AUTHOR, new JoinEdge.ToOne(new ColumnRef.ForeignKey("author_id", "book"),
                new ColumnRef.Simple("id", "author")));
        assertEquals(List.of(LINES), List.of(new JoinEdge.ToMany(new ColumnRef.Simple("id", "order"),
                new ColumnRef.ForeignKey("order_id", "line"))));
    }

    @Test
    void aTablelessEndpointIsRejectedRatherThanRenderedAsJoinNull() {
        assertThrows(NullPointerException.class,
                () -> new JoinEdge.ToOne(new ColumnRef.ForeignKey("author_id", null),
                        new ColumnRef.Simple("id", "author")));
        assertThrows(NullPointerException.class,
                () -> new JoinEdge.ToMany(new ColumnRef.Simple("id", "order"),
                        new ColumnRef.ForeignKey("order_id", null)));
    }
}
