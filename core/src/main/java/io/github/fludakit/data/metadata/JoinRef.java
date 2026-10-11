package io.github.fludakit.data.metadata;

/**
 * One directed join edge between two tables: the physical foreign-key edge
 * {@code ownerTableName.ownerColumn -> childTableName.childColumn}.
 *
 * <p>The join condition is {@code ownerTableName.ownerColumn = childTableName.childColumn}. Explicit
 * owner and child identities make every edge independently understandable rather than relying on
 * list position; the SQL alias for a joined table is the table name itself, applied by
 * {@code SqlBuilder} at assembly time.</p>
 *
 * @param ownerTableName physical table on the owning side
 * @param ownerColumn join column on the owner table
 * @param childTableName physical table on the child side
 * @param childColumn join column on the child table
 */
public record JoinRef(String ownerTableName, String ownerColumn,
                      String childTableName, String childColumn) {
}
