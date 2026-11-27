package io.github.fludakit.data.processor.context;

import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The ordered cascade steps one repository write needs beyond its root row.
 *
 * <p>Built by {@link #of(TypeDefinition, WriteKind)} from a {@link TypeDefinition} and
 * {@link WriteKind}, which applies the aggregate cascade rules. Consumed by
 * {@code WriteEmitter} for cascade-aware code
 * generation.</p>
 *
 * <p>Carries everything the emitter needs to decide what to mutate, in what order, and with what
 * mode.</p>
 *
 * @param steps ordered cascade steps (children of the root)
 */
public record CascadePlan(List<CascadeStep> steps) {
    public CascadePlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    // ── Cascade rules  ───────────────────
    //
    // 1. Aggregate root: full cascade (compositions + entity collections + element collections)
    // 2. Entity: own row + element collections only
    // 3. ValueObject: delegates to owner (never the root)
    // 4. Aggregate nesting: entity collections of @AggregateRoot targets are skipped
    // 5. Full cascade: children follow Aggregate rules when saved as part of aggregate cascade

    /**
     * Builds the cascade plan for writing {@code root} with {@code type}.
     *
     * @param root the root type definition (Entity or Aggregate)
     * @param type the write to perform
     * @return the plan with its cascade steps
     */
    public static CascadePlan of(TypeDefinition root, WriteKind type) {
        List<CascadeStep> steps = root.accept(new TypeDefinition.Visitor<>() {
            @Override
            public List<CascadeStep> visitAggregate(TypeDefinition.Aggregate aggregate) {
                return buildAggregateCascade(aggregate, type);
            }

            @Override
            public List<CascadeStep> visitEntity(TypeDefinition.Entity entity) {
                return buildEntityCascade(entity, type);
            }

            @Override
            public List<CascadeStep> visitValueObject(TypeDefinition.ValueObject valueObject) {
                return List.of();
            }
        });
        return new CascadePlan(steps);
    }

    private static List<CascadeStep> buildAggregateCascade(TypeDefinition entity, WriteKind type) {
        List<TypeDefinition.Prop.Association> associations;
        List<TypeDefinition.Prop.EntityCollection> entityCollections;
        List<TypeDefinition.Prop.ElementCollection> elementCollections;

        if (entity instanceof TypeDefinition.Aggregate agg) {
            associations = agg.associations();
            entityCollections = agg.entityCollections();
            elementCollections = agg.elementCollections();
        } else if (entity instanceof TypeDefinition.Entity ent) {
            associations = ent.associations();
            entityCollections = ent.entityCollections();
            elementCollections = ent.elementCollections();
        } else {
            return List.of();
        }

        List<CascadeStep> preSteps = new ArrayList<>();
        List<CascadeStep> postSteps = new ArrayList<>();

        for (TypeDefinition.Prop.Association assoc : associations) {
            if (!assoc.composition()) continue;
            if (assoc.definition() == null) continue;
            TypeDefinition.Entity child = assoc.definition();

            if (type == WriteKind.INSERT || type == WriteKind.SAVE) {
                List<CascadeStep> grandchildren = buildAggregateCascade(child, type);
                preSteps.add(new CascadeStep(child, assoc, CascadeMode.PRE_INSERT, grandchildren));
            } else if (type == WriteKind.UPDATE) {
                List<CascadeStep> grandchildren = buildAggregateCascade(child, type);
                postSteps.add(new CascadeStep(child, assoc, CascadeMode.UPDATE, grandchildren));
            } else if (type == WriteKind.DELETE) {
                preSteps.add(new CascadeStep(child, assoc, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        for (TypeDefinition.Prop.EntityCollection ec : entityCollections) {
            if (ec.definition() instanceof TypeDefinition.Aggregate) continue;
            if (!(ec.definition() instanceof TypeDefinition.Entity child)) continue;

            if (type == WriteKind.INSERT || type == WriteKind.SAVE || type == WriteKind.UPDATE) {
                List<CascadeStep> grandchildren = buildAggregateCascade(child, type);
                postSteps.add(new CascadeStep(child, ec, CascadeMode.DIFF_SYNC, grandchildren));
            } else if (type == WriteKind.DELETE) {
                postSteps.add(new CascadeStep(child, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        for (TypeDefinition.Prop.ElementCollection ec : elementCollections) {
            if (type == WriteKind.INSERT || type == WriteKind.SAVE || type == WriteKind.UPDATE) {
                postSteps.add(new CascadeStep(null, ec, CascadeMode.REPLACE, List.of()));
            } else if (type == WriteKind.DELETE) {
                preSteps.add(new CascadeStep(null, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        List<CascadeStep> all = new ArrayList<>(preSteps);
        all.addAll(postSteps);
        return List.copyOf(all);
    }

    private static List<CascadeStep> buildEntityCascade(TypeDefinition.Entity entity, WriteKind type) {
        List<CascadeStep> steps = new ArrayList<>();

        for (TypeDefinition.Prop.ElementCollection ec : entity.elementCollections()) {
            if (type == WriteKind.INSERT || type == WriteKind.SAVE || type == WriteKind.UPDATE) {
                steps.add(new CascadeStep(null, ec, CascadeMode.REPLACE, List.of()));
            } else if (type == WriteKind.DELETE) {
                steps.add(new CascadeStep(null, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        return List.copyOf(steps);
    }

    /** The write performed on the root entity: the write-only subset of {@code MethodContext.Operation}. */
    public enum WriteKind {
        INSERT, SAVE, UPDATE, DELETE
    }

    /** How to mutate a child entity/collection at this join. */
    public enum CascadeMode {
        /** Insert child before root (need child's generated ID as FK on root). */
        PRE_INSERT,
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
     * One cascade step: mutate a child via a specific property, then recurse into its children.
     *
     * @param target   the child's TypeDefinition (Entity or ValueObject); null if not resolved
     * @param prop     the property on the parent that leads to this child (Association, EntityCollection, or ElementCollection)
     * @param mode     how to mutate this child
     * @param children cascade steps for this child's own children (nested cascade)
     */
    public record CascadeStep(
        TypeDefinition target,
        TypeDefinition.Prop prop,
        CascadeMode mode,
        List<CascadeStep> children
    ) {
        public CascadeStep {
            Objects.requireNonNull(prop, "prop");
            Objects.requireNonNull(mode, "mode");
            children = children == null ? List.of() : List.copyOf(children);
        }
    }
}
