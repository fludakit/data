package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.LoadPlan;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.RepositoryProcessor;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.GeneratedArtifacts;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides WHAT an entity's read side materialises besides its own row: which
 * {@code List<@ValueObject>} element collections get a loader, and how each relationship is
 * materialised. It decides scope only — the loaders themselves are spelled by
 * {@link io.github.fludakit.data.processor.generator.HydrationEmitter}, and the JOIN projection of a
 * composing to-one by {@link io.github.fludakit.data.processor.generator.RepositoryMethodCompiler}.
 *
 * <p>The read side is deliberately EAGER for element collections (they are owned rows, part of the
 * parent's own state) and gated for relationships: a relationship is materialised only when its
 * target is managed — some {@code @Repository} in the current round resolves to it — and has a
 * generated row mapper to delegate to. An ineligible relationship gets one NOTE and is left
 * unmapped on read rather than failing the repository. That asymmetry against the write side is
 * exactly why {@code REFACTOR-PLAN.md} §4 keeps {@code LoadPlanner} and
 * {@code SavePlanner} apart: folding them into one class would produce flag arguments that
 * interfere with each other.</p>
 *
 * <p>Constructed after the round pre-scan: {@code managedEntities} is per-round. This planner reads
 * from the pre-populated cache in {@link GeneratedArtifacts} — all row mappers are generated
 * during the analysis phase by {@link io.github.fludakit.data.processor.generator.ArtifactCoordinator#preGenerateRowMappers}, so planning is pure
 * analysis with no generation side effects.</p>
 */
public final class LoadPlanner {

    private final Diagnostics diagnostics;
    private final TypeModelRegistry typeMappingBuilder;
    private final GeneratedArtifacts registry;
    /** Entity FQNs managed in the CURRENT round — see the pre-scan in {@link RepositoryProcessor#process}. */
    private final Set<String> managedEntities;

    public LoadPlanner(Diagnostics diagnostics,
                            TypeModelRegistry typeMappingBuilder,
                            GeneratedArtifacts registry,
                            Set<String> managedEntities) {
        this.diagnostics = diagnostics;
        this.typeMappingBuilder = typeMappingBuilder;
        this.registry = registry;
        this.managedEntities = managedEntities;
    }

    /**
     * Computes the read-side scope of one repository entity. Every rejection is reported as a NOTE
     * here rather than silently dropped, so a relationship that is unmapped on read says why.
     * Reads all row mapper availability from the pre-populated cache — no generation occurs.
     */
    public LoadPlan plan(TypeElement entityElement, TypeMapping<?> entity) {
        // Element-collection hydration: read the pre-computed <VO>RowMapper for each
        // List<@ValueObject> child from the registry cache.
        List<Prop.ElementCollection> hydratedCollections = new ArrayList<>();
        Map<String, String> collectionMappers = new LinkedHashMap<>();
        for (Prop.ElementCollection c : entity.elementCollections()) {
            ClassName voMapper = registry.voRowMapper(c.name());
            if (voMapper != null) {
                hydratedCollections.add(c);
                collectionMappers.put(c.name(), voMapper.canonicalName());
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
        for (Prop a : entity.props()) {
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
            TypeElement targetElement = typeMappingBuilder.elementFor(targetFqn);
            ClassName targetMapper = null;
            TypeMapping<?> targetModel = null;
            if (targetElement != null && managedEntities.contains(targetFqn)) {
                targetModel = typeMappingBuilder.descriptorFor(targetElement);
                // Read from the pre-populated cache — no generation
                targetMapper = registry.rowMapper(targetFqn);
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
                hydratedRelationships.add(new EntityRelationship(a, targetModel, targetMapper.canonicalName()));
            }
        }

        return new LoadPlan(hydratedCollections, collectionMappers, joinedReferences,
                hydratedRelationships);
    }
}
