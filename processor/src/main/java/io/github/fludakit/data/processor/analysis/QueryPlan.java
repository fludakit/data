package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.QueryPlanCompiler;
import io.github.fludakit.data.query.ParamBinding;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.SortSpec;

import javax.lang.model.element.VariableElement;
import java.util.List;

/**
 * One query-by-names method read as a PLAN: the descriptors its paths were resolved against, the
 * predicates and sorts with the placements and logical paths each one resolved to, the row window,
 * and the parameter bindings that tie method arguments to named SQL tokens.
 *
 * <p>A record rather than eleven parameters because the lists are PARALLEL — {@code predicateRefs},
 * {@code predicateAttributePaths} and {@code predicates} must stay index-aligned, and so must the
 * three sort lists. Passing them as separate arguments makes a misalignment a silent off-by-one in
 * the published definition; passing one record makes the alignment the producer's problem, in the
 * one place that built them.</p>
 *
 * <p>What is NOT here is what the method DECLARES rather than what it asks for: the published type
 * name, the operation, the declared and the published result shape, and {@code distinct} — those
 * stay arguments to {@link QueryPlanCompiler#queryDefinitionOf}.</p>
 */
public record QueryPlan(TypeMapping<?> mapping, List<Predicate> predicates, List<ColumnPlacement> predicateRefs,
                 List<String> predicateAttributePaths, List<SortSpec> sorts,
                 List<ColumnPlacement> sortRefs, List<String> sortAttributePaths,
                 List<Boolean> existentialSorts, int limit, List<ParamBinding> paramBindings,
                 List<? extends VariableElement> parameters) {
}
