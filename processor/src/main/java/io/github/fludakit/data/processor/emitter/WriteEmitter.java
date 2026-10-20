package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
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
            String paramType = method.parameters().get(0).asType().toString();
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
                : method.parameters().get(0).getSimpleName().toString();
        TypeDefinition.Prop.Id idProp = idProp(entity);
        String idTypeFqn = idProp != null ? idProp.javaType() : "java.lang.Long";
        IdStrategy strategy = idStrategy(idTypeFqn);
        boolean hasUpsert = dialect.upsertForm() != UpsertForm.NONE;

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

        // For aggregate roots with composing TO_ONE relations, emit pre-insert cascade
        // to ensure child IDs are generated before binding them as FKs
        boolean hasComposingAssociation = ctx.entity().ownsAggregateLifecycle() &&
                ctx.entity().properties().stream()
                    .anyMatch(prop -> prop instanceof TypeDefinition.Prop.Association assoc && assoc.composition() && assoc.target() != null);

        if (hasComposingAssociation) {
            emitPreInsertCascade(body, ctx, entityVar);
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
            // Generated key strategy: null id → insert, non-null id → upsert
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

        builder.addCode(body.build());

        // Post-insert cascade: entity collections (aggregate roots only) and element collections (all entities)
        if (ctx.entity().ownsAggregateLifecycle()) {
            emitPostInsertCascade(builder, ctx, entityVar);
        } else {
            // For non-aggregate entities, still persist element collections
            for (TypeDefinition.Prop prop : ctx.entity().properties()) {
                if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                    emitElementCollectionSave(builder, ctx, entityVar, ec);
                }
            }
        }

        // Return after cascade so the return statement isn't placed before cascade code
        if (returnsEntity) {
            builder.addStatement("return $L", entityVar);
        }

        return builder.build();
    }

    /**
     * Emits cascade write logic for aggregate root entities.
     * - TypeDefinition.Prop.Association with composition=true: save the composed child (backfill FK)
     * - TypeDefinition.Prop.EntityCollection: save each child with parent FK set
     */
    private void emitCascadeWrites(MethodSpec.Builder builder, RepositoryContext ctx, String entityVar) {
        TypeDefinition entity = ctx.entity();

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.composition() && assoc.target() != null) {
                // Composition: cascade save to the composed child
                emitCompositionSave(builder, ctx, entityVar, assoc);
            } else if (prop instanceof TypeDefinition.Prop.EntityCollection ec && ec.target() != null) {
                // Entity collection: save each child with parent FK
                emitEntityCollectionSave(builder, ctx, entityVar, ec);
            }
        }
    }

    /**
     * Pre-insert cascade for aggregate roots: saves composing ASSOCIATION children BEFORE
     * the root insert so their generated IDs can be bound as FK columns.
     */
    private void emitPreInsertCascade(CodeBlock.Builder body, RepositoryContext ctx, String entityVar) {
        TypeDefinition entity = ctx.entity();

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.composition() && assoc.target() instanceof TypeDefinition.Entity childEntity) {
                // Generate cascade save code inline in the body
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

                body.add("}\n");
            }
        }
    }

    /**
     * Post-insert cascade for aggregate roots: handles entity collections and element collections
     * AFTER the root has been inserted.
     */
    private void emitPostInsertCascade(MethodSpec.Builder builder, RepositoryContext ctx, String entityVar) {
        TypeDefinition entity = ctx.entity();

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection ec && ec.target() != null) {
                emitEntityCollectionSave(builder, ctx, entityVar, ec);
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                emitElementCollectionSave(builder, ctx, entityVar, ec);
            }
        }
    }

    /**
     * Cascade save for a composition: save the child aggregate, then backfill the FK
     * on the parent if needed.
     */
    private void emitCompositionSave(MethodSpec.Builder builder, RepositoryContext ctx,
                                      String entityVar, TypeDefinition.Prop.Association assoc) {
        if (!(assoc.target() instanceof TypeDefinition.Entity childEntity)) return;

        String childVar = entityVar + "." + getter(assoc.name()) + "()";

        // Build insert SQL for the child
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

        // Build update SQL for the child
        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "CASCADE_UPDATE_" + assoc.name().toUpperCase(), childUpdateSql);

        CodeBlock.Builder cascade = CodeBlock.builder();
        cascade.add("if ($L != null) {\n", childVar);

        if (childStrategy == IdStrategy.APP_ASSIGNED) {
            // Simple upsert
            cascade.add("    jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(cascade, childEntity, childVar);
            cascade.add(".update();\n");
        } else {
            // Generated key: check if child has ID
            cascade.add("    if ($L.getId() == null) {\n", childVar);
            cascade.add("        $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "childKeys_" + assoc.name(), GENERATED_KEY_HOLDER);
            cascade.add("        jdbcClient.sql($L)", childInsertConst);
            appendNonIdBindings(cascade, childEntity, childVar);
            cascade.add(".update($L);\n", "childKeys_" + assoc.name());
            if (childStrategy == IdStrategy.NUMERIC) {
                cascade.add("        if ($L.getKey() != null) {\n", "childKeys_" + assoc.name());
                cascade.add("            $L.setId((($T) $L.getKey()).$L());\n",
                        childVar, Number.class, "childKeys_" + assoc.name(), numericAccessor(childIdTypeFqn));
                cascade.add("        }\n");
            } else {
                // UUID
                cascade.add("        if ($L.getKey() != null) {\n", "childKeys_" + assoc.name());
                cascade.add("            $L.setId(($T) $L.getKey());\n",
                        childVar, ClassName.bestGuess(childIdTypeFqn), "childKeys_" + assoc.name());
                cascade.add("        }\n");
            }
            cascade.add("    } else {\n");
            cascade.add("        jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(cascade, childEntity, childVar);
            cascade.add(".update();\n");
            cascade.add("    }\n");
        }

        cascade.add("}");
        builder.addStatement(cascade.build());
    }

    /**
     * Cascade save for an EntityCollection using diff sync:
     * 1. Load existing child IDs from DB
     * 2. Upsert each in-memory child (insert if null id, update if non-null)
     * 3. Delete children whose IDs are no longer in the collection
     */
    private void emitEntityCollectionSave(MethodSpec.Builder builder, RepositoryContext ctx,
                                           String entityVar, TypeDefinition.Prop.EntityCollection ec) {
        if (!(ec.target() instanceof TypeDefinition.Entity childEntity)) return;

        String collectionGetter = entityVar + "." + getter(ec.name()) + "()";
        String childVar = "child_" + ec.name();
        String parentIdExpr = entityVar + ".getId()";

        // Find the inverse relation name on the child (TO_ONE targeting parent type)
        String inverseName = findInverseRelationName(childEntity, ctx.entity().typeName());

        // Build insert SQL for the child
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

        // Build update SQL for the child
        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "EC_UPDATE_" + ec.name().toUpperCase(), childUpdateSql);

        // Build delete SQL: DELETE FROM child_table WHERE id = :id AND fk_column = :parentId
        String childTable = dialect.quoteIdentifier(childEntity.tableName());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());
        String idColumn = dialect.quoteIdentifier(childEntity.idColumn());
        String deleteSql = "DELETE FROM " + childTable + " WHERE " + idColumn + " = :childId AND " + fkColumn + " = :parentId";
        String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);

        // Build select-existing-IDs SQL: SELECT id FROM child_table WHERE fk_column = :parentId
        String selectIdsSql = "SELECT " + idColumn + " FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
        String selectIdsConst = ctx.registerConstant("EC_IDS_" + ec.name().toUpperCase(), selectIdsSql);

        CodeBlock.Builder cascade = CodeBlock.builder();

        // Load existing child IDs into a set
        ClassName childIdType = ClassName.bestGuess(childIdTypeFqn);
        cascade.add("$T<$T> $L = new $T<>(jdbcClient\n",
                java.util.Set.class, childIdType, "existingIds_" + ec.name(), java.util.HashSet.class);
        cascade.add("    .sql($L)\n", selectIdsConst);
        cascade.add("    .param($S, $L)\n", "parentId", parentIdExpr);
        cascade.add("    .query($T.class)\n", childIdType);
        cascade.add("    .list());\n");

        // Track which IDs are still in the collection
        cascade.add("$T<$T> $L = new $T<>();\n",
                java.util.Set.class, childIdType, "currentIds_" + ec.name(), java.util.HashSet.class);

        // Upsert each in-memory child
        cascade.add("if ($L != null) {\n", collectionGetter);
        cascade.add("    for (var $L : $L) {\n", childVar, collectionGetter);

        if (childStrategy == IdStrategy.APP_ASSIGNED) {
            cascade.add("        jdbcClient.sql($L)", childUpdateConst);
            appendChildPropBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            // Explicitly re-bind FK to parent ID in case inverse detection failed
            String fkParamName1 = findFkParamName(childEntity, ec.fkColumn());
            if (fkParamName1 != null) {
                cascade.add(".param($S, $L)", fkParamName1, parentIdExpr);
            }
            cascade.add(".update();\n");
            cascade.add("        $L.add($L.getId());\n", "currentIds_" + ec.name(), childVar);
        } else {
            cascade.add("        if ($L.getId() == null) {\n", childVar);
            cascade.add("            $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "ecKeys_" + ec.name(), GENERATED_KEY_HOLDER);
            cascade.add("            jdbcClient.sql($L)", childInsertConst);
            appendChildNonIdBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            // Explicitly re-bind FK to parent ID in case inverse detection failed
            String fkParamName2 = findFkParamName(childEntity, ec.fkColumn());
            if (fkParamName2 != null) {
                cascade.add(".param($S, $L)", fkParamName2, parentIdExpr);
            }
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
            // Explicitly re-bind FK to parent ID in case inverse detection failed
            String fkParamName3 = findFkParamName(childEntity, ec.fkColumn());
            if (fkParamName3 != null) {
                cascade.add(".param($S, $L)", fkParamName3, parentIdExpr);
            }
            cascade.add(".update();\n");
            cascade.add("        }\n");
            cascade.add("        $L.add($L.getId());\n", "currentIds_" + ec.name(), childVar);
        }

        cascade.add("    }\n");
        cascade.add("}\n");

        // Delete orphans: existing IDs that are not in the current collection
        cascade.add("$L.removeAll($L);\n", "existingIds_" + ec.name(), "currentIds_" + ec.name());
        cascade.add("for ($T $L : $L) {\n", childIdType, "orphanId", "existingIds_" + ec.name());
        cascade.add("    jdbcClient.sql($L).param($S, $L).param($S, $L).update();\n",
                deleteConst, "childId", "orphanId", "parentId", parentIdExpr);
        cascade.add("}");

        builder.addStatement(cascade.build());
    }

    /**
     * Cascade save for an ElementCollection: inserts all value objects into the child table
     * with the parent FK. Unlike EntityCollections, element collections don't have IDs so we
     * always delete old and insert new (no upsert logic).
     */
    private void emitElementCollectionSave(MethodSpec.Builder builder, RepositoryContext ctx,
                                            String entityVar, TypeDefinition.Prop.ElementCollection ec) {
        String getter = entityVar + ".get" + capitalize(ec.name()) + "()";
        String parentIdExpr = entityVar + ".getId()";
        
        // Get the element type definition to build proper INSERT
        TypeDefinition elementType = ec.targetType();
        if (elementType == null) return; // Can't proceed without type info
        
        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());
        
        // Build column list from element type properties (scalars only)
        java.util.List<String> columns = new java.util.ArrayList<>();
        columns.add(fkColumn);
        for (TypeDefinition.Prop prop : elementType.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.columnName()));
            }
        }
        
        // Build param placeholders
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
        
        // Build DELETE SQL to clear old elements before re-inserting
        String deleteSql = "DELETE FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
        String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);
        
        CodeBlock.Builder cascade = CodeBlock.builder();
        
        // Delete existing elements first (simple approach: delete all, re-insert all)
        cascade.add("jdbcClient.sql($L).param($S, $L).update();\n", 
                deleteConst, "parentId", parentIdExpr);
        
        // Insert each element in the collection
        cascade.add("if ($L != null) {\n", getter);
        cascade.add("    for (var element : $L) {\n", getter);
        cascade.add("        jdbcClient.sql($L)", insertConst);
        cascade.add(".param($S, $L)", "parentId", parentIdExpr);
        
        // Bind element properties
        for (TypeDefinition.Prop prop : elementType.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                String getterName = "get" + capitalize(s.name());
                cascade.add(".param($S, element.$L())", s.name(), getterName);
            }
        }
        
        cascade.add(".update();\n");
        cascade.add("    }\n");
        cascade.add("}");
        
        builder.addStatement(cascade.build());
    }

    // ── INSERT ───────────────────────────────────────────────────────────

    private MethodSpec emitInsert(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().get(0).getSimpleName().toString();
        TypeDefinition.Prop.Id idProp = idProp(entity);
        String idTypeFqn = idProp != null ? idProp.javaType() : "java.lang.Long";
        IdStrategy strategy = idStrategy(idTypeFqn);

        SqlBuilder sb = new SqlBuilder(entity, entity);
        sb.dialect(dialect);
        String insertSql = strategy == IdStrategy.APP_ASSIGNED
                ? sb.insert(true)
                : sb.insert(false);
        String constName = ctx.registerConstant("INSERT", insertSql);

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        if (strategy == IdStrategy.APP_ASSIGNED) {
            // Bind all columns including id, no keyholder
            body.add("jdbcClient.sql($L)", constName);
            appendAllBindings(body, entity, entityVar);
            body.add(".update();");
            if (returnsEntity) body.add("\nreturn $L;", entityVar);
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
            if (returnsEntity) body.add("\nreturn $L;", entityVar);
        }

        builder.addCode(body.build());
        return builder.build();
    }

    // ── UPDATE ───────────────────────────────────────────────────────────

    private MethodSpec emitUpdate(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().get(0).getSimpleName().toString();

        SqlBuilder sb = new SqlBuilder(entity, entity);
        sb.dialect(dialect);
        String updateSql = sb.update();
        String constName = ctx.registerConstant("UPDATE", updateSql);

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        // Bind non-id columns (SET clause), then id (WHERE clause)
        body.add("jdbcClient.sql($L)", constName);
        appendNonIdBindings(body, entity, entityVar);
        body.add(".param($S, $L.getId()).update();", "id", entityVar);

        // Cascade writes for aggregate roots (same diff sync as save)
        if (ctx.entity().ownsAggregateLifecycle()) {
            emitCascadeWrites(builder, ctx, entityVar);
        } else {
            // For non-aggregate entities, still persist element collections on update
            for (TypeDefinition.Prop prop : ctx.entity().properties()) {
                if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                    emitElementCollectionSave(builder, ctx, entityVar, ec);
                }
            }
        }

        if (returnsEntity) body.add("\nreturn $L;", entityVar);

        builder.addCode(body.build());
        return builder.build();
    }

    // ── DELETE BY ID ─────────────────────────────────────────────────────

    private MethodSpec emitDeleteById(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(method.method(), resolveTypeFor(method.method()), types, ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();

        SqlBuilder sb = new SqlBuilder(entity, entity);
        sb.dialect(dialect);
        String deleteSql = sb.deleteById();
        String constName = ctx.registerConstant("DELETE", deleteSql);

        // Extract the entity parameter name from the method signature
        String entityVar = method.parameters().isEmpty() ? "entity" 
                : method.parameters().get(0).getSimpleName().toString();

        CodeBlock.Builder body = CodeBlock.builder();
        
        // Cascade delete element collections first (before deleting parent row)
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                emitElementCollectionDelete(body, ctx, entityVar, ec);
            }
        }
        
        // Then delete the parent row
        body.add("jdbcClient.sql($L).param($S, $L.getId()).update();", constName, "id", entityVar);

        builder.addCode(body.build());
        return builder.build();
    }
    
    /**
     * Emits code to delete all elements in an element collection when the parent is deleted.
     */
    private void emitElementCollectionDelete(CodeBlock.Builder body, RepositoryContext ctx,
                                              String entityVar, TypeDefinition.Prop.ElementCollection ec) {
        String parentIdExpr = entityVar + ".getId()";
        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());
        
        String deleteSql = "DELETE FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
        String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);
        
        body.add("jdbcClient.sql($L).param($S, $L).update();\n", 
                deleteConst, "parentId", parentIdExpr);
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
                    && assoc.target() != null 
                    && parentTypeName.equals(assoc.target().typeName())) {
                return assoc.name();
            }
        }
        // Second pass: relaxed match - check simple class name (without package)
        String parentSimpleName = parentTypeName.contains(".") 
                ? parentTypeName.substring(parentTypeName.lastIndexOf('.') + 1)
                : parentTypeName;
        for (TypeDefinition.Prop prop : childDef.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc 
                    && assoc.target() != null 
                    && assoc.target().typeName() != null) {
                String targetSimpleName = assoc.target().typeName().contains(".")
                        ? assoc.target().typeName().substring(assoc.target().typeName().lastIndexOf('.') + 1)
                        : assoc.target().typeName();
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

    /**
     * Finds the parameter name for the FK column by looking for an Association with matching fkColumn.
     */
    private String findFkParamName(TypeDefinition.Entity childEntity, String fkColumn) {
        if (fkColumn == null) return null;
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc 
                    && assoc.fkColumn() != null 
                    && fkColumn.equals(assoc.fkColumn())) {
                return assoc.name();
            }
        }
        return null;
    }

    private String getter(String propertyName) {
        return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    }

    private String findIdParam(MethodContext method) {
        for (VariableElement param : method.parameters()) {
            return param.getSimpleName().toString();
        }
        return "id";
    }

    /**
     * Converts a method name to a SQL constant name base.
     * Delegates to FindEmitter's shared utility.
     */
    static String toSqlConstantName(String methodName) {
        return FindEmitter.toSqlConstantName(methodName);
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
