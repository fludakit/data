package io.github.fludakit.data.processor;

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
final class RepositoryTypeResolver {

    private static final Set<String> ENTITY_REPOSITORIES = Set.of(
            "jakarta.data.repository.DataRepository",
            "jakarta.data.repository.BasicRepository",
            "jakarta.data.repository.CrudRepository");
    private static final String CRUD_REPOSITORY = "jakarta.data.repository.CrudRepository";

    private final Types types;
    private final Elements elements;

    RepositoryTypeResolver(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    TypeMirror entityType(TypeElement repository) {
        TypeMirror inherited = entityType(repository.asType(), new HashSet<>());
        return inherited != null ? inherited : entityTypeFromAnnotatedMethods(repository);
    }

    boolean extendsCrud(TypeElement repository) {
        return extendsCrud(repository.asType(), new HashSet<>());
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

    private boolean extendsCrud(TypeMirror repository, Set<String> visited) {
        if (!visited.add(repository.toString())) {
            return false;
        }
        for (TypeMirror supertype : types.directSupertypes(repository)) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            if (CRUD_REPOSITORY.equals(types.erasure(declared).toString())) {
                return true;
            }
            if (extendsCrud(declared, visited)) {
                return true;
            }
        }
        return false;
    }

    boolean extendsDataRepository(TypeElement repository) {
        return extendsDataRepository(repository.asType(), new HashSet<>());
    }

    private boolean extendsDataRepository(TypeMirror repository, Set<String> visited) {
        if (!visited.add(repository.toString())) {
            return false;
        }
        for (TypeMirror supertype : types.directSupertypes(repository)) {
            if (!(supertype instanceof DeclaredType declared)) {
                continue;
            }
            if (ENTITY_REPOSITORIES.contains(types.erasure(declared).toString())) {
                return true;
            }
            if (extendsDataRepository(declared, visited)) {
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
