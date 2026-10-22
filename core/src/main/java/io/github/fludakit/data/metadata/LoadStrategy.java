package io.github.fludakit.data.metadata;

/**
 * Strategy that controls how a relationship property is loaded at runtime.
 *
 * <p>Each {@link TypeDefinition.Prop} that represents a relationship (association,
 * entity collection, or element collection) carries a {@code LoadStrategy} that tells
 * the hydration layer what to fetch when the owning entity is materialized.</p>
 */
public enum LoadStrategy {

    /**
     * The full entity or collection is loaded eagerly as part of the owner's hydration.
     *
     * <p>Used for aggregate composing associations, aggregate entity collections,
     * and all element collections (both aggregate and entity owners).</p>
     */
    EAGER,

    /**
     * Only the foreign-key column is read; the related entity is resolved lazily
     * by the application when accessed.
     *
     * <p>Used for {@code @Association}-annotated associations on aggregates and
     * all associations on plain entities and value objects.</p>
     */
    FK_ONLY,

    /**
     * The relationship is not loaded at all — neither the FK nor the target entity.
     *
     * <p>Used for entity collections on plain entities and value objects, where
     * the collection is not part of the aggregate boundary and must be queried
     * explicitly if needed.</p>
     */
    NOT_LOADED
}
