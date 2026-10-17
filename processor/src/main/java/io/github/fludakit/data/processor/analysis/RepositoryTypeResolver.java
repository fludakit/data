package io.github.fludakit.data.processor.analysis;

import io.github.fludakit.data.processor.analysis.RepositoryFlavour;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/** Resolves Jakarta Data repository entity types through generic repository interface hierarchies. */
public final class RepositoryTypeResolver {

    private static final Set<String> ENTITY_REPOSITORIES = Set.of(
            "jakarta.data.repository.DataRepository",
            "jakarta.data.repository.BasicRepository",
            "jakarta.data.repository.CrudRepository");
    private static final String CRUD_REPOSITORY = "jakarta.data.repository.CrudRepository";

    private final Types types;
    private final Elements elements;

    public RepositoryTypeResolver(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    public TypeMirror entityType(TypeElement repository) {
        TypeMirror inherited = entityType(repository.asType(), new HashSet<>());
        return inherited != null ? inherited : entityTypeFromAnnotatedMethods(repository);
    }

    private boolean extendsCrud(TypeElement repository) {
        return extendsNamed(repository.asType(), CRUD_REPOSITORY, new HashSet<>());
    }

    private TypeMirror entityType(TypeMirror repository, Set<String> visited) {
        if (!visited.add(repository.toString())) {
            return null;
        }
        for (TypeMirror supertype : types.directSupertypes(repository)) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            if (ENTITY_REPOSITORIES.contains(types.erasure(declared).toString())) {
                List<? extends TypeMirror> arguments = declared.getTypeArguments();
                if (!arguments.isEmpty()) {
                    return arguments.get(0);
                }
            }
            TypeMirror resolved = entityType(declared, visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * Whether {@code repository} reaches {@code interfaceFqn} through its own supertypes. One walk
     * shared by the two questions this class answers — {@link #extendsCrud} and {@link #flavourOf} —
     * which previously each carried a copy of it that differed only in the name being matched.
     *
     * @param visited guards the recursion; a hierarchy that names itself is walked once, not forever
     */
    private boolean extendsNamed(TypeMirror repository, String interfaceFqn, Set<String> visited) {
        if (!visited.add(repository.toString())) {
            return false;
        }
        for (TypeMirror supertype : types.directSupertypes(repository)) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            if (interfaceFqn.equals(types.erasure(declared).toString())) {
                return true;
            }
            if (extendsNamed(declared, interfaceFqn, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@link RepositoryFlavour} of {@code repository}, decided by ONE walk of the supertypes:
     * {@code CRUD_REPOSITORY} when it reaches {@code CrudRepository}, {@code DATA_REPOSITORY} when it
     * reaches only {@code DataRepository} or {@code BasicRepository}, {@code PLAIN} otherwise.
     *
     * <p>Answering the {@code CrudRepository} case here rather than leaving callers to ask
     * {@code extendsCrud} separately is what keeps the two from disagreeing: they are one question —
     * which of the three spec interfaces is this? — and {@code CrudRepository} extends
     * {@code BasicRepository}, which extends {@code DataRepository}, so the specific answer has to be
     * decided before the general one.</p>
     */
    public RepositoryFlavour flavourOf(TypeElement repository) {
        if (extendsCrud(repository)) {
            return RepositoryFlavour.CRUD_REPOSITORY;
        }
        return extendsAnyOf(repository.asType(), ENTITY_REPOSITORIES, new HashSet<>())
                ? RepositoryFlavour.DATA_REPOSITORY
                : RepositoryFlavour.PLAIN;
    }

    /** As {@link #extendsNamed} but matching any of {@code interfaceFqns}: the three spec interfaces. */
    private boolean extendsAnyOf(TypeMirror repository, Set<String> interfaceFqns, Set<String> visited) {
        if (!visited.add(repository.toString())) {
            return false;
        }
        for (TypeMirror supertype : types.directSupertypes(repository)) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            if (interfaceFqns.contains(types.erasure(declared).toString())) {
                return true;
            }
            if (extendsAnyOf(declared, interfaceFqns, visited)) {
                return true;
            }
        }
        return false;
    }

    private TypeMirror entityTypeFromAnnotatedMethods(TypeElement repository) {
        List<TypeMirror> candidates = new ArrayList<>();
        for (Element member : elements.getAllMembers(repository)) {
            if (member.getKind() != ElementKind.METHOD || !(member instanceof ExecutableElement method)) {
                continue;
            }
            if (method.getAnnotation(Find.class) != null) {
                ExecutableType resolved = (ExecutableType) types.asMemberOf(
                        (DeclaredType) repository.asType(), method);
                TypeMirror candidate = entityResultType(resolved.getReturnType());
                if (candidate != null) {
                    candidates.add(candidate);
                }
            } else if (method.getAnnotation(Save.class) != null
                    || method.getAnnotation(Insert.class) != null
                    || method.getAnnotation(Update.class) != null) {
                ExecutableType resolved = (ExecutableType) types.asMemberOf(
                        (DeclaredType) repository.asType(), method);
                if (!resolved.getParameterTypes().isEmpty()) {
                    TypeMirror candidate = entityResultType(resolved.getParameterTypes().get(0));
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        TypeMirror first = candidates.get(0);
        for (TypeMirror candidate : candidates) {
            if (!types.isSameType(types.erasure(first), types.erasure(candidate))) {
                return null;
            }
        }
        return first;
    }

    private TypeMirror entityResultType(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return ((ArrayType) type).getComponentType();
        }
        if (!(type instanceof DeclaredType declared)) {
            return null;
        }
        List<? extends TypeMirror> arguments = declared.getTypeArguments();
        String raw = types.erasure(type).toString();
        if (Set.of("java.util.List", "java.util.Collection", "java.util.Set",
                "java.util.Optional", "java.util.stream.Stream", "jakarta.data.page.Page",
                "jakarta.data.page.CursoredPage").contains(raw)) {
            return arguments.size() == 1 ? arguments.get(0) : null;
        }
        return type;
    }
}
