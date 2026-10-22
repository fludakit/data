package io.github.fludakit.data.metadata;

import java.util.List;
import java.util.Objects;

/**
 * The immutable result of analyzing a write method's cascade requirements.
 *
 * <p>Built by {@code MutationPlanAnalyzer} (processor) from a {@code TypeDefinition} and
 * {@code Operation}. Consumed by {@code WriteEmitter} (processor) for cascade-aware code generation.</p>
 *
 * <p>Carries everything the emitter needs to decide what to mutate, in what order, and with what
 * mode — analogous to {@link NavigationPlan} for read queries.</p>
 *
 * @param mutationType  the operation to perform on the root entity
 * @param cascadeSpecs  ordered list of cascade specs (children of root)
 */
public record MutationPlan(
    MutationType mutationType,
    List<CascadeSpec> cascadeSpecs
) {
    public MutationPlan {
        Objects.requireNonNull(mutationType, "mutationType");
        cascadeSpecs = cascadeSpecs == null ? List.of() : List.copyOf(cascadeSpecs);
    }

    public boolean hasCascade() {
        return !cascadeSpecs.isEmpty();
    }

    /** The operation performed on the root entity. */
    public enum MutationType {
        INSERT, SAVE, UPDATE, DELETE
    }

    /** How to mutate a child entity/collection at this join. */
    public enum CascadeMode {
        /** Insert child before root (need child's generated ID as FK on root). */
        PRE_INSERT,
        /** Insert/save child after root (need root's generated ID as FK on child). */
        POST_INSERT,
        /** Upsert: INSERT if id is null, UPDATE if id is non-null. */
        UPSERT,
        /** Diff sync: upsert each child + delete orphans. */
        DIFF_SYNC,
        /** Delete all elements + re-insert all. */
        REPLACE,
        /** Delete child row(s). */
        CASCADE_DELETE,
        /** Update child row. */
        UPDATE
    }

    /**
     * One cascade spec: mutate a child via a specific property, then recurse into its children.
     *
     * @param target   the child's TypeDefinition (Entity or ValueObject); null if not resolved
     * @param prop     the property on the parent that leads to this child (Association, EntityCollection, or ElementCollection)
     * @param mode     how to mutate this child
     * @param children cascade specs for this child's own children (nested cascade)
     */
    public record CascadeSpec(
        TypeDefinition target,
        TypeDefinition.Prop prop,
        CascadeMode mode,
        List<CascadeSpec> children
    ) {
        public CascadeSpec {
            Objects.requireNonNull(prop, "prop");
            Objects.requireNonNull(mode, "mode");
            children = children == null ? List.of() : List.copyOf(children);
        }
    }
}
