package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.MutationPlan;
import io.github.fludakit.data.processor.context.MutationPlan.CascadeMode;
import io.github.fludakit.data.processor.context.MutationPlan.CascadeSpec;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds cascade / child-table {@link CodeBlock}s from a {@link MutationPlan}.
 *
 * <p>Not a method emitter — {@link WriteEmitter} owns repository {@code MethodSpec}s and
 * embeds these blocks. Child insert uses the null-id rule (keyholder vs update), not
 * identity-generation metadata.</p>
 */
final class CascadeSupport {

    private final SqlDialect dialect;
    private final WriteSupport support;

    CascadeSupport(SqlDialect dialect, WriteSupport support) {
        this.dialect = dialect;
        this.support = support;
    }

    /**
     * Generates code for a single PRE_INSERT cascade spec (composition association).
     * Inserts the child before the root so its generated ID can be bound as FK on the root row.
     */
    void emitPreInsertSpec(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) return;
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return;

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

        for (CascadeSpec child : spec.children()) {
            if (child.mode() == CascadeMode.PRE_INSERT) {
                emitPreInsertSpec(body, child, ctx, childVar);
            }
        }

        body.add("    if ($L.getId() == null) {\n", childVar);
        body.add("        $T $L = new $T();\n", WriteSupport.GENERATED_KEY_HOLDER,
                "childKeys_" + assoc.name(), WriteSupport.GENERATED_KEY_HOLDER);
        body.add("        jdbcClient.sql($L)", childInsertConst);
        support.appendNonIdBindings(body, childEntity, childVar);
        body.add(".update($L);\n", "childKeys_" + assoc.name());
        support.applyGeneratedKey(body, childVar, "childKeys_" + assoc.name(), childIdTypeFqn);
        body.add("    } else {\n");
        body.add("        jdbcClient.sql($L)", childUpdateConst);
        support.appendAllBindings(body, childEntity, childVar);
        body.add(".update();\n");
        body.add("    }\n");

        for (CascadeSpec child : spec.children()) {
            if (child.mode() != CascadeMode.PRE_INSERT) {
                emitPostCascadeSpec(child, ctx, childVar, body::add);
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
    void emitPostCascadeSpec(CascadeSpec spec, RepositoryContext ctx, String entityVar,
                             Consumer<CodeBlock> sink) {
        CodeBlock code = buildPostCascadeCodeBlock(spec, ctx, entityVar);
        if (!code.toString().isEmpty()) {
            sink.accept(code);
        }
    }

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

    private CodeBlock buildCompositionUpdateCodeBlock(CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) return CodeBlock.builder().build();
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return CodeBlock.builder().build();

        String childVar = entityVar + "." + assoc.name();

        SqlBuilder childSb = new SqlBuilder(childEntity);
        childSb.dialect(dialect);
        String childUpdateSql = childSb.update();
        String childUpdateConst = ctx.registerConstant(
                "CASCADE_UPDATE_" + assoc.name().toUpperCase(), childUpdateSql);

        CodeBlock.Builder cascade = CodeBlock.builder();
        cascade.add("if ($L != null) {\n", childVar);
        cascade.add("    jdbcClient.sql($L)", childUpdateConst);
        support.appendAllBindings(cascade, childEntity, childVar);
        cascade.add(".update();\n");

        for (CascadeSpec child : spec.children()) {
            emitPostCascadeSpec(child, ctx, childVar, code -> {
                cascade.add(code);
                cascade.add("\n");
            });
        }

        cascade.add("}");
        return cascade.build();
    }

    private CodeBlock buildDiffSyncCodeBlock(CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (!(spec.prop() instanceof TypeDefinition.Prop.EntityCollection ec)) return CodeBlock.builder().build();
        if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) return CodeBlock.builder().build();

        String collectionGetter = entityVar + "." + support.getter(ec.name()) + "()";
        String childVar = "child_" + ec.name();
        String parentIdExpr = entityVar + ".getId()";

        String inverseName = WriteSupport.findInverseRelationName(childEntity, ctx.entity().typeName());

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
        cascade.add("            $T $L = new $T();\n", WriteSupport.GENERATED_KEY_HOLDER,
                "ecKeys_" + ec.name(), WriteSupport.GENERATED_KEY_HOLDER);
        cascade.add("            jdbcClient.sql($L)", childInsertConst);
        support.appendChildNonIdBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
        cascade.add(".update($L);\n", "ecKeys_" + ec.name());
        support.applyGeneratedKey(cascade, childVar, "ecKeys_" + ec.name(), childIdTypeFqn);
        cascade.add("        } else {\n");
        cascade.add("            jdbcClient.sql($L)", childUpdateConst);
        support.appendChildPropBindings(cascade, childEntity, childVar, parentIdExpr, inverseName, ec.fkColumn());
        cascade.add(".update();\n");
        cascade.add("        }\n");
        cascade.add("        $L.add($L.getId());\n", "currentIds_" + ec.name(), childVar);

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

    private void emitCascadeSpecIntoLoop(CodeBlock.Builder body, CascadeSpec spec, RepositoryContext ctx, String entityVar) {
        if (spec.mode() == CascadeMode.PRE_INSERT) {
            emitPreInsertSpec(body, spec, ctx, entityVar);
        } else {
            emitPostCascadeSpec(spec, ctx, entityVar, body::add);
        }
    }

    private CodeBlock buildCascadeDeleteCodeBlock(CascadeSpec spec, RepositoryContext ctx, String parentIdExpr) {
        if (spec.prop() instanceof TypeDefinition.Prop.EntityCollection ec
                && spec.target() instanceof TypeDefinition.Entity childEntity) {
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
        String getterName = entityVar + ".get" + support.capitalize(ec.name()) + "()";
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
                String columnName = WriteSupport.qualify(columnPrefix, assoc.fkColumn());
                String getterCall = chain + "." + support.getter(assoc.name()) + "()";
                String binding = columnPrefix.isEmpty()
                        ? getterCall + " == null ? null : " + getterCall + ".getId()"
                        : chain + " == null ? null : " + getterCall + " == null ? null : " + getterCall + ".getId()";
                leaves.add(new Leaf(columnName, binding));
            }
        }
        for (TypeDefinition.Prop prop : type.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                String columnName = WriteSupport.qualify(columnPrefix, s.columnName());
                String getterCall = chain + "." + support.getter(s.name()) + "()";
                String binding;
                if (support.isEnum(s.javaType())) {
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
                String newChain = chain + "." + support.getter(emb.name()) + "()";
                collectLeaves(emb.definition(), leaves, newChain, WriteSupport.qualify(columnPrefix, emb.name()));
            }
        }
    }

    void emitCascadePreDelete(CodeBlock.Builder body, RepositoryContext ctx, MutationPlan plan,
                              String parentIdExpr) {
        SqlBuilder lifecycle = new SqlBuilder(ctx.entity());
        lifecycle.dialect(dialect);

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.CASCADE_DELETE
                    || !(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) {
                continue;
            }
            if (!(spec.target() instanceof TypeDefinition.Entity)) {
                continue;
            }
            String loadSql = lifecycle.selectForeignKey(assoc.fkColumn());
            String loadConst = ctx.registerConstant("LOAD_FK_" + assoc.name().toUpperCase(), loadSql);
            body.add("Long $L = jdbcClient.sql($L).param($S, $L).query(Long.class).single();\n",
                    "childId_" + assoc.name(), loadConst, "id", parentIdExpr);
        }

        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.CASCADE_DELETE
                    || spec.prop() instanceof TypeDefinition.Prop.Association) {
                continue;
            }
            String table;
            String fkColumn;
            String constBase;
            if (spec.prop() instanceof TypeDefinition.Prop.ElementCollection ec) {
                table = ec.childTable();
                fkColumn = ec.fkColumn();
                constBase = "EC_DELETE_" + ec.name().toUpperCase();
            } else if (spec.prop() instanceof TypeDefinition.Prop.EntityCollection coll
                    && spec.target() instanceof TypeDefinition.Entity child) {
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

    void emitCascadePostDelete(CodeBlock.Builder body, RepositoryContext ctx, MutationPlan plan) {
        for (CascadeSpec spec : plan.cascadeSpecs()) {
            if (spec.mode() != CascadeMode.CASCADE_DELETE
                    || !(spec.prop() instanceof TypeDefinition.Prop.Association assoc)) {
                continue;
            }
            if (!(spec.target() instanceof TypeDefinition.Entity childEntity)) {
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
