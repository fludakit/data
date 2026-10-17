package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.WriteCascade;

import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedKeyCoercion;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides WHAT an {@code @AggregateRoot} writes besides its own row: the entity collections it owns
 * and the to-one composition targets it owns. It decides membership only — the statements themselves
 * are spelled by the write emitters.
 *
 * <p>The write side is deliberately NOT the mirror image of the read side, which is why
 * {@code REFACTOR-PLAN.md} §4 keeps this class apart from {@link LoadPlanner}:</p>
 * <ul>
 *   <li><b>Kind gate.</b> The composition lifecycle is bound to the root ONLY for an
 *       {@code @AggregateRoot} owner. Inside a plain {@code @Entity} an entity collection is a
 *       "collection of associated objects" whose lifecycle is NOT bound, so a replace-all there
 *       would silently DELETE associated child rows. A non-root owner therefore gets NO write
 *       cascade at all — its read hydration loaders are untouched by this class.</li>
 *   <li><b>Write-gate decoupling.</b> Membership is computed from EVERY {@code EntityCollection} on
 *       the descriptor, NOT from the read-gate-passed relationships: the cascade INSERT/DELETE
 *       constants are plain SQL driven by the child's OWN table metadata and a
 *       generated-key-coercible id ({@link #targetWriteBlocker}), and
 *       need no row mapper. A target that fails the managed/mapper READ gate loses only its
 *       hydration LOADER, never its write cascade.</li>
 * </ul>
 *
 * <p>Every skip is reported as its own write-honest NOTE ("children will NOT be cascaded on
 * save/update/delete"), so {@code save()} can never silently drop children without write-side
 * diagnostics. Unlike {@link LoadPlanner} this needs no injected function: resolving a
 * target's compile-time model is memoized inside {@link TypeModelBuilder} and generates
 * no artifact, so the planner stays free of row-mapper generation entirely.</p>
 */
public final class SavePlanner {

    private final Diagnostics diagnostics;
    private final TypeModelBuilder typeMappingBuilder;

    public SavePlanner(Diagnostics diagnostics, TypeModelBuilder typeMappingBuilder) {
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    /**
     * Why one cascade target cannot be written as a plain-SQL child row, or {@code null} when it can.
     *
     * <p>A write cascade needs no row mapper — only that the target names a real table and carries a
     * generated-key-coercible id (the {@code save}/{@code insert} path assigns that id from a
     * {@code GeneratedKeyHolder}). This is a metadata predicate, so it lives on the planning side rather
     * than the generator: both the entity-collection and composition write paths answer it here.</p>
     */
    private String targetWriteBlocker(TypeMapping<?> target) {
        if (target.id() == null) {
            return "target has no identity property";
        }
        if (target.tableName() == null) {
            return "target table metadata is unresolved";
        }
        if (!GeneratedKeyCoercion.isCoercible(
                typeMappingBuilder.memberType(target.typeName(), target.id().name()))) {
            return "target id type " + typeMappingBuilder.memberType(target.typeName(), target.id().name()).box()
                    + " is not generated-key coercible";
        }
        return null;
    }

    /**
     * Computes the write-side scope of one repository entity. A plain {@code @Entity} — and a root
     * with no relations — comes back with two empty lists.
     */
    public WriteCascade plan(TypeElement entityElement, TypeMapping<?> entity) {
        // Aggregate write cascade. Two rules:
        // Kind gate: the composition lifecycle is bound to the root ONLY for
        // @AggregateRoot owners; inside an @Entity an entity collection is a
        // "collection of associated objects" whose lifecycle is NOT bound, so a replace-all
        // there would silently DELETE associated child rows. Non-root owners therefore get NO write
        // cascade at all — their read hydration loaders (the scan above) are untouched.
        // Write-gate decoupling: membership is computed from EVERY EntityCollection on the descriptor,
        // NOT from the read-gate-passed hydratedRelationships, mirroring the element-collection write
        // path (appendUpdateStatements "iterates EVERY collection"): the cascade INSERT/DELETE constants
        // are plain SQL driven by the child's OWN table metadata + generated-key-coercible id
        // (entityCollectionWriteBlocker) and need no row mapper. A target that fails the managed/mapper
        // read gate thereby loses only its hydration LOADER, never its write cascade. Every write skip
        // emits its own write-honest NOTE ("children will NOT be cascaded on save/update/delete") so
        // save() can never silently drop children without write-side diagnostics.
        List<EntityRelationship> cascadeRelationships = new ArrayList<>();
        if (entity.ownsAggregateLifecycle()) {
            for (Prop a : entity.props()) {
                if (!(a instanceof Prop.EntityCollection list)) {
                    continue;
                }
                String targetFqn = list.targetTypeName();
                // entityModel() is a pure memo of the idempotent buildEntity — safe for targets the
                // read gate excluded; no mapper or descriptor artifact is generated from this path.
                TypeMapping<?> targetModel = typeMappingBuilder.descriptorFor(targetFqn);
                String blocker = targetModel == null
                        ? "target type " + targetFqn + " is unresolvable"
                        : list.inverseName() == null
                                ? "one-to-many inverse is unresolved on the target"
                                : targetWriteBlocker(targetModel);
                if (blocker != null) {
                    diagnostics.note(
                            "entity collection '" + list.name() + "' on " + entityElement.getQualifiedName()
                                    + ": " + blocker + " — children will NOT be cascaded on save/update/delete",
                            entityElement);
                    continue;
                }
                // The write emitters read only attribute()/targetModel(); the row mapper is
                // irrelevant to plain-SQL writes, so a cascade member may carry a null mapper.
                cascadeRelationships.add(new EntityRelationship(a, targetModel, null));
            }
        }
        // To-one composition targets: the root owns them too, so save/update write them BEFORE the
        // root's own row — the root carries the target's foreign key, read from the live object, so a
        // target with no identifier yet must be inserted first for the root to bind its generated key.
        // Two kinds of Reference are deliberately NOT written:
        //   - composition=false (@Association): an EXTERNAL entity the root merely points at. The root
        //     stores its key and nothing more; writing it would give the aggregate authority over a
        //     row another aggregate owns.
        //   - backReference=true: the inverse FK of the root's own EntityCollection, a write-only
        //     mirror of a column the child already owns — it is not a target, and it has no row.
        List<EntityRelationship> compositionRelationships = new ArrayList<>();
        if (entity.ownsAggregateLifecycle()) {
            for (Prop a : entity.props()) {
                if (!(a instanceof Prop.Reference reference)
                        || !reference.composition() || reference.backReference()) {
                    continue;
                }
                String targetFqn = reference.targetTypeName();
                TypeMapping<?> targetModel = typeMappingBuilder.descriptorFor(targetFqn);
                String blocker = targetModel == null
                        ? "target type " + targetFqn + " is unresolvable"
                        : targetWriteBlocker(targetModel);
                if (blocker != null) {
                    diagnostics.note(
                            "composition reference '" + reference.name() + "' on "
                                    + entityElement.getQualifiedName() + ": " + blocker
                                    + " — the target will NOT be written on save/update",
                            entityElement);
                    continue;
                }
                compositionRelationships.add(new EntityRelationship(reference, targetModel, null));
            }
        }

        return new WriteCascade(cascadeRelationships, compositionRelationships);
    }
}
