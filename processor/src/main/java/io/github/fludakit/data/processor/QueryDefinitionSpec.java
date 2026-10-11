package io.github.fludakit.data.processor;

import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.PagingMode;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.query.SeekTerm;
import io.github.fludakit.data.query.SortSpec;

import java.util.List;

/**
 * The resolved query plan for one structured repository method, as published in the generated
 * {@code <Repository>Queries} holder and consumed by the SQL renderer.
 *
 * <p>A pure data type: it depends only on the {@code query} vocabulary, so it is extracted from the
 * processor where it used to be a nested record. Its builder ({@code queryDefinitionOf}) still lives
 * in the processor because that fold needs the processor-private {@code Kind} and {@code Binding} —
 * moving those two is the step that lets the builder follow.
 */
record QueryDefinitionSpec(String typeName, Operation operation, ResultKind resultKind,
                           List<Predicate> predicates, List<SortSpec> sorts,
                           PagingMode paging, int limit, List<SeekTerm> seekTerms,
                           List<ParamBinding> paramBindings,
                           boolean distinct) implements QueryDefinition {
}
