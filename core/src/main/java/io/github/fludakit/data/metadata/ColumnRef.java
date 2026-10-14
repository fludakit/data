package io.github.fludakit.data.metadata;

/**
 * Where a column physically lives: <em>which column, on which table</em>. Nothing else.
 *
 * <p>This is deliberately the whole of it. A {@code ColumnRef} carries NO table-to-table
 * relationship knowledge — that is {@link JoinEdge}'s job — and no notion of how a column was
 * reached — that is {@link ColumnPlacement}'s. It used to carry all three, which is why it needed
 * {@code targetTable}/{@code childTable}/{@code ownerColumn} fields a column placement has no
 * business knowing about, and why {@code table()} answered two different questions depending on
 * which case you held.</p>
 *
 * <p>Both cases are placements ON the table named by {@link #table()}; they differ only in what the
 * column MEANS, not in where it is or how it is rendered. {@link ForeignKey} exists so a caller can
 * tell "this column is a to-one reference's own FK" from "this column is a scalar" without a
 * name-shape guess, and so the generated {@code resolve} can hand back {@code author} as a
 * placement with no edge to cross — reading the FK needs no join.</p>
 */
public sealed interface ColumnRef {

    /** The physical column name. */
    String column();

    /** The physical table the column lives on, or {@code null} for a table-less value object. */
    String table();

    /**
     * An ordinary column: identifier, scalar, or a flattened embedded value-object leaf.
     *
     * @param column     the physical column name
     * @param ownerTable the physical table containing the column
     */
    record Simple(String column, String ownerTable) implements ColumnRef {
        @Override
        public String table() {
            return ownerTable;
        }
    }

    /**
     * A to-one foreign key — physically ON this table, so reading it needs no join. Traversing it
     * does, and that traversal is a {@link JoinEdge}, not part of this placement.
     *
     * @param column     the physical FK column name
     * @param ownerTable the physical table carrying the FK
     */
    record ForeignKey(String column, String ownerTable) implements ColumnRef {
        @Override
        public String table() {
            return ownerTable;
        }
    }
}
