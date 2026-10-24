package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.ExceptionTranslation;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.Operation;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates method implementations for batch lifecycle operations:
 * saveAll, deleteAll(Iterable), and deleteByIds.
 *
 * <p>Each method loops over the input iterable and delegates to the
 * corresponding single-entity method (save, deleteById).</p>
 */
public final class LifecycleEmitter {

    private final Types types;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;

    public LifecycleEmitter(Types types, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.types = types;
        this.baseRepositoryType = baseRepositoryType;
        this.repositoryOwnType = repositoryOwnType;
    }

    private DeclaredType resolveTypeFor(ExecutableElement method) {
        if (method.getEnclosingElement().equals(repositoryOwnType.asElement())) {
            return repositoryOwnType;
        }
        return baseRepositoryType;
    }

    /**
     * Generates a method override for a batch lifecycle method.
     *
     * @return the method spec, or {@code null} if the method is not a batch lifecycle method
     */
    public MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        Operation op = method.operation();
        String name = method.name();

        // Detect batch methods by name or by parameter type (List/Collection)
        boolean isBatch = false;
        if ("saveAll".equals(name) || "insertAll".equals(name) || "updateAll".equals(name)
                || "deleteAll".equals(name) || "deleteByIds".equals(name)) {
            isBatch = true;
        } else if (!method.parameters().isEmpty()) {
            String paramType = method.parameters().getFirst().asType().toString();
            if (paramType.startsWith("java.util.List") || paramType.startsWith("java.util.Collection")) {
                isBatch = true;
            }
        }

        if (!isBatch) {
            return null;
        }

        if (op == Operation.SAVE) {
            return emitSaveAll(ctx, method);
        }
        if (op == Operation.INSERT) {
            return emitInsertAll(ctx, method);
        }
        if (op == Operation.UPDATE) {
            return emitUpdateAll(ctx, method);
        }
        if (op == Operation.DELETE) {
            if ("deleteByIds".equals(name)) {
                return emitDeleteByIds(ctx, method);
            }
            return emitDeleteAll(ctx, method);
        }
        return null;
    }

    // ── saveAll ──────────────────────────────────────────────────────────

    private MethodSpec emitSaveAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        // Extract the collection parameter name from the method signature
        String collectionVar = method.parameters().isEmpty() ? "entities" 
                : method.parameters().getFirst().getSimpleName().toString();

        // Determine the ArrayList type parameter from the method's return type
        TypeName listTypeArg = resolveListTypeArg(method, entityClass);
        TypeName arrayListType = ParameterizedTypeName.get(
                ClassName.get(ArrayList.class), listTypeArg);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T result = new $T();\n", List.class, arrayListType);
        body.add("for ($T entity : $L) {\n", listTypeArg, collectionVar);
        body.add("    save(entity);\n");
        body.add("    result.add(entity);\n");
        body.add("}\n");
        body.add("return result;");

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    // ── insertAll ────────────────────────────────────────────────────────

    private MethodSpec emitInsertAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        String collectionVar = method.parameters().isEmpty() ? "entities" 
                : method.parameters().getFirst().getSimpleName().toString();
        TypeName listTypeArg = resolveListTypeArg(method, entityClass);
        TypeName arrayListType = ParameterizedTypeName.get(ClassName.get(ArrayList.class), listTypeArg);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T result = new $T();\n", List.class, arrayListType);
        body.add("for ($T entity : $L) {\n", listTypeArg, collectionVar);
        body.add("    insert(entity);\n");
        body.add("    result.add(entity);\n");
        body.add("}\n");
        body.add("return result;");

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    // ── updateAll ────────────────────────────────────────────────────────

    private MethodSpec emitUpdateAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        String collectionVar = method.parameters().isEmpty() ? "entities" 
                : method.parameters().getFirst().getSimpleName().toString();
        TypeName listTypeArg = resolveListTypeArg(method, entityClass);
        TypeName arrayListType = ParameterizedTypeName.get(ClassName.get(ArrayList.class), listTypeArg);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T result = new $T();\n", List.class, arrayListType);
        body.add("for ($T entity : $L) {\n", listTypeArg, collectionVar);
        body.add("    update(entity);\n");
        body.add("    result.add(entity);\n");
        body.add("}\n");
        body.add("return result;");

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    // ── deleteAll(Iterable<E>) ───────────────────────────────────────────

    private MethodSpec emitDeleteAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        String collectionVar = method.parameters().isEmpty() ? "entities" 
                : method.parameters().getFirst().getSimpleName().toString();

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("for ($T entity : $L) {\n", entityClass, collectionVar);
        body.add("    deleteById(entity.getId());\n");
        body.add("}");

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    // ── deleteByIds(Iterable<ID>) ────────────────────────────────────────

    private MethodSpec emitDeleteByIds(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        String idTypeFqn = idProp(ctx.entity()).javaType();
        ClassName idClass = ClassName.bestGuess(idTypeFqn);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("for ($T id : ids) {\n", idClass);
        body.add("    deleteById(id);\n");
        body.add("}");

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Returns the id Prop from a TypeDefinition, or null if none. */
    private static TypeDefinition.Prop.Id idProp(TypeDefinition def) {
        return switch (def) {
            case TypeDefinition.Entity e -> e.id();
            case TypeDefinition.Aggregate a -> a.id();
            default -> null;
        };
    }

    /**
     * Resolves the type argument for the ArrayList in saveAll.
     * If the method return type has a type variable (e.g. List&lt;S&gt;), uses that.
     * Otherwise falls back to the concrete entity type.
     */
    private TypeName resolveListTypeArg(MethodContext method, ClassName entityClass) {
        TypeName returnType = TypeName.get(method.method().getReturnType());
        if (returnType instanceof ParameterizedTypeName pt) {
            if (!pt.typeArguments.isEmpty()) {
                return pt.typeArguments.getFirst();
            }
        }
        return entityClass;
    }
}
