package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.MethodContext.Operation;
import io.github.fludakit.data.processor.context.CascadePlan;
import io.github.fludakit.data.processor.context.CascadePlan.CascadeMode;
import io.github.fludakit.data.processor.context.CascadePlan.CascadeStep;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.ExceptionTranslation;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.sql.UpsertForm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Generates method implementations for write operations: save, insert, update, and deleteById.
 *
 * <p>Dispatches on {@link Operation}. The cascade plan comes from
 * {@link CascadePlan#of(TypeDefinition, CascadePlan.WriteKind)}; the cascade / child-table
 * bodies and the parameter-binding helpers live in the nested classes {@link Cascades} and
 * {@link Bindings} (formerly {@code CascadeSupport} and {@code WriteSupport}). All SQL comes from {@link SqlBuilder}.
 * Insert/save branch on null id at emit time — no identity-generation metadata.</p>
 */
final class WriteEmitter {

    private final SqlDialect dialect;
    private final Types types;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;
    private final Bindings bindings;
    private final Cascades cascades;

    private static final ClassName GENERATED_KEY_HOLDER =
            ClassName.get("io.github.fludakit.jdbc.support", "GeneratedKeyHolder");

    WriteEmitter(SqlDialect dialect, Types types, Elements elements,
                        DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.dialect = dialect;
        this.types = types;
        this.baseRepositoryType = baseRepositoryType;
        this.repositoryOwnType = repositoryOwnType;
        this.bindings = new Bindings(elements);
        this.cascades = new Cascades(dialect, this.bindings);
    }

    private DeclaredType resolveTypeFor(ExecutableElement method) {
        if (method.getEnclosingElement().equals(repositoryOwnType.asElement())) {
            return repositoryOwnType;
        }
        return baseRepositoryType;
    }

    /**
     * Generates a method override for a write method.
     *
     * @return the method spec, or {@code null} if the method is not handled by this emitter
     */
    MethodSpec emit(RepositoryContext ctx, MethodContext method) {
        Operation op = method.operation();

        if (LifecycleEmitter.isBatchLifecycleMethod(method)) {
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
        if (op == Operation.DELETE && method.query() instanceof QueryDefinition.DeleteById) {
            return emitDeleteById(ctx, method);
        }
        if (op == Operation.DELETE && method.query() instanceof QueryDefinition.DeleteWhere) {
            return emitDeleteBy(ctx, method);
        }
        return null;
    }

    private MethodSpec emitSave(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(
                method.method(), resolveTypeFor(method.method()), types,
                ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity"
                : method.parameters().getFirst().getSimpleName().toString();
        TypeDefinition.Prop.Id idProp = entity.id();
        String idTypeFqn = idProp != null ? idProp.javaType() : "java.lang.Long";
        boolean hasUpsert = dialect.upsertForm() != UpsertForm.NONE;

        CascadePlan plan = CascadePlan.of(ctx.entity(), method.writeKind());

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
        String insertConstName = ctx.registerConstant("INSERT", insertSb.insert(false));

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        for (CascadeStep step : plan.steps()) {
            if (step.mode() == CascadeMode.PRE_INSERT) {
                cascades.emitPreInsertStep(body, step, ctx, entityVar);
            }
        }

        // null id → insert + keyholder; id present → upsert/update
        body.add("if ($L.getId() == null) {\n", entityVar);
        body.add("    $T $L = new $T();\n", GENERATED_KEY_HOLDER, "keys",
                GENERATED_KEY_HOLDER);
        body.add("    jdbcClient.sql($L)", insertConstName);
        bindings.appendNonIdBindings(body, entity, entityVar);
        body.add(".update($L);\n", "keys");
        bindings.applyGeneratedKey(body, entityVar, "keys", idTypeFqn);
        body.add("} else {\n");
        body.add("    jdbcClient.sql($L)", upsertConstName);
        bindings.appendAllBindings(body, entity, entityVar);
        body.add(".update();\n");
        body.add("}");

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        for (CascadeStep step : plan.steps()) {
            if (step.mode() != CascadeMode.PRE_INSERT) {
                cascades.emitPostCascadeStep(step, ctx, entityVar, builder::addStatement);
            }
        }

        if (returnsEntity) {
            builder.addStatement("return $L", entityVar);
        }

        return builder.build();
    }

    private MethodSpec emitInsert(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(
                method.method(), resolveTypeFor(method.method()), types,
                ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity"
                : method.parameters().getFirst().getSimpleName().toString();
        TypeDefinition.Prop.Id idProp = entity.id();
        String idTypeFqn = idProp != null ? idProp.javaType() : "java.lang.Long";

        CascadePlan plan = CascadePlan.of(ctx.entity(), method.writeKind());

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String insertWithoutId = ctx.registerConstant("INSERT", sb.insert(false));
        String insertWithId = ctx.registerConstant("INSERT_WITH_ID", sb.insert(true));

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        for (CascadeStep step : plan.steps()) {
            if (step.mode() == CascadeMode.PRE_INSERT) {
                cascades.emitPreInsertStep(body, step, ctx, entityVar);
            }
        }

        body.add("if ($L.getId() == null) {\n", entityVar);
        body.add("    $T $L = new $T();\n", GENERATED_KEY_HOLDER, "keys",
                GENERATED_KEY_HOLDER);
        body.add("    jdbcClient.sql($L)", insertWithoutId);
        bindings.appendNonIdBindings(body, entity, entityVar);
        body.add(".update($L);\n", "keys");
        bindings.applyGeneratedKey(body, entityVar, "keys", idTypeFqn);
        body.add("} else {\n");
        body.add("    jdbcClient.sql($L)", insertWithId);
        bindings.appendAllBindings(body, entity, entityVar);
        body.add(".update();\n");
        body.add("}");

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        for (CascadeStep step : plan.steps()) {
            if (step.mode() != CascadeMode.PRE_INSERT) {
                cascades.emitPostCascadeStep(step, ctx, entityVar, builder::addStatement);
            }
        }

        if (returnsEntity) {
            builder.addStatement("return $L", entityVar);
        }

        return builder.build();
    }

    private MethodSpec emitUpdate(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(
                method.method(), resolveTypeFor(method.method()), types,
                ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();
        String entityVar = method.parameters().isEmpty() ? "entity"
                : method.parameters().getFirst().getSimpleName().toString();

        CascadePlan plan = CascadePlan.of(ctx.entity(), method.writeKind());

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String constName = ctx.registerConstant("UPDATE", sb.update());

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        body.add("jdbcClient.sql($L)", constName);
        bindings.appendNonIdBindings(body, entity, entityVar);
        body.add(".param($S, $L.getId()).update();\n", "id", entityVar);

        for (CascadeStep step : plan.steps()) {
            cascades.emitPostCascadeStep(step, ctx, entityVar, code -> {
                body.add(code);
                body.add("\n");
            });
        }

        if (returnsEntity) body.add("return $L;", entityVar);

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    private MethodSpec emitDeleteById(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(
                method.method(), resolveTypeFor(method.method()), types,
                ClassName.bestGuess(ctx.entity().typeName()));
        TypeDefinition entity = ctx.entity();

        CascadePlan plan = CascadePlan.of(ctx.entity(), method.writeKind());

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String constName = ctx.registerConstant("DELETE", sb.deleteById());

        String entityVar = method.parameters().isEmpty() ? "entity"
                : method.parameters().getFirst().getSimpleName().toString();
        String parentIdExpr = method.firstParameterIsEntity(entity) ? entityVar + ".getId()" : entityVar;

        CodeBlock.Builder body = CodeBlock.builder();
        cascades.emitCascadePreDelete(body, ctx, plan, parentIdExpr);
        body.add("jdbcClient.sql($L).param($S, $L).update();\n", constName, "id", parentIdExpr);
        cascades.emitCascadePostDelete(body, ctx, plan);

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    private MethodSpec emitDeleteBy(RepositoryContext ctx, MethodContext method) {
        MethodSpec.Builder builder = MethodOverrides.resolveOverride(
                method.method(), resolveTypeFor(method.method()), types,
                ClassName.bestGuess(ctx.entity().typeName()));

        TypeDefinition entity = ctx.entity();
        SqlBuilder sqlBuilder = new SqlBuilder(entity);
        sqlBuilder.dialect(dialect);
        String sql = sqlBuilder.sql(method.query());
        String constName = ctx.registerConstant(method.sqlConstantBase(), sql);

        CodeBlock.Builder body = CodeBlock.builder();

        boolean isDeleteById = isDeleteByIdPredicate(entity, method);
        String parentIdExpr = isDeleteById ? idParamName(method, entity) : null;
        CascadePlan plan = isDeleteById ? CascadePlan.of(entity, method.writeKind()) : null;

        if (plan != null) {
            cascades.emitCascadePreDelete(body, ctx, plan, parentIdExpr);
        }

        boolean isVoid = method.resultKind() == ResultKind.VOID;
        if (!isVoid) body.add("return ");
        body.add("jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        ParamBindingCode.append(body, method);
        body.add("    .update();\n");

        if (plan != null) {
            cascades.emitCascadePostDelete(body, ctx, plan);
        }

        builder.addCode(ExceptionTranslation.wrap(body.build()));
        return builder.build();
    }

    private static boolean isDeleteByIdPredicate(TypeDefinition entity, MethodContext method) {
        if (method.operation() != Operation.DELETE) return false;
        List<Predicate> preds = method.predicates();
        if (preds.size() != 1) return false;
        Predicate pred = preds.getFirst();
        TypeDefinition.Prop.Id id = entity.id();
        String idPropName = id != null ? id.name() : null;
        return idPropName != null && pred.property().equals(idPropName);
    }

    private static String idParamName(MethodContext method, TypeDefinition entity) {
        if (method.parameters().isEmpty()) return "id";
        String paramName = method.parameters().getFirst().getSimpleName().toString();
        return method.firstParameterIsEntity(entity) ? paramName + ".getId()" : paramName;
    }

    /**
     * Package-private pass-through to {@link Cascades#emitPostCascadeStep}; kept so
     * {@code WriteEmitterCascadeTest} can drive the post-cascade renderer directly.
     */
    void emitPostCascadeStep(CascadeStep step, RepositoryContext ctx, String entityVar,
                             Consumer<CodeBlock> sink) {
        cascades.emitPostCascadeStep(step, ctx, entityVar, sink);
    }

    // ══ Cascade / child-table code (formerly CascadeSupport) ═══════════════
    private static final class Cascades {

        private final SqlDialect dialect;
        private final Bindings bindings;

        Cascades(SqlDialect dialect, Bindings bindings) {
            this.dialect = dialect;
            this.bindings = bindings;
        }

        /**
         * Generates code for a single PRE_INSERT cascade step (composition association).
         * Inserts the child before the root so its generated ID can be bound as FK on the root row.
         */
        void emitPreInsertStep(CodeBlock.Builder body, CascadeStep step, RepositoryContext ctx, String entityVar) {
            if (!(step.prop() instanceof TypeDefinition.Prop.Association assoc)) return;
            if (!(step.target() instanceof TypeDefinition.Entity childEntity)) return;

            String childVar = entityVar + "." + assoc.name();

            SqlBuilder childSb = new SqlBuilder(childEntity);
            childSb.dialect(dialect);
            TypeDefinition.Prop.Id childIdProp = childEntity.id();
            String childIdTypeFqn = childIdProp != null ? childIdProp.javaType() : "java.lang.Long";
            String childInsertSql = childSb.insert(false);
            String childInsertConst = ctx.registerConstant(
                    "CASCADE_INSERT_" + assoc.name().toUpperCase(), childInsertSql);
            String childUpdateSql = childSb.update();
            String childUpdateConst = ctx.registerConstant(
                    "CASCADE_UPDATE_" + assoc.name().toUpperCase(), childUpdateSql);

            body.add("if ($L != null) {\n", childVar);

            for (CascadeStep child : step.children()) {
                if (child.mode() == CascadeMode.PRE_INSERT) {
                    emitPreInsertStep(body, child, ctx, childVar);
                }
            }

            body.add("    if ($L.getId() == null) {\n", childVar);
            body.add("        $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "childKeys_" + assoc.name(), GENERATED_KEY_HOLDER);
            body.add("        jdbcClient.sql($L)", childInsertConst);
            bindings.appendNonIdBindings(body, childEntity, childVar);
            body.add(".update($L);\n", "childKeys_" + assoc.name());
            bindings.applyGeneratedKey(body, childVar, "childKeys_" + assoc.name(), childIdTypeFqn);
            body.add("    } else {\n");
            body.add("        jdbcClient.sql($L)", childUpdateConst);
            bindings.appendAllBindings(body, childEntity, childVar);
            body.add(".update();\n");
            body.add("    }\n");

            for (CascadeStep child : step.children()) {
                if (child.mode() != CascadeMode.PRE_INSERT) {
                    emitPostCascadeStep(child, ctx, childVar, body::add);
                }
            }

            body.add("}\n");
        }

        /**
         * Renders one post-cascade step and hands it to the sink. A step that contributes no code is
         * skipped, so a sink never receives an empty statement.
         *
         * @param sink where the rendered step goes — a method body, a statement, or a loop body
         */
        void emitPostCascadeStep(CascadeStep step, RepositoryContext ctx, String entityVar,
                                 Consumer<CodeBlock> sink) {
            CodeBlock code = buildPostCascadeCodeBlock(step, ctx, entityVar);
            if (!code.toString().isEmpty()) {
                sink.accept(code);
            }
        }

        private CodeBlock buildPostCascadeCodeBlock(CascadeStep step, RepositoryContext ctx, String entityVar) {
            return switch (step.mode()) {
                case UPDATE -> buildCompositionUpdateCodeBlock(step, ctx, entityVar);
                case DIFF_SYNC -> buildDiffSyncCodeBlock(step, ctx, entityVar);
                case REPLACE -> {
                    if (step.prop() instanceof TypeDefinition.Prop.ElementCollection ec) {
                        yield buildElementCollectionReplaceCodeBlock(ctx, entityVar, ec);
                    }
                    yield CodeBlock.builder().build();
                }
                case CASCADE_DELETE -> buildCascadeDeleteCodeBlock(step, ctx, entityVar);
                default -> CodeBlock.builder().build();
            };
        }

        private CodeBlock buildCompositionUpdateCodeBlock(CascadeStep step, RepositoryContext ctx, String entityVar) {
            if (!(step.prop() instanceof TypeDefinition.Prop.Association assoc)) return CodeBlock.builder().build();
            if (!(step.target() instanceof TypeDefinition.Entity childEntity)) return CodeBlock.builder().build();

            String childVar = entityVar + "." + assoc.name();

            SqlBuilder childSb = new SqlBuilder(childEntity);
            childSb.dialect(dialect);
            String childUpdateSql = childSb.update();
            String childUpdateConst = ctx.registerConstant(
                    "CASCADE_UPDATE_" + assoc.name().toUpperCase(), childUpdateSql);

            CodeBlock.Builder cascade = CodeBlock.builder();
            cascade.add("if ($L != null) {\n", childVar);
            cascade.add("    jdbcClient.sql($L)", childUpdateConst);
            bindings.appendAllBindings(cascade, childEntity, childVar);
            cascade.add(".update();\n");

            for (CascadeStep child : step.children()) {
                emitPostCascadeStep(child, ctx, childVar, code -> {
                    cascade.add(code);
                    cascade.add("\n");
                });
            }

            cascade.add("}");
            return cascade.build();
        }

        private CodeBlock buildDiffSyncCodeBlock(CascadeStep step, RepositoryContext ctx, String entityVar) {
            if (!(step.prop() instanceof TypeDefinition.Prop.EntityCollection ec)) return CodeBlock.builder().build();
            if (!(step.target() instanceof TypeDefinition.Entity childEntity)) return CodeBlock.builder().build();

            String collectionGetter = entityVar + "." + bindings.getter(ec.name()) + "()";
            String childVar = "child_" + ec.name();
            String parentIdExpr = entityVar + ".getId()";

            String inverseName = Bindings.findInverseRelationName(childEntity, ctx.entity().typeName());

            SqlBuilder childSb = new SqlBuilder(childEntity);
            childSb.dialect(dialect);
            TypeDefinition.Prop.Id childIdProp = childEntity.id();
            String childIdTypeFqn = childIdProp != null ? childIdProp.javaType() : "java.lang.Long";
            String childInsertSql = childSb.insert(false);
            String childInsertConst = ctx.registerConstant(
                    "EC_INSERT_" + ec.name().toUpperCase(), childInsertSql);

            String childUpdateSql = childSb.update();
            String childUpdateConst = ctx.registerConstant(
                    "EC_UPDATE_" + ec.name().toUpperCase(), childUpdateSql);

            String deleteSql = childSb.deleteChildByIdAndFk(childEntity.tableName(), childEntity.idColumn(), ec.fkColumn());
            String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);

            String selectIdsSql = childSb.selectChildIds(childEntity.tableName(), childEntity.idColumn(), ec.fkColumn());
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

            cascade.add("        if ($L.getId() == null) {\n", childVar);
            cascade.add("            $T $L = new $T();\n", GENERATED_KEY_HOLDER,
                    "ecKeys_" + ec.name(), GENERATED_KEY_HOLDER);
            cascade.add("            jdbcClient.sql($L)", childInsertConst);
            bindings.appendChildNonIdBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            cascade.add(".update($L);\n", "ecKeys_" + ec.name());
            bindings.applyGeneratedKey(cascade, childVar, "ecKeys_" + ec.name(), childIdTypeFqn);
            cascade.add("        } else {\n");
            cascade.add("            jdbcClient.sql($L)", childUpdateConst);
            bindings.appendChildPropBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
            cascade.add(".update();\n");
            cascade.add("        }\n");
            cascade.add("        $L.add($L.getId());\n", "currentIds_" + ec.name(), childVar);

            for (CascadeStep child : step.children()) {
                emitCascadeStepIntoLoop(cascade, child, ctx, childVar);
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

        private void emitCascadeStepIntoLoop(CodeBlock.Builder body, CascadeStep step, RepositoryContext ctx, String entityVar) {
            if (step.mode() == CascadeMode.PRE_INSERT) {
                emitPreInsertStep(body, step, ctx, entityVar);
            } else {
                emitPostCascadeStep(step, ctx, entityVar, body::add);
            }
        }

        private CodeBlock buildCascadeDeleteCodeBlock(CascadeStep step, RepositoryContext ctx, String parentIdExpr) {
            if (step.prop() instanceof TypeDefinition.Prop.EntityCollection ec
                    && step.target() instanceof TypeDefinition.Entity childEntity) {
                SqlBuilder childSb = new SqlBuilder(childEntity);
                childSb.dialect(dialect);
                String deleteSql = childSb.deleteByFk(childEntity.tableName(), ec.fkColumn());
                String deleteConst = ctx.registerConstant("EC_COLL_DELETE_" + ec.name().toUpperCase(), deleteSql);
                return CodeBlock.builder().add("jdbcClient.sql($L).param($S, $L).update()",
                        deleteConst, "parentId", parentIdExpr).build();
            }
            return CodeBlock.builder().build();
        }

        private CodeBlock buildElementCollectionReplaceCodeBlock(RepositoryContext ctx,
                                                                 String entityVar, TypeDefinition.Prop.ElementCollection ec) {
            String getterName = entityVar + ".get" + bindings.capitalize(ec.name()) + "()";
            String parentIdExpr = entityVar + ".getId()";

            TypeDefinition elementType = ec.definition();
            if (elementType == null) return CodeBlock.builder().build();

            List<Leaf> leaves = new ArrayList<>();
            collectLeaves(elementType, leaves, "element", "");

            List<String> columns = new ArrayList<>();
            columns.add(ec.fkColumn());
            List<String> params = new ArrayList<>();
            params.add("parentId");
            for (Leaf leaf : leaves) {
                columns.add(leaf.columnName());
                params.add(leaf.columnName());
            }

            SqlBuilder childSb = new SqlBuilder(ctx.entity());
            childSb.dialect(dialect);
            String insertSql = childSb.insertColumns(ec.childTable(), columns, params);
            String insertConst = ctx.registerConstant("EC_INSERT_" + ec.name().toUpperCase(), insertSql);

            String deleteSql = childSb.deleteByFk(ec.childTable(), ec.fkColumn());
            String deleteConst = ctx.registerConstant("EC_DELETE_" + ec.name().toUpperCase(), deleteSql);

            CodeBlock.Builder cascade = CodeBlock.builder();
            cascade.add("jdbcClient.sql($L).param($S, $L).update();\n",
                    deleteConst, "parentId", parentIdExpr);
            cascade.add("if ($L != null) {\n", getterName);
            cascade.add("    for (var element : $L) {\n", getterName);
            cascade.add("        jdbcClient.sql($L)", insertConst);
            cascade.add(".param($S, $L)", "parentId", parentIdExpr);
            for (Leaf leaf : leaves) {
                cascade.add(".param($S, $L)", leaf.columnName(), leaf.binding());
            }
            cascade.add(".update();\n");
            cascade.add("    }\n");
            cascade.add("}");

            return cascade.build();
        }

        private record Leaf(String columnName, String binding) {
        }

        private void collectLeaves(TypeDefinition type, List<Leaf> leaves, String chain, String columnPrefix) {
            for (TypeDefinition.Prop prop : type.properties()) {
                if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.fkColumn() != null) {
                    String columnName = Bindings.qualify(columnPrefix, assoc.fkColumn());
                    String getterCall = chain + "." + bindings.getter(assoc.name()) + "()";
                    String binding = columnPrefix.isEmpty()
                            ? getterCall + " == null ? null : " + getterCall + ".getId()"
                            : chain + " == null ? null : " + getterCall + " == null ? null : " + getterCall + ".getId()";
                    leaves.add(new Leaf(columnName, binding));
                }
            }
            for (TypeDefinition.Prop prop : type.properties()) {
                if (prop instanceof TypeDefinition.Prop.Scalar s) {
                    String columnName = Bindings.qualify(columnPrefix, s.columnName());
                    String getterCall = chain + "." + bindings.getter(s.name()) + "()";
                    String binding;
                    if (bindings.isEnum(s.javaType())) {
                        binding = getterCall + " == null ? null : " + getterCall + ".name()";
                    } else if (columnPrefix.isEmpty()) {
                        binding = getterCall;
                    } else {
                        binding = chain + " == null ? null : " + getterCall;
                    }
                    leaves.add(new Leaf(columnName, binding));
                }
            }
            for (TypeDefinition.Prop prop : type.properties()) {
                if (prop instanceof TypeDefinition.Prop.Embedded emb && emb.definition() != null) {
                    String newChain = chain + "." + bindings.getter(emb.name()) + "()";
                    collectLeaves(emb.definition(), leaves, newChain, Bindings.qualify(columnPrefix, emb.name()));
                }
            }
        }

        void emitCascadePreDelete(CodeBlock.Builder body, RepositoryContext ctx, CascadePlan plan,
                                  String parentIdExpr) {
            SqlBuilder lifecycle = new SqlBuilder(ctx.entity());
            lifecycle.dialect(dialect);

            for (CascadeStep step : plan.steps()) {
                if (step.mode() != CascadeMode.CASCADE_DELETE
                        || !(step.prop() instanceof TypeDefinition.Prop.Association assoc)) {
                    continue;
                }
                if (!(step.target() instanceof TypeDefinition.Entity)) {
                    continue;
                }
                String loadSql = lifecycle.selectForeignKey(assoc.fkColumn());
                String loadConst = ctx.registerConstant("LOAD_FK_" + assoc.name().toUpperCase(), loadSql);
                body.add("Long $L = jdbcClient.sql($L).param($S, $L).query(Long.class).single();\n",
                        "childId_" + assoc.name(), loadConst, "id", parentIdExpr);
            }

            for (CascadeStep step : plan.steps()) {
                if (step.mode() != CascadeMode.CASCADE_DELETE
                        || step.prop() instanceof TypeDefinition.Prop.Association) {
                    continue;
                }
                String table;
                String fkColumn;
                String constBase;
                if (step.prop() instanceof TypeDefinition.Prop.ElementCollection ec) {
                    table = ec.childTable();
                    fkColumn = ec.fkColumn();
                    constBase = "EC_DELETE_" + ec.name().toUpperCase();
                } else if (step.prop() instanceof TypeDefinition.Prop.EntityCollection coll
                        && step.target() instanceof TypeDefinition.Entity child) {
                    table = child.tableName();
                    fkColumn = coll.fkColumn();
                    constBase = "EC_COLL_DELETE_" + coll.name().toUpperCase();
                } else {
                    continue;
                }
                String deleteSql = lifecycle.deleteByFk(table, fkColumn);
                String deleteConst = ctx.registerConstant(constBase, deleteSql);
                body.add("jdbcClient.sql($L).param($S, $L).update();\n", deleteConst, "parentId", parentIdExpr);
            }
        }

        void emitCascadePostDelete(CodeBlock.Builder body, RepositoryContext ctx, CascadePlan plan) {
            for (CascadeStep step : plan.steps()) {
                if (step.mode() != CascadeMode.CASCADE_DELETE
                        || !(step.prop() instanceof TypeDefinition.Prop.Association assoc)) {
                    continue;
                }
                if (!(step.target() instanceof TypeDefinition.Entity childEntity)) {
                    continue;
                }
                SqlBuilder childSql = new SqlBuilder(childEntity);
                childSql.dialect(dialect);
                String deleteSql = childSql.deleteById();
                String deleteConst = ctx.registerConstant("CASCADE_DELETE_" + assoc.name().toUpperCase(), deleteSql);
                body.add("if ($L != null) {\n", "childId_" + assoc.name())
                        .add("    jdbcClient.sql($L).param($S, $L).update();\n",
                                deleteConst, "id", "childId_" + assoc.name())
                        .add("}\n");
            }
        }
    }

    // ══ Parameter binding & naming helpers (formerly WriteSupport) ═════════
    private static final class Bindings {

        private final Elements elements;

        /** @param elements may be {@code null} (tests); only {@link #isEnum} dereferences it. */
        Bindings(Elements elements) {
            this.elements = elements;
        }

        /** Appends .param() calls for ALL columns (id + non-id props). */
        void appendAllBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
            TypeDefinition.Prop.Id idProp = entity.id();
            if (idProp != null) {
                body.add(".param($S, $L.getId())", idProp.name(), varName);
            }
            appendPropBindings(body, entity, varName);
        }

        /** Appends .param() calls for non-id columns only. */
        void appendNonIdBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
            appendPropBindings(body, entity, varName);
        }

        /**
         * Appends .param() calls for all props of a child entity in an entity collection.
         * The inverse FK (back-reference to parent) is bound to the parent's ID.
         */
        void appendChildPropBindings(CodeBlock.Builder body, TypeDefinition.Entity childEntity,
                                     String childVar, String parentIdExpr,
                                     String inverseName, String fkColumn) {
            TypeDefinition.Prop.Id idProp = childEntity.id();
            if (idProp != null) {
                body.add(".param($S, $L.getId())", idProp.name(), childVar);
            }
            for (TypeDefinition.Prop prop : childEntity.properties()) {
                appendChildPropBinding(body, prop, childVar, parentIdExpr, inverseName, fkColumn);
            }
        }

        /**
         * Appends .param() calls for non-id props of a child entity in an entity collection.
         * The inverse FK (back-reference to parent) is bound to the parent's ID.
         */
        void appendChildNonIdBindings(CodeBlock.Builder body, TypeDefinition.Entity childEntity,
                                      String childVar, String parentIdExpr,
                                      String inverseName, String fkColumn) {
            for (TypeDefinition.Prop prop : childEntity.properties()) {
                appendChildPropBinding(body, prop, childVar, parentIdExpr, inverseName, fkColumn);
            }
        }

        private void appendChildPropBinding(CodeBlock.Builder body, TypeDefinition.Prop prop, String ownerExpr,
                                            String parentIdExpr, String inverseName, String fkColumn) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                appendColumnBinding(body, s, "", ownerExpr, null);
            } else if (prop instanceof TypeDefinition.Prop.Association assoc
                    && (assoc.name().equals(inverseName)
                    || (fkColumn != null && fkColumn.equals(assoc.fkColumn())))) {
                body.add(".param($S, $L)", assoc.name(), parentIdExpr);
            } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
                appendRelationBinding(body, assoc, "", ownerExpr, null);
            } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
                appendRelationBinding(body, embedded, "", ownerExpr, null);
            }
        }

        private void appendPropBindings(CodeBlock.Builder body, TypeDefinition entity, String varName) {
            for (TypeDefinition.Prop prop : entity.properties()) {
                if (prop instanceof TypeDefinition.Prop.Scalar s) {
                    appendColumnBinding(body, s, "", varName, null);
                } else if (prop instanceof TypeDefinition.Prop.Association assoc) {
                    appendRelationBinding(body, assoc, "", varName, null);
                } else if (prop instanceof TypeDefinition.Prop.Embedded embedded) {
                    appendRelationBinding(body, embedded, "", varName, null);
                }
            }
        }

        /**
         * @param ownerExpr Java expression for the current owner ({@code entity} or {@code entity.getAddress()})
         * @param nullGuard accumulated null-check for enclosing embeds, or {@code null} at root
         */
        private void appendColumnBinding(CodeBlock.Builder body, TypeDefinition.Prop.Scalar col,
                                         String columnPrefix, String ownerExpr, String nullGuard) {
            String paramName = qualify(columnPrefix, col.name());
            String leafGetter = ownerExpr + "." + getter(col.name()) + "()";
            if (nullGuard == null) {
                if (isEnum(col.javaType())) {
                    body.add(".param($S, $L == null ? null : $L.name())",
                            paramName, leafGetter, leafGetter);
                } else {
                    body.add(".param($S, $L)", paramName, leafGetter);
                }
            } else {
                String guarded = nullGuard + " ? null : " + leafGetter;
                if (isEnum(col.javaType())) {
                    body.add(".param($S, ($L) == null ? null : ($L).name())",
                            paramName, guarded, guarded);
                } else {
                    body.add(".param($S, $L)", paramName, guarded);
                }
            }
        }

        private void appendRelationBinding(CodeBlock.Builder body, TypeDefinition.Prop rel,
                                           String columnPrefix, String ownerExpr, String nullGuard) {
            if (rel instanceof TypeDefinition.Prop.Association assoc && assoc.fkColumn() != null) {
                String paramName = qualify(columnPrefix, assoc.name());
                String assocGetter = ownerExpr + "." + getter(assoc.name()) + "()";
                if (nullGuard == null) {
                    body.add(".param($S, $L == null ? null : $L.getId())",
                            paramName, assocGetter, assocGetter);
                } else {
                    body.add(".param($S, $L || $L == null ? null : $L.getId())",
                            paramName, nullGuard, assocGetter, assocGetter);
                }
            } else if (rel instanceof TypeDefinition.Prop.Embedded embedded && embedded.definition() != null) {
                String childPrefix = qualify(columnPrefix, embedded.name());
                String childOwner = ownerExpr + "." + getter(embedded.name()) + "()";
                String childGuard = nullGuard == null
                        ? childOwner + " == null"
                        : nullGuard + " || " + childOwner + " == null";
                for (TypeDefinition.Prop childProp : embedded.definition().properties()) {
                    if (childProp instanceof TypeDefinition.Prop.Scalar s) {
                        appendColumnBinding(body, s, childPrefix, childOwner, childGuard);
                    } else if (childProp instanceof TypeDefinition.Prop.Association childAssoc) {
                        appendRelationBinding(body, childAssoc, childPrefix, childOwner, childGuard);
                    } else if (childProp instanceof TypeDefinition.Prop.Embedded childEmbedded) {
                        appendRelationBinding(body, childEmbedded, childPrefix, childOwner, childGuard);
                    }
                }
            }
        }

        /**
         * Applies a {@code GeneratedKeyHolder} key onto {@code entityVar.setId(...)}.
         * Cast details follow {@code idTypeFqn}; this is not an identity-generation strategy.
         */
        void applyGeneratedKey(CodeBlock.Builder body, String entityVar, String keysVar, String idTypeFqn) {
            body.add("if ($L.getKey() != null) {\n", keysVar);
            switch (idTypeFqn) {
                case "java.lang.Integer", "int" ->
                        body.add("    $L.setId((($T) $L.getKey()).intValue());\n",
                                entityVar, Number.class, keysVar);
                case "java.lang.Short", "short" ->
                        body.add("    $L.setId((($T) $L.getKey()).shortValue());\n",
                                entityVar, Number.class, keysVar);
                case "java.lang.Long", "long" ->
                        body.add("    $L.setId((($T) $L.getKey()).longValue());\n",
                                entityVar, Number.class, keysVar);
                default ->
                        body.add("    $L.setId(($T) $L.getKey());\n",
                                entityVar, ClassName.bestGuess(idTypeFqn), keysVar);
            }
            body.add("}\n");
        }

        /**
         * Finds the name of the TO_ONE relation on the child that targets the parent type.
         */
        static String findInverseRelationName(TypeDefinition.Entity childDef, String parentTypeName) {
            for (TypeDefinition.Prop prop : childDef.properties()) {
                if (prop instanceof TypeDefinition.Prop.Association assoc
                        && assoc.definition() != null
                        && parentTypeName.equals(assoc.definition().typeName())) {
                    return assoc.name();
                }
            }
            String parentSimpleName = parentTypeName.contains(".")
                    ? parentTypeName.substring(parentTypeName.lastIndexOf('.') + 1)
                    : parentTypeName;
            for (TypeDefinition.Prop prop : childDef.properties()) {
                if (prop instanceof TypeDefinition.Prop.Association assoc
                        && assoc.definition() != null
                        && assoc.definition().typeName() != null) {
                    String target = assoc.definition().typeName();
                    String targetSimpleName = target.contains(".")
                            ? target.substring(target.lastIndexOf('.') + 1)
                            : target;
                    if (parentSimpleName.equals(targetSimpleName)) {
                        return assoc.name();
                    }
                }
            }
            return null;
        }

        static String qualify(String prefix, String leaf) {
            return prefix == null || prefix.isEmpty() ? leaf : prefix + "_" + leaf;
        }

        String getter(String propertyName) {
            return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
        }

        boolean isEnum(String typeFqn) {
            TypeElement typeElement = elements.getTypeElement(typeFqn);
            return typeElement != null && typeElement.getKind() == ElementKind.ENUM;
        }

        String capitalize(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
