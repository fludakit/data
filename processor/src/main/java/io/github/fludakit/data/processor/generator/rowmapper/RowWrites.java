package io.github.fludakit.data.processor.generator.rowmapper;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelBuilder;
import io.github.fludakit.data.processor.generator.SqlConstants;
import io.github.fludakit.data.processor.support.GeneratedKeyCoercion;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.data.sql.UpsertForm;
import io.github.fludakit.jdbc.support.GeneratedKeyHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How ONE ROW is written, whichever statement carries it: the INSERT (with or without a generated key),
 * the dialect's one-statement upsert where it has one, the plain UPDATE, and the column/parameter lists
 * both of them bind.
 *
 * <p>The write-side twin of {@link RowReads}: {@code RowReads} answers "how is one row read back", this answers
 * "how is one row written". They are separate because the two halves have no shared rule — a read
 * decides a getter per column, a write decides a parameter per column — but each is shared by callers
 * that must not disagree: {@link WriteMethodEmitter} writes the ROOT row, {@link ChildCascadeEmitter}
 * writes the rows of the children an aggregate owns, and a composition target is written through this
 * same path, which is exactly why a composition and a root cannot end up with different INSERT
 * spellings for the same entity.</p>
 *
 * <p>Holds no entity-level state: every method takes the model it is writing and the constant registry
 * it registers its SQL in, so the same instance serves the root and every child.</p>
 */
public final class RowWrites {

    private final AttributeTypes attributeTypes;
    private final SqlDialect sqlDialect;
    private final TypeModelBuilder typeMappingBuilder;

    /**
     * @param attributeTypes for {@code isEnumProp} — whether a property's declared type is an
     *                       {@code enum} decides how the row binds it. That is a question about the
     *                       MODEL, not about the statement emitted here, so the resolution is owned
     *                       once in {@code AttributeTypes} rather than this class carrying
     *                       {@code Elements} to answer it
     */
    public RowWrites(AttributeTypes attributeTypes, SqlDialect sqlDialect,
                     TypeModelBuilder typeMappingBuilder) {
        this.attributeTypes = attributeTypes;
        this.sqlDialect = sqlDialect;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    public boolean upsertsInOneStatement() {
        return sqlDialect.upsertForm() != UpsertForm.NONE;
    }

    public void appendRowWrite(MethodSpec.Builder builder, String value, TypeMapping<?> model,
                                Map<String, String> constants, String insertName, String upsertName,
                                String updateName, String keysVar) {
        CodeBlock coercion = GeneratedKeyCoercion.coercion(
                typeMappingBuilder.memberType(model.typeName(), model.id().name()), keysVar);
        builder.beginControlFlow("if ($L == null)", value + "." + MemberNames.getter(model.id().name()) + "()");
        if (coercion == null) {
            // An id type no GeneratedKeyHolder key can produce is application-assigned: the identity
            // column joins the INSERT and binds from the object, so there is no keyholder roundtrip.
            appendRowInsert(builder, value, model, constants, insertName, true, keysVar);
        } else {
            appendRowInsert(builder, value, model, constants, insertName, false, keysVar);
            builder.beginControlFlow("if ($L.getKey() != null)", keysVar);
            builder.addStatement("$L.$L($L)",
                    value, MemberNames.setter(model.id().name()), coercion);
            builder.endControlFlow();
        }
        builder.nextControlFlow("else");
        if (upsertsInOneStatement()) {
            String constant = SqlConstants.register(constants, upsertName,
                    new SqlBuilder(model, model).dialect(sqlDialect).upsert());
            CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
            appendAllColumnParams(sql, model, value);
            sql.add(".update()");
            builder.addStatement("$L", sql.build());
        } else {
            builder.beginControlFlow("if ($L == 0)", rowUpdate(value, model, constants, updateName));
            appendRowInsert(builder, value, model, constants, insertName, true, keysVar);
            builder.endControlFlow();
        }
        builder.endControlFlow();
    }

    public void appendRowInsert(MethodSpec.Builder builder, String value, TypeMapping<?> model,
                                 Map<String, String> constants, String insertName, boolean includeId,
                                 String keysVar) {
        boolean readBackKey = !includeId && keysVar != null;
        String constant = SqlConstants.register(constants, insertName,
                new SqlBuilder(model, model).dialect(sqlDialect).insert(includeId));
        if (readBackKey) {
            builder.addStatement("$T $L = new $T()", GeneratedKeyHolder.class, keysVar,
                    GeneratedKeyHolder.class);
        }
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        if (includeId) {
            sql.add(".param($S, $L.$L())", SqlBuilder.paramName(model.id().name()),
                    value, MemberNames.getter(model.id().name()));
        }
        appendColumnParams(sql, model, value);
        if (readBackKey) {
            sql.add(".update($L)", keysVar);
        } else {
            sql.add(".update()");
        }
        builder.addStatement("$L", sql.build());
    }

    public void appendChildColumnParams(CodeBlock.Builder params, TypeMapping<?> mapping, String entityVar,
                                         String inverseFk, String rootValue, TypeMapping<?> root,
                                         boolean includeId) {
        for (Map.Entry<String, ColumnRef> entry : mapping.writeColumnRefs().entrySet()) {
            ColumnRef ref = entry.getValue();
            if (!includeId && isIdentifierColumn(mapping, ref)) {
                continue;
            }
            if (ref.column().equals(inverseFk)) {
                params.add(".param($S, $L.$L())", SqlBuilder.paramName(entry.getKey()),
                        rootValue, MemberNames.getter(root.id().name()));
            } else {
                params.add(".param($S, $L)", SqlBuilder.paramName(entry.getKey()),
                        boundValue(mapping, entityVar, entry.getKey(), ref));
            }
        }
    }

    public void appendColumnParams(CodeBlock.Builder params, TypeMapping<?> entity, String entityVar) {
        TypeMapping<?> mapping = entity;
        for (Map.Entry<String, ColumnRef> entry : mapping.writeColumnRefs().entrySet()) {
            ColumnRef ref = entry.getValue();
            if (isIdentifierColumn(mapping, ref)) {
                continue;
            }
            params.add(".param($S, $L)", SqlBuilder.paramName(entry.getKey()),
                    boundValue(mapping, entityVar, entry.getKey(), ref));
        }
    }

    public boolean isIdentifierColumn(TypeMapping<?> mapping, ColumnRef columnRef) {
        return mapping.id() != null && columnRef.column().equals(mapping.id().column());
    }

    public void appendAllColumnParams(CodeBlock.Builder params, TypeMapping<?> entity, String entityVar) {
        TypeMapping<?> mapping = entity;
        for (Map.Entry<String, ColumnRef> entry : mapping.writeColumnRefs().entrySet()) {
            params.add(".param($S, $L)", SqlBuilder.paramName(entry.getKey()),
                    boundValue(mapping, entityVar, entry.getKey(), entry.getValue()));
        }
    }

    public CodeBlock boundValue(TypeMapping<?> mapping, String rootVar, String attributePath, ColumnRef columnRef) {
        String[] segments = attributePath.split("\\.");
        StringBuilder chain = new StringBuilder(rootVar);
        List<String> nullChecks = new ArrayList<>();
        TypeMapping<?> current = mapping;
        for (int i = 0; i < segments.length; i++) {
            Prop attr = current.prop(segments[i]);
            chain.append('.').append(MemberNames.getter(segments[i])).append("()");
            if (i < segments.length - 1) {
                nullChecks.add(chain.toString());
                current = ((Prop.Embedded) attr).target();
            } else if (attr instanceof Prop.Reference) {
                nullChecks.add(chain.toString());
                chain.append(".getId()");
            } else if (isEnumProp(attr)) {
                // Enums persist as their name, not the Java-serialized value.
                nullChecks.add(chain.toString());
                chain.append(".name()");
            }
        }
        if (nullChecks.isEmpty()) {
            return CodeBlock.of("$L", chain.toString());
        }
        StringBuilder guard = new StringBuilder();
        for (int i = 0; i < nullChecks.size(); i++) {
            if (i > 0) {
                guard.append(" || ");
            }
            guard.append(nullChecks.get(i)).append(" == null");
        }
        return CodeBlock.of("$L ? null : $L", guard.toString(), chain.toString());
    }

    public boolean isEnumProp(Prop prop) {
        String fqn = prop instanceof Prop.Id id ? id.typeFqn()
                : prop instanceof Prop.Scalar scalar ? scalar.typeFqn() : null;
        if (fqn == null) {
            return false;
        }
        return attributeTypes.isEnum(fqn);
    }

    public CodeBlock rowUpdate(String value, TypeMapping<?> model, Map<String, String> constants, String updateName) {
        String constant = SqlConstants.register(constants, updateName,
                new SqlBuilder(model, model).dialect(sqlDialect).update());
        CodeBlock.Builder update = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        appendColumnParams(update, model, value);
        update.add(".param($S, $L.$L())", MemberNames.ID_PARAM, value, MemberNames.getter(model.id().name()));
        return update.add(".update()").build();
    }
}
