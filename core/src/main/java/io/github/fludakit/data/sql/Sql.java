package io.github.fludakit.data.sql;

import io.github.fludakit.data.metadata.ColumnResolver;
import io.github.fludakit.data.metadata.TableSchema;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Operator;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SortSpec;

import java.util.List;

/**
 * The fluent entry point for assembling SQL — the API a caller reaches for when they want to *see*
 * the statement they are building.
 *
 * <pre>
 * String sql = Sql.select(metadata)
 *         .columns("id", "title", "author.name")
 *         .where("genre", Operator.EQ)
 *         .and("year", Operator.GREATER_THAN_EQUAL)
 *         .orderBy(SortSpec.of("year", false, false))
 *         .limit(10)
 *         .toSql();
 * </pre>
 *
 * <p>Two entry levels, each constructed with everything it needs — there is no
 * {@code new SqlBuilder(a, b, c, d)} with nullable dependencies, and no combination of fields that
 * is legal to construct and illegal to render.</p>
 *
 * <ul>
 * <li><b>Queries</b> ({@link #select}, {@link #count}, {@link #delete}) need a {@link TableSchema}
 *     plus an {@link ColumnResolver}; a {@link TypeDefinition} is both.</li>
 * <li><b>Lifecycle statements</b> ({@link #insert}, {@link #update}, {@link #deleteById}) need only
 *     the table shape.</li>
 * </ul>
 *
 * <h2>Staged types: no clause can be revisited or arrive out of order</h2>
 *
 * <pre>
 * Sql.select(m)   → {@link QueryDraft}  columns(..) | distinct(..) | where(..) | orderBy(..) | limit(..)
 *                 → {@link Predicated}  and(..) | or(..) | orderBy(..) | limit(..)
 *                 → {@link Ordered}     limit(..)
 *                 → {@link Limited}     offset(..)
 * </pre>
 *
 * <p>Each stage exposes only what SQL still allows after what has already been emitted, so
 * {@code columns(..)} after {@code where(..)}, {@code and(..)} after {@code orderBy(..)} and
 * {@code orderBy(..)} after {@code limit(..)} are compile errors rather than run-time surprises.</p>
 *
 * <p><b>One deliberate deviation from {@code REFACTOR-PLAN.md}:</b> that plan made {@code orderBy}
 * require a preceding {@code where}. It does not here, because {@code ORDER BY} without
 * {@code WHERE} is legal SQL and is exactly what {@code findAll(Sort)} emits — forbidding it would
 * block a query the framework supports. The invariant enforced is SQL's own clause order, not a
 * stricter one.</p>
 *
 * <p>Rendering is not duplicated here: every chain accumulates logical predicates and sorts, then
 * hands them to {@link QueryCompiler} — the same renderer {@link SqlBuilder} uses — so a statement
 * built either way is rendered by the same code.</p>
 */
public final class Sql {

    private Sql() {
    }

    // ------------------------------------------------------------------- queries

    /** Starts a SELECT chain rooted at {@code metadata}. */
    public static QueryDraft select(TypeDefinition metadata) {
        return SqlQuery.query(metadata, metadata, Operation.FIND);
    }

    /** Starts a SELECT chain rooted at {@code schema}, resolving paths through {@code resolver}. */
    public static QueryDraft select(TableSchema schema, ColumnResolver resolver) {
        return SqlQuery.query(schema, resolver, Operation.FIND);
    }

    /** Starts a COUNT chain. A count has no projection and no ordering, so neither is offered. */
    public static Filtered count(TypeDefinition metadata) {
        return SqlQuery.query(metadata, metadata, Operation.COUNT);
    }

    /** Starts a COUNT chain rooted at {@code schema}, resolving paths through {@code resolver}. */
    public static Filtered count(TableSchema schema, ColumnResolver resolver) {
        return SqlQuery.query(schema, resolver, Operation.COUNT);
    }

    /** Starts a DELETE-by-predicate chain. Like count: predicates only. */
    public static Filtered delete(TypeDefinition metadata) {
        return SqlQuery.query(metadata, metadata, Operation.DELETE);
    }

    /** Starts a DELETE-by-predicate chain rooted at {@code schema}. */
    public static Filtered delete(TableSchema schema, ColumnResolver resolver) {
        return SqlQuery.query(schema, resolver, Operation.DELETE);
    }

    // ------------------------------------------------------------------- lifecycle

    /** Starts an INSERT chain over {@code metadata}. */
    public static Insert insert(TypeDefinition metadata) {
        return SqlLifecycle.insert(metadata, metadata);
    }

    /** Starts an INSERT chain over {@code schema}, resolving paths through {@code resolver}. */
    public static Insert insert(TableSchema schema, ColumnResolver resolver) {
        return SqlLifecycle.insert(schema, resolver);
    }

    /** Starts an UPDATE-by-identifier chain over {@code metadata}. */
    public static Lifecycle update(TypeDefinition metadata) {
        return SqlLifecycle.update(metadata, metadata);
    }

    /** Starts an UPDATE-by-identifier chain over {@code schema}. */
    public static Lifecycle update(TableSchema schema, ColumnResolver resolver) {
        return SqlLifecycle.update(schema, resolver);
    }

    /** Starts a DELETE-by-identifier chain. */
    public static Lifecycle deleteById(TableSchema schema) {
        return SqlLifecycle.deleteById(schema);
    }

    // ------------------------------------------------------------------- stages

    /**
     * Stage one of a SELECT: nothing has been emitted yet, so every clause is still open.
     *
     * <p>The projection stays open here and closes the moment anything follows it, because a
     * {@code columns(..)} arriving after a {@code where(..)} would silently change the shape of a
     * statement the reader has already half-read.</p>
     */
    public sealed interface QueryDraft permits SqlQuery {

        /** Overrides the dialect; {@link H2Dialect} is the default. */
        QueryDraft dialect(SqlDialect dialect);

        /**
         * Sets the projection to the given logical attribute paths, resolved and qualified exactly as
         * a predicate's column is. A path that traverses a to-one relation contributes its join.
         * With no call, the projection is every root column.
         */
        QueryDraft columns(String... paths);

        /** As {@link #columns(String...)}. */
        QueryDraft columns(List<String> paths);

        /** Marks the projection {@code DISTINCT}. */
        QueryDraft distinct();

        /** States the first predicate, joined to nothing. */
        Predicated where(Predicate predicate);

        /** States the first predicate on {@code property}; the bound parameter is the property's name. */
        Predicated where(String property, Operator operator);

        /** States the first predicate on {@code property} bound to {@code param}. */
        Predicated where(String property, Operator operator, String param);

        /** Orders by the given sort specs. Legal without a predicate — {@code findAll(Sort)}. */
        Ordered orderBy(SortSpec... sorts);

        /** Orders by the given sort specs. */
        Ordered orderBy(List<SortSpec> sorts);

        /** Orders by {@code property} ascending, case-sensitively. */
        Ordered asc(String property);

        /** Orders by {@code property} descending, case-sensitively. */
        Ordered desc(String property);

        /** Caps the result at {@code limit} rows; {@code <= 0} renders no cap. */
        Limited limit(int limit);

        /** Renders the statement. */
        String toSql();
    }

    /**
     * Stage two of a SELECT: a predicate has been emitted, so the projection is closed but further
     * predicates, ordering and the row window are not.
     */
    public sealed interface Predicated permits SqlQuery {

        /** Overrides the dialect. */
        Predicated dialect(SqlDialect dialect);

        /** Adds a predicate joined with {@code AND}. */
        Predicated and(Predicate predicate);

        /** Adds a predicate on {@code property} joined with {@code AND}. */
        Predicated and(String property, Operator operator);

        /** Adds a predicate on {@code property} bound to {@code param}, joined with {@code AND}. */
        Predicated and(String property, Operator operator, String param);

        /** Adds a predicate joined with {@code OR}. */
        Predicated or(Predicate predicate);

        /** Adds a predicate on {@code property} joined with {@code OR}. */
        Predicated or(String property, Operator operator);

        /** Adds a predicate on {@code property} bound to {@code param}, joined with {@code OR}. */
        Predicated or(String property, Operator operator, String param);

        /** Orders by the given sort specs. */
        Ordered orderBy(SortSpec... sorts);

        /** Orders by the given sort specs. */
        Ordered orderBy(List<SortSpec> sorts);

        /** Orders by {@code property} ascending, case-sensitively. */
        Ordered asc(String property);

        /** Orders by {@code property} descending, case-sensitively. */
        Ordered desc(String property);

        /** Caps the result at {@code limit} rows; {@code <= 0} renders no cap. */
        Limited limit(int limit);

        /** Renders the statement. */
        String toSql();
    }

    /** Stage three: the ORDER BY is emitted, so no clause but the row window may still follow. */
    public sealed interface Ordered permits SqlQuery {

        /** Overrides the dialect. */
        Ordered dialect(SqlDialect dialect);

        /** Caps the result at {@code limit} rows; {@code <= 0} renders no cap. */
        Limited limit(int limit);

        /** Renders the statement. */
        String toSql();
    }

    /** Stage four: the row window is emitted — the terminal stage. */
    public sealed interface Limited permits SqlQuery {

        /** Overrides the dialect. */
        Limited dialect(SqlDialect dialect);

        /** Skips {@code offset} rows before the cap; {@code <= 0} renders no offset. */
        Limited offset(int offset);

        /** Renders the statement. */
        String toSql();
    }

    /**
     * A predicate-only chain: COUNT and DELETE. Neither has a projection, an ordering or a row
     * window, so the staged types that carry those are absent rather than ignored.
     */
    public sealed interface Filtered permits SqlQuery {

        /** Overrides the dialect; {@link H2Dialect} is the default. */
        Filtered dialect(SqlDialect dialect);

        /** States the first predicate. */
        Filtered where(Predicate predicate);

        /** States the first predicate on {@code property}. */
        Filtered where(String property, Operator operator);

        /** States the first predicate on {@code property} bound to {@code param}. */
        Filtered where(String property, Operator operator, String param);

        /** Adds a predicate joined with {@code AND}. */
        Filtered and(Predicate predicate);

        /** Adds a predicate on {@code property} joined with {@code AND}. */
        Filtered and(String property, Operator operator);

        /** Adds a predicate on {@code property} bound to {@code param}, joined with {@code AND}. */
        Filtered and(String property, Operator operator, String param);

        /** Adds a predicate joined with {@code OR}. */
        Filtered or(Predicate predicate);

        /** Adds a predicate on {@code property} joined with {@code OR}. */
        Filtered or(String property, Operator operator);

        /** Adds a predicate on {@code property} bound to {@code param}, joined with {@code OR}. */
        Filtered or(String property, Operator operator, String param);

        /** Renders the statement. */
        String toSql();
    }

    /** A lifecycle statement: no projection, no predicate, no ordering — just the table shape. */
    public sealed interface Lifecycle permits Insert, SqlLifecycle {

        /** Overrides the dialect; {@link H2Dialect} is the default. */
        Lifecycle dialect(SqlDialect dialect);

        /**
         * Declares what the caller gets back: {@link ResultKind#VOID} (the default),
         * {@link ResultKind#SINGLE} when the method hands the entity back, or
         * {@link ResultKind#AFFECTED} when it reports the number of rows changed. It is part of the
         * statement's {@link QueryDefinition} and changes no SQL.
         */
        Lifecycle returning(ResultKind resultKind);

        /** Renders the statement. */
        String toSql();
    }

    /**
     * An INSERT. The one decision a caller makes is whether the identifier rides along: a
     * database-generated identity column must be omitted, because binding an explicit NULL to one is
     * rejected by most databases, including H2.
     */
    public sealed interface Insert extends Lifecycle permits SqlLifecycle {

        @Override
        Insert dialect(SqlDialect dialect);

        /** Omits the identifier column so the database can generate it. */
        Insert withIdentityGenerated();

        /** Includes the identifier column — the default. */
        Insert includingId();

        /** As {@link Lifecycle#returning(ResultKind)}, narrowed so the chain keeps its stage. */
        @Override
        Insert returning(ResultKind resultKind);

        /** Renders the statement. */
        @Override
        String toSql();
    }
}
