package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.metadata.MutationPlan;
import io.github.fludakit.data.metadata.MutationPlan.*;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.query.Operation;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link MutationPlan} for a given entity and operation.
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
     * Analyzes the root type and operation to produce a mutation plan.
     *
     * @param root      the root type definition (Entity or Aggregate)
     * @param operation the write operation to perform
     * @return the mutation plan with cascade specs
     */
    public static MutationPlan analyze(TypeDefinition root, Operation operation) {
        MutationType rootOp = mapOperation(operation);
        List<CascadeSpec> steps;
        if (root instanceof TypeDefinition.Aggregate aggregate) {
            steps = buildAggregateCascade(aggregate, operation);
        } else if (root instanceof TypeDefinition.Entity entity) {
            steps = buildEntityCascade(entity, operation);
        } else {
            steps = List.of();
        }
        return new MutationPlan(rootOp, steps);
    }

    /**
     * Builds cascade specs for an aggregate root or any entity being cascaded as part of an aggregate.
     * Implements Rule 1 (full cascade) and Rule 5 (children follow aggregate rules in cascade context).
     */
    private static List<CascadeSpec> buildAggregateCascade(TypeDefinition entity, Operation op) {
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

        // Composition to-one associations
        for (TypeDefinition.Prop.Association assoc : associations) {
            if (!assoc.composition()) continue;
            if (assoc.definition() == null) continue;
            TypeDefinition.Entity child = assoc.definition();

            if (op == Operation.INSERT || op == Operation.SAVE) {
                // Rule 5: children follow Aggregate rules when saved as part of aggregate cascade
                List<CascadeSpec> grandchildren = buildAggregateCascade(child, op);
                preSteps.add(new CascadeSpec(child, assoc, CascadeMode.PRE_INSERT, grandchildren));
            } else if (op == Operation.UPDATE) {
                List<CascadeSpec> grandchildren = buildAggregateCascade(child, op);
                postSteps.add(new CascadeSpec(child, assoc, CascadeMode.UPDATE, grandchildren));
            } else if (op == Operation.DELETE) {
                preSteps.add(new CascadeSpec(child, assoc, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        // Entity collections
        for (TypeDefinition.Prop.EntityCollection ec : entityCollections) {
            // Rule 4: skip entity collections of @AggregateRoot targets
            if (ec.definition() instanceof TypeDefinition.Aggregate) continue;
            if (!(ec.definition() instanceof TypeDefinition.Entity child)) continue;

            if (op == Operation.INSERT || op == Operation.SAVE || op == Operation.UPDATE) {
                // Rule 5: children of aggregate cascade follow Aggregate rules
                List<CascadeSpec> grandchildren = buildAggregateCascade(child, op);
                postSteps.add(new CascadeSpec(child, ec, CascadeMode.DIFF_SYNC, grandchildren));
            } else if (op == Operation.DELETE) {
                postSteps.add(new CascadeSpec(child, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        // Element collections
        for (TypeDefinition.Prop.ElementCollection ec : elementCollections) {
            if (op == Operation.INSERT || op == Operation.SAVE || op == Operation.UPDATE) {
                postSteps.add(new CascadeSpec(null, ec, CascadeMode.REPLACE, List.of()));
            } else if (op == Operation.DELETE) {
                preSteps.add(new CascadeSpec(null, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        List<CascadeSpec> all = new ArrayList<>(preSteps);
        all.addAll(postSteps);
        return List.copyOf(all);
    }

    /**
     * Builds cascade specs for a standalone entity (not part of aggregate cascade).
     * Implements Rule 2: Entity has own row + element collections only.
     */
    private static List<CascadeSpec> buildEntityCascade(TypeDefinition.Entity entity, Operation op) {
        List<CascadeSpec> steps = new ArrayList<>();

        // Entity rules: only element collections
        for (TypeDefinition.Prop.ElementCollection ec : entity.elementCollections()) {
            if (op == Operation.INSERT || op == Operation.SAVE || op == Operation.UPDATE) {
                steps.add(new CascadeSpec(null, ec, CascadeMode.REPLACE, List.of()));
            } else if (op == Operation.DELETE) {
                steps.add(new CascadeSpec(null, ec, CascadeMode.CASCADE_DELETE, List.of()));
            }
        }

        return List.copyOf(steps);
    }

    /**
     * Maps the operation enum to the mutation type enum.
     */
    private static MutationType mapOperation(Operation op) {
        return switch (op) {
            case INSERT -> MutationType.INSERT;
            case SAVE -> MutationType.SAVE;
            case UPDATE -> MutationType.UPDATE;
            case DELETE -> MutationType.DELETE;
            default -> throw new IllegalArgumentException("Not a write operation: " + op);
        };
    }
}
