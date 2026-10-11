package io.github.fludakit.data.metadata;

/**
 * One directed join edge in a rendered query: a physical {@link Relationship} (the foreign-key
 * edge {@code ownerTableName.ownerColumn -> childTableName.childColumn}) plus the query-scoped
 * owner and child aliases. The aliases are SQL-assembly state, not schema metadata —
 * {@link #relationship()} returns the pure physical edge without them.
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
                      String childTableName, String childAlias, String childColumn) {

    /** The physical foreign-key edge, without the query-scoped aliases. */
    public Relationship relationship() {
        return new Relationship(ownerTableName, ownerColumn, childTableName, childColumn);
    }
}
