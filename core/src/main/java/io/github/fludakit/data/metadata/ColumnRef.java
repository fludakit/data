package io.github.fludakit.data.metadata;

import java.util.ArrayList;
import java.util.List;

/**
 * A resolvable property path and where it physically lives: its column, the table owning it
 * ({@code null} = the root table, or — when {@code joins} is non-empty — the LAST join's alias),
 * and the ordered joins the path contributes.
 */
public record ColumnRef(String column, String path, String table, List<JoinRef> joins) {

    public ColumnRef { joins = joins == null ? List.of() : List.copyOf(joins); }

    /** A local column of the owner table: no join, no table override. */
    public ColumnRef(String column, String path) { this(column, path, null, List.of()); }

    /** Rebase this ref under an outer navigation segment: full path, prepended join edge. */
    public ColumnRef withPrefix(String head, JoinRef outerJoin) {
        List<JoinRef> all = new ArrayList<>(joins.size() + 1);
        all.add(outerJoin);
        all.addAll(joins);
        return new ColumnRef(column, head + "." + path, table, List.copyOf(all));
    }
}
