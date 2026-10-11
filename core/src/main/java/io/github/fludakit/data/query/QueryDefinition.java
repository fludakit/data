package io.github.fludakit.data.query;

import java.util.List;

/**
 * Resolved metadata for one structured repository method.
 *
 * <p>The processor publishes this definition alongside the generated repository. Predicates and
 * sorts retain logical property paths; an entity-level attribute resolver resolves those paths to
 * physical placement while a {@code TableSchema} supplies the root table shape. This definition does
 * not depend on an entity mapping or Java type, and does not contain SQL text.</p>
 */
public record QueryDefinition(
        Operation operation,
        ResultKind resultKind,
        List<Predicate> predicates,
        List<SortSpec> sorts,
        PagingMode paging,
        LimitSpec limit,
        List<SeekTerm> seekTerms,
        List<ParamBinding> paramBindings,
        boolean distinct) {

    public QueryDefinition {
        predicates = List.copyOf(predicates);
        sorts = List.copyOf(sorts);
        seekTerms = List.copyOf(seekTerms);
        paramBindings = List.copyOf(paramBindings);
    }
}
