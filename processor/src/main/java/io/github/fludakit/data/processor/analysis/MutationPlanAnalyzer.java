package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.MutationPlan;
import io.github.fludakit.data.metadata.MutationPlan.*;
import io.github.fludakit.data.metadata.TypeDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link MutationPlan} for a given entity and {@link MutationType}.
 *
 * <p>The analyzer walks the aggregate tree from the entry point, applying the 5 cascade rules:
 * <ol>
 *   <li>Aggregate root: full cascade (compositions + entity collections + element collections)</li>
 *   <li>Entity: own row + element collections only</li>
 *   <li>ValueObject: delegates to owner (never the root)</li>
 *   <li>Aggregate nesting: entity collections of @AggregateRoot targets are skipped</li>
 *   <li>Full cascade: children follow Aggregate rules when saved as part of aggregate cascade</li>
 * </ol>
 */
public final class MutationPlanAnalyzer {

    private MutationPlanAnalyzer() {}

    /**
     * Analyzes the root type and mutation type to produce a mutation plan.
     *
     * @param root the root type definition (Entity or Aggregate)
     * @param type the write mutation to perform
     * @return the mutation plan with cascade specs
     */
    public static MutationPlan analyze(TypeDefinition root, MutationType type) {
        List<CascadeSpec> steps = root.accept(new TypeDefinition.Visitor<>() {
            @Override
            public List<CascadeSpec> visitAggregate(TypeDefinition.Aggregate aggregate) {
                return buildAggregateCascade(aggregate, type);
            }

            @Override
            public List<CascadeSpec> visitEntity(TypeDefinition.Entity entity) {
                return buildEntityCascade(entity, type);
            }

            @Override
            public List<CascadeSpec> visitValueObject(TypeDefinition.ValueObject valueObject) {
                return List.of();
            }
        });
        return new MutationPlan(type, steps);
    }

    private static List<CascadeSpec> buildAggregateCascade(TypeDefinition entity, MutationType type) {
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

        List<CascadeSpec> preSteps = new ArrayList<>();
        List<CascadeSpec> postSteps = new ArrayList<>();

        for (TypeDefinition.Prop.Association assoc : associations) {
            if (!assoc.composition()) continue;
            if (assoc.definition() == null) continue;
            TypeDefinition.Entity child = assoc.definition();

            if (type == MutationType.INSERT || type == MutationType.SAVE) {
                List<CascadeSpec> grandchildren = buildAggregateCascade(child, type);
                preSteps.add(new CascadeSpec(child, assoc, CascadeMode.PRE_INSERT, grandchildren));
            } else if (type == MutationType.UPDATE) {
                List<CascadeSpec> grandchildren = buildAggregateCascade(child, type);
                postSteps.add(new CascadeSpec(child, assoc, CascadeMode.UPDATE, grandchildren));
            } else if (type == MutationType.DELETE) {
                preSteps.add(new CascadeSpec(child, assoc, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        for (TypeDefinition.Prop.EntityCollection ec : entityCollections) {
            if (ec.definition() instanceof TypeDefinition.Aggregate) continue;
            if (!(ec.definition() instanceof TypeDefinition.Entity child)) continue;

            if (type == MutationType.INSERT || type == MutationType.SAVE || type == MutationType.UPDATE) {
                List<CascadeSpec> grandchildren = buildAggregateCascade(child, type);
                postSteps.add(new CascadeSpec(child, ec, CascadeMode.DIFF_SYNC, grandchildren));
            } else if (type == MutationType.DELETE) {
                postSteps.add(new CascadeSpec(child, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        for (TypeDefinition.Prop.ElementCollection ec : elementCollections) {
            if (type == MutationType.INSERT || type == MutationType.SAVE || type == MutationType.UPDATE) {
                postSteps.add(new CascadeSpec(null, ec, CascadeMode.REPLACE, List.of()));
            } else if (type == MutationType.DELETE) {
                preSteps.add(new CascadeSpec(null, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        List<CascadeSpec> all = new ArrayList<>(preSteps);
        all.addAll(postSteps);
        return List.copyOf(all);
    }

    private static List<CascadeSpec> buildEntityCascade(TypeDefinition.Entity entity, MutationType type) {
        List<CascadeSpec> steps = new ArrayList<>();

        for (TypeDefinition.Prop.ElementCollection ec : entity.elementCollections()) {
            if (type == MutationType.INSERT || type == MutationType.SAVE || type == MutationType.UPDATE) {
                steps.add(new CascadeSpec(null, ec, CascadeMode.REPLACE, List.of()));
            } else if (type == MutationType.DELETE) {
                steps.add(new CascadeSpec(null, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        return List.copyOf(steps);
    }
}
