package io.github.fludakit.data.processor.analysis;

import com.squareup.javapoet.ClassName;

import io.github.fludakit.data.processor.emit.MetadataGenerator;
import io.github.fludakit.data.processor.emit.MetamodelGenerator;
import io.github.fludakit.data.processor.emit.RepositoryGenerator;
import io.github.fludakit.data.processor.emit.RowMapperGenerator;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import javax.lang.model.element.TypeElement;

/**
 * Turns a {@code TypeElement} into the compile-time model every generator consumes, and resolves the
 * generated artifacts an entity needs — its {@code _Entity} static metamodel, its
 * {@code <Entity>Metadata} descriptor and its {@code <Entity>RowMapper} — each exactly once, whoever
 * asks first.
 *
 * <p>"Whoever asks first" is the whole difficulty. A repository's own {@link RepositoryGenerator}
 * pass asks, and so does the read planner on behalf of a relationship TARGET whose own repository may
 * not have been processed yet in this round. The caches behind that idempotence live in
 * {@link GeneratedArtifactRegistry} and survive rounds; this class is the per-round miss path that
 * fills them.</p>
 *
 * <p>Per-round, like the generators it calls: it holds the two emitters that write source, and those
 * are rebuilt every round (they carry the round's {@code Filer} and the round's managed-entity set).</p>
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

    public TypeMapping<?> buildEntity(TypeElement element) {
        return typeMappingBuilder.buildEntity(element);
    }

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
