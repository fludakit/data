package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.CascadeSavePlanner;
import io.github.fludakit.data.processor.analysis.EntityAnalysis;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.QueryPlanCompiler;
import io.github.fludakit.data.processor.analysis.RelationshipLoadPlanner;
import io.github.fludakit.data.processor.analysis.RepositoryMethodValidator;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.analysis.RepositoryTypeResolver;
import io.github.fludakit.data.processor.analysis.TypeShapes;
import io.github.fludakit.data.processor.emit.HydrationEmitter;
import io.github.fludakit.data.processor.emit.JoinedProjectionEmitter;
import io.github.fludakit.data.processor.emit.MetadataGenerator;
import io.github.fludakit.data.processor.emit.MetamodelGenerator;
import io.github.fludakit.data.processor.emit.NavigationGenerator;
import io.github.fludakit.data.processor.emit.QueriesWriter;
import io.github.fludakit.data.processor.emit.RepositoryGenerator;
import io.github.fludakit.data.processor.emit.RepositoryMethodCompiler;
import io.github.fludakit.data.processor.emit.RepositorySourceWriter;
import io.github.fludakit.data.processor.emit.RepositoryShellEmitter;
import io.github.fludakit.data.processor.emit.RowMapperGenerator;
import io.github.fludakit.data.processor.emit.WriteMethodEmitter;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedArtifactRegistry;
import io.github.fludakit.data.sql.SqlDialect;
import jakarta.data.repository.Repository;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One assembly of every collaborator a single processing ROUND needs, in the order the dependencies
 * require. The order is not incidental and each step says why it sits where it does; the comments
 * below moved here verbatim from {@link RepositoryProcessor#process}.
 *
 * <p>Per-round, because several collaborators capture round state: the {@code Filer} wrapper, the
 * managed-entity set, and the freshly built generators that write through it. The two caches that
 * must NOT be rebuilt live in {@link GeneratedArtifactRegistry}, which is injected and outlives this
 * object — as does {@link MappedTypeBuilder}, whose descriptor memo is the whole point of
 * keeping it across rounds.</p>
 *
 * <p>Why this class exists rather than the assembly staying in {@code process()}: {@code process()}
 * had grown to a hundred lines of {@code new}, and the ordering constraints were invisible next to
 * the round loop. Here the ordering is the only thing in the file, and
 * {@link RepositoryGenerator} takes this one object instead of the fourteen collaborators it would
 * otherwise list as constructor parameters.</p>
 */
public class RoundWiring {

    private final Types types;
    private final TypeShapes typeShapes;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final GeneratedArtifactRegistry registry;
    private final MappedTypeBuilder typeMappingBuilder;
    private final RepositoryTypeResolver repositoryTypeResolver;
    private final RepositoryMethodValidator repositoryMethodValidator;
    private final QueryPlanCompiler queryPlanCompiler;
    private final WriteMethodEmitter writeMethodEmitter;
    private final JoinedProjectionEmitter joinedProjections;
    private final RepositoryShellEmitter repositoryWriter;
    private final QueriesWriter queriesWriter;
    private final PathResolver pathResolver;
    private final MetadataGenerator metadataGenerator;
    private final RowMapperGenerator rowMapperGenerator;
    private final NavigationGenerator navigationGenerator;
    private final MetamodelGenerator metamodelGenerator;
    private final EntityAnalysis entityAnalysis;
    private final RepositoryMethodCompiler methodCompiler;
    private final HydrationEmitter hydrationEmitter;
    private final RelationshipLoadPlanner loadPlanner;
    private final CascadeSavePlanner savePlanner;
    private final RepositoryGenerator generator;
    private final List<TypeElement> repositories;

    public RoundWiring(ProcessingEnvironment processingEnv, SqlDialect sqlDialect, ClassName dialectClass,
                GeneratedArtifactRegistry registry, MappedTypeBuilder typeMappingBuilder,
                RepositorySourceWriter sourceWriter, RoundEnvironment roundEnv) {
        this.types = processingEnv.getTypeUtils();
        this.elements = processingEnv.getElementUtils();
        this.diagnostics = Diagnostics.of(processingEnv.getMessager());
        this.registry = registry;
        this.typeMappingBuilder = typeMappingBuilder;
        this.repositoryTypeResolver = new RepositoryTypeResolver(types, elements);
        // The resolver needs the descriptor cache the mapping builder owns, and nothing else: it emits
        // no source and claims no generated name.
        // Name-keyed, not element-keyed: a path's target reaches the resolver as the FQN the
        // descriptor carries, so letting it ask by name is what keeps Elements out of the resolver.
        this.pathResolver = new PathResolver(diagnostics, typeMappingBuilder::descriptorFor);
        // The shape questions four classes were each answering for themselves — the erased name of a
        // declared type, whether it is a List, whether it is the entity, and a method's signature in
        // the context that declares it. Owning Types once here is what lets all four drop it.
        this.typeShapes = new TypeShapes(types);
        this.repositoryMethodValidator = new RepositoryMethodValidator(diagnostics, typeShapes);
        // The plan compiler reads a method DECLARATION, not a type: it needs the erasure tests for the
        // positional paging parameters and nothing else, so no generator and no writer is involved.
        this.queryPlanCompiler = new QueryPlanCompiler(typeShapes);
        // The three compile-time questions several emitters ask — an attribute's metamodel KIND, the
        // class literal for a target FQN, and whether a declared type is an enum — all read the
        // environment. It is owned ONCE here (in AttributeTypes) and that instance is threaded
        // through, rather than every caller carrying Elements and Types to answer the same questions.
        AttributeTypes attributeTypes = new AttributeTypes(elements, types);
        // The write emitter is built after init() has settled the dialect, since whether a save is a
        // one-statement upsert is a property of the configured database.
        this.writeMethodEmitter = new WriteMethodEmitter(attributeTypes, typeShapes, diagnostics, sqlDialect);
        // The JOIN projection reuses the mapping builder's memo, which resolves a target from the FQN
        // its descriptor carries — so it needs no environment of its own. It writes no source file:
        // the mapper it builds is handed back to the caller.
        this.joinedProjections = new JoinedProjectionEmitter(typeMappingBuilder, sqlDialect);
        // Both writers are built after init() has settled the dialect: the impl shell pins it as
        // its SQL_DIALECT field, and neither writer survives a round.
        this.repositoryWriter = new RepositoryShellEmitter(sourceWriter, dialectClass);
        this.queriesWriter = new QueriesWriter(sourceWriter);
        // The descriptor emitter is the only writer of metadataTypes; it resolves value objects through
        // the mapping builder and writes through the source writer, so it never sees the Filer.
        this.metadataGenerator = new MetadataGenerator(diagnostics, typeMappingBuilder,
                pathResolver, sourceWriter, registry.metadataTypes());
        // Round pre-scan: the set of managed entity FQNs (every entity that some
        // @Repository in THIS round resolves to) must be known BEFORE generation dispatch, so a
        // repository processed early in the round can already see targets that only get their own
        // repository later in the same round. getSupportedAnnotationTypes hands all repositories in one
        // Set, so this scan is complete for the round.
        this.repositories = new ArrayList<>();
        Set<String> managed = new HashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Repository.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            TypeElement repository = (TypeElement) element;
            repositories.add(repository);
            TypeMirror entityMirror = repositoryTypeResolver.entityType(repository);
            if (entityMirror != null && entityMirror.getKind() != TypeKind.TYPEVAR
                    && entityMirror.getKind() != TypeKind.WILDCARD
                    && types.erasure(entityMirror) instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement entityElement) {
                managed.add(entityElement.getQualifiedName().toString());
                // Pre-claim every aggregate-root metamodel FQN BEFORE any nav class is generated:
                // a nav for another entity (e.g. Order.item → pkg._OrderItem) must lose the name
                // race and degrade to the NOTE path, whichever repository the round processes first.
                // Value objects get no metamodel, so their name stays available to owners' navs.
                if (!typeMappingBuilder.isValueObjectType(entityElement)) {
                    registry.claim(ClassName.get(entityElement).packageName()
                            + "._" + entityElement.getSimpleName());
                }
            }
            // the <Repository>Queries holder name is claimed up-front, exactly like the
            // _Entity metamodel, so a nav class can never win the race against it.
            registry.claim(ClassName.get(repository) + "Queries");
        }
        registry.managedEntities(Set.copyOf(managed));
        // Built here, after the round pre-scan: the metamodel emitter captures THIS round's
        // managed-entity set, and the mapper emitter asks the metamodel emitter whether a relation
        // target is materialisable — so the order is mappers, then metamodels.
        this.rowMapperGenerator = new RowMapperGenerator(attributeTypes, diagnostics,
                typeMappingBuilder, metadataGenerator, sourceWriter, registry.metadataTypes());
        // The nav generator is built after the mapper generator: it asks whether a relation target is
        // materialisable through the same mapper gate, and it captures THIS round's managed set.
        this.navigationGenerator = new NavigationGenerator(attributeTypes, diagnostics,
                typeMappingBuilder, rowMapperGenerator, sourceWriter, registry.managedEntities());
        this.metamodelGenerator = new MetamodelGenerator(attributeTypes, diagnostics, sourceWriter,
                registry.claimedNames(), navigationGenerator);
        // Built after the metamodel and mapper generators: it IS the on-demand miss path for the
        // descriptors and row mappers they emit, so it can only be assembled once both exist.
        this.entityAnalysis = new EntityAnalysis(typeMappingBuilder, metadataGenerator,
                metamodelGenerator, rowMapperGenerator, registry);
        // The method compiler is built last: it reads a method DECLARATION through every collaborator
        // above (the descriptor registry for dynamic paging, the write emitter for the CRUD writes, the
        // plan compiler for query-by-names), and it emits no source of its own.
        this.methodCompiler = new RepositoryMethodCompiler(typeShapes, diagnostics, sqlDialect,
                pathResolver, queryPlanCompiler, metadataGenerator, writeMethodEmitter, joinedProjections);
        // Like the write emitter, built after init() settled the dialect: the loader column lists are
        // quoted through it.
        this.hydrationEmitter = new HydrationEmitter(sqlDialect, attributeTypes);
        // Built after the round pre-scan (it captures THIS round's managed-entity set) and after the
        // mapper generator (it asks for a relationship target's row mapper through it).
        this.loadPlanner = new RelationshipLoadPlanner(diagnostics, typeMappingBuilder,
                rowMapperGenerator, entityAnalysis::ensureEntityRowMapper, registry.managedEntities());
        // No artifact generation from the write side: a cascade member needs only the target's
        // compile-time model, so no row-mapper function is injected here.
        this.savePlanner = new CascadeSavePlanner(diagnostics, typeMappingBuilder);
        this.generator = new RepositoryGenerator(this);
    }

    // ------------------------------------------------- every repository in this round

    public List<TypeElement> repositories() {
        return repositories;
    }

    public RepositoryGenerator generator() {
        return generator;
    }

    // ----------------------------------------------------------------- collaborators

    public Types types() {
        return types;
    }

    public TypeShapes typeShapes() {
        return typeShapes;
    }

    public Elements elements() {
        return elements;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    public GeneratedArtifactRegistry registry() {
        return registry;
    }

    public RepositoryTypeResolver repositoryTypeResolver() {
        return repositoryTypeResolver;
    }

    public RepositoryMethodValidator methodValidator() {
        return repositoryMethodValidator;
    }

    public MetadataGenerator metadataGenerator() {
        return metadataGenerator;
    }

    public RowMapperGenerator rowMapperGenerator() {
        return rowMapperGenerator;
    }

    public EntityAnalysis entityAnalysis() {
        return entityAnalysis;
    }

    public JoinedProjectionEmitter joinedProjections() {
        return joinedProjections;
    }

    public RepositoryMethodCompiler methodCompiler() {
        return methodCompiler;
    }

    public HydrationEmitter hydrationEmitter() {
        return hydrationEmitter;
    }

    public RelationshipLoadPlanner loadPlanner() {
        return loadPlanner;
    }

    public CascadeSavePlanner savePlanner() {
        return savePlanner;
    }

    public RepositoryShellEmitter repositoryWriter() {
        return repositoryWriter;
    }

    public QueriesWriter queriesWriter() {
        return queriesWriter;
    }
}
