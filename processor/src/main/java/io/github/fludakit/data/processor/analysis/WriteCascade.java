package io.github.fludakit.data.processor.analysis;

import java.util.List;

/**
 * What an {@code @AggregateRoot} writes besides its own row: the entity collections it owns and
 * the to-one composition targets it owns. A plain {@code @Entity} gets an empty one — its
 * relations are "collections of associated objects" whose lifecycle is not bound to it.
 *
 * <p>One record rather than two {@code List} parameters because the two are both
 * {@code List<EntityRelationship>} and would be silently interchangeable at every call site.</p>
 */
public record WriteCascade(List<EntityRelationship> collections,
                    List<EntityRelationship> compositions) {
}
