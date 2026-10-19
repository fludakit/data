package io.github.fludakit.data.processor.support;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeVariableName;
import com.squareup.javapoet.TypeName;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

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
        rebindTypeVariables(builder, method, repositoryDeclaredType, entityType);
        return builder;
    }

    private static void rebindTypeVariables(
            MethodSpec.Builder builder, ExecutableElement method, 
            DeclaredType repositoryDeclaredType, ClassName entityType) {
        if (!builder.typeVariables.isEmpty() && !method.getTypeParameters().isEmpty()) {
            builder.typeVariables.clear();
            
            // For each type parameter in the method, rebind it properly
            for (int i = 0; i < method.getTypeParameters().size(); i++) {
                var typeParam = method.getTypeParameters().get(i);
                String paramName = typeParam.getSimpleName().toString();
                
                // Get the original bounds
                List<? extends javax.lang.model.type.TypeMirror> bounds = typeParam.getBounds();
                
                // If first type param and has bounds like "S extends T", replace T with entity type
                if (i == 0 && !bounds.isEmpty()) {
                    // Simple approach: just use entityType as the bound
                    builder.typeVariables.add(TypeVariableName.get(paramName, entityType));
                } else if (!bounds.isEmpty()) {
                    // Keep original bounds for other type params
                    TypeName[] boundNames = new TypeName[bounds.size()];
                    for (int j = 0; j < bounds.size(); j++) {
                        boundNames[j] = TypeName.get(bounds.get(j));
                    }
                    builder.typeVariables.add(TypeVariableName.get(paramName, boundNames));
                } else {
                    // No bounds
                    builder.typeVariables.add(TypeVariableName.get(paramName));
                }
            }
        }
    }
}
