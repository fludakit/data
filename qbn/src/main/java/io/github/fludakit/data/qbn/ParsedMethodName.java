package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.List;

/**
 * Everything a repository METHOD NAME states about a query — and nothing more.
 *
 * <p>What the name cannot know, this type does not pretend to know: the result kind, the paging
 * mode, the keyset terms and the argument bindings all come from the method's SIGNATURE and
 * annotations, which only the processor sees. The processor folds this record together with those
 * into a {@link io.github.fludakit.data.query.QueryDefinition} — the assembly point is the
 * repository, where the return type lives.</p>
 *
 * @param action the query action the name starts with
 * @param distinct whether the name carries {@code Distinct}
 * @param predicates the WHERE conjuncts, in declaration order
 * @param sorts the ORDER BY terms, in declaration order
 * @param limit the {@code First<n>} row cap, or {@code 0} when absent
 */
public record ParsedMethodName(Action action, boolean distinct, List<Predicate> predicates,
                               List<SortSpec> sorts, int limit) {

    /** The query operation the name asks for — the name's own half of a query definition. */
    public Operation operation() {
        return action.operation();
    }
}
