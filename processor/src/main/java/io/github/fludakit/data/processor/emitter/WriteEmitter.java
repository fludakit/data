package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.query.Operation;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.sql.H2Dialect;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.sql.UpsertForm;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import java.util.ArrayList;
import java.util.List;

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

    private static final ClassName GENERATED_KEY_HOLDER =
            ClassName.get("org.springframework.jdbc.support", "GeneratedKeyHolder");

    private final SqlDialect dialect;

    public WriteEmitter(SqlDialect dialect) {
        this.dialect = dialect;
    }

    /** Default constructor using H2 dialect. */
    public WriteEmitter() {
        this(H2Dialect.INSTANCE);
    }

    /**
     * Generates a method override for a write method.
     *
     * @return the method spec, or {@code null} if the method is not handled by this emitter
     */
    public MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        Operation op = method.operation();

        // Skip batch lifecycle methods — handled by LifecycleEmitter
        String name = method.name();
        if ("saveAll".equals(name) || "deleteAll".equals(name) || "deleteByIds".equals(name)) {
            return null;
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
        MethodSpec.Builder builder = MethodSpec.overriding(method.method());
        TypeMapping<?> entity = ctx.entity();
        String entityVar = "entity";
        String idTypeFqn = entity.id().typeFqn();
        IdStrategy strategy = idStrategy(idTypeFqn);
        boolean hasUpsert = dialect.upsertForm() != UpsertForm.NONE;

        // Register SQL constants
        String upsertSql;
        if (hasUpsert) {
            SqlBuilder sb = new SqlBuilder(entity);
            sb.dialect(dialect);
            upsertSql = sb.upsert();
        } else {
            SqlBuilder sb = new SqlBuilder(entity);
            sb.dialect(dialect);
            upsertSql = sb.update();
        }
        String upsertConstName = ctx.registerConstant(
                hasUpsert ? "UPSERT" : "UPDATE_FOR_SAVE", upsertSql);

        SqlBuilder insertSb = new SqlBuilder(entity);
        insertSb.dialect(dialect);
        String insertSql = strategy == IdStrategy.APP_ASSIGNED
                ? insertSb.insert(true)
                : insertSb.insert(false);
        String insertConstName = ctx.registerConstant("INSERT", insertSql);

        // Build method body
        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        if (strategy == IdStrategy.APP_ASSIGNED) {
            if (hasUpsert) {
                // Just upsert — no null check needed for app-assigned
                body.add("jdbcClient.sql($L)", upsertConstName);
                appendAllBindings(body, entity, entityVar);
                body.add(".update();");
                if (returnsEntity) body.add("\nreturn $L;", entityVar);
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
                if (returnsEntity) body.add("\nreturn $L;", entityVar);
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
            if (returnsEntity) body.add("\nreturn $L;", entityVar);
        }

        builder.addStatement(body.build());

        // Cascade writes for aggregate roots
        if (ctx.entity().ownsAggregateLifecycle()) {
            emitCascadeWrites(builder, ctx, entityVar);
        }

        return builder.build();
    }

    /**
     * Emits cascade write logic for aggregate root entities.
     * - Prop.Reference with composition=true: save the composed child (backfill FK)
     * - Prop.EntityCollection: save each child with parent FK set
     * - Prop.ElementCollection: delete all + re-insert (replace-all)
     */
    private void emitCascadeWrites(MethodSpec.Builder builder, RepositoryContext ctx, String entityVar) {
        TypeMapping<?> entity = ctx.entity();

        for (TypeMapping.Prop prop : entity.props()) {
            if (prop instanceof TypeMapping.Prop.Reference ref && ref.composition() && ref.target() != null) {
                // Composition to-one: cascade save to the composed child
                emitCompositionSave(builder, ctx, entityVar, ref);
            } else if (prop instanceof TypeMapping.Prop.EntityCollection ec && ec.target() != null) {
                // Entity collection: save each child with parent FK
                emitEntityCollectionSave(builder, ctx, entityVar, ec);
            } else if (prop instanceof TypeMapping.Prop.ElementCollection ec && ec.target() != null) {
                // Element collection: replace-all (delete + re-insert)
                emitElementCollectionReplace(builder, ctx, entityVar, ec);
            }
        }
    }

    /**
     * Cascade save for a composition Reference: save the child entity, then backfill the FK
     * on the parent if needed.
     */
    private void emitCompositionSave(MethodSpec.Builder builder, RepositoryContext ctx,
                                      String entityVar, TypeMapping.Prop.Reference ref) {
        TypeMapping<?> childMapping = ref.target();
        String childVar = entityVar + "." + getter(ref.name()) + "()";
        String childTypeFqn = childMapping.typeName();

        // Build insert SQL for the child
        SqlBuilder childSb = new SqlBuilder(childMapping);
        childSb.dialect(dialect);
        String childIdTypeFqn = childMapping.id().typeFqn();
        IdStrategy childStrategy = idStrategy(childIdTypeFqn);
        String childInsertSql = childStrategy == IdStrategy.APP_ASSIGNED
                ? childSb.insert(true)
                : childSb.insert(false);
        String childInsertConst = ctx.registerConstant(
                "CASCADE_INSERT_" + ref.name().toUpperCase(), childInsertSql);

        // Build update SQL for the child
        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "CASCADE_UPDATE_" + ref.name().toUpperCase(), childUpdateSql);

        CodeBlock.Builder cascade = CodeBlock.builder();
        cascade.add("if ($L != null) {\n", childVar);

        if (childStrategy == IdStrategy.APP_ASSIGNED) {
            // Simple upsert
            cascade.add("    jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(cascade, childMapping, childVar);
            cascade.add(".update();\n");
        } else {
            // Generated key: check if child has ID
            cascade.add("    if ($L.getId() == null) {\n", childVar);
            cascade.add("        $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "childKeys_" + ref.name(), GENERATED_KEY_HOLDER);
            cascade.add("        jdbcClient.sql($L)", childInsertConst);
            appendNonIdBindings(cascade, childMapping, childVar);
            cascade.add(".update($L);\n", "childKeys_" + ref.name());
            if (childStrategy == IdStrategy.NUMERIC) {
                cascade.add("        if ($L.getKey() != null) {\n", "childKeys_" + ref.name());
                cascade.add("            $L.setId((($T) $L.getKey()).$L());\n",
                        childVar, Number.class, "childKeys_" + ref.name(), numericAccessor(childIdTypeFqn));
                cascade.add("        }\n");
            } else {
                // UUID
                cascade.add("        if ($L.getKey() != null) {\n", "childKeys_" + ref.name());
                cascade.add("            $L.setId(($T) $L.getKey());\n",
                        childVar, ClassName.bestGuess(childIdTypeFqn), "childKeys_" + ref.name());
                cascade.add("        }\n");
            }
            cascade.add("    } else {\n");
            cascade.add("        jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(cascade, childMapping, childVar);
            cascade.add(".update();\n");
            cascade.add("    }\n");
        }

        cascade.add("}");
        builder.addStatement(cascade.build());
    }

    /**
     * Cascade save for an EntityCollection: iterate children, set parent FK, save each.
     */
    private void emitEntityCollectionSave(MethodSpec.Builder builder, RepositoryContext ctx,
                                           String entityVar, TypeMapping.Prop.EntityCollection ec) {
        TypeMapping<?> childMapping = ec.target();
        String collectionGetter = entityVar + "." + getter(ec.name()) + "()";
        String childVar = "child_" + ec.name();

        // Build insert SQL for the child
        SqlBuilder childSb = new SqlBuilder(childMapping);
        childSb.dialect(dialect);
        String childIdTypeFqn = childMapping.id().typeFqn();
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

        CodeBlock.Builder cascade = CodeBlock.builder();
        cascade.add("if ($L != null) {\n", collectionGetter);
        cascade.add("    for (var $L : $L) {\n", childVar, collectionGetter);

        if (childStrategy == IdStrategy.APP_ASSIGNED) {
            cascade.add("        jdbcClient.sql($L)", childUpdateConst);
            appendAllBindings(cascade, childMapping, childVar);
            cascade.add(".update();\n");
        } else {
            cascade.add("        if ($L.getId() == null) {\n", childVar);
            cascade.add("            $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "ecKeys_" + ec.name(), GENERATED_KEY_HOLDER);
            cascade.add("            jdbcClient.sql($L)", childInsertConst);
            appendNonIdBindings(cascade, childMapping, childVar);
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
            appendAllBindings(cascade, childMapping, childVar);
            cascade.add(".update();\n");
            cascade.add("        }\n");
        }

        cascade.add("    }\n");
        cascade.add("}");
        builder.addStatement(cascade.build());
    }

    /**
     * Replace-all for an ElementCollection: delete all existing rows, then insert new ones.
     */
    private void emitElementCollectionReplace(MethodSpec.Builder builder, RepositoryContext ctx,
                                               String entityVar, TypeMapping.Prop.ElementCollection ec) {
        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.parentFkColumn());

        // DELETE SQL: DELETE FROM child_table WHERE fk_column = :parentId
        String deleteSql = "DELETE FROM " + childTable + " WHERE " + fkColumn + " = :parentId";
        String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);

        // INSERT SQL for element collection items
        TypeMapping<?> voMapping = ec.target();
        List<TypeMapping.Prop> voProps = voMapping.props(); // value object props (no id)
        String voTable = dialect.quoteIdentifier(voMapping.tableName());

        // Build column list: fk_column + vo columns
        StringBuilder insertCols = new StringBuilder();
        insertCols.append(fkColumn);
        for (TypeMapping.Prop vp : voProps) {
            insertCols.append(", ");
            if (vp instanceof TypeMapping.Prop.Scalar s) {
                insertCols.append(dialect.quoteIdentifier(s.column()));
            } else if (vp instanceof TypeMapping.Prop.Reference r) {
                insertCols.append(dialect.quoteIdentifier(r.fkColumn()));
            } else if (vp instanceof TypeMapping.Prop.Embedded emb) {
                // Flatten embedded columns
                if (emb.target() != null) {
                    for (TypeMapping.Prop ep : emb.target().props()) {
                        if (ep instanceof TypeMapping.Prop.Scalar es) {
                            insertCols.append(", ");
                            insertCols.append(dialect.quoteIdentifier(
                                    io.github.fludakit.data.Names.columnBase(emb.name()) + "_" + es.column()));
                        }
                    }
                }
                // Remove the last comma since we handle it in the loop
                continue;
            }
        }

        // Build param list
        StringBuilder insertParams = new StringBuilder();
        insertParams.append(":parentId");
        for (TypeMapping.Prop vp : voProps) {
            insertParams.append(", ");
            if (vp instanceof TypeMapping.Prop.Scalar s) {
                insertParams.append(":").append(s.name());
            } else if (vp instanceof TypeMapping.Prop.Reference r) {
                insertParams.append(":").append(r.name());
            } else if (vp instanceof TypeMapping.Prop.Embedded emb) {
                if (emb.target() != null) {
                    for (TypeMapping.Prop ep : emb.target().props()) {
                        if (ep instanceof TypeMapping.Prop.Scalar es) {
                            insertParams.append(", :");
                            insertParams.append(io.github.fludakit.data.Names.columnBase(emb.name()) + "_" + es.name());
                        }
                    }
                }
                continue;
            }
        }

        String insertSql = "INSERT INTO " + voTable + " (" + insertCols + ") VALUES (" + insertParams + ")";
        String insertConst = ctx.registerConstant("EC_INSERT_" + ec.name().toUpperCase(), insertSql);

        String collectionGetter = entityVar + "." + getter(ec.name()) + "()";
        String itemVar = "item_" + ec.name();

        CodeBlock.Builder cascade = CodeBlock.builder();
        // Delete all existing
        cascade.add("jdbcClient.sql($L).param($S, $L.getId()).update();\n",
                deleteConst, "parentId", entityVar);
        // Insert new items
        cascade.add("if ($L != null) {\n", collectionGetter);
        cascade.add("    for (var $L : $L) {\n", itemVar, collectionGetter);
        cascade.add("        jdbcClient.sql($L)", insertConst);
        cascade.add(".param($S, $L.getId())", "parentId", entityVar);
        // Bind value object properties
        appendElementBindings(cascade, voProps, itemVar, "");
        cascade.add(".update();\n");
        cascade.add("    }\n");
        cascade.add("}");
        builder.addStatement(cascade.build());
    }

    /** Appends .param() calls for element collection value object properties. */
    private void appendElementBindings(CodeBlock.Builder body, List<TypeMapping.Prop> voProps,
                                        String itemVar, String prefix) {
        for (TypeMapping.Prop prop : voProps) {
            if (prop instanceof TypeMapping.Prop.Scalar scalar) {
                String paramName = prefix.isEmpty() ? scalar.name() : prefix + scalar.name();
                body.add(".param($S, $L.$L())", paramName, itemVar, getter(scalar.name()));
            } else if (prop instanceof TypeMapping.Prop.Reference ref) {
                String paramName = prefix.isEmpty() ? ref.name() : prefix + ref.name();
                body.add(".param($S, $L.$L() == null ? null : $L.$L().getId())",
                        paramName, itemVar, getter(ref.name()), itemVar, getter(ref.name()));
            } else if (prop instanceof TypeMapping.Prop.Embedded embedded) {
                if (embedded.target() != null) {
                    String newPrefix = prefix.isEmpty()
                            ? io.github.fludakit.data.Names.columnBase(embedded.name()) + "_"
                            : prefix + io.github.fludakit.data.Names.columnBase(embedded.name()) + "_";
                    for (TypeMapping.Prop ep : embedded.target().props()) {
                        if (ep instanceof TypeMapping.Prop.Scalar scalar) {
                            String paramName = newPrefix + scalar.name();
                            body.add(".param($S, $L.$L().$L())",
                                    paramName, itemVar, getter(embedded.name()), getter(scalar.name()));
                        }
                    }
                }
            }
        }
    }

    // ── INSERT ───────────────────────────────────────────────────────────

    private MethodSpec emitInsert(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodSpec.overriding(method.method());
        TypeMapping<?> entity = ctx.entity();
        String entityVar = "entity";
        String idTypeFqn = entity.id().typeFqn();
        IdStrategy strategy = idStrategy(idTypeFqn);

        SqlBuilder sb = new SqlBuilder(entity);
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

        builder.addStatement(body.build());
        return builder.build();
    }

    // ── UPDATE ───────────────────────────────────────────────────────────

    private MethodSpec emitUpdate(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodSpec.overriding(method.method());
        TypeMapping<?> entity = ctx.entity();
        String entityVar = "entity";

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String updateSql = sb.update();
        String constName = ctx.registerConstant("UPDATE", updateSql);

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        // Bind non-id columns (SET clause), then id (WHERE clause)
        body.add("jdbcClient.sql($L)", constName);
        appendNonIdBindings(body, entity, entityVar);
        body.add(".param($S, $L.getId()).update();", "id", entityVar);
        if (returnsEntity) body.add("\nreturn $L;", entityVar);

        builder.addStatement(body.build());
        return builder.build();
    }

    // ── DELETE BY ID ─────────────────────────────────────────────────────

    private MethodSpec emitDeleteById(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodSpec.overriding(method.method());
        TypeMapping<?> entity = ctx.entity();

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String deleteSql = sb.deleteById();
        String constName = ctx.registerConstant("DELETE", deleteSql);

        // Find the id parameter name from the method signature
        String idParam = findIdParam(method);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("jdbcClient.sql($L).param($S, $L).update();", constName, "id", idParam);

        builder.addStatement(body.build());
        return builder.build();
    }

    // ── Binding helpers ──────────────────────────────────────────────────

    /** Appends .param() calls for ALL columns (id + non-id props). */
    private void appendAllBindings(CodeBlock.Builder body, TypeMapping<?> entity, String varName) {
        // Id binding
        String idParamName = entity.id().name();
        body.add(".param($S, $L.getId())", idParamName, varName);
        // Non-id props
        appendPropBindings(body, entity, varName);
    }

    /** Appends .param() calls for non-id columns only. */
    private void appendNonIdBindings(CodeBlock.Builder body, TypeMapping<?> entity, String varName) {
        appendPropBindings(body, entity, varName);
    }

    /** Appends .param() calls for each property (non-id). */
    private void appendPropBindings(CodeBlock.Builder body, TypeMapping<?> entity, String varName) {
        for (TypeMapping.Prop prop : entity.props()) {
            appendPropBinding(body, prop, varName, "");
        }
    }

    /** Appends .param() calls for element-collection child item properties. */
    private void appendItemBindings(CodeBlock.Builder body, List<TypeMapping.Prop> childProps,
                                     String itemVar) {
        for (TypeMapping.Prop prop : childProps) {
            appendPropBinding(body, prop, itemVar, "");
        }
    }

    /**
     * Appends one or more .param() calls for a single property.
     * Handles Scalar, Reference, and Embedded recursively.
     */
    private void appendPropBinding(CodeBlock.Builder body, TypeMapping.Prop prop,
                                    String varName, String prefix) {
        if (prop instanceof TypeMapping.Prop.Scalar scalar) {
            String paramName = prefix.isEmpty() ? scalar.name() : prefix + scalar.name();
            String getter = buildGetterChain(varName, prefix, scalar.name());
            body.add(".param($S, $L)", paramName, getter);
        } else if (prop instanceof TypeMapping.Prop.Reference ref) {
            if (ref.backReference()) {
                // Back-reference: bind fk column from the target's id
                String paramName = prefix.isEmpty() ? ref.name() : prefix + ref.name();
                String getter = buildGetterChain(varName, prefix, ref.name());
                body.add(".param($S, $L == null ? null : $L.getId())",
                        paramName, getter, getter);
            } else {
                String paramName = prefix.isEmpty() ? ref.name() : prefix + ref.name();
                String getter = buildGetterChain(varName, prefix, ref.name());
                body.add(".param($S, $L == null ? null : $L.getId())",
                        paramName, getter, getter);
            }
        } else if (prop instanceof TypeMapping.Prop.Embedded embedded) {
            if (embedded.target() != null) {
                String newPrefix = prefix.isEmpty()
                        ? embedded.name() + "_"
                        : prefix + embedded.name() + "_";
                for (TypeMapping.Prop childProp : embedded.target().props()) {
                    appendPropBinding(body, childProp, varName, newPrefix);
                }
            }
        }
        // EntityCollection and ElementCollection are not bound on the parent row
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
}
