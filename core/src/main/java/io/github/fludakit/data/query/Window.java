package io.github.fludakit.data.query;

/**
 * The row window of a {@link QueryDefinition.Read}.
 *
 * <p>Sealed so a method cannot carry two windows at once: a static {@code First<n>} cap, a
 * {@code Limit} parameter, or a {@code PageRequest} (offset or cursor). Illegal combinations are
 * rejected before a definition is built.</p>
 */
public sealed interface Window {

    /**
     * No paging parameter. Optional static cap from {@code First<n>} (or {@link LimitSpec#none()}).
     */
    record None(LimitSpec limit) implements Window {
        public None {
            limit = limit == null ? LimitSpec.none() : limit;
        }

        public static None unpaged() {
            return new None(LimitSpec.none());
        }
    }

    /** A {@code jakarta.data.Limit} parameter at {@code parameterIndex}. */
    record Limit(int parameterIndex) implements Window {
    }

    /** Offset paging via a {@code PageRequest} at {@code pageRequestIndex}. */
    record Offset(int pageRequestIndex) implements Window {
    }

    /** Cursor (keyset) paging via a {@code PageRequest} at {@code pageRequestIndex}. */
    record Cursor(int pageRequestIndex) implements Window {
    }
}
