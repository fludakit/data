package io.github.fludakit.data.sql;

/**
 * How a database spells "insert this row, or update it if it is already there" in ONE statement.
 *
 * <p>There is no portable spelling. The three families:</p>
 * <ul>
 *   <li>{@link #MERGE} — {@code MERGE INTO t (id, c1, ...) KEY (id) VALUES (:id, :c1, ...)}, the
 *       legacy H2 form. The {@code KEY} list is the match key; H2 updates the rest when a row with
 *       that key exists. It is one row's worth of {@code VALUES}, so no {@code USING} subquery and
 *       therefore no untyped-parameter problem (H2 rejects {@code SELECT :p AS "id"} because it
 *       cannot infer the type).</li>
 *   <li>{@link #ON_CONFLICT} — {@code INSERT INTO t (...) VALUES (...) ON CONFLICT (id) DO UPDATE
 *       SET c1 = EXCLUDED.c1, ...}, the PostgreSQL form. {@code EXCLUDED} is the row the INSERT
 *       tried to write, so the update half rebinds nothing and the same named parameters serve both
 *       halves.</li>
 *   <li>{@link #NONE} — the database has no single-statement upsert this renderer emits. A caller
 *       that must still honour {@code save}'s insert-or-update contract issues an {@code UPDATE}
 *       first and, when it matched no row, an {@code INSERT} carrying the identifier — two
 *       statements instead of one, same outcome.</li>
 * </ul>
 *
 * <p>A dialect declares the form through {@link SqlDialect#upsertForm()}; {@link QueryCompiler} owns
 * the text for every form. That split is the same one {@link SqlDialect#limitClause} already uses:
 * the dialect knows WHICH syntax its database accepts, the compiler knows how to write column lists
 * and parameter names.</p>
 */
public enum UpsertForm {

    /** No single-statement upsert; the caller falls back to update-then-insert. */
    NONE,

    /** {@code MERGE INTO <table> (<columns>) KEY (<id>) VALUES (<params>)} — H2. */
    MERGE,

    /** {@code INSERT ... ON CONFLICT (<id>) DO UPDATE SET <col> = EXCLUDED.<col>, ...} — PostgreSQL. */
    ON_CONFLICT
}
