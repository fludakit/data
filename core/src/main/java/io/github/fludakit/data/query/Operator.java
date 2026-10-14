package io.github.fludakit.data.query;

/**
 * The comparison a predicate applies, as the parser resolved it.
 *
 * <p>The SQL each operator renders to lives in exactly one place — the operator switches in
 * {@code QueryCompiler} — and deliberately not here. A single token cannot carry the shapes:
 * {@code Between} renders {@code col BETWEEN :lo AND :hi}, {@code In} renders
 * {@code col IN (:x0, :x1)}, and {@code IsNull} renders {@code col IS NULL} with no bound value at
 * all. A token field here would be a second copy of that table, free to drift from it — and it did
 * drift: the field was carrying {@code "LIKE"} for four operators whose rendering differs only in
 * how the bound value is decorated, which is {@link LikePattern}'s job, not the token's.
 */
public enum Operator {
    EQ, NOT_EQ, LESS_THAN, LESS_THAN_EQUAL,
    GREATER_THAN, GREATER_THAN_EQUAL, LIKE,
    NOT_LIKE, BETWEEN, NOT_BETWEEN,
    IN, NOT_IN,
    IS_NULL, IS_NOT_NULL, TRUE, FALSE,
    CONTAINS, STARTS_WITH, ENDS_WITH,
    NOT_CONTAINS, NOT_STARTS_WITH, NOT_ENDS_WITH,
    IGNORE_CASE_EQ;
}
