package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * A physical column reached through a query-specific join chain. The physical identity — the column
 * name and its owning table — is stable across queries and is exposed alone as
 * {@link #physicalColumn()}; the query-scoped part is the {@link #ownerAlias()} and the ordered
 * {@link #joins()} (each a {@link JoinRef}) that reach the owner from the query's root table.
 *
 * @param column the physical column name in the owning table
 * @param ownerTableName the physical table containing the column
 * @param ownerAlias the query alias for the owner table, or {@code null} when the table has no alias
 * @param joins the ordered edges from the query's root table to the owner table
 */
public record ColumnRef(String column, String ownerTableName, String ownerAlias, List<JoinRef> joins) {

    public ColumnRef {
        joins = joins == null ? List.of() : List.copyOf(joins);
    }

    /** The physical placement alone, without the query-scoped alias or join chain. */
    public Column physicalColumn() {
        return new Column(column, ownerTableName);
    }

    /** Creates a physical column whose owner is supplied by query context. */
    public ColumnRef(String column) {
        this(column, null, null, List.of());
    }

    /** Creates a root-table binding without a query alias. */
    public ColumnRef(String column, String ownerTableName) {
        this(column, ownerTableName, null, List.of());
    }

    /**
     * Carries this leaf binding through one relationship to the target table.
     *
     * @param join the edge from the current owner table to the target table
     * @return a table-local leaf binding with the edge appended to its join chain
     */
    public ColumnRef through(JoinRef join) {
        List<JoinRef> all = new ArrayList<>(joins.size() + 1);
        all.addAll(joins);
        all.add(join);
        return new ColumnRef(column, join.childTableName(), join.childAlias(), all);
    }

    /**
     * Prepends an edge to the front of this ref's join chain, keeping the leaf's owner table/alias
     * unchanged. Used when a resolved nested-path ref (already carrying its own root-to-leaf chain)
     * is reached through an additional outer relationship: the outer edge must come FIRST so the
     * chain reads root-to-leaf.
     *
     * @param join the edge from the outer owner table to this ref's current root table
     * @return this leaf binding with the edge prepended to its join chain
     */
    public ColumnRef prepend(JoinRef join) {
        List<JoinRef> all = new ArrayList<>(joins.size() + 1);
        all.add(join);
        all.addAll(joins);
        return new ColumnRef(column, ownerTableName, ownerAlias, all);
    }
}
