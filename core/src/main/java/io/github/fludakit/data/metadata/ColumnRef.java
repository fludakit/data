package io.github.fludakit.data.metadata;

/**
 * A physical column placement in the aggregate tree, classified by its relation role.
 *
 * <p>Each case carries the information {@code SqlBuilder} needs to qualify and join the column; the
 * SQL alias for a table is always the table name itself, applied at assembly time.</p>
 */
public sealed interface ColumnRef {

    /** The physical column name. */
    String column();

    /** The physical table the column lives on. */
    String table();

    /**
     * A plain column on its own table: an identifier, scalar, or a flattened embedded value-object
     * leaf. Carries no relation edge.
     *
     * @param column     the physical column name
     * @param ownerTable the physical table containing the column
     */
    record Simple(String column, String ownerTable) implements ColumnRef {
        @Override public String table() { return ownerTable; }
    }

    /**
     * The to-one foreign-key column in the OWNER table referencing an external entity. Doubles as the
     * join edge {@code ownerTable.fkColumn = targetTable.targetIdColumn} when a path traverses it.
     *
     * @param fkColumn       the FK column in the owner table
     * @param ownerTable     the table carrying the FK
     * @param targetTable    the referenced entity's table
     * @param targetIdColumn the referenced entity's identifier column
     */
    record Reference(String fkColumn, String ownerTable, String targetTable,
                     String targetIdColumn) implements ColumnRef {
        @Override public String column() { return fkColumn; }
        @Override public String table() { return ownerTable; }
    }

    /**
     * The to-many foreign-key column in a CHILD table pointing back to the owner. Encodes the join
     * edge {@code ownerTable.ownerIdColumn = childTable.fkColumn}.
     *
     * @param fkColumn      the FK column in the child table
     * @param childTable    the child table carrying the FK
     * @param ownerTable    the owner table referenced by the FK
     * @param ownerIdColumn the owner table's identifier column
     */
    record Collection(String fkColumn, String childTable, String ownerTable,
                      String ownerIdColumn) implements ColumnRef {
        @Override public String column() { return fkColumn; }
        @Override public String table() { return childTable; }
    }
}
