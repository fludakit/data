package io.github.fludakit.data.metadata;

/**
 * A physical foreign-key edge between two tables:
 * {@code ownerTableName.ownerColumn -> childTableName.childColumn}. This is pure schema metadata
 * with no query aliases; the query-scoped {@link JoinRef} adds the per-query owner and child
 * aliases on top of this edge.
 *
 * @param ownerTableName physical table on the owning side
 * @param ownerColumn join column on the owner table
 * @param childTableName physical table on the child side
 * @param childColumn join column on the child table
 */
public record Relationship(String ownerTableName, String ownerColumn,
                           String childTableName, String childColumn) {
}
