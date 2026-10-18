package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.LoadPlanner;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.analysis.MethodResults;
import io.github.fludakit.data.processor.analysis.RepositoryMethodResolver;
import io.github.fludakit.data.processor.analysis.SavePlanner;
import io.github.fludakit.data.processor.analysis.TypeModelResolver;
import io.github.fludakit.data.processor.generator.ArtifactCoordinator;
import io.github.fludakit.data.processor.generator.HydrationEmitter;
import io.github.fludakit.data.processor.generator.JoinedProjectionEmitter;
import io.github.fludakit.data.processor.generator.RepositoryImplGenerator;
import io.github.fludakit.data.processor.generator.RepositoryMethodCompiler;
import io.github.fludakit.data.processor.generator.RepositoryShellEmitter;
import io.github.fludakit.data.processor.generator.WriteMethodEmitter;
import io.github.fludakit.data.processor.generator.metadata.MetamodelGenerator;
import io.github.fludakit.data.processor.generator.metadata.QueryDefinitionWriter;
import io.github.fludakit.data.processor.generator.metadata.TypeMappingGenerator;
import io.github.fludakit.data.processor.generator.nav.NavigationGenerator;
import io.github.fludakit.data.processor.generator.rowmapper.RowMapperGenerator;
import io.github.fludakit.data.sql.SqlDialect;

/**
 * A round's PLAN result — the generators and emitters, built in load-bearing dependency order, plus the
 * {@link RepositoryImplGenerator} that drives the generate phase.
 */
public final class Generators {

    private final WriteMethodEmitter writeMethodEmitter;
    private final JoinedProjectionEmitter joinedProjections;
    private final RepositoryShellEmitter repositoryWriter;
    private final QueryDefinitionWriter queriesWriter;
    private final TypeMappingGenerator metadataGenerator;
    private final RowMapperGenerator rowMapperGenerator;
    private final NavigationGenerator navigationGenerator;
    private final MetamodelGenerator metamodelGenerator;
    private final ArtifactCoordinator artifactCoordinator;
    private final RepositoryMethodCompiler methodCompiler;
    private final HydrationEmitter hydrationEmitter;
    private final LoadPlanner loadPlanner;
    private final SavePlanner savePlanner;
    private final RepositoryImplGenerator generator;

    public Generators(Session session, Analysis analysis) {
        SqlDialect sqlDialect = session.sqlDialect();
        TypeModelResolver registry = session.typeMappingBuilder().registry();
        AttributeTypes attributeTypes = analysis.attributeTypes();
        PathResolver pathResolver = analysis.pathResolver();
        QueryPlanBuilder queryPlanBuilder = analysis.queryPlanBuilder();
        MethodResults methodResults = analysis.methodResults();
        RepositoryMethodResolver repositoryMethodResolver = analysis.repositoryMethodResolver();

        // The write emitter is built after init() has settled the dialect: whether a save is a
        // one-statement upsert is a property of the configured database.
        this.writeMethodEmitter = new WriteMethodEmitter(attributeTypes, registry,
                session.types(), session.elements(), session.diagnostics(), sqlDialect);
        // The JOIN projection resolves a target from the FQN its descriptor carries — no environment.
        this.joinedProjections = new JoinedProjectionEmitter(registry, sqlDialect, attributeTypes);
        // Both writers pin the dialect as SQL_DIALECT / the shell's field.
        this.repositoryWriter = new RepositoryShellEmitter(session.sourceWriter(), ClassName.get(sqlDialect.getClass()));
        this.queriesWriter = new QueryDefinitionWriter(session.sourceWriter());
        // The descriptor emitter is the only writer of metadataTypes; it never sees the Filer.
        this.metadataGenerator = new TypeMappingGenerator(session.diagnostics(), registry,
                pathResolver, session.sourceWriter(), session.registry().metadataTypes());
        // Mappers before metamodels: the metamodel emitter asks the mapper emitter whether a relation
        // target is materialisable.
        this.rowMapperGenerator = new RowMapperGenerator(attributeTypes, session.diagnostics(),
                registry, metadataGenerator, session.sourceWriter(), session.registry().metadataTypes());
        // The nav generator is built after the mapper generator (same materialisable gate) and captures
        // THIS round's managed set.
        this.navigationGenerator = new NavigationGenerator(attributeTypes, session.diagnostics(),
                registry, rowMapperGenerator, session.sourceWriter(), session.registry().managedEntities());
        this.metamodelGenerator = new MetamodelGenerator(attributeTypes, session.diagnostics(), session.sourceWriter(),
                session.registry().claimedNames(), navigationGenerator);
        // The on-demand miss path for descriptors and row mappers, assembled once both emitters exist.
        this.artifactCoordinator = new ArtifactCoordinator(registry, metadataGenerator,
                metamodelGenerator, rowMapperGenerator, session.registry());
        // The method compiler reads a method declaration through every collaborator above.
        this.methodCompiler = new RepositoryMethodCompiler(session.types(), registry,
                session.diagnostics(),
                sqlDialect, pathResolver, queryPlanBuilder, metadataGenerator, writeMethodEmitter,
                joinedProjections, session.registry(), methodResults, repositoryMethodResolver);
        this.hydrationEmitter = new HydrationEmitter(sqlDialect, attributeTypes, registry);
        // Pre-generate all row mappers so the load planner can read from the cache without triggering
        // generation.
        artifactCoordinator.preGenerateRowMappers(session.registry().managedEntities());
        this.loadPlanner = new LoadPlanner(session.diagnostics(), registry,
                session.registry(), session.registry().managedEntities());
        // No artifact generation from the write side: a cascade member needs only the target's model.
        this.savePlanner = new SavePlanner(session.diagnostics(), registry);
        this.generator = new RepositoryImplGenerator(session, analysis, this);
    }

    public WriteMethodEmitter writeMethodEmitter() {
        return writeMethodEmitter;
    }

    public JoinedProjectionEmitter joinedProjections() {
        return joinedProjections;
    }

    public RepositoryShellEmitter repositoryWriter() {
        return repositoryWriter;
    }

    public QueryDefinitionWriter queriesWriter() {
        return queriesWriter;
    }

    public TypeMappingGenerator metadataGenerator() {
        return metadataGenerator;
    }

    public RowMapperGenerator rowMapperGenerator() {
        return rowMapperGenerator;
    }

    public NavigationGenerator navigationGenerator() {
        return navigationGenerator;
    }

    public MetamodelGenerator metamodelGenerator() {
        return metamodelGenerator;
    }

    public ArtifactCoordinator artifactCoordinator() {
        return artifactCoordinator;
    }

    public RepositoryMethodCompiler methodCompiler() {
        return methodCompiler;
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

    public RepositoryImplGenerator generator() {
        return generator;
    }
}
