package io.github.fludakit.data.metadata;

import java.util.Map;

/**
 * Resolves entity-rooted attribute paths to their {@link ColumnPlacement}: the column the path
 * names plus the {@link JoinEdge}s that must be crossed to reach it.
 *
 * <p>This is the entity-property → column mapping. It is a separate concern from
 * {@link TableSchema} (the physical shape of one table) and from {@link ColumnRef} (a placement),
 * and it is the only one of the three that knows about property names.</p>
 *
 * <p>Every mapped type gets a generated implementation: the navigation list is fixed at compile
 * time, so {@link #resolve(String)} is emitted as a {@code switch} over the finite path set rather
 * than walked at runtime. {@link TypeMapping} supplies a generic walk as the default for mappings
 * that have no generated descriptor — the processor's own compile-time models and value objects —
 * so resolution works the same either way.</p>
 */
public interface ColumnResolver {

    /**
     * The entity's OWN columns by logical path: the identifier, scalars, forward to-one foreign
     * keys, and flattened embedded leaves. Placements only — a path that traverses a relation is
     * resolved by {@link #resolve}, never stored here.
     */
    default Map<String, ColumnRef> columnRefs() {
        return Map.of();
    }

    /**
     * Resolves an entity-rooted dot path to its placement.
     *
     * @throws IllegalArgumentException when the path is unknown, names a write-only back-reference,
     *                                  or would walk a cycle
     */
    ColumnPlacement resolve(String path);

    /** Whether this entity-rooted path starts with a to-many relation or element collection. */
    boolean isToManyPath(String path);
}
