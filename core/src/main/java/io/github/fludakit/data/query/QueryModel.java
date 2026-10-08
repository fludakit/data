package io.github.fludakit.data.query;

import java.util.List;

/**
 * The structural description of ONE repository query method: everything needed to render and drive
 * it, resolved against the entity's compile-time model at generation time. Implemented by nested
 * static classes of the generated {@code <Entity>RepositoryModel} (Plan E); the rendered SQL is NOT
 * a member — the {@code SqlBuilder} produces it from this model plus the entity's
 * {@code io.github.fludakit.data.model.DataModel}, and the literal is baked as a
 * {@code static final String} in the generated {@code <Entity>RepositoryImpl}.
 *
 * @param <T> the entity type the query reads or writes
 */
public interface QueryModel<T> {

    /** The CRUD/query operation this model drives. */
    Operation operation();

    /** The declared return shape. */
    ResultKind resultKind();

    /** WHERE conjuncts, in emission order. */
    List<ResolvedPredicate> predicates();

    /** ORDER BY terms, in declaration order. */
    List<ResolvedSort> sorts();

    /** Paging strategy. */
    PagingMode paging();

    /** Static row cap ({@code @Limit} / query-by-names First); {@code 0} = none. Runtime-limit
     *  parameters bind through {@link #paramBindings()}, not here. */
    int limit();

    /** Cursor seek terms (non-empty iff {@link #paging()} is {@link PagingMode#CURSOR}). */
    List<SeekTerm> seekTerms();

    /** Method-argument slot → named-parameter token. */
    List<ParamBinding> paramBindings();
}
