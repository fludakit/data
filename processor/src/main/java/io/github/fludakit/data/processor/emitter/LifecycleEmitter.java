package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.ColumnMapping;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.Operation;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
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
    private final Elements elements;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;

    public LifecycleEmitter(Types types, Elements elements, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.types = types;
        this.elements = elements;
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

        if (op == Operation.SAVE && "saveAll".equals(name)) {
            return emitSaveAll(ctx, method);
        }
        if (op == Operation.INSERT && "insertAll".equals(name)) {
            return emitInsertAll(ctx, method);
        }
        if (op == Operation.UPDATE && "updateAll".equals(name)) {
            return emitUpdateAll(ctx, method);
        }
        if (op == Operation.DELETE && "deleteAll".equals(name)) {
            return emitDeleteAll(ctx, method);
        }
        if (op == Operation.DELETE && "deleteByIds".equals(name)) {
            return emitDeleteByIds(ctx, method);
        }
        return null;
    }

    // ── saveAll ──────────────────────────────────────────────────────────

    private MethodSpec emitSaveAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());

        // Register the SAVE SQL constant (same as single save)
        WriteEmitter writeEmitter = new WriteEmitter(types, elements, baseRepositoryType, repositoryOwnType);
        String saveSql = buildSaveSql(ctx);
        ctx.registerConstant("SAVE_ALL", saveSql);

        // Determine the ArrayList type parameter from the method's return type
        TypeName listTypeArg = resolveListTypeArg(method, entityClass);
        TypeName arrayListType = ParameterizedTypeName.get(
                ClassName.get(ArrayList.class), listTypeArg);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T result = new $T();\n", List.class, arrayListType);
        body.add("for ($T entity : entities) {\n", listTypeArg);
        body.add("    save(entity);\n");
        body.add("    result.add(entity);\n");
        body.add("}\n");
        body.add("return result;");

        builder.addCode(body.build());
        return builder.build();
    }

    // ── insertAll ────────────────────────────────────────────────────────

    private MethodSpec emitInsertAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        TypeName listTypeArg = resolveListTypeArg(method, entityClass);
        TypeName arrayListType = ParameterizedTypeName.get(ClassName.get(ArrayList.class), listTypeArg);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T result = new $T();\n", List.class, arrayListType);
        body.add("for ($T entity : entities) {\n", listTypeArg);
        body.add("    insert(entity);\n");
        body.add("    result.add(entity);\n");
        body.add("}\n");
        body.add("return result;");

        builder.addCode(body.build());
        return builder.build();
    }

    // ── updateAll ────────────────────────────────────────────────────────

    private MethodSpec emitUpdateAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        TypeName listTypeArg = resolveListTypeArg(method, entityClass);
        TypeName arrayListType = ParameterizedTypeName.get(ClassName.get(ArrayList.class), listTypeArg);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T result = new $T();\n", List.class, arrayListType);
        body.add("for ($T entity : entities) {\n", listTypeArg);
        body.add("    update(entity);\n");
        body.add("    result.add(entity);\n");
        body.add("}\n");
        body.add("return result;");

        builder.addCode(body.build());
        return builder.build();
    }

    // ── deleteAll(Iterable<E>) ───────────────────────────────────────────

    private MethodSpec emitDeleteAll(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("for ($T entity : entities) {\n", entityClass);
        body.add("    deleteById(entity.getId());\n");
        body.add("}");

        builder.addCode(body.build());
        return builder.build();
    }

    // ── deleteByIds(Iterable<ID>) ────────────────────────────────────────

    private MethodSpec emitDeleteByIds(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        String idTypeFqn = idColumnMapping(ctx.entity()).typeFqn();
        ClassName idClass = ClassName.bestGuess(idTypeFqn);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("for ($T id : ids) {\n", idClass);
        body.add("    deleteById(id);\n");
        body.add("}");

        builder.addCode(body.build());
        return builder.build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Returns the id ColumnMapping from a TypeDefinition, or null if none. */
    private static ColumnMapping idColumnMapping(TypeDefinition def) {
        for (ColumnMapping col : def.columnMappings()) {
            if (col.isId()) return col;
        }
        return null;
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
                return pt.typeArguments.get(0);
            }
        }
        return entityClass;
    }

    /**
     * Builds a placeholder save SQL string for the constant registration.
     * The actual SQL is generated by WriteEmitter for the single save method.
     */
    private String buildSaveSql(RepositoryContext ctx) {
        // The saveAll method delegates to save(), so it doesn't directly use SQL.
        // We register a placeholder constant for consistency.
        return "/* saveAll delegates to save() */";
    }
}
