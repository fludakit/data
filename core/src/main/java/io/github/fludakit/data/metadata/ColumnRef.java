package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical column placement in one table, with the join chain needed to reach its owner.
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
}
