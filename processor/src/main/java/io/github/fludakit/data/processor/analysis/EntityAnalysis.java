package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;

import io.github.fludakit.data.processor.generator.metadata.MetadataGenerator;
import io.github.fludakit.data.processor.generator.metadata.MetamodelGenerator;
import io.github.fludakit.data.processor.generator.RepositoryGenerator;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import javax.lang.model.element.TypeElement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns a {@code TypeElement} into the compile-time model every generator consumes, and resolves the
 * generated artifacts an entity needs — its {@code _Entity} static metamodel, its
 * {@code <Entity>Metadata} descriptor and its {@code <Entity>RowMapper} — each exactly once, whoever
 * asks first.
 *
 * <h2>Two-phase protocol</h2>
 *
 * <p>This class supports two distinct phases:</p>
 *
 * <ol>
 *   <li><strong>Analysis phase</strong> — {@link #buildEntity(TypeElement)} reads metadata and builds
 *       the compile-time model. {@link #preGenerateRowMappers(Set)} pre-generates row mappers
 *       for all managed entities, populating the cache before any planning occurs.</li>
 *   <li><strong>Generation phase</strong> — {@link #ensureDescriptorArtifacts} and
 *       {@link #ensureEntityRowMapper} generate artifacts on demand, reading from the cache first.
 *       These are called by {@link RepositoryGenerator} during the generation pass.</li>
 * </ol>
 *
 * <p>The analysis phase must complete before any planning (e.g., {@link RelationshipLoadPlanner})
 * occurs, so planners read from a fully-populated cache rather than triggering generation.</p>
 *
 * <h2>Idempotent artifact generation</h2>
 *
 * <p>A repository's entity may be reached from multiple repositories in the same round. For example,
 * {@code OrderRepository} and {@code ItemRepository} may both reference {@code Customer} — as the
 * direct entity, as a relationship target, or as a cascade participant. Each needs {@code _Customer},
 * {@code CustomerMetadata}, and {@code CustomerRowMapper}, but the compiler's {@code Filer} only
 * allows writing each type once.</p>
 *
 * <p>The protocol works in two layers:</p>
 *
 * <ol>
 *   <li><strong>Name claiming</strong> — {@link GeneratedArtifactRegistry}
 *       tracks every generated or claimed FQN. Before any generation starts, the round pre-scan
 *       (see {@link io.github.fludakit.data.processor.RoundWiring#RoundWiring}) claims every
 *       {@code _Entity} metamodel and {@code <Repository>Queries} FQN for the round. This prevents
 *       nav classes from winning the name race against the real metamodel.</li>
 *   <li><strong>Cache check</strong> — each {@code ensure*} method in this class checks the registry
 *       before generating. If the artifact is already cached, it returns immediately. If not, it
 *       generates, caches, and returns. The cache keys are entity FQNs; the cache values are
 *       {@code ClassName} objects (the generated type's fully-qualified name).</li>
 * </ol>
 *
 * <p><strong>Who can trigger generation.</strong> Two callers:</p>
 * <ul>
 *   <li>A repository's own {@link RepositoryGenerator#generate}
 *       pass — the normal path. Asks for the entity's metadata, metamodel, and row mapper.</li>
 *   <li>{@link #preGenerateRowMappers} — called during the analysis phase to pre-populate the cache
 *       for all managed entities, so planners can read without triggering generation.</li>
 * </ul>
 *
 * <p><strong>What survives rounds.</strong> The {@code GeneratedArtifactRegistry} is session-scoped:
 * it outlives every round. A type generated in round 1 is not regenerated in round 2. The
 * {@code MappedTypeBuilder} is also session-scoped: its descriptor memo prevents a value object
 * reached from two owners from being analyzed twice. Everything else (the generators, the emitters,
 * the round pre-scan results) is rebuilt per round; see
 * {@link io.github.fludakit.data.processor.RoundWiring}.</p>
 *
 * <p><strong>Per-round, like the generators it calls.</strong> This class holds the two emitters
 * that write source ({@code MetadataGenerator} and {@code MetamodelGenerator}), and those are
 * rebuilt every round (they carry the round's {@code Filer} wrapper and the round's managed-entity
 * set). The caches they write into ({@code GeneratedArtifactRegistry}) are the session-scoped half.</p>
 */
public class EntityAnalysis {

    private final MappedTypeBuilder typeMappingBuilder;
    private final MetadataGenerator metadataGenerator;
    private final MetamodelGenerator metamodelGenerator;
    private final RowMapperGenerator rowMapperGenerator;
    private final GeneratedArtifactRegistry registry;

    public EntityAnalysis(MappedTypeBuilder typeMappingBuilder,
                   MetadataGenerator metadataGenerator, MetamodelGenerator metamodelGenerator,
                   RowMapperGenerator rowMapperGenerator, GeneratedArtifactRegistry registry) {
        this.typeMappingBuilder = typeMappingBuilder;
        this.metadataGenerator = metadataGenerator;
        this.metamodelGenerator = metamodelGenerator;
        this.rowMapperGenerator = rowMapperGenerator;
        this.registry = registry;
    }

    // ---- Analysis phase: pure metadata reading and cache population ----

    /**
     * Reads the compile-time model for an entity element. Pure analysis — no generation.
     */
    public TypeMapping<?> buildEntity(TypeElement element) {
        return typeMappingBuilder.buildEntity(element);
    }

    /**
     * Pre-generates row mappers for all managed entities — and, for each entity's element
     * collections, the {@code <VO>RowMapper} that read hydration delegates to — during the analysis
     * phase. This populates the cache so that planners (e.g., {@link RelationshipLoadPlanner}) can read
     * from it without triggering generation. A value object that fails the reflection-free gate records
     * its NOTE here and gets no mapper, so its collection stays unhydrated rather than failing the
     * repository.
     *
     * @param managedEntities the set of entity FQNs managed in this round
     */
    public void preGenerateRowMappers(Set<String> managedEntities) {
        // Build every managed entity's model FIRST. A cyclic relationship makes one partner resolve
        // the other while it is still in progress, capturing an empty placeholder as its target; the
        // whole graph must be cached before anything resolves through it.
        for (String entityFqn : managedEntities) {
            TypeElement entityElement = typeMappingBuilder.elementFor(entityFqn);
            if (entityElement != null) {
                typeMappingBuilder.descriptorFor(entityElement);
            }
        }
        // Re-point those placeholder targets against the now-complete cache before a mapper,
        // descriptor or loader reads them.
        typeMappingBuilder.reconcileRelationships(managedEntities);

        Map<String, ClassName> voMappers = new LinkedHashMap<>();
        for (String entityFqn : managedEntities) {
            TypeElement entityElement = typeMappingBuilder.elementFor(entityFqn);
            if (entityElement == null) continue;
            TypeMapping<?> entity = typeMappingBuilder.descriptorFor(entityElement);
            ensureEntityRowMapper(entityElement, entity);
            for (TypeMapping.Prop.ElementCollection collection : entity.elementCollections()) {
                ClassName voMapper = rowMapperGenerator.voRowMapperFor(collection, entityElement);
                if (voMapper != null) {
                    voMappers.put(collection.name(), voMapper);
                }
            }
        }
        // Register each VO mapper under its collection name — the key the load planner looks up.
        voMappers.forEach(registry::rememberVoRowMapper);
    }

    // ---- Generation phase: on-demand artifact generation ----

    /**
     * Emits the aggregate's {@code _Entity} static metamodel (idempotent via
     * {@link GeneratedArtifactRegistry#hasMetamodel}) and, when {@code withMetadata} asks for it, its
     * {@code <Entity>Metadata} descriptor (idempotent via
     * {@link GeneratedArtifactRegistry#hasMetadata}). Each emitter derives the member list it needs
     * from the same descriptor, so the descriptor's {@code id()}/{@code attributes()} return the very
     * metamodel field instances; value objects have no metamodel and their metadata falls back to
     * record literals.
     */
    public void ensureDescriptorArtifacts(TypeElement entityElement, TypeMapping<?> entity, boolean withMetadata) {
        String fqn = entityElement.getQualifiedName().toString();
        boolean needMetamodel = !typeMappingBuilder.isValueObjectType(entityElement) && !registry.hasMetamodel(fqn);
        boolean needMetadata = withMetadata && !registry.hasMetadata(fqn);
        if (!needMetamodel && !needMetadata) {
            return;
        }
        if (needMetamodel) {
            String entityPackage = typeMappingBuilder.classNameFor(entity.typeName()).packageName();
            registry.rememberMetamodel(fqn, ClassName.get(entityPackage, "_" + entityElement.getSimpleName()));
            metamodelGenerator.generateStaticMetamodel(entityElement, entity);
        }
        if (needMetadata) {
            metadataGenerator.metadataFor(entityElement, entity);
        }
    }

    /**
     * Resolves (generating on first use) the {@code <Entity>RowMapper} for a managed relationship
     * target. The managed-target gate already proved the target has a repository, but its own
     * {@link RepositoryGenerator#generate} pass may not have run yet in this round, so the metadata +
     * mapper are emitted here on demand, exactly as the entity's own pass would: the row-mapper and
     * metadata caches make the emission idempotent, which keeps one {@code Filer} write per type
     * however the repositories are ordered. A target failing the reflection-free gate yields
     * {@code null}: the caller records the gate NOTE and leaves the reference unmapped (no loader).
     */
    public ClassName ensureEntityRowMapper(TypeElement targetElement, TypeMapping<?> targetModel) {
        String targetFqn = targetElement.getQualifiedName().toString();
        ClassName cached = registry.rowMapper(targetFqn);
        if (cached != null) {
            return cached;
        }
        if (!rowMapperGenerator.canGenerateRowMapper(targetModel)) {
            return null;
        }
        if (!registry.hasMetadata(targetFqn)) {
            metadataGenerator.generateEmbeddedDescriptors(targetModel);
            ensureDescriptorArtifacts(targetElement, targetModel, true);
        }
        ClassName mapper = rowMapperGenerator.generateRowMapper(targetElement, targetModel,
                registry.metadataType(targetFqn));
        registry.rememberRowMapper(targetFqn, mapper);
        return mapper;
    }
}
