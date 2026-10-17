package io.github.fludakit.data.query;

import java.util.List;

/**
 * One structured repository statement, described with logical attribute paths.
 *
 * <p>A sealed interface rather than one record with optional parts, because the seven statements do
 * not draw on the same facts. A {@link Count} has no ordering, no paging and no row window; an
 * {@link Insert} has no criteria at all. Carrying every component on every statement meant each
 * renderer had to know which of them to ignore — the split makes the ignoring impossible, because
 * the ignored component is not there.</p>
 *
 * <p>Two families:</p>
 * <ul>
 *   <li><b>Criteria statements</b> ({@link Read}, {@link Count}, {@link Exists},
 *       {@link DeleteWhere}) — resolved and planned against a {@code TableSchema} plus a column
 *       resolver, because their paths can traverse relations and therefore need joins.</li>
 *   <li><b>Lifecycle statements</b> ({@link Insert}, {@link Upsert}, {@link Update},
 *       {@link DeleteById}) — the table shape rendered directly. They carry no path, so they never
 *       reach the planner.</li>
 * </ul>
 *
 * <p>{@link #operation()} and {@link #resultKind()} stay on the interface so a caller can read a
 * statement's kind without switching. {@link Read} carries {@code resultKind} as a component because
 * a read's shape is whatever the repository method declares; so do the four <b>write</b> variants,
 * because a write is not always a fire-and-forget — an INSERT or an UPDATE hands the entity back when
 * the method returns it, and any write can instead report the number of rows it changed
 * ({@link ResultKind#AFFECTED}). Only {@link Count} and {@link Exists} know their own shape.</p>
 */
public sealed interface QueryDefinition {

    /** The operation this statement performs. */
    Operation operation();

    /** The declared result shape. Constant on every variant but {@link Read}. */
    ResultKind resultKind();

    /**
     * A statement whose rows are chosen by criteria — the four that resolve and plan against a
     * {@code TableSchema} plus a column resolver, because their paths can traverse relations and
     * therefore need joins. The lifecycle variants deliberately do not implement this: an INSERT has
     * no predicate to resolve and no plan to build.
     */
    sealed interface Criteria extends QueryDefinition {

        /** The WHERE conjuncts, in declaration order. */
        List<Predicate> predicates();

        /** Method argument slots mapped to named SQL parameters. */
        List<ParamBinding> paramBindings();
    }

    // ------------------------------------------------------------------- criteria statements

    /**
     * A {@code SELECT}: the only statement with an ordering, a row window, a paging mode and keyset
     * terms, and the only one whose result shape is not fixed by its kind of statement.
     *
     * @param resultKind     the declared result shape — list, optional, page, ...
     * @param predicates     the WHERE conjuncts, in declaration order
     * @param sorts          the ORDER BY terms, in declaration order
     * @param paging         the paging mode; anything but {@link PagingMode#NONE} renders the base
     *                       constant and leaves the window to the runtime
     * @param limit          the static row cap (a {@code First<n>} method name), or {@code 0}
     * @param seekTerms      the resolved key terms published for cursor paging
     * @param paramBindings  method argument slots mapped to named SQL parameters
     * @param distinct       whether the read de-duplicates its rows
     */
    record Read(ResultKind resultKind, List<Predicate> predicates, List<SortSpec> sorts,
                PagingMode paging, LimitSpec limit, List<SeekTerm> seekTerms,
                List<ParamBinding> paramBindings, boolean distinct) implements Criteria {

        public Read {
            predicates = List.copyOf(predicates);
            sorts = List.copyOf(sorts);
            seekTerms = List.copyOf(seekTerms);
            paramBindings = List.copyOf(paramBindings);
        }

        @Override
        public Operation operation() {
            return Operation.FIND;
        }
    }

    /**
     * A {@code COUNT} of the matching rows — {@code COUNT(DISTINCT id)} when {@code distinct} is set,
     * because {@code COUNT(DISTINCT *)} is not SQL.
     */
    record Count(List<Predicate> predicates, List<ParamBinding> paramBindings, boolean distinct)
            implements Criteria {

        public Count {
            predicates = List.copyOf(predicates);
            paramBindings = List.copyOf(paramBindings);
        }

        @Override
        public ResultKind resultKind() {
            return ResultKind.COUNT;
        }

        @Override
        public Operation operation() {
            return Operation.COUNT;
        }
    }

    /** An {@code EXISTS}: a count compared against zero, so it renders as one. */
    record Exists(List<Predicate> predicates, List<ParamBinding> paramBindings) implements Criteria {

        public Exists {
            predicates = List.copyOf(predicates);
            paramBindings = List.copyOf(paramBindings);
        }

        @Override
        public ResultKind resultKind() {
            return ResultKind.EXISTS;
        }

        @Override
        public Operation operation() {
            return Operation.EXISTS;
        }
    }

    /**
     * A {@code DELETE} carrying criteria. A delete by identifier is {@link DeleteById} instead.
     *
     * @param resultKind {@link ResultKind#VOID} for a fire-and-forget delete,
     *                   {@link ResultKind#AFFECTED} when the method reports how many rows it removed
     */
    record DeleteWhere(List<Predicate> predicates, List<ParamBinding> paramBindings, ResultKind resultKind)
            implements Criteria {

        /** A delete that reports nothing. */
        public DeleteWhere(List<Predicate> predicates, List<ParamBinding> paramBindings) {
            this(predicates, paramBindings, ResultKind.VOID);
        }

        public DeleteWhere {
            predicates = List.copyOf(predicates);
            paramBindings = List.copyOf(paramBindings);
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }

        @Override
        public Operation operation() {
            return Operation.DELETE;
        }
    }

    // ------------------------------------------------------------------- lifecycle statements

    /**
     * An {@code INSERT}.
     *
     * @param resultKind {@link ResultKind#VOID}, {@link ResultKind#SINGLE} when the method hands the
     *                   inserted entity back, or {@link ResultKind#AFFECTED} when it reports the row
     *                   count
     * @param includeId  whether the identifier rides along: a database-generated identity column must
     *                   be omitted, because binding an explicit NULL to one is rejected by most
     *                   databases, including H2
     */
    record Insert(ResultKind resultKind, boolean includeId) implements QueryDefinition {

        /** An insert including the identifier column that reports nothing. */
        public Insert() {
            this(ResultKind.VOID, true);
        }

        /** An insert that reports nothing. */
        public Insert(boolean includeId) {
            this(ResultKind.VOID, includeId);
        }

        public Insert {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }

        @Override
        public Operation operation() {
            return Operation.INSERT;
        }
    }

    /**
     * An <strong>upsert</strong>: insert the row, or update it when a row with this identifier is
     * already there. One statement, and the one {@code save} is defined to perform.
     *
     * <p>Unlike {@link Insert} it has no {@code includeId} decision: the identifier is the match key,
     * so it always rides along. That is also why a null identifier cannot be upserted — there is no
     * key to match on — and a {@code save} of an entity with no identifier yet stays a plain
     * {@link Insert} that lets the database assign the key.</p>
     *
     * <p>The spelling is the database's, not this model's: {@code QueryCompiler} renders whichever
     * {@code UpsertForm} the dialect declares, and a dialect that declares none cannot render this
     * statement at all. A repository generated for such a database never builds one — it keeps the
     * two-statement update-then-insert fallback that reaches the same outcome.</p>
     *
     * @param resultKind {@link ResultKind#VOID}, {@link ResultKind#SINGLE} when the method hands the
     *                   saved entity back, or {@link ResultKind#AFFECTED} when it reports how many
     *                   rows it changed
     */
    record Upsert(ResultKind resultKind) implements QueryDefinition {

        /** An upsert that reports nothing. */
        public Upsert() {
            this(ResultKind.VOID);
        }

        public Upsert {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }

        @Override
        public Operation operation() {
            return Operation.SAVE;
        }
    }

    /**
     * An {@code UPDATE} of every non-identifier column, matched by identifier.
     *
     * @param resultKind {@link ResultKind#VOID}, {@link ResultKind#SINGLE} when the method hands the
     *                   updated entity back, or {@link ResultKind#AFFECTED} when it reports how many
     *                   rows it changed
     */
    record Update(ResultKind resultKind) implements QueryDefinition {

        /** An update that reports nothing. */
        public Update() {
            this(ResultKind.VOID);
        }

        public Update {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }

        @Override
        public Operation operation() {
            return Operation.UPDATE;
        }
    }

    /**
     * A {@code DELETE} matched by identifier — no criteria, so no resolver and no plan.
     *
     * @param resultKind {@link ResultKind#VOID}, or {@link ResultKind#AFFECTED} when the method
     *                   reports whether/how many rows it removed
     */
    record DeleteById(ResultKind resultKind) implements QueryDefinition {

        /** A delete by identifier that reports nothing. */
        public DeleteById() {
            this(ResultKind.VOID);
        }

        public DeleteById {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }

        @Override
        public Operation operation() {
            return Operation.DELETE;
        }
    }

    /**
     * A user-supplied native SQL statement declared with {@code @Query}. It has no logical attribute
     * paths — the statement text is the whole query — so it publishes only the SQL and the declared
     * result shape. {@link #operation()} returns {@link Operation#FIND} as a placeholder: a native
     * statement's operation cannot be inferred from its text, and the result shape is the signal that
     * actually distinguishes a read from a write.
     *
     * @param sql        the raw SQL, exactly as the application wrote it
     * @param resultKind the declared result shape — {@link ResultKind#VOID} for a fire-and-forget
     *                   statement, otherwise the shape the caller asked for
     */
    record Native(String sql, ResultKind resultKind) implements QueryDefinition {

        public Native {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }

        @Override
        public Operation operation() {
            return Operation.FIND;
        }
    }
}
