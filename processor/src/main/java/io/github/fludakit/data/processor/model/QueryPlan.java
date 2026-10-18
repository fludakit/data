package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.query.ParamBinding;

import javax.lang.model.element.VariableElement;
import java.util.List;

/**
 * One query-by-names method read as a PLAN: the entity, the predicates and sorts with their resolved
 * placements and attribute paths, the row window, and the parameter bindings that tie method
 * arguments to named SQL tokens.
 *
 * <p>Each predicate and sort is a self-contained {@link ResolvedPredicate}/{@link ResolvedSort}, so
 * there are no index-aligned parallel lists to keep in step. What is NOT here is what the method
 * DECLARES rather than what it asks for — the operation, the declared and published result shape and
 * {@code distinct} — which stay arguments to {@link io.github.fludakit.data.processor.analysis.PlanPublisher#queryDefinitionOf}.</p>
 */
public record QueryPlan(TypeMapping<?> mapping, List<ResolvedPredicate> predicates, List<ResolvedSort> sorts,
                 int limit, List<ParamBinding> paramBindings, List<? extends VariableElement> parameters) {
}
