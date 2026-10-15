package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.query.QueryDefinition;

/**
 * The resolved statement for one structured repository method, as published in the generated
 * {@code <Repository>Queries} holder.
 *
 * <p>A pure data type: it depends only on the {@code query} vocabulary. The {@code definition} is a
 * {@link QueryDefinition.Criteria} because only those four variants are published — they are what a
 * query-by-method-name and a structured {@code @Find} compile to. The lifecycle variants
 * (insert / update / delete-by-id) are built by the emitter to render a constant and are never
 * published: they carry no logical path worth introspecting.</p>
 *
 * @param typeName   the generated field name
 * @param definition the statement itself
 */
public record QueryDefinitionSpec(String typeName, QueryDefinition.Criteria definition) {
}
