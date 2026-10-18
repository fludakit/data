package io.github.fludakit.data.processor.support;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeVariableName;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;

/**
 * Builds method overrides with proper type variable resolution.
 *
 * <p>{@link MethodSpec#overriding(ExecutableElement, DeclaredType, Types)} resolves parameter
 * and return types but may leave method-level type variable bounds referencing the supertype's
 * type parameters (e.g. {@code <S extends T>} instead of {@code <S extends Task>}). This
 * utility rebinds those bounds to the concrete entity type after the initial override.</p>
 */
public final class MethodOverrides {

    private MethodOverrides() {}

    public static MethodSpec.Builder resolveOverride(
            ExecutableElement method,
            DeclaredType repositoryDeclaredType,
            Types types,
            ClassName entityType) {

        MethodSpec.Builder builder = MethodSpec.overriding(method, repositoryDeclaredType, types);
        rebindTypeVariables(builder, method, entityType);
        return builder;
    }

    private static void rebindTypeVariables(
            MethodSpec.Builder builder, ExecutableElement method, ClassName entityType) {
        if (!builder.typeVariables.isEmpty() && !method.getTypeParameters().isEmpty()) {
            builder.typeVariables.clear();
            builder.typeVariables.add(TypeVariableName.get(
                    method.getTypeParameters().getFirst().getSimpleName().toString(), entityType));
        }
    }
}
