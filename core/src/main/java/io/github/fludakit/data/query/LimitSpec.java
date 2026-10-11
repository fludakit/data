package io.github.fludakit.data.query;

/**
 * A static row cap and offset for a query. {@code limit == 0} means no cap; {@code offset} is the
 * number of rows skipped before the cap applies.
 */
public record LimitSpec(int limit, int offset) {

    /** No cap and no offset. */
    public static LimitSpec none() {
        return new LimitSpec(0, 0);
    }
}
