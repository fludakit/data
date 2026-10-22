package io.github.fludakit.data.metadata;

/**
 * Generates unique table aliases following the pattern {@code tableName_index}.
 *
 * <p>Each instance maintains a global counter to ensure all aliases are unique across
 * all table references in a query plan. A fresh instance should be created per query plan.</p>
 */
public final class TableAlias {
    private int counter = 0;

    /** Generates the next alias for the given table name. */
    public String next(String tableName) {
        int index = counter++;
        return tableName + "_" + index;
    }

    /** Returns the root alias (first alias for the root table). */
    public String root(String tableName) {
        return next(tableName);
    }
}
