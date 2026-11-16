package io.github.fludakit.data.sql;

/**
 * Whether {@link SqlBuilder#sql(SqlFragment)} leaves the rendered statement compact or runs it
 * through {@link SqlFormatter}.
 */
public enum SqlFormat {

    /** Single-line statement as the fragment rendered it. */
    COMPACT,

    /** Clause-per-line layout via {@link SqlFormatter#format(String)}. */
    PRETTY
}
