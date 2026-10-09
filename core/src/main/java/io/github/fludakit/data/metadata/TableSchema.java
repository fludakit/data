package io.github.fludakit.data.metadata;

import java.util.List;

/**
 * Physical SQL shape of one table. Logical entity paths and relationship classification belong to
 * the entity mapping that resolves paths to these table-local columns.
 */
public interface TableSchema {

    /** Physical table name, or {@code null} when the mapped value has no table of its own. */
    String tableName();

    /** Physical identifier column, or {@code null} when the table has no identifier. */
    String idColumn();

    /** Physical columns owned by this table, in declaration order. */
    List<ColumnRef> columns();
}
