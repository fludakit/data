package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeMapping.Prop;

import io.github.fludakit.data.processor.Session;
import io.github.fludakit.data.processor.Analysis;
import io.github.fludakit.data.processor.Generators;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.LoadPlan;
import io.github.fludakit.data.processor.generator.RepositoryContext;
import io.github.fludakit.data.processor.analysis.RepositoryFlavour;
import io.github.fludakit.data.processor.model.WriteCascade;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.query.QueryDefinition;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Query;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.List;
import java.util.Map;

/**
 * One {@code @Repository} interface → its {@code <Repository>Impl} (and, when it declares at least one
 * query-by-names or {@code @Find} finder, its {@code <Repository>Queries} holder).
 *
 * <p>{@link #generate} is only the step list — resolve the entity, make sure the artifacts the model
 * needs exist, ask the planners what the read and write scope is, compile every abstract method,
 * write the two files. Each step is a named method below, so the file reads top-down: the step a
 * reader is looking for is the one whose name it is, not a line number inside a hundred-line body.</p>
 *
 * <p>Per-round, and it holds nothing but the three collaborator bundles — {@link Session},
 * {@link Analysis} and {@link Generators}.
 * Every call it makes is idempotent at the artifact level, so two repositories over the same entity
 * (or a repository and a relationship target's planner) can each ask for the same artifact and only
 * the first one writes it.</p>
 */
public class RepositoryImplGenerator {

    private final Session session;
    private final Analysis analysis;
    private final Generators generators;

    public RepositoryImplGenerator(Session session, Analysis analysis, Generators generators) {
        this.session = session;
        this.analysis = analysis;
        this.generators = generators;
    }

    public void generate(TypeElement repository) {
        DeclaredType entityErasure = entityErasureOf(repository);
        if (entityErasure == null) {
            return;
        }
        TypeElement entityElement = (TypeElement) entityErasure.asElement();
        TypeMapping<?> entity = session.typeMappingBuilder().buildEntity(entityElement);
        ClassName mapperType = ensureRowMapper(entityElement, entity);
        RepositoryContext context = newContext(repository, entity, mapperType,
                generators.loadPlanner().plan(entityElement, entity),
                generators.savePlanner().plan(entityElement, entity));
        preResolveMethods(repository, (DeclaredType) repository.asType(), entity);
        generateMembers(repository, context);
        writeArtifacts(repository, context);
    }

    /**
     * The repository's entity type, or {@code null} when the interface does not name one concretely —
     * that case has already been reported, and the caller stops.
     */
    private DeclaredType entityErasureOf(TypeElement repository) {
        TypeMirror entityMirror = analysis.repositoryTypeResolver().entityType(repository);
        if (entityMirror == null || entityMirror.getKind() == TypeKind.TYPEVAR
                || entityMirror.getKind() == TypeKind.WILDCARD
                || !(session.types().erasure(entityMirror) instanceof DeclaredType)) {
            session.diagnostics().error(
                    "@Repository interface " + repository.getQualifiedName()
                            + " must extend DataRepository/BasicRepository/CrudRepository with a concrete entity type",
                    repository);
            return null;
        }
        return (DeclaredType) session.types().erasure(entityMirror);
    }

    /**
     * The row mapper for the entity, generating it here if this repository is the first to ask, or
     * {@code null} when the entity is not row-mappable. The descriptor is requested either way:
     * dynamic paging resolves sort paths through {@code <Entity>Metadata.columnRef}, so it is a hard
     * runtime dependency that stands independent of the mapper gate.
     */
    private ClassName ensureRowMapper(TypeElement entityElement, TypeMapping<?> entity) {
        String entityFqn = entityElement.getQualifiedName().toString();
        ClassName mapperType = null;
        boolean mapperPending = false;
        if (generators.rowMapperGenerator().canGenerateRowMapper(entity)) {
            mapperType = session.registry().rowMapper(entityFqn);
            mapperPending = mapperType == null;
        }
        // Jakarta Data 1.0 §3.3 static metamodel + <Entity>Metadata descriptor: the metamodel is
        // additive for aggregate roots (it references no JDBC types) and its field instances ARE the
        // descriptor's attributes. Value objects surface only as navigable member
        // classes (_<Entity><Field> chains) of their owner's metamodel, never as `_VO` classes.
        generators.artifactCoordinator().ensureDescriptorArtifacts(entityElement, entity, true);
        if (mapperPending) {
            mapperType = generators.rowMapperGenerator().generateRowMapper(entityElement, entity,
                    session.registry().metadataType(entityFqn));
            session.registry().rememberRowMapper(entityFqn, mapperType);
        }
        // EMBEDDED value objects carry descriptor metadata even when the owner's mapper gate skips
        // them; the generated <VO>Metadata classes are what the owner's attributes() reference.
        generators.metadataGenerator().generateEmbeddedDescriptors(entity);
        return mapperType;
    }

    /**
     * The two planners' answers as the one object the emitters are fed, with the collections they
     * append to created here and empty. {@code hydrate} and the CRUD JOIN-projection mapper name are
     * both derived rather than passed in, because they are consequences of what the planners decided
     * and nothing outside this class needs to see them separately.
     */
    private RepositoryContext newContext(TypeElement repository, TypeMapping<?> entity, ClassName mapperType,
                                         LoadPlan loadPlan, WriteCascade cascade) {
        boolean hydrate = generators.hydrationEmitter().hasGeneratedHydration(mapperType,
                loadPlan.hydratedCollections(), loadPlan.hydratedRelationships());
        List<Prop.Reference> joinedReferences = loadPlan.joinedReferences();
        // A composing to-one target is JOIN-projected onto the parent's own row instead of being
        // re-read by a loader, so it needs a mapper that reads the joined labels — one per repository,
        // shared by every CRUD read shape.
        boolean joinProjected = mapperType != null && !joinedReferences.isEmpty();
        String crudFullMapper = joinProjected ? "mapRow_full_crud" : null;
        // Decided ONCE, not per method: what the interface inherits cannot change between its methods.
        RepositoryFlavour flavour = analysis.repositoryTypeResolver().flavourOf(repository);
        return new RepositoryContext(entity, flavour, loadPlan, cascade, mapperType, hydrate,
                crudFullMapper, repository.getQualifiedName().toString());
    }

    /**
     * Resolves every abstract finder method ahead of generation, so the method compiler and the finder
     * emitter read a cached {@code ResolvedMethod} rather than resolving on the fly. Non-finder methods
     * (native, lifecycle, CRUD built-ins) are resolved here too, harmlessly: another generator handles
     * them, so their cached result is never read, and the resolution no longer claims names.
     */
    private void preResolveMethods(TypeElement repository, DeclaredType repositoryType, TypeMapping<?> entity) {
        String fqn = repository.getQualifiedName().toString();
        for (Element member : session.elements().getAllMembers(repository)) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (!method.getModifiers().contains(Modifier.ABSTRACT)
                    || method.getAnnotation(Query.class) != null
                    || MemberNames.isJdbcClientAccessor(method,
                            session.types().erasure(method.getReturnType()).toString())) {
                continue;
            }
            ExecutableType resolved = (ExecutableType) session.types().asMemberOf(repositoryType, method);
            analysis.repositoryMethodResolver().resolveFindMethod(method, resolved, entity,
                    method.getAnnotation(Find.class) != null, method.getAnnotation(Delete.class) != null, fqn);
        }
    }

    /**
     * Every method the {@code <Repository>Impl} gets: one per abstract inherited member, then the
     * JOIN-projection mapper the read shapes share, then the hydration umbrella and its loaders.
     */
    private void generateMembers(TypeElement repository, RepositoryContext context) {
        for (Element member : session.elements().getAllMembers(repository)) {
            MethodSpec method = methodFor(member, repository, context);
            if (method != null) {
                context.auxMethods().add(method);
            }
        }
        if (context.crudFullMapper() != null) {
            context.auxMethods().add(generators.joinedProjections().buildFullMapper(context.crudFullMapper(),
                    context.entity(), context.mapperType(), context.joinedReferences()));
        }
        // Registered after the member loop on purpose, so the loaders' SELECT constants render as
        // fields of the class the member loop just filled.
        if (context.hydrate()) {
            appendHydration(context);
        }
    }

    /**
     * One abstract inherited member's implementation, or {@code null} for everything that is not the
     * repository's business: non-methods, concrete/default methods, {@code Object}'s, and any method
     * the validator rejected (having already reported why).
     */
    private MethodSpec methodFor(Element member, TypeElement repository, RepositoryContext context) {
        if (member.getKind() != ElementKind.METHOD) {
            return null;
        }
        ExecutableElement method = (ExecutableElement) member;
        if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
            return null;
        }
        if (method.getEnclosingElement().toString().equals("java.lang.Object")) {
            return null;
        }
        DeclaredType repositoryType = (DeclaredType) repository.asType();
        // Ahead of BOTH the validator and the method compiler on purpose. The accessor is not a
        // query, so validating it would only reject it as an unrecognised method, and handing it
        // to the compiler would ask it to derive a SELECT from a method that names no criteria.
        // Answering it here — from the declared signature alone — keeps the whole rule in one
        // branch: `JdbcClient getJdbcClient();` returns the injected client, and anything else
        // still falls through to the ordinary path.
        if (MemberNames.isJdbcClientAccessor(method,
                session.types().erasure(method.getReturnType()).toString())) {
            return MethodSpec.overriding(method, repositoryType, session.types())
                    .addStatement("return $L", MemberNames.JDBC_CLIENT_FIELD)
                    .build();
        }
        if (!analysis.repositoryMethodValidator().validate(method, repositoryType, context.entity(),
                context.flavour())) {
            return null;
        }
        return generators.methodCompiler().generateMethod(method, repositoryType, context);
    }

    /**
     * One umbrella reached from every read shape, delegating to a loader per hydrated collection and
     * per hydrated entity relationship.
     */
    private void appendHydration(RepositoryContext context) {
        context.auxMethods().add(generators.hydrationEmitter().hydrationUmbrella(context.entity(),
                context.hydratedCollections(), context.hydratedRelationships()));
        for (Prop.ElementCollection c : context.hydratedCollections()) {
            context.auxMethods().add(generators.hydrationEmitter().appendCollectionHydration(
                    context.constants(), context.entity(), c, ClassName.bestGuess(context.collectionMappers().get(c.name()))));
        }
        // Only ONE_TO_MANY members reach here: a composing MANY_TO_ONE is JOIN-projected onto the
        // parent's own row (see joinedReferences), so it needs no loader of its own.
        for (EntityRelationship r : context.hydratedRelationships()) {
            context.auxMethods().add(generators.hydrationEmitter().appendOneToManyHydration(
                    context.constants(), context.entity(), r));
        }
    }

    private void writeArtifacts(TypeElement repository, RepositoryContext context) {
        String packageName = ClassName.get(repository).packageName();
        String implementationName = repository.getSimpleName() + "Impl";
        generators.repositoryWriter().write(repository, packageName, implementationName,
                context.constants(), context.auxMethods());
        Map<String, QueryDefinition> queryDefinitions =
                session.registry().queryDefinitions(context.repositoryFqn());
        if (!queryDefinitions.isEmpty()) {
            generators.queriesWriter().write(repository, packageName,
                    repository.getSimpleName() + "Queries", queryDefinitions);
        }
    }
}
