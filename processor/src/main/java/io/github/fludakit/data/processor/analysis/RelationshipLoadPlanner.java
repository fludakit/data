package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.processor.emit.HydrationEmitter;
import io.github.fludakit.data.processor.emit.RepositoryMethodCompiler;
import io.github.fludakit.data.processor.emit.RowMapperGenerator;
import io.github.fludakit.data.processor.model.ElementCollection;
import io.github.fludakit.data.processor.model.EntityModel;
import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.LoadPlan;
import io.github.fludakit.data.processor.support.Diagnostics;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Decides WHAT an entity's read side materialises besides its own row: which
 * {@code List<@ValueObject>} element collections get a loader, and how each relationship is
 * materialised. It decides scope only — the loaders themselves are spelled by
 * {@link HydrationEmitter}, and the JOIN projection of a composing to-one by
 * {@link RepositoryMethodCompiler}.
 *
 * <p>The read side is deliberately EAGER for element collections (they are owned rows, part of the
 * parent's own state) and gated for relationships: a relationship is materialised only when its
 * target is managed — some {@code @Repository} in the current round resolves to it — and has a
 * generated row mapper to delegate to. An ineligible relationship gets one NOTE and is left
 * unmapped on read rather than failing the repository. That asymmetry against the write side is
 * exactly why {@code REFACTOR-PLAN.md} §4 keeps {@code RelationshipLoadPlanner} and
 * {@code CascadeSavePlanner} apart: folding them into one class would produce flag arguments that
 * interfere with each other.</p>
 *
 * <p>Constructed after the round pre-scan: {@code managedEntities} is per-round. Resolving a
 * relationship target's row mapper is injected as a {@link BiFunction} rather than reached for
 * directly, because generating that mapper is ARTIFACT work (it writes {@code <Target>Metadata} and
 * {@code <Target>RowMapper} on first use), not planning — the planner asks for the mapper and
 * reports the gate, and stays unit-testable behind a plain function.</p>
 */
public final class RelationshipLoadPlanner {

    private final Elements elements;
    private final Diagnostics diagnostics;
    private final MappedTypeBuilder typeMappingBuilder;
    private final RowMapperGenerator rowMapperGenerator;
    /** Resolves (generating on first use) the {@code <Entity>RowMapper} of a relationship target. */
    private final BiFunction<TypeElement, EntityModel, ClassName> targetMapperFor;
    /** Entity FQNs managed in the CURRENT round — see the pre-scan in {@link RepositoryProcessor#process}. */
    private final Set<String> managedEntities;

    public RelationshipLoadPlanner(Elements elements, Diagnostics diagnostics,
                            MappedTypeBuilder typeMappingBuilder,
                            RowMapperGenerator rowMapperGenerator,
                            BiFunction<TypeElement, EntityModel, ClassName> targetMapperFor,
                            Set<String> managedEntities) {
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
        this.rowMapperGenerator = rowMapperGenerator;
        this.targetMapperFor = targetMapperFor;
        this.managedEntities = managedEntities;
    }

    /**
     * Computes the read-side scope of one repository entity. Every rejection is reported as a NOTE
     * here rather than silently dropped, so a relationship that is unmapped on read says why.
     */
    public LoadPlan plan(TypeElement entityElement, EntityModel entity) {
        // Element-collection hydration: resolve a generated <VO>RowMapper for each
        // List<@ValueObject> child. A child whose VO fails the mapper gate is skipped — hydration
        // then degrades to the parent's default (empty) field value rather than failing the whole
        // repository, matching the "gate fails closed" spirit of the row-mapper gate.
        List<ElementCollection> hydratedCollections = new ArrayList<>();
        Map<String, ClassName> collectionMappers = new LinkedHashMap<>();
        for (ElementCollection c : entity.collections()) {
            ClassName voMapper = rowMapperGenerator.voRowMapperFor(c, entityElement);
            if (voMapper != null) {
                hydratedCollections.add(c);
                collectionMappers.put(c.field(), voMapper);
            }
        }
        // Relationship read gate: a relationship is materialised on read only when its TARGET is
        // managed (some @Repository in this round resolves to it — see process()'s pre-scan) AND has a
        // generated row mapper to delegate to, and (for ONE_TO_MANY) buildEntity's second pass resolved
        // the inverse. Ineligible relationships get one NOTE each and are left unmapped on read rather
        // than failing the repository or calling a mapper that was never emitted. The gate is checked
        // BEFORE resolving the target model so an unmanaged target never gets a row mapper generated.
        //
        // The two KINDS then diverge on HOW they are materialised, and that split is the point:
        //   - a composing MANY_TO_ONE is JOIN-projected — the target's columns ride the parent's own
        //     row, so no second query is ever issued. A to-one join adds at most one row per parent,
        //     so it cannot fan out and cannot disturb LIMIT or COUNT.
        //   - a ONE_TO_MANY still fans out N rows per parent, which would move LIMIT/COUNT onto join
        //     rows; it keeps its grouped fetch until the row-grouping work lands.
        List<Prop.Reference> joinedReferences = new ArrayList<>();
        List<EntityRelationship> hydratedRelationships = new ArrayList<>();
        for (Prop a : entity.metadata().props()) {
            // Java 17 safety: instanceof, never a pattern switch. MANY_TO_ONE is a composing
            // Reference; an external @Association (composition=false) is not projected here.
            boolean manyToOne = a instanceof Prop.Reference ref && ref.composition();
            boolean oneToMany = a instanceof Prop.EntityCollection;
            if (!manyToOne && !oneToMany) {
                continue;
            }
            if (oneToMany && ((Prop.EntityCollection) a).inverseName() == null) {
                diagnostics.note(
                        "relationship '" + a.name() + "' on " + entityElement.getQualifiedName()
                                + ": one-to-many inverse is unresolved on the target — left unmapped on read",
                        entityElement);
                continue;
            }
            String targetFqn = a instanceof Prop.EntityCollection el
                    ? el.targetTypeName() : ((Prop.Reference) a).targetTypeName();
            TypeElement targetElement = targetFqn == null ? null : elements.getTypeElement(targetFqn);
            ClassName targetMapper = null;
            EntityModel targetModel = null;
            if (targetElement != null && managedEntities.contains(targetFqn)) {
                targetModel = typeMappingBuilder.entityModel(targetElement);
                targetMapper = targetMapperFor.apply(targetElement, targetModel);
            }
            if (targetMapper == null) {
                diagnostics.note(
                        "relationship '" + a.name() + "' on " + entityElement.getQualifiedName()
                                + ": target " + targetFqn + " has no generated row mapper — left unmapped on read",
                        entityElement);
                continue;
            }
            if (manyToOne) {
                joinedReferences.add((Prop.Reference) a);
            } else {
                hydratedRelationships.add(new EntityRelationship(a, targetModel, targetMapper));
            }
        }

        return new LoadPlan(hydratedCollections, collectionMappers, joinedReferences,
                hydratedRelationships);
    }
}
