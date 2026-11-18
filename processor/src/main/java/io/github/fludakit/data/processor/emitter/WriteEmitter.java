package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.MutationPlan;
import io.github.fludakit.data.metadata.MutationPlan.CascadeMode;
import io.github.fludakit.data.metadata.MutationPlan.CascadeSpec;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.analysis.MutationPlanAnalyzer;
import io.github.fludakit.data.processor.context.MethodContext;
import io.github.fludakit.data.processor.context.MethodContext.Operation;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.processor.support.ExceptionTranslation;
import io.github.fludakit.data.processor.support.MethodOverrides;
import io.github.fludakit.data.query.Predicate;
import io.github.fludakit.data.query.QueryDefinition;
import io.github.fludakit.data.query.ResultKind;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.sql.UpsertForm;

import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Generates method implementations for write operations: save, insert, update, and deleteById.
 *
 * <p>Dispatches on {@link Operation}. Cascade / child-table bodies come from {@link CascadeSupport};
 * binding helpers from {@link WriteSupport}. Insert/save branch on null id at emit time — no
 * identity-generation metadata.</p>
 */
public final class WriteEmitter {

    private final SqlDialect dialect;
    private final Types types;
    private final DeclaredType baseRepositoryType;
    private final DeclaredType repositoryOwnType;
    private final WriteSupport support;
    private final CascadeSupport cascades;

    public WriteEmitter(SqlDialect dialect, Types types, Elements elements,
                        DeclaredType baseRepositoryType, DeclaredType repositoryOwnType) {
        this.dialect = dialect;
        this.types = types;
        this.baseRepositoryType = baseRepositoryType;
        this.repositoryOwnType = repositoryOwnType;
        this.support = new WriteSupport(elements);
        this.cascades = new CascadeSupport(dialect, support);
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
    public MethodSpec emit(RepositoryContext ctx, MethodContext method) {
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

        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.mutationType());

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

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.PRE_INSERT) {
                cascades.emitPreInsertSpec(body, spec, ctx, entityVar);
            }
        }

        // null id → insert + keyholder; id present → upsert/update
        body.add("if ($L.getId() == null) {\n", entityVar);
        body.add("    $T $L = new $T();\n", WriteSupport.GENERATED_KEY_HOLDER, "keys",
                WriteSupport.GENERATED_KEY_HOLDER);
        body.add("    jdbcClient.sql($L)", insertConstName);
        support.appendNonIdBindings(body, entity, entityVar);
        body.add(".update($L);\n", "keys");
        support.applyGeneratedKey(body, entityVar, "keys", idTypeFqn);
        body.add("} else {\n");
        body.add("    jdbcClient.sql($L)", upsertConstName);
        support.appendAllBindings(body, entity, entityVar);
        body.add(".update();\n");
        body.add("}");

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.PRE_INSERT) {
                cascades.emitPostCascadeSpec(builder, spec, ctx, entityVar);
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

        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.mutationType());

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String insertWithoutId = ctx.registerConstant("INSERT", sb.insert(false));
        String insertWithId = ctx.registerConstant("INSERT_WITH_ID", sb.insert(true));

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() == CascadeMode.PRE_INSERT) {
                cascades.emitPreInsertSpec(body, spec, ctx, entityVar);
            }
        }

        body.add("if ($L.getId() == null) {\n", entityVar);
        body.add("    $T $L = new $T();\n", WriteSupport.GENERATED_KEY_HOLDER, "keys",
                WriteSupport.GENERATED_KEY_HOLDER);
        body.add("    jdbcClient.sql($L)", insertWithoutId);
        support.appendNonIdBindings(body, entity, entityVar);
        body.add(".update($L);\n", "keys");
        support.applyGeneratedKey(body, entityVar, "keys", idTypeFqn);
        body.add("} else {\n");
        body.add("    jdbcClient.sql($L)", insertWithId);
        support.appendAllBindings(body, entity, entityVar);
        body.add(".update();\n");
        body.add("}");

        builder.addCode(ExceptionTranslation.wrap(body.build()));

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.PRE_INSERT) {
                cascades.emitPostCascadeSpec(builder, spec, ctx, entityVar);
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

        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.mutationType());

        SqlBuilder sb = new SqlBuilder(entity);
        sb.dialect(dialect);
        String constName = ctx.registerConstant("UPDATE", sb.update());

        CodeBlock.Builder body = CodeBlock.builder();
        boolean returnsEntity = method.resultKind() == ResultKind.SINGLE;

        body.add("jdbcClient.sql($L)", constName);
        support.appendNonIdBindings(body, entity, entityVar);
        body.add(".param($S, $L.getId()).update();\n", "id", entityVar);

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            CodeBlock code = cascades.buildPostCascadeCodeBlock(spec, ctx, entityVar);
            if (!code.toString().isEmpty()) {
                body.add(code);
                body.add("\n");
            }
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

        MutationPlan plan = MutationPlanAnalyzer.analyze(ctx.entity(), method.mutationType());

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
        String constName = ctx.registerConstant(FindEmitter.toSqlConstantName(method.name()), sql);

        CodeBlock.Builder body = CodeBlock.builder();

        boolean isDeleteById = isDeleteByIdPredicate(entity, method);
        String parentIdExpr = isDeleteById ? idParamName(method, entity) : null;
        MutationPlan plan = isDeleteById ? MutationPlanAnalyzer.analyze(entity, method.mutationType()) : null;

        if (plan != null) {
            cascades.emitCascadePreDelete(body, ctx, plan, parentIdExpr);
        }

        boolean isVoid = method.resultKind() == ResultKind.VOID;
        if (!isVoid) body.add("return ");
        body.add("jdbcClient\n");
        body.add("    .sql($L)\n", constName);
        FindEmitter.appendParamBindings(body, method.paramBindings(), method);
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
}
