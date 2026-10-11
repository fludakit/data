package io.github.fludakit.data.qbn;

import io.github.fludakit.data.query.Action;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import java.util.List;

/**
 * A parsed query-by-method-name.
 *
 * @param action the query action the name starts with
 * @param distinct whether the name carries the {@code Distinct} subject keyword
 * @param predicates the WHERE conjuncts, in declaration order
 * @param sorts the ORDER BY terms, in declaration order
 * @param limit the {@code First<n>} row cap, or {@code 0} when absent
 */
public record ParsedMethodName(Action action, boolean distinct, List<Predicate> predicates,
                               List<SortSpec> sorts, int limit) {}
