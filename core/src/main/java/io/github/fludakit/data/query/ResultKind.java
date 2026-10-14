package io.github.fludakit.data.query;

/**
 * What a repository method declares it gives back.
 *
 * <p>Two families. A <b>read</b> shape says how the matched rows are delivered. Jakarta Data 1.0
 * names {@link #SINGLE} (the entity), {@link #OPTIONAL} ({@code Optional<E>}), {@link #LIST}
 * ({@code List<E>}), {@link #ARRAY} ({@code E[]}), {@link #STREAM} ({@code Stream<E>}),
 * {@link #PAGE} and {@link #CURSORED_PAGE}; {@link #SET} is this implementation's own extension
 * ({@code Set<E>}, materialized in row order), as are the {@code Collection}/{@code Iterable}
 * declarations that publish {@link #LIST} because a {@code List} satisfies them.</p>
 *
 * <p>A <b>write</b> shape says what the caller gets after the statement runs: {@link #VOID}
 * (nothing), {@link #SINGLE} (the entity handed back, re-keyed when the database assigned its
 * identifier) or {@link #AFFECTED} (the number of rows the statement changed — a {@code long},
 * {@code int} or, for a delete, {@code boolean} return). {@link #COUNT} and {@link #EXISTS} are
 * their own shapes, fixed by the statement rather than by the declaration.</p>
 */
public enum ResultKind {
    SINGLE, OPTIONAL, LIST, SET, ARRAY, STREAM, PAGE, CURSORED_PAGE, COUNT, EXISTS, AFFECTED, VOID
}
