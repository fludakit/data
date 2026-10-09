package io.github.fludakit.data.metadata;

/**
 * One directed relationship edge between two tables in a rendered query.
 *
 * <p>The join condition is {@code ownerAlias.ownerColumn = childAlias.childColumn}; a null owner
 * alias means the root table is referenced by {@code ownerTableName}. Explicit owner and child
 * identities make every edge independently understandable rather than relying on list position.</p>
 *
 * @param ownerTableName physical table on the owning side
 * @param ownerAlias query alias for the owner table, or {@code null} for an unaliased root table
 * @param ownerColumn join column on the owner table
 * @param childTableName physical table on the child side
 * @param childAlias query alias assigned to the child table
 * @param childColumn join column on the child table
 */
public record JoinRef(String ownerTableName, String ownerAlias, String ownerColumn,
                      String childTableName, String childAlias, String childColumn) { }
