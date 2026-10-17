package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.SavePlanner;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.analysis.MethodResults;
import io.github.fludakit.data.processor.analysis.RepositoryMethodResolver;
import io.github.fludakit.data.processor.analysis.LoadPlanner;
import io.github.fludakit.data.processor.analysis.RepositoryMethodValidator;
import io.github.fludakit.data.processor.analysis.RepositoryTypeResolver;
import io.github.fludakit.data.processor.generator.ArtifactCoordinator;
import io.github.fludakit.data.processor.generator.HydrationEmitter;
import io.github.fludakit.data.processor.generator.JoinedProjectionEmitter;
import io.github.fludakit.data.processor.generator.RepositoryImplGenerator;
import io.github.fludakit.data.processor.generator.RepositoryMethodCompiler;
import io.github.fludakit.data.processor.generator.RepositoryShellEmitter;
import io.github.fludakit.data.processor.generator.WriteMethodEmitter;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.processor.generator.metadata.MetamodelGenerator;
import io.github.fludakit.data.processor.generator.metadata.QueryDefinitionWriter;
import io.github.fludakit.data.processor.generator.nav.NavigationGenerator;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.sql.SqlDialect;
import jakarta.data.repository.Repository;

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
 * One processing ROUND, split into three phases:
 *
 * <ol>
 *   <li><b>ANALYZE</b> (the constructor) — build the compile-time question objects, then pre-scan
 *       every {@code @Repository} interface to resolve its entity type and claim the generated names.</li>
 *   <li><b>PLAN</b> ({@link #plan}) — build the round's generators and emitters in dependency order,
 *       then pre-generate the row mappers and planners the generate phase reads from.</li>
 *   <li><b>GENERATE</b> (the caller's loop) — hand each repository to the {@link RepositoryImplGenerator}.</li>
 * </ol>
 *
 * <p>The session-scoped primitives live in {@link Session}, which is injected; everything
 * here is rebuilt per round because it captures round state (the managed-entity set, the freshly built
 * generators). {@code RepositoryImplGenerator} takes this one object rather than the fourteen collaborators
 * it would otherwise list as constructor parameters, which is why the accessors below stay.
 */
public class Round {

    private final Session session;

    // ---------------------------------------------------------------- ANALYZE (constructor)

    private final RepositoryTypeResolver repositoryTypeResolver;
    private final PathResolver pathResolver;
    private final RepositoryMethodValidator repositoryMethodValidator;
    private final QueryPlanBuilder queryPlanCompiler;
    private final MethodResults methodResults;
    private final RepositoryMethodResolver repositoryMethodResolver;
    private final AttributeTypes attributeTypes;
    private final List<TypeElement> repositories = new ArrayList<>();

    // ---------------------------------------------------------------------- PLAN (plan())

    private WriteMethodEmitter writeMethodEmitter;
    private JoinedProjectionEmitter joinedProjections;
    private RepositoryShellEmitter repositoryWriter;
    private QueryDefinitionWriter queriesWriter;
    private TypeMappingGenerator metadataGenerator;
    private RowMapperGenerator rowMapperGenerator;
    private NavigationGenerator navigationGenerator;
    private MetamodelGenerator metamodelGenerator;
    private ArtifactCoordinator artifactCoordinator;
    private RepositoryMethodCompiler methodCompiler;
    private HydrationEmitter hydrationEmitter;
    private LoadPlanner loadPlanner;
    private SavePlanner savePlanner;
    private RepositoryImplGenerator generator;

    public Round(Session session, RoundEnvironment roundEnv) {
        this.session = session;
        this.repositoryTypeResolver = new RepositoryTypeResolver(session.types(), session.elements());
        // Name-keyed, not element-keyed: a path's target reaches the resolver as the FQN the
        // descriptor carries, so letting it ask by name is what keeps Elements out of the resolver.
        this.pathResolver = new PathResolver(session.diagnostics(), session.typeMappingBuilder().registry()::descriptorFor);
        this.repositoryMethodValidator = new RepositoryMethodValidator(session.diagnostics(), session.types(), session.elements());
        this.queryPlanCompiler = new QueryPlanBuilder(session.types());
        this.methodResults = new MethodResults(session.types(), session.elements());
        this.repositoryMethodResolver = new RepositoryMethodResolver(queryPlanCompiler, pathResolver, methodResults, session.diagnostics());
        // The compile-time questions several emitters ask — a metamodel KIND, a class literal, an enum
        // test — all read the environment, so they are owned once here and threaded through.
        this.attributeTypes = new AttributeTypes(session.elements(), session.types());
        // Round pre-scan: resolve every repository's entity type and claim names BEFORE any nav class
        // is generated, so a repository processed early sees targets that only get their own repository
        // later in the same round.
        Set<String> managed = scanRepositories(roundEnv, repositories);
        session.registry().managedEntities(Set.copyOf(managed));
    }

    /**
     * Builds the round's generators and emitters, then pre-generates the row mappers the planners read.
     * The order is load-bearing and each step names why it sits where it does.
     */
    public void plan() {
        SqlDialect sqlDialect = session.sqlDialect();
        // The write emitter is built after init() has settled the dialect: whether a save is a
        // one-statement upsert is a property of the configured database.
        this.writeMethodEmitter = new WriteMethodEmitter(attributeTypes, session.typeMappingBuilder().registry(),
                session.types(), session.elements(), session.diagnostics(), sqlDialect);
        // The JOIN projection resolves a target from the FQN its descriptor carries — no environment.
        this.joinedProjections = new JoinedProjectionEmitter(session.typeMappingBuilder().registry(), sqlDialect, attributeTypes);
        // Both writers pin the dialect as SQL_DIALECT / the shell's field.
        this.repositoryWriter = new RepositoryShellEmitter(session.sourceWriter(), ClassName.get(sqlDialect.getClass()));
        this.queriesWriter = new QueryDefinitionWriter(session.sourceWriter());
        // The descriptor emitter is the only writer of metadataTypes; it never sees the Filer.
        this.metadataGenerator = new TypeMappingGenerator(session.diagnostics(), session.typeMappingBuilder().registry(),
                pathResolver, session.sourceWriter(), session.registry().metadataTypes());
        // Mappers before metamodels: the metamodel emitter asks the mapper emitter whether a relation
        // target is materialisable.
        this.rowMapperGenerator = new RowMapperGenerator(attributeTypes, session.diagnostics(),
                session.typeMappingBuilder().registry(), metadataGenerator, session.sourceWriter(), session.registry().metadataTypes());
        // The nav generator is built after the mapper generator (same materialisable gate) and captures
        // THIS round's managed set.
        this.navigationGenerator = new NavigationGenerator(attributeTypes, session.diagnostics(),
                session.typeMappingBuilder().registry(), rowMapperGenerator, session.sourceWriter(), session.registry().managedEntities());
        this.metamodelGenerator = new MetamodelGenerator(attributeTypes, session.diagnostics(), session.sourceWriter(),
                session.registry().claimedNames(), navigationGenerator);
        // The on-demand miss path for descriptors and row mappers, assembled once both emitters exist.
        this.artifactCoordinator = new ArtifactCoordinator(session.typeMappingBuilder().registry(), metadataGenerator,
                metamodelGenerator, rowMapperGenerator, session.registry());
        // The method compiler reads a method declaration through every collaborator above.
        this.methodCompiler = new RepositoryMethodCompiler(session.types(), session.typeMappingBuilder().registry(),
                session.diagnostics(),
                sqlDialect, pathResolver, queryPlanCompiler, metadataGenerator, writeMethodEmitter,
                joinedProjections, session.registry(), methodResults, repositoryMethodResolver);
        this.hydrationEmitter = new HydrationEmitter(sqlDialect, attributeTypes, session.typeMappingBuilder().registry());
        // Pre-generate all row mappers so the load planner can read from the cache without triggering
        // generation.
        artifactCoordinator.preGenerateRowMappers(session.registry().managedEntities());
        this.loadPlanner = new LoadPlanner(session.diagnostics(), session.typeMappingBuilder().registry(),
                session.registry(), session.registry().managedEntities());
        // No artifact generation from the write side: a cascade member needs only the target's model.
        this.savePlanner = new SavePlanner(session.diagnostics(), session.typeMappingBuilder().registry());
        this.generator = new RepositoryImplGenerator(this);
    }

    /**
     * Round pre-scan: discovers every {@code @Repository} interface in this round, resolves its entity
     * type, and claims the generated names ({@code _Entity} metamodel and {@code <Repository>Queries})
     * before any nav class is generated.
     */
    private Set<String> scanRepositories(RoundEnvironment roundEnv, List<TypeElement> out) {
        Set<String> managed = new HashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(Repository.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            TypeElement repository = (TypeElement) element;
            out.add(repository);
            TypeMirror entityMirror = repositoryTypeResolver.entityType(repository);
            if (entityMirror != null && entityMirror.getKind() != TypeKind.TYPEVAR
                    && entityMirror.getKind() != TypeKind.WILDCARD
                    && session.types().erasure(entityMirror) instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement entityElement) {
                managed.add(entityElement.getQualifiedName().toString());
                if (!session.typeMappingBuilder().registry().isValueObjectType(entityElement)) {
                    session.registry().claim(ClassName.get(entityElement).packageName()
                            + "._" + entityElement.getSimpleName());
                }
            }
            session.registry().claim(ClassName.get(repository) + "Queries");
        }
        return managed;
    }

    // ------------------------------------------------- every repository in this round

    public List<TypeElement> repositories() {
        return repositories;
    }

    public RepositoryImplGenerator generator() {
        return generator;
    }

    // ----------------------------------------------------------------- collaborators

    public Types types() {
        return session.types();
    }

    public Elements elements() {
        return session.elements();
    }

    public Diagnostics diagnostics() {
        return session.diagnostics();
    }

    public GeneratedArtifacts registry() {
        return session.registry();
    }

    public TypeModelBuilder typeMappingBuilder() {
        return session.typeMappingBuilder();
    }

    public RepositoryTypeResolver repositoryTypeResolver() {
        return repositoryTypeResolver;
    }

    public RepositoryMethodValidator methodValidator() {
        return repositoryMethodValidator;
    }

    public TypeMappingGenerator metadataGenerator() {
        return metadataGenerator;
    }

    public RowMapperGenerator rowMapperGenerator() {
        return rowMapperGenerator;
    }

    public ArtifactCoordinator artifactCoordinator() {
        return artifactCoordinator;
    }

    public JoinedProjectionEmitter joinedProjections() {
        return joinedProjections;
    }

    public RepositoryMethodCompiler methodCompiler() {
        return methodCompiler;
    }

    public RepositoryMethodResolver repositoryMethodResolver() {
        return repositoryMethodResolver;
    }

    public HydrationEmitter hydrationEmitter() {
        return hydrationEmitter;
    }

    public LoadPlanner loadPlanner() {
        return loadPlanner;
    }

    public SavePlanner savePlanner() {
        return savePlanner;
    }

    public RepositoryShellEmitter repositoryWriter() {
        return repositoryWriter;
    }

    public QueryDefinitionWriter queriesWriter() {
        return queriesWriter;
    }
}
