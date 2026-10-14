package io.github.fludakit.data.metadata;

import java.util.Objects;

/**
 * ONE relation edge between two physical tables: <em>owner table/column → child table/column</em>.
 *
 * <p>This is the counterpart of {@link ColumnRef}: a {@code ColumnRef} answers "where does this
 * column live", an edge answers "how do I reach the table that column lives on". They are different
 * questions and they are deliberately different types — a column placement never carries
 * table-to-table knowledge, and an edge never carries a column of its own.</p>
 *
 * <p>The two cases exist because they permit different SQL. A {@link ToOne} edge may be spliced as
 * a {@code LEFT JOIN}: it adds at most one row per owner row, so LIMIT and COUNT still count owners.
 * A {@link ToMany} edge may NOT — it adds N rows per owner row, which would move LIMIT and COUNT
 * onto join rows — so it must be rendered as an {@code EXISTS} conjunct or an aggregate subquery
 * instead. Making that distinction a type rather than a flag is the point of having two cases.</p>
 *
 * <p>An edge is built from two {@link ColumnRef} endpoints rather than four loose strings, and the
 * ENDPOINT TYPES SAY WHICH SIDE HOLDS THE FOREIGN KEY — the one fact that distinguishes the cases.
 * {@code ToOne} takes {@link ColumnRef.ForeignKey} first: a to-one reference crosses its OWN FK,
 * stored on the owner table. {@code ToMany} takes it second: a collection is reached through the FK
 * stored on the CHILD table. That was previously only javadoc; a caller could pass the identifier
 * and the FK the wrong way round, or pair a table with the other side's column, and it compiled.
 * Now it does not.</p>
 *
 * <p>The four string accessors are derived from {@link #owner()} and {@link #child()}, so a renderer
 * still reads {@code join.childTable()} — one source of truth, no second copy of the pairing.</p>
 */
public sealed interface JoinEdge {

    /** The endpoint on the side the edge starts from. */
    ColumnRef owner();

    /** The endpoint on the side the edge reaches. */
    ColumnRef child();

    /** The table the edge starts from: {@link #owner()}'s table. */
    default String ownerTable() {
        return owner().table();
    }

    /** The column on {@link #ownerTable()} that participates: {@link #owner()}'s column. */
    default String ownerColumn() {
        return owner().column();
    }

    /** The table the edge reaches: {@link #child()}'s table. */
    default String childTable() {
        return child().table();
    }

    /** The column on {@link #childTable()} that participates: {@link #child()}'s column. */
    default String childColumn() {
        return child().column();
    }

    /**
     * A to-one edge: {@code owner.fkColumn = child.idColumn}, where the OWNER endpoint is the
     * foreign key. Splices as a {@code LEFT JOIN}.
     */
    record ToOne(ColumnRef.ForeignKey owner, ColumnRef.Simple child) implements JoinEdge {

        public ToOne {
            requireEndpoints(owner, child);
        }
    }

    /**
     * A to-many edge: {@code owner.idColumn = child.fkColumn}, where the CHILD endpoint is the
     * foreign key pointing back. Must be rendered as {@code EXISTS} or an aggregate subquery, never
     * spliced as a join.
     */
    record ToMany(ColumnRef.Simple owner, ColumnRef.ForeignKey child) implements JoinEdge {

        public ToMany {
            requireEndpoints(owner, child);
        }
    }

    private static void requireEndpoints(ColumnRef owner, ColumnRef child) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(child, "child");
        // A column ref may be table-less (a value object analysed on its own); an edge may not —
        // it would render LEFT JOIN "null". Reject it here rather than at every render site.
        Objects.requireNonNull(owner.table(), "owner table");
        Objects.requireNonNull(child.table(), "child table");
        Objects.requireNonNull(owner.column(), "owner column");
        Objects.requireNonNull(child.column(), "child column");
    }
}
