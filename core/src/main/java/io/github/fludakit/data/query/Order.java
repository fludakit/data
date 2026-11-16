package io.github.fludakit.data.query;

import java.util.List;

/**
 * How a {@link QueryDefinition.Read} orders its rows.
 *
 * <p>Static order is baked into the compiled statement. Dynamic order is a method parameter
 * ({@code Sort}/{@code Order}); the constant has no {@code ORDER BY} and the generated method
 * splices terms at runtime.</p>
 */
public sealed interface Order {

    /** No ordering. */
    record None() implements Order {
        public static final None INSTANCE = new None();
    }

    /** Declared sorts ({@code @OrderBy}, query-by-method-name {@code OrderBy}). */
    record Static(List<SortSpec> sorts) implements Order {
        public Static {
            sorts = List.copyOf(sorts);
        }

        public static Static of(List<SortSpec> sorts) {
            return sorts == null || sorts.isEmpty() ? new Static(List.of()) : new Static(sorts);
        }
    }

    /** A {@code Sort} or {@code Order} method parameter at {@code parameterIndex}. */
    record Dynamic(int parameterIndex) implements Order {
    }
}
