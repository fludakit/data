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
public interface QueryDefinition {

    /** Requested data operation. */
    Operation operation();

    /** Declared result shape. */
    ResultKind resultKind();

    /** Logical WHERE terms, in emission order. */
    List<Predicate> predicates();

    /** Logical ORDER BY terms, in declaration order. */
    List<SortSpec> sorts();

    /** Paging strategy used by the method. */
    PagingMode paging();

    /** Static row cap, or {@code 0} when there is none. */
    int limit();

    /** Resolved keyset ordering terms used when constructing cursor predicates. */
    List<SeekTerm> seekTerms();

    /** Method argument slots mapped to named SQL parameters. */
    List<ParamBinding> paramBindings();
}
