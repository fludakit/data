package io.github.fludakit.data.processor.model;

import io.github.fludakit.data.metadata.TypeMapping.Prop;

import java.util.List;
import java.util.Map;

/**
 * The read-side scope {@link io.github.fludakit.data.processor.analysis.LoadPlanner} computed for one repository entity.
 *
 * @param hydratedCollections the {@code List<@ValueObject>} children that cleared the VO row-mapper
 *                            gate, each of which gets a {@code hydrate<Field>} loader
 * @param collectionMappers   field name → the {@code <VO>RowMapper} FQN that reads that child's rows
 * @param joinedReferences    the composing to-one references, JOIN-projected onto the parent's own
 *                            row — they ride the parent's SELECT and get no loader of their own
 * @param hydratedRelationships the one-to-many relationships that cleared the managed-target gate,
 *                            each of which gets a grouped {@code hydrate<Field>} loader
 */
public record LoadPlan(List<Prop.ElementCollection> hydratedCollections,
                Map<String, String> collectionMappers,
                List<Prop.Reference> joinedReferences,
                List<EntityRelationship> hydratedRelationships) {
}
