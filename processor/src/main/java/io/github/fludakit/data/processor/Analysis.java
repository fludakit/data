package io.github.fludakit.data.processor;

import com.squareup.javapoet.ClassName;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.MethodResults;
import io.github.fludakit.data.processor.analysis.PathResolver;
import io.github.fludakit.data.processor.analysis.QueryPlanBuilder;
import io.github.fludakit.data.processor.analysis.RepositoryMethodResolver;
import io.github.fludakit.data.processor.analysis.RepositoryMethodValidator;
import io.github.fludakit.data.processor.analysis.RepositoryTypeResolver;
import jakarta.data.repository.Repository;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A round's ANALYZE result — the compile-time question objects and the pre-scan (the repository list
 * and the managed-entity set). Built once per round, then consumed read-only by the generators.
 */
public final class Analysis {

    private final RepositoryTypeResolver repositoryTypeResolver;
    private final PathResolver pathResolver;
    private final RepositoryMethodValidator repositoryMethodValidator;
    private final QueryPlanBuilder queryPlanBuilder;
    private final MethodResults methodResults;
    private final RepositoryMethodResolver repositoryMethodResolver;
    private final AttributeTypes attributeTypes;
    private final List<TypeElement> repositories = new ArrayList<>();

    public Analysis(Session session, RoundEnvironment roundEnv) {
        this.repositoryTypeResolver = new RepositoryTypeResolver(session.types(), session.elements());
        // Name-keyed, not element-keyed: a path's target reaches the resolver as the FQN the
        // descriptor carries, so letting it ask by name is what keeps Elements out of the resolver.
        this.pathResolver = new PathResolver(session.diagnostics(), session.typeMappingBuilder().registry()::descriptorFor);
        this.repositoryMethodValidator = new RepositoryMethodValidator(session.diagnostics(), session.types(), session.elements());
        this.queryPlanBuilder = new QueryPlanBuilder(session.types());
        this.methodResults = new MethodResults(session.types(), session.elements());
        this.repositoryMethodResolver = new RepositoryMethodResolver(queryPlanBuilder, pathResolver, methodResults, session.diagnostics());
        this.attributeTypes = new AttributeTypes(session.elements(), session.types());
        scanRepositories(session, roundEnv);
    }

    /**
     * Round pre-scan: discovers every {@code @Repository} interface, resolves its entity type, and
     * claims the generated names ({@code _Entity} metamodel and {@code <Repository>Queries}) before any
     * nav class is generated.
     */
    private void scanRepositories(Session session, RoundEnvironment roundEnv) {
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
        session.registry().managedEntities(Set.copyOf(managed));
    }

    public RepositoryTypeResolver repositoryTypeResolver() {
        return repositoryTypeResolver;
    }

    public PathResolver pathResolver() {
        return pathResolver;
    }

    public RepositoryMethodValidator repositoryMethodValidator() {
        return repositoryMethodValidator;
    }

    public QueryPlanBuilder queryPlanBuilder() {
        return queryPlanBuilder;
    }

    public MethodResults methodResults() {
        return methodResults;
    }

    public RepositoryMethodResolver repositoryMethodResolver() {
        return repositoryMethodResolver;
    }

    public AttributeTypes attributeTypes() {
        return attributeTypes;
    }

    public List<TypeElement> repositories() {
        return repositories;
    }
}
