package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.MutationPlan;
import io.github.fludakit.data.metadata.MutationPlan.*;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.analysis.MutationPlanAnalyzer;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.ExceptionTranslation;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.sql.UpsertForm;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Generates method implementations for write operations: save, insert, update, and deleteById.
 *
 * <p>Dispatches on {@link Operation}:</p>
 * <ul>
 *   <li>{@code SAVE} &rarr; upsert (dialect-specific) with null-id fallback to insert</li>
 *   <li>{@code INSERT} &rarr; insert with optional generated key</li>
 *   <li>{@code UPDATE} &rarr; update by identifier</li>
 *   <li>{@code DELETE} (no predicates) &rarr; delete by identifier</li>
 * </ul>
 */
public final class WriteEmitter {

    private final SqlDialect dialect;
    private final Types types;
    private final Elements elements;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;

    public WriteEmitter(SqlDialect dialect, Types types, Elements elements, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.dialect = dialect;
        this.types = types;
        this.elements = elements;
        this.baseRepositoryType = baseRepositoryType;
        this.repositoryOwnType = repositoryOwnType;
    }

    public WriteEmitter(Types types, Elements elements, DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this(H2Dialect.INSTANCE, types, elements, baseRepositoryType, repositoryOwnType);
    }

    private DeclaredType resolveTypeFor(ExecutableElement method) {
        if (method.getEnclosingElement().equals(repositoryOwnType.asElement())) {
            return repositoryOwnType;
        }
        return baseRepositoryType;
    }

    private static final ClassName GENERATED_KEY_HOLDER =
            ClassName.get("io.github.fludakit.jdbc.support", "GeneratedKeyHolder");

    /**
     * Generates a method override for a write method.
     *
     * @return the method spec, or {@code null} if the method is not handled by this emitter
     */
    public MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        Operation op = method.operation();

        // Skip batch lifecycle methods — handled by LifecycleEmitter
        // Detect by name or by parameter type (List/Collection)
        String name = method.name();
        if ("saveAll".equals(name) || "deleteAll".equals(name) || "deleteByIds".equals(name)
                || "insertAll".equals(name) || "updateAll".equals(name)) {
            return null;
        }
        // Also skip if the first parameter is a collection type (batch operation)
        if (!method.parameters().isEmpty()) {
            String paramType = method.parameters().getFirst().asType().toString();
            if (paramType.startsWith("java.util.List") || paramType.startsWith("java.util.Collection")) {
                return null;
            }
        }

        if (op == Operation.SAVE) {
            return emitSave(ctx, method);
        }
        if (op == Operation.INSERT) {
            return emitInsert(ctx, method);
        }
        if (op == Operation.UPDATE) {
            return emitUpdate(ctx, method);
        }
        if (op == Operation.DELETE && method.predicates().isEmpty()) {
            return emitDeleteById(ctx, method);
        }
        return null;
    }

    // ── SAVE ─────────────────────────────────────────────────────────────

    private MethodSpec emitSave(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        // Extract the entity parameter name from the method signature
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().getFirst().getSimpleName().toString();
        TypeDefinition.Prop.Id idProp = idProp(entity);
        String idTypeFqn = idProp != null ? idProp.javaType() : "java.lang.Long";
        IdStrategy strategy = idStrategy(idTypeFqn);
        boolean hasUpsert = dialect.upsertForm() != UpsertForm.NONE;

        // Compute the mutation plan for cascade-driven code generation
        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.operation());

        // Register SQL constants
        String upsertSql;
        if (hasUpsert) {
            SqlBuilder sb = new SqlBuilder(entity, entity);
            sb.dialect(dialect);
            upsertSql = sb.upsert();
        } else {
            SqlBuilder sb = new SqlBuilder(entity, entity);
            sb.dialect(dialect);
            upsertSql = sb.update();
        }
        String upsertConstName = ctx.registerConstant(
                hasUpsert ? "UPSERT" : "UPDATE_FOR_SAVE", upsertSql);

        SqlBuilder insertSb = new SqlBuilder(entity, entity);
        insertSb.dialect(dialect);
        String insertSql = strategy == IdStrategy.APP_ASSIGNED
                ? insertSb.insert(true)
                : insertSb.insert(false);
        String insertConstName = ctx.registerConstant("INSERT", insertSql);

        // Build method body
        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        // Pre-insert cascade: PRE_INSERT specs (compositions that need child IDs before root insert)
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.PRE_INSERT) {
                emitPreInsertSpec(body, spec, ctx, entityVar);
            }
        }

        if (strategy == IdStrategy.APP_ASSIGNED) {
            if (hasUpsert) {
                // Just upsert — no null check needed for app-assigned
                body.add("jdbcClient.sql($L)", upsertConstName);
                appendAllBindings(body, entity, entityVar);
                body.add(".update();");
            } else {
                // Update; if 0 rows, insert
                body.add("int $L = jdbcClient.sql($L)", "updated", upsertConstName);
                appendAllBindings(body, entity, entityVar);
                body.add(".update();\n");
                body.add("if ($L == 0) {\n", "updated");
                body.add("    jdbcClient.sql($L)", insertConstName);
                appendAllBindings(body, entity, entityVar);
                body.add(".update();\n");
                body.add("}");
            }
        } else {
            // Generated key strategy: null id → insert, non-id → upsert
            body.add("if ($L.getId() == null) {\n", entityVar);

            if (strategy == IdStrategy.NUMERIC) {
                body.add("    $T $L = new $T();\n", GENERATED_KEY_HOLDER, "keys", GENERATED_KEY_HOLDER);
                body.add("    jdbcClient.sql($L)", insertConstName);
                appendNonIdBindings(body, entity, entityVar);
                body.add(".update($L);\n", "keys");
                body.add("    if ($L.getKey() != null) {\n", "keys");
                body.add("        $L.setId((($T) $L.getKey()).$L());\n",
                        entityVar, Number.class, "keys", numericAccessor(idTypeFqn));
                body.add("    }\n");
            } else {
                // UUID
                body.add("    $T $L = new $T();\n", GENERATED_KEY_HOLDER, "keys", GENERATED_KEY_HOLDER);
                body.add("    jdbcClient.sql($L)", insertConstName);
                appendNonIdBindings(body, entity, entityVar);
                body.add(".update($L);\n", "keys");
                body.add("    if ($L.getKey() != null) {\n", "keys");
                body.add("        $L.setId(($T) $L.getKey());\n",
                        entityVar, ClassName.bestGuess(idTypeFqn), "keys");
                body.add("    }\n");
            }

            body.add("} else {\n");
            body.add("    jdbcClient.sql($L)", upsertConstName);
            appendAllBindings(body, entity, entityVar);
            body.add(".update();\n");
            body.add("}");
        }

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        // Post-insert cascade: non-PRE_INSERT specs (DIFF_SYNC, REPLACE, etc.)
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.PRE_INSERT) {
                emitPostCascadeSpec(builder, spec, ctx, entityVar);
            }
        }

        // Return after cascade so the return statement isn't placed before cascade code
        if (returnsEntity) {
            builder.addStatement("return $L", entityVar);
        }

        return builder.build();
    }

    /**
     * Generates code for a single PRE_INSERT cascade spec (composition association).
     * Inserts the child before the root so its generated ID can be bound as FK on the root row.
     * Recurses into the spec's children after the child insert.
     */
    private void emitPreInsertSpec(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) return;
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return;

        String childVar = entityVar + "." + assoc.name();

        SqlBuilder childSb = new SqlBuilder(childEntity, childEntity);
        childSb.dialect(dialect);
        TypeDefinition.Prop.Id childIdProp = idProp(childEntity);
        String childIdTypeFqn = childIdProp != null ? childIdProp.javaType() : "java.lang.Long";
        IdStrategy childStrategy = idStrategy(childIdTypeFqn);
        String childInsertSql = childStrategy == IdStrategy.APP_ASSIGNED
                ? childSb.insert(true)
                : childSb.insert(false);
        String childInsertConst = ctx.registerConstant(
                "CASCADE_INSERT_" + assoc.name().toUpperCase(), childInsertSql);
        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "CASCADE_UPDATE_" + assoc.name().toUpperCase(), childUpdateSql);

        body.add("if ($L != null) {\n", childVar);

        // Pre-insert children of this composition (e.g. grandchild compositions)
        for (CascadeSpec child : spec.children()) {
            if (child.mode() == CascadeMode.PRE_INSERT) {
                emitPreInsertSpec(body, child, ctx, childVar);
            }
        }

        if (childStrategy == IdStrategy.APP_ASSIGNED) {
            body.add("    jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(body, childEntity, childVar);
            body.add(".update();\n");
        } else {
            body.add("    if ($L.getId() == null) {\n", childVar);
            body.add("        $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "childKeys_" + assoc.name(), GENERATED_KEY_HOLDER);
            body.add("        jdbcClient.sql($L)", childInsertConst);
            appendNonIdBindings(body, childEntity, childVar);
            body.add(".update($L);\n", "childKeys_" + assoc.name());
            if (childStrategy == IdStrategy.NUMERIC) {
                body.add("        if ($L.getKey() != null) {\n", "childKeys_" + assoc.name());
                body.add("            $L.setId((($T) $L.getKey()).$L());\n",
                        childVar, Number.class, "childKeys_" + assoc.name(), numericAccessor(childIdTypeFqn));
                body.add("        }\n");
            } else {
                body.add("        if ($L.getKey() != null) {\n", "childKeys_" + assoc.name());
                body.add("            $L.setId(($T) $L.getKey());\n",
                        childVar, ClassName.bestGuess(childIdTypeFqn), "childKeys_" + assoc.name());
                body.add("        }\n");
            }
            body.add("    } else {\n");
            body.add("        jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(body, childEntity, childVar);
            body.add(".update();\n");
            body.add("    }\n");
        }

        // Post-insert children of this composition (e.g. grandchild entity/element collections)
        for (CascadeSpec child : spec.children()) {
            if (child.mode() != CascadeMode.PRE_INSERT) {
                emitPostCascadeSpecIntoBody(body, child, ctx, childVar);
            }
        }

        body.add("}\n");
    }

    /**
     * Dispatches a post-root cascade spec to the appropriate code generator.
     * Writes to MethodSpec.Builder (outside the try-catch exception translation block).
     */
    private void emitPostCascadeSpec(MethodSpec.Builder builder, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        CodeBlock code = buildPostCascadeCodeBlock(spec, ctx, entityVar);
        if (!code.toString().isEmpty()) {
            builder.addStatement(code);
        }
    }

    /**
     * Dispatches a post-root cascade spec into a CodeBlock.Builder (inside the try-catch block).
     * Used for children of PRE_INSERT specs that are themselves post-insert (DIFF_SYNC, REPLACE).
     */
    private void emitPostCascadeSpecIntoBody(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        CodeBlock code = buildPostCascadeCodeBlock(spec, ctx, entityVar);
        if (!code.toString().isEmpty()) {
            body.add(code);
        }
    }

    /**
     * Builds a CodeBlock for a post-root cascade spec (DIFF_SYNC, REPLACE, CASCADE_DELETE).
     */
    private CodeBlock buildPostCascadeCodeBlock(CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        return switch (spec.mode()) {
            case UPDATE -> buildCompositionUpdateCodeBlock(spec, ctx, entityVar);
            case DIFF_SYNC -> buildDiffSyncCodeBlock(spec, ctx, entityVar);
            case REPLACE -> {
                if (spec.prop() instanceof TypeDefinition.Prop.ElementCollection ec) {
                    yield buildElementCollectionReplaceCodeBlock(ctx, entityVar, ec);
                }
                yield CodeBlock.builder().build();
            }
            case CASCADE_DELETE -> buildCascadeDeleteCodeBlock(spec, ctx, entityVar);
            default -> CodeBlock.builder().build();
        };
    }

    /**
     * Builds a CodeBlock for updating a composition child (UPDATE mode).
     * The child already exists in DB; performs an UPDATE with all column bindings.
     */
    private CodeBlock buildCompositionUpdateCodeBlock(CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) return CodeBlock.builder().build();
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return CodeBlock.builder().build();

        String childVar = entityVar + "." + assoc.name();

        SqlBuilder childSb = new SqlBuilder(childEntity, childEntity);
        childSb.dialect(dialect);
        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "CASCADE_UPDATE_" + assoc.name().toUpperCase(), childUpdateSql);

        CodeBlock.Builder cascade = CodeBlock.builder();
        cascade.add("if ($L != null) {\n", childVar);
        cascade.add("    jdbcClient.sql($L)", childUpdateConst);
        appendAllBindings(cascade, childEntity, childVar);
        cascade.add(".update();\n");

        // Recurse into children of this composition (element collections, entity collections)
        for (CascadeSpec child : spec.children()) {
            CodeBlock code = buildPostCascadeCodeBlock(child, ctx, childVar);
            if (!code.toString().isEmpty()) {
                cascade.add(code);
                cascade.add("\n");
            }
        }

        cascade.add("}");
        return cascade.build();
    }

    /**
     * Builds a CodeBlock for diff-sync of an EntityCollection cascade spec.
     * 1. Load existing child IDs from DB
     * 2. Upsert each in-memory child (insert if null id, update if non-null)
     * 3. Delete children whose IDs are no longer in the collection
     * Recurses into spec's children inside the per-child loop.
     */
    private CodeBlock buildDiffSyncCodeBlock(CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.EntityCollection ec)) return CodeBlock.builder().build();
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return CodeBlock.builder().build();

        String collectionGetter = entityVar + "." + getter(ec.name()) + "()";
        String childVar = "child_" + ec.name();
        String parentIdExpr = entityVar + ".getId()";

        String inverseName = findInverseRelationName(childEntity, ctx.entity().typeName());

        SqlBuilder childSb = new SqlBuilder(childEntity, childEntity);
        childSb.dialect(dialect);
        TypeDefinition.Prop.Id childIdProp = idProp(childEntity);
        String childIdTypeFqn = childIdProp != null ? childIdProp.javaType() : "java.lang.Long";
        IdStrategy childStrategy = idStrategy(childIdTypeFqn);
        String childInsertSql = childStrategy == IdStrategy.APP_ASSIGNED
                ? childSb.insert(true)
                : childSb.insert(false);
        String childInsertConst = ctx.registerConstant(
                "EC_INSERT_" + ec.name().toUpperCase(), childInsertSql);

        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "EC_UPDATE_" + ec.name().toUpperCase(), childUpdateSql);

        String childTable = dialect.quoteIdentifier(childEntity.tableName());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());
        String idColumn = dialect.quoteIdentifier(childEntity.idColumn());
        String deleteSql = "DELETE FROM " + childTable + " WHERE " + idColumn + " = :childId AND " + fkColumn + " = :parentId";
        String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);

        String selectIdsSql = "SELECT " + idColumn + " FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
        String selectIdsConst = ctx.registerConstant("EC_IDS_" + ec.name().toUpperCase(), selectIdsSql);

        CodeBlock.Builder cascade = CodeBlock.builder();

        ClassName childIdType = ClassName.bestGuess(childIdTypeFqn);
        cascade.add("$T<$T> $L = new $T<>(jdbcClient\n",
                java.util.Set.class, childIdType, "existingIds_" + ec.name(), java.util.HashSet.class);
        cascade.add("    .sql($L)\n", selectIdsConst);
        cascade.add("    .param($S, $L)\n", "parentId", parentIdExpr);
        cascade.add("    .query($T.class)\n", childIdType);
        cascade.add("    .list());\n");

        cascade.add("$T<$T> $L = new $T<>();\n",
                java.util.Set.class, childIdType, "currentIds_" + ec.name(), java.util.HashSet.class);

        cascade.add("if ($L != null) {\n", collectionGetter);
        cascade.add("    for (var $L : $L) {\n", childVar, collectionGetter);

        if (childStrategy == IdStrategy.APP_ASSIGNED) {
            cascade.add("        jdbcClient.sql($L)", childUpdateConst);
            appendChildPropBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            cascade.add(".update();\n");
            cascade.add("        $L.add($L.getId());\n", "currentIds_" + ec.name(), childVar);
        } else {
            cascade.add("        if ($L.getId() == null) {\n", childVar);
            cascade.add("            $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "ecKeys_" + ec.name(), GENERATED_KEY_HOLDER);
            cascade.add("            jdbcClient.sql($L)", childInsertConst);
            appendChildNonIdBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            cascade.add(".update($L);\n", "ecKeys_" + ec.name());
            if (childStrategy == IdStrategy.NUMERIC) {
                cascade.add("            if ($L.getKey() != null) {\n", "ecKeys_" + ec.name());
                cascade.add("                $L.setId((($T) $L.getKey()).$L());\n",
                        childVar, Number.class, "ecKeys_" + ec.name(), numericAccessor(childIdTypeFqn));
                cascade.add("            }\n");
            } else {
                cascade.add("            if ($L.getKey() != null) {\n", "ecKeys_" + ec.name());
                cascade.add("                $L.setId(($T) $L.getKey());\n",
                        childVar, ClassName.bestGuess(childIdTypeFqn), "ecKeys_" + ec.name());
                cascade.add("            }\n");
            }
            cascade.add("        } else {\n");
            cascade.add("            jdbcClient.sql($L)", childUpdateConst);
            appendChildPropBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            cascade.add(".update();\n");
            cascade.add("        }\n");
            cascade.add("        $L.add($L.getId());\n", "currentIds_" + ec.name(), childVar);
        }

        for (CascadeSpec child : spec.children()) {
            emitCascadeSpecIntoLoop(cascade, child, ctx, childVar);
        }

        cascade.add("    }\n");
        cascade.add("}\n");

        cascade.add("$L.removeAll($L);\n", "existingIds_" + ec.name(), "currentIds_" + ec.name());
        cascade.add("for ($T $L : $L) {\n", childIdType, "orphanId", "existingIds_" + ec.name());
        cascade.add("    jdbcClient.sql($L).param($S, $L).param($S, $L).update();\n",
                deleteConst, "childId", "orphanId", "parentId", parentIdExpr);
        cascade.add("}");

        return cascade.build();
    }

    /**
     * Emits cascade code inside a per-child loop (for children of DIFF_SYNC specs).
     * Dispatches based on the child spec's mode.
     */
    private void emitCascadeSpecIntoLoop(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (spec.mode() == CascadeMode.PRE_INSERT) {
            emitPreInsertSpec(body, spec, ctx, entityVar);
        } else {
            CodeBlock code = buildPostCascadeCodeBlock(spec, ctx, entityVar);
            if (!code.toString().isEmpty()) {
                body.add(code);
            }
        }
    }

    /**
     * Builds a CodeBlock for CASCADE_DELETE (DELETE by FK).
     */
    private CodeBlock buildCascadeDeleteCodeBlock(CascadeSpec spec, RepositoryContext ctx, String parentIdExpr) {
        if (spec.prop() instanceof TypeDefinition.Prop.EntityCollection ec
                && spec.target() instanceof TypeDefinition.Entity childEntity) {
            String childTable = dialect.quoteIdentifier(childEntity.tableName());
            String fkColumn = dialect.quoteIdentifier(ec.fkColumn());
            String deleteSql = "DELETE FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
            String deleteConst = ctx.registerConstant("EC_COLL_DELETE_" + ec.name().toUpperCase(), deleteSql);
            return CodeBlock.builder().add("jdbcClient.sql($L).param($S, $L).update()",
                    deleteConst, "parentId", parentIdExpr).build();
        }
        return CodeBlock.builder().build();
    }

    /**
     * Builds a CodeBlock for element collection REPLACE (delete-all + re-insert-all).
     */
    private CodeBlock buildElementCollectionReplaceCodeBlock(RepositoryContext ctx,
                                                            String entityVar, TypeDefinition.Prop.ElementCollection ec) {
        String getterName = entityVar + ".get" + capitalize(ec.name()) + "()";
        String parentIdExpr = entityVar + ".getId()";

        TypeDefinition elementType = ec.definition();
        if (elementType == null) return CodeBlock.builder().build();

        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());

        java.util.List<String> columns = new java.util.ArrayList<>();
        columns.add(fkColumn);
        for (TypeDefinition.Prop prop : elementType.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.columnName()));
            }
        }

        java.util.List<String> params = new java.util.ArrayList<>();
        params.add(":parentId");
        for (TypeDefinition.Prop prop : elementType.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                params.add(":" + s.name());
            }
        }

        String insertSql = "INSERT INTO " + childTable + " (" + String.join(", ", columns) +
                          ") VALUES (" + String.join(", ", params) + ")";
        String insertConst = ctx.registerConstant("EC_INSERT_" + ec.name().toUpperCase(), insertSql);

        String deleteSql = "DELETE FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
        String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);

        CodeBlock.Builder cascade = CodeBlock.builder();
        cascade.add("jdbcClient.sql($L).param($S, $L).update();\n",
                deleteConst, "parentId", parentIdExpr);
        cascade.add("if ($L != null) {\n", getterName);
        cascade.add("    for (var element : $L) {\n", getterName);
        cascade.add("        jdbcClient.sql($L)", insertConst);
        cascade.add(".param($S, $L)", "parentId", parentIdExpr);
        for (TypeDefinition.Prop prop : elementType.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                String propGetter = "get" + capitalize(s.name());
                cascade.add(".param($S, element.$L())", s.name(), propGetter);
            }
        }
        cascade.add(".update();\n");
        cascade.add("    }\n");
        cascade.add("}");

        return cascade.build();
    }

    // ── INSERT ───────────────────────────────────────────────────────────

    private MethodSpec emitInsert(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().getFirst().getSimpleName().toString();
        TypeDefinition.Prop.Id idProp = idProp(entity);
        String idTypeFqn = idProp != null ? idProp.javaType() : "java.lang.Long";
        IdStrategy strategy = idStrategy(idTypeFqn);

        // Compute the mutation plan for cascade-driven code generation
        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.operation());

        SqlBuilder sb = new SqlBuilder(entity, entity);
        sb.dialect(dialect);
        String insertSql = strategy == IdStrategy.APP_ASSIGNED
                ? sb.insert(true)
                : sb.insert(false);
        String constName = ctx.registerConstant("INSERT", insertSql);

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        // Pre-insert cascade: PRE_INSERT specs (compositions that need child IDs before root insert)
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.PRE_INSERT) {
                emitPreInsertSpec(body, spec, ctx, entityVar);
            }
        }

        if (strategy == IdStrategy.APP_ASSIGNED) {
            // Bind all columns including id, no keyholder
            body.add("jdbcClient.sql($L)", constName);
            appendAllBindings(body, entity, entityVar);
            body.add(".update();");
        } else {
            // Generated key with keyholder
            body.add("$T $L = new $T();\n", GENERATED_KEY_HOLDER, "keys", GENERATED_KEY_HOLDER);
            body.add("jdbcClient.sql($L)", constName);
            appendNonIdBindings(body, entity, entityVar);
            body.add(".update($L);\n", "keys");

            body.add("if ($L.getKey() != null) {\n", "keys");
            if (strategy == IdStrategy.NUMERIC) {
                body.add("    $L.setId((($T) $L.getKey()).$L());\n",
                        entityVar, Number.class, "keys", numericAccessor(idTypeFqn));
            } else {
                // UUID
                body.add("    $L.setId(($T) $L.getKey());\n",
                        entityVar, ClassName.bestGuess(idTypeFqn), "keys");
            }
            body.add("}");
        }

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        // Post-insert cascade: non-PRE_INSERT specs (DIFF_SYNC, REPLACE, etc.)
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.PRE_INSERT) {
                emitPostCascadeSpec(builder, spec, ctx, entityVar);
            }
        }

        if (returnsEntity) {
            builder.addStatement("return $L", entityVar);
        }

        return builder.build();
    }

    // ── UPDATE ───────────────────────────────────────────────────────────

    private MethodSpec emitUpdate(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().getFirst().getSimpleName().toString();

        // Compute the mutation plan for cascade-driven code generation
        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.operation());

        SqlBuilder sb = new SqlBuilder(entity, entity);
        sb.dialect(dialect);
        String updateSql = sb.update();
        String constName = ctx.registerConstant("UPDATE", updateSql);

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        // Bind non-id columns (SET clause), then id (WHERE clause)
        body.add("jdbcClient.sql($L)", constName);
        appendNonIdBindings(body, entity, entityVar);
        body.add(".param($S, $L.getId()).update();\n", "id", entityVar);

        // Post-update cascade: iterate over plan's cascade specs
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            CodeBlock code = buildPostCascadeCodeBlock(spec, ctx, entityVar);
            if (!code.toString().isEmpty()) {
                body.add(code);
                body.add("\n");
            }
        }

        if (returnsEntity) body.add("return $L;", entityVar);

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        return builder.build();
    }

    // ── DELETE BY ID ─────────────────────────────────────────────────────

    private MethodSpec emitDeleteById(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();

        // Compute the mutation plan for cascade-driven code generation
        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.operation());

        SqlBuilder sb = new SqlBuilder(entity, entity);
        sb.dialect(dialect);
        String deleteSql = sb.deleteById();
        String constName = ctx.registerConstant("DELETE", deleteSql);

        // Determine if this is deleteById(ID) or delete(Entity)
        // deleteById takes the entity's ID type as parameter, delete takes the entity itself
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().getFirst().getSimpleName().toString();
        String parentIdExpr = method.firstParameterIsEntity(entity) ? entityVar + ".getId()" : entityVar;

        CodeBlock.Builder body = CodeBlock.builder();

        // Phase 1: Load FK values for composing to-one associations (before parent delete)
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.CASCADE_DELETE && spec.prop() instanceof TypeDefinition.Prop.Association) {
                emitCompositionLoadFk(body, spec, ctx, parentIdExpr);
            }
        }

        // Phase 2: Pre-delete cascade for element collections and entity collections
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.CASCADE_DELETE && !(spec.prop() instanceof TypeDefinition.Prop.Association)) {
                emitCascadeDelete(body, spec, ctx, parentIdExpr);
            }
        }

        // Phase 3: Delete the parent row
        body.add("jdbcClient.sql($L).param($S, $L).update();\n", constName, "id", parentIdExpr);

        // Phase 4: Delete composing to-one associations (after parent delete, using loaded FK values)
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.CASCADE_DELETE && spec.prop() instanceof TypeDefinition.Prop.Association) {
                emitCompositionDelete(body, spec, ctx, entityVar);
            }
        }

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    /**
     * Emits a CASCADE_DELETE step into a CodeBlock.Builder.
     * Delegates to {@link #buildCascadeDeleteCodeBlock} which handles
     * EntityCollection, Association (composition), and ElementCollection deletes by FK.
     */
    private void emitCascadeDelete(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String parentIdExpr) {
        if (spec.prop() instanceof TypeDefinition.Prop.ElementCollection ec) {
            // Element collection: delete by FK from the element child table
            String childTable = dialect.quoteIdentifier(ec.childTable());
            String fkColumn = dialect.quoteIdentifier(ec.fkColumn());
            String deleteSql = "DELETE FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
            String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);
            body.add("jdbcClient.sql($L).param($S, $L).update();\n", deleteConst, "parentId", parentIdExpr);
        } else {
            // Entity collection or composition association: delegate to shared builder
            CodeBlock code = buildCascadeDeleteCodeBlock(spec, ctx, parentIdExpr);
            if (!code.toString().isEmpty()) {
                body.add(code);
                body.add(";\n");
            }
        }
    }

    /**
     * Emits code to load the FK value for a composing to-one association before the parent is deleted.
     */
    private void emitCompositionLoadFk(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String parentIdExpr) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) return;
        if (!(spec.target() instanceof TypeDefinition.Entity)) return;

        String parentTable = dialect.quoteIdentifier(ctx.entity().tableName());
        String fkColumn = dialect.quoteIdentifier(assoc.fkColumn());
        String idColumn = dialect.quoteIdentifier(ctx.entity().idColumn());

        String loadSql = "SELECT " + fkColumn + " FROM " + parentTable + " WHERE " + idColumn + " = :id";
        String loadConst = ctx.registerConstant("LOAD_FK_" + assoc.name().toUpperCase(), loadSql);

        body.add("Long $L = jdbcClient.sql($L).param($S, $L).query(Long.class).single();\n",
                "childId_" + assoc.name(), loadConst, "id", parentIdExpr);
    }

    /**
     * Emits code to delete a composing to-one association after the parent is deleted.
     */
    private void emitCompositionDelete(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) return;
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return;

        String childTable = dialect.quoteIdentifier(childEntity.tableName());
        String childIdColumn = dialect.quoteIdentifier(childEntity.idColumn());

        String deleteSql = "DELETE FROM " + childTable + " WHERE " + childIdColumn + " = :childId";
        String deleteConst = ctx.registerConstant("CASCADE_DELETE_" + assoc.name().toUpperCase(), deleteSql);

        body.add("if ($L != null) {\n", "childId_" + assoc.name())
            .add("    jdbcClient.sql($L).param($S, $L).update();\n",
                    deleteConst, "childId", "childId_" + assoc.name())
            .add("}\n");
    }

    // ── Binding helpers ──────────────────────────────────────────────────

    /** Appends .param() calls for ALL columns (id + non-id props). */
    private void appendAllBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
        // Id binding
        TypeDefinition.Prop.Id idProp = idProp(entity);
        if (idProp != null) {
            body.add(".param($S, $L.getId())", idProp.name(), varName);
        }
        // Non-id props
        appendPropBindings(body, entity, varName);
    }

    /** Appends .param() calls for non-id columns only. */
    private void appendNonIdBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
        appendPropBindings(body, entity, varName);
    }

    /**
     * Appends .param() calls for all props of a child entity in an entity collection.
     * The inverse FK (back-reference to parent) is bound to the parent's ID.
     */
    private void appendChildPropBindings(CodeBlock.Builder body, TypeDefinition.Entity childEntity,
                                          String childVar, String parentIdExpr,
                                          String inverseName, String fkColumn) {
        TypeDefinition.Prop.Id idProp = idProp(childEntity);
        if (idProp != null) {
            body.add(".param($S, $L.getId())", idProp.name(), childVar);
        }
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            appendChildPropBinding(body, prop, childVar, "", parentIdExpr, inverseName, fkColumn);
        }
    }

    /**
     * Appends .param() calls for non-id props of a child entity in an entity collection.
     * The inverse FK (back-reference to parent) is bound to the parent's ID.
     */
    private void appendChildNonIdBindings(CodeBlock.Builder body, TypeDefinition.Entity childEntity,
                                           String childVar, String parentIdExpr,
                                           String inverseName, String fkColumn) {
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            appendChildPropBinding(body, prop, childVar, "", parentIdExpr, inverseName, fkColumn);
        }
    }

    /**
     * Appends one .param() call for a child property. The inverse FK uses the parent ID expression.
     */
    private void appendChildPropBinding(CodeBlock.Builder body, TypeDefinition.Prop prop,
                                         String varName, String prefix, String parentIdExpr,
                                         String inverseName, String fkColumn) {
        // Scalar props — just bind normally
        if (prop instanceof TypeDefinition.Prop.Scalar s) {
            appendColumnBinding(body, s, varName, prefix);
        } else if (prop instanceof TypeDefinition.Prop.Association assoc && 
                   (assoc.name().equals(inverseName) || (fkColumn != null && fkColumn.equals(assoc.fkColumn())))) {
            // This is the inverse FK — bind parent ID directly
            String paramName = prefix.isEmpty() ? assoc.name() : prefix + assoc.name();
            body.add(".param($S, $L)", paramName, parentIdExpr);
        } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
            appendRelationBinding(body, assoc, varName, prefix);
        } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
            appendRelationBinding(body, embedded, varName, prefix);
        }
    }

    /** Appends .param() calls for each column and relation. */
    private void appendPropBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                appendColumnBinding(body, s, varName, "");
            } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
                appendRelationBinding(body, assoc, varName, "");
            } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
                appendRelationBinding(body, embedded, varName, "");
            }
        }
    }

    /**
     * Appends .param() call for a scalar column mapping.
     */
    private void appendColumnBinding(CodeBlock.Builder body, TypeDefinition.Prop.Scalar col,
                                      String varName, String prefix) {
        String paramName = prefix.isEmpty() ? col.name() : prefix + col.name();
        if (prefix.isEmpty()) {
            // Top-level scalar: direct getter, with enum conversion
            if (isEnum(col.javaType())) {
                body.add(".param($S, $L.$L() == null ? null : $L.$L().name())",
                        paramName, varName, getter(col.name()), varName, getter(col.name()));
            } else {
                body.add(".param($S, $L.$L())", paramName, varName, getter(col.name()));
            }
        } else {
            // Nested scalar (inside embedded): null-guard the embedded chain
            if (isEnum(col.javaType())) {
                String nullGuarded = buildNullGuardedGetter(varName, prefix, col.name());
                body.add(".param($S, $L == null ? null : $L.name())", paramName, nullGuarded, nullGuarded);
            } else {
                String nullGuarded = buildNullGuardedGetter(varName, prefix, col.name());
                body.add(".param($S, $L)", paramName, nullGuarded);
            }
        }
    }

    /**
     * Appends .param() calls for a relation mapping (ASSOCIATION FK or EMBEDDED flattened columns).
     */
    private void appendRelationBinding(CodeBlock.Builder body, TypeDefinition.Prop rel,
                                        String varName, String prefix) {
        if (rel instanceof TypeDefinition.Prop.Association assoc && assoc.fkColumn() != null) {
            String paramName = prefix.isEmpty() ? assoc.name() : prefix + assoc.name();
            String getterChain = buildGetterChain(varName, prefix, assoc.name());
            body.add(".param($S, $L == null ? null : $L.getId())",
                    paramName, getterChain, getterChain);
        } else if (rel instanceof TypeDefinition.Prop.Embedded embedded && embedded.definition() != null) {
            String newPrefix = prefix.isEmpty()
                    ? embedded.name() + "_"
                    : prefix + embedded.name() + "_";
            for (TypeDefinition.Prop childProp : embedded.definition().properties()) {
                if (childProp instanceof TypeDefinition.Prop.Scalar s) {
                    appendColumnBinding(body, s, varName, newPrefix);
                } else if (childProp instanceof TypeDefinition.Prop.Association childAssoc) {
                    appendRelationBinding(body, childAssoc, varName, newPrefix);
                } else if (childProp instanceof TypeDefinition.Prop.Embedded childEmbedded) {
                    appendRelationBinding(body, childEmbedded, varName, newPrefix);
                }
            }
        }
        // TO_MANY and COMPOSITION are not bound on the parent row
    }

    /**
     * Builds a null-guarded getter chain for nested embedded paths.
     * E.g., for prefix="address_point_" and name="lat", produces:
     * {@code entity.getAddress() == null || entity.getAddress().getPoint() == null ? null : entity.getAddress().getPoint().getLat()}
     */
    private String buildNullGuardedGetter(String varName, String prefix, String simpleName) {
        String[] segments = prefix.split("_");
        int segCount = segments.length;
        if (segCount > 0 && segments[segCount - 1].isEmpty()) segCount--;

        if (segCount == 0) {
            return varName + "." + getter(simpleName) + "()";
        }

        // Build null check: check each intermediate level
        StringBuilder nullCheck = new StringBuilder();
        StringBuilder chain = new StringBuilder(varName);
        for (int i = 0; i < segCount; i++) {
            if (i > 0) nullCheck.append(" || ");
            chain.append(".").append(getter(segments[i])).append("()");
            nullCheck.append(chain).append(" == null");
        }

        // Build the full getter: entity.getAddress().getPoint().getLat()
        String fullGetter = chain + "." + getter(simpleName) + "()";

        return nullCheck + " ? null : " + fullGetter;
    }

    /** Builds a getter chain like "entity.getAddress().getStreet()" for embedded paths. */
    private String buildGetterChain(String varName, String prefix, String simpleName) {
        if (prefix.isEmpty()) {
            return varName + "." + getter(simpleName) + "()";
        }
        // prefix is like "address_" — split by _ to get path segments
        String[] segments = prefix.split("_");
        StringBuilder sb = new StringBuilder(varName);
        for (String seg : segments) {
            sb.append(".").append(getter(seg)).append("()");
        }
        sb.append(".").append(getter(simpleName)).append("()");
        return sb.toString();
    }

    // ── ID strategy ──────────────────────────────────────────────────────

    /** Returns the id Prop from a TypeDefinition, or null if none. */
    private static TypeDefinition.Prop.Id idProp(TypeDefinition def) {
        return switch (def) {
            case TypeDefinition.Entity e -> e.id();
            case TypeDefinition.Aggregate a -> a.id();
            default -> null;
        };
    }

    /**
     * Finds the name of the TO_ONE relation on the child that targets the parent type.
     * Used to identify the inverse FK when binding child entity parameters.
     */
    private static String findInverseRelationName(TypeDefinition.Entity childDef, String parentTypeName) {
        // First pass: exact type name match
        for (TypeDefinition.Prop prop : childDef.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc 
                    && assoc.definition() != null 
                    && parentTypeName.equals(assoc.definition().typeName())) {
                return assoc.name();
            }
        }
        // Second pass: relaxed match - check simple class name (without package)
        String parentSimpleName = parentTypeName.contains(".") 
                ? parentTypeName.substring(parentTypeName.lastIndexOf('.') + 1)
                : parentTypeName;
        for (TypeDefinition.Prop prop : childDef.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc 
                    && assoc.definition() != null 
                    && assoc.definition().typeName() != null) {
                String targetSimpleName = assoc.definition().typeName().contains(".")
                        ? assoc.definition().typeName().substring(assoc.definition().typeName().lastIndexOf('.') + 1)
                        : assoc.definition().typeName();
                if (parentSimpleName.equals(targetSimpleName)) {
                    return assoc.name();
                }
            }
        }
        return null;
    }

    private enum IdStrategy { NUMERIC, UUID, APP_ASSIGNED }

    private IdStrategy idStrategy(String typeFqn) {
        return switch (typeFqn) {
            case "java.lang.Long", "long", "java.lang.Integer", "int",
                 "java.lang.Short", "short" -> IdStrategy.NUMERIC;
            case "java.util.UUID" -> IdStrategy.UUID;
            default -> IdStrategy.APP_ASSIGNED;
        };
    }

    private String numericAccessor(String typeFqn) {
        return switch (typeFqn) {
            case "java.lang.Integer", "int" -> "intValue";
            case "java.lang.Short", "short" -> "shortValue";
            default -> "longValue"; // Long, long
        };
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private String getter(String propertyName) {
        return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    }

    private boolean isEnum(String typeFqn) {
        TypeElement typeElement = elements.getTypeElement(typeFqn);
        return typeElement != null && typeElement.getKind() == ElementKind.ENUM;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
