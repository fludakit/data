package io.github.fludakit.data.metadata;

/**
 * The physical placement of one column: its name and the table that owns it. This is pure schema
 * metadata — a column's physical identity never changes across queries. The query-scoped
 * {@link ColumnRef} adds a per-query alias and join chain on top of this placement.
 *
 * @param name the physical column name
 * @param table the physical table containing the column, or {@code null} when the column has no
 *              owning table of its own
 */
public record Column(String name, String table) {
}
