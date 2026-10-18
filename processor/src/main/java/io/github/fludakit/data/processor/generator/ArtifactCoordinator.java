package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;

import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.TypeModelResolver;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.processor.generator.metadata.MetamodelGenerator;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.processor.GeneratedArtifacts;
import javax.lang.model.element.TypeElement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the generated artifacts an entity needs — its {@code _Entity} static metamodel, its
 * {@code <Entity>Metadata} descriptor and its {@code <Entity>RowMapper} — each exactly once, whoever
 * asks first.
 *
 * <p>This is the GENERATE-side half of the old {@code EntityAnalysis}: the part that triggers code
 * generation. It lives in {@code generate} so that {@code analysis} (and its model output) no longer
 * imports anything from {@code generate}, which is what keeps the analysis → plan → generate layers
 * pointing one way. The analysis-side half — building the compile-time {@link TypeMapping} model — is
 * {@link TypeModelResolver}, which this coordinator reads.
 *
 * <p>Idempotent at the artifact level: the name-claiming and cache checks in
 * {@link GeneratedArtifacts} mean two repositories over the same entity (or a repository and a
 * relationship target's planner) can each ask for the same artifact and only the first one writes it.
 */
public final class ArtifactCoordinator {

    private final TypeModelResolver typeMappingBuilder;
    private final TypeMappingGenerator metadataGenerator;
    private final MetamodelGenerator metamodelGenerator;
    private final RowMapperGenerator rowMapperGenerator;
    private final GeneratedArtifacts registry;

    public ArtifactCoordinator(TypeModelResolver typeMappingBuilder,
                               TypeMappingGenerator metadataGenerator, MetamodelGenerator metamodelGenerator,
                               RowMapperGenerator rowMapperGenerator, GeneratedArtifacts registry) {
        this.typeMappingBuilder = typeMappingBuilder;
        this.metadataGenerator = metadataGenerator;
        this.metamodelGenerator = metamodelGenerator;
        this.rowMapperGenerator = rowMapperGenerator;
        this.registry = registry;
    }

    /**
     * Pre-generates row mappers for all managed entities — and, for each entity's element
     * collections, the {@code <VO>RowMapper} that read hydration delegates to — during the analysis
     * phase. This populates the cache so that planners (e.g., the load planner) can read from it
     * without triggering generation. A value object that fails the reflection-free gate records its
     * NOTE here and gets no mapper, so its collection stays unhydrated rather than failing the
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

    /**
     * Emits the aggregate's {@code _Entity} static metamodel (idempotent via
     * {@link GeneratedArtifacts#hasMetamodel}) and, when {@code withMetadata} asks for it, its
     * {@code <Entity>Metadata} descriptor (idempotent via
     * {@link GeneratedArtifacts#hasMetadata}). Each emitter derives the member list it needs
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
     * repository pass may not have run yet in this round, so the metadata + mapper are emitted here on
     * demand, exactly as the entity's own pass would: the row-mapper and metadata caches make the
     * emission idempotent, which keeps one {@code Filer} write per type however the repositories are
     * ordered. A target failing the reflection-free gate yields {@code null}: the caller records the
     * gate NOTE and leaves the reference unmapped (no loader).
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
