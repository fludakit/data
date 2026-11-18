package io.github.fludakit.data.query;

import java.util.List;

/**
 * One structured repository statement, described with logical attribute paths.
 *
 * <p>A sealed interface rather than one record with optional parts: a {@link Count} has no ordering
 * and no window; an {@link Insert} has no criteria. Carrying every component on every statement
 * forced each renderer to ignore absent parts — the split makes that impossible.</p>
 *
 * <p>Three families:</p>
 * <ul>
 *   <li><b>Criteria</b> ({@link Read}, {@link Count}, {@link Exists}, {@link DeleteWhere}) — paths
 *       that may traverse relations and therefore need joins when compiled.</li>
 *   <li><b>Lifecycle</b> ({@link Insert}, {@link Upsert}, {@link Update}, {@link DeleteById}) —
 *       table shape only; no path, no planner.</li>
 *   <li><b>Native</b> ({@link Native}) — opaque provider-extension SQL from {@code @Query}.</li>
 * </ul>
 *
 * <p>{@link ResultKind} and {@link ParamBinding} ride on the definition for the emitter. The SQL
 * compiler ignores both.</p>
 */
public sealed interface QueryDefinition {

    /** The declared result shape. Fixed on every variant but {@link Read} and the writes. */
    ResultKind resultKind();

    /**
     * A statement whose rows are chosen by criteria — resolved against a {@code TypeDefinition}
     * because paths can traverse relations.
     */
    sealed interface Criteria extends QueryDefinition {

        List<Predicate> predicates();

        List<ParamBinding> paramBindings();
    }

    // ------------------------------------------------------------------- criteria

    /**
     * A {@code SELECT}: the only statement with an order and a row window, and the only one whose
     * result shape is not fixed by its kind.
     */
    record Read(ResultKind resultKind, List<Predicate> predicates, List<ParamBinding> paramBindings,
                boolean distinct, Order order, Window window) implements Criteria {

        public Read {
            predicates = List.copyOf(predicates);
            paramBindings = List.copyOf(paramBindings);
            order = order == null ? Order.None.INSTANCE : order;
            window = window == null ? Window.None.unpaged() : window;
            resultKind = resultKind == null ? ResultKind.LIST : resultKind;
        }

        /** Static sorts when the order is {@link Order.Static}; otherwise empty. */
        public List<SortSpec> sorts() {
            return order instanceof Order.Static s ? s.sorts() : List.of();
        }
    }

    /**
     * A {@code COUNT} of matching rows — {@code COUNT(DISTINCT id)} when {@code distinct} is set.
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
    }

    /** An {@code EXISTS}: rendered as a count compared against zero. */
    record Exists(List<Predicate> predicates, List<ParamBinding> paramBindings) implements Criteria {

        public Exists {
            predicates = List.copyOf(predicates);
            paramBindings = List.copyOf(paramBindings);
        }

        @Override
        public ResultKind resultKind() {
            return ResultKind.EXISTS;
        }
    }

    /**
     * A {@code DELETE} with criteria. A delete by identifier is {@link DeleteById}.
     *
     * @param resultKind {@link ResultKind#VOID} or {@link ResultKind#AFFECTED}
     */
    record DeleteWhere(List<Predicate> predicates, List<ParamBinding> paramBindings, ResultKind resultKind)
            implements Criteria {

        public DeleteWhere(List<Predicate> predicates, List<ParamBinding> paramBindings) {
            this(predicates, paramBindings, ResultKind.VOID);
        }

        public DeleteWhere {
            predicates = List.copyOf(predicates);
            paramBindings = List.copyOf(paramBindings);
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }
    }

    // ------------------------------------------------------------------- lifecycle

    /**
     * An {@code INSERT}.
     *
     * @param includeId whether the identifier column rides along (omit for database-generated ids)
     */
    record Insert(ResultKind resultKind, boolean includeId) implements QueryDefinition {

        public Insert() {
            this(ResultKind.VOID, true);
        }

        public Insert(boolean includeId) {
            this(ResultKind.VOID, includeId);
        }

        public Insert {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }
    }

    /**
     * An upsert: insert the row, or update it when the identifier already exists. The spelling is
     * the dialect's; a dialect with no form cannot compile this statement.
     */
    record Upsert(ResultKind resultKind) implements QueryDefinition {

        public Upsert() {
            this(ResultKind.VOID);
        }

        public Upsert {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }
    }

    /** An {@code UPDATE} of every non-identifier column, matched by identifier. */
    record Update(ResultKind resultKind) implements QueryDefinition {

        public Update() {
            this(ResultKind.VOID);
        }

        public Update {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }
    }

    /** A {@code DELETE} matched by identifier. */
    record DeleteById(ResultKind resultKind) implements QueryDefinition {

        public DeleteById() {
            this(ResultKind.VOID);
        }

        public DeleteById {
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }
    }

    // ------------------------------------------------------------------- native extension

    /**
     * User-supplied native SQL from {@code @Query}. The text is opaque — never parsed as JDQL/JPQL.
     * Bindings cover {@code :name} and positional {@code ?1} parameters.
     */
    record Native(String sql, ResultKind resultKind, List<ParamBinding> paramBindings)
            implements QueryDefinition {

        public Native(String sql, ResultKind resultKind) {
            this(sql, resultKind, List.of());
        }

        public Native {
            paramBindings = List.copyOf(paramBindings);
            resultKind = resultKind == null ? ResultKind.VOID : resultKind;
        }
    }
}
