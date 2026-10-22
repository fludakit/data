package io.github.fludakit.data.metadata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates unique table aliases following the pattern {@code tableName_index}.
 *
 * <p>Thread-safe: each instance maintains its own counter. A fresh instance should be created
 * per query plan.</p>
 */
public final class TableAlias {
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    /** Generates the next alias for the given table name. */
    public String next(String tableName) {
        int index = counters.getOrDefault(tableName, 0);
        counters.put(tableName, index + 1);
        return tableName + "_" + index;
    }

    /** Returns the root alias (first alias for the root table). */
    public String root(String tableName) {
        return next(tableName);
    }
}
