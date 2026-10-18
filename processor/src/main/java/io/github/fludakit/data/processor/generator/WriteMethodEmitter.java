package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeVariableName;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelResolver;
import io.github.fludakit.data.processor.analysis.TypeMirrors;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.WriteCascade;
import io.github.fludakit.data.processor.support.Diagnostics;
import io.github.fludakit.data.processor.support.GeneratedKeyCoercion;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Emits the CRUD WRITE method bodies of a repository implementation: {@code save}, {@code insert},
 * {@code update}, {@code deleteById}, their batch (sequence-parameter) forms, and every child
 * statement those cascade into — element-collection child tables, owned entity collections and
 * to-one composition targets.
 *
 * <p>The ordering rules live here because they are the correctness of a write, not formatting:
 * children before parents on delete, owned to-one targets before the root on insert (the root's own
 * row carries their foreign keys), and a save that is an <em>upsert</em> — a null id inserts and lets
 * the database assign the key, a non-null id takes whichever route the dialect offers.</p>
 *
 * <p>Writes go into the shared {@link SqlConstants} registry, so two methods that render the same
 * statement share one {@code SQL_...} constant. Nothing here writes a source file or claims a type
 * name: the {@code MethodSpec.Builder} it is handed belongs to the caller's implementation class.</p>
 *
 * <p>It holds a raw {@link Types}/{@link Elements} pair. The two questions it asks about a declared
 * type — is this parameter the entity, and what does a sequence parameter carry — are
 * {@link TypeMirrors}, which was split out precisely because this emitter and the validator each had
 * their own copy of both.</p>
 */
public final class WriteMethodEmitter {

    /**
     * Handed down to {@link RowWrites}, which asks whether a property's declared type is an
     * {@code enum} to decide how the row binds it. This emitter resolves no type itself: everything
     * it writes comes from the {@link TypeMapping} it is handed.
     */
    private final AttributeTypes attributeTypes;
    private final TypeModelResolver typeMappingBuilder;
    private final Types types;
    private final Elements elements;
    private final Diagnostics diagnostics;
    private final SqlDialect sqlDialect;
    /** The row-level statements every write is built from — see {@link RowWrites}. */
    private final RowWrites rowWrites;
    /** The child-cascade statements: element collections, owned entity collections, compositions. */
    private final ChildCascadeEmitter childCascade;

    public WriteMethodEmitter(AttributeTypes attributeTypes, TypeModelResolver typeMappingBuilder,
                       Types types, Elements elements, Diagnostics diagnostics, SqlDialect sqlDialect) {
        this.attributeTypes = attributeTypes;
        this.typeMappingBuilder = typeMappingBuilder;
        this.types = types;
        this.elements = elements;
        this.diagnostics = diagnostics;
        this.sqlDialect = sqlDialect;
        this.rowWrites = new RowWrites(attributeTypes, sqlDialect, typeMappingBuilder);
        this.childCascade = new ChildCascadeEmitter(sqlDialect, rowWrites, attributeTypes, typeMappingBuilder);
    }

    public void emitSave(MethodSpec.Builder builder, ExecutableElement method, String value,
                          TypeMapping<?> entity, Map<String, String> constants,
                          WriteCascade cascade) {
        if (!GeneratedKeyCoercion.isCoercible(typeMappingBuilder.memberType(entity.typeName(), entity.id().name()))) {
            // The null-id discrimination routes an application-assigned entity with no id yet into an
            // INSERT that would bind NULL into the identity column — warn and keep compiling.
            diagnostics.note(
                    "id type " + typeMappingBuilder.memberType(entity.typeName(), entity.id().name()).box() + " requires application-assigned identifiers",
                    method);
        }
        appendUpsertStatements(builder, value, entity, constants, cascade);
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            builder.addStatement("return $L", value);
        }
    }

    private void appendUpsertStatements(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                        Map<String, String> constants, WriteCascade cascade) {
        childCascade.appendCompositionWrites(builder, value, entity, cascade, constants);
        builder.beginControlFlow("if ($L == null)", value + "." + MemberNames.getter(entity.id().name()) + "()");
        appendRootInsertAndChildInserts(builder, value, entity, constants, cascade);
        builder.nextControlFlow("else");
        if (rowWrites.upsertsInOneStatement()) {
            appendRootUpsert(builder, value, entity, constants);
            childCascade.appendChildSyncStatements(builder, value, entity, constants, cascade);
        } else {
            builder.beginControlFlow("if ($L == 0)", rootUpdate(value, entity, constants));
            appendInsertWithIdStatements(builder, value, entity, constants, cascade);
            // The row was updated, so only the child handling is left: the root UPDATE has already run
            // and must not be issued twice. With no children there is nothing to do at all.
            if (hasChildren(entity, cascade)) {
                builder.nextControlFlow("else");
                childCascade.appendChildSyncStatements(builder, value, entity, constants, cascade);
            }
            builder.endControlFlow();
        }
        builder.endControlFlow();
    }

    private static boolean hasChildren(TypeMapping<?> entity, WriteCascade cascade) {
        return !entity.elementCollections().isEmpty() || !cascade.collections().isEmpty();
    }

    public void emitLifecycleBatch(MethodSpec.Builder builder, ExecutableElement method,
                                    VariableElement parameter, TypeMirror parameterType,
                                    TypeMapping<?> entity, Map<String, String> constants,
                                    WriteCascade cascade) {
        String values = MemberNames.parameterName(parameter, "entities");
        TypeMirror valueType = TypeMirrors.sequenceElementType(parameterType);
        TypeName entityType = TypeName.get(types.erasure(valueType));
        builder.beginControlFlow("for ($T entity : $L)", entityType, values);
        if (method.getAnnotation(Insert.class) != null) {
            appendInsertAndChildCascades(builder, "entity", entity, constants, cascade);
        } else if (method.getAnnotation(Update.class) != null) {
            appendUpdateStatements(builder, "entity", entity, constants, cascade);
        } else if (method.getAnnotation(Save.class) != null) {
            appendUpsertStatements(builder, "entity", entity, constants, cascade);
        } else {
            emitDeleteById(builder, "entity." + MemberNames.getter(entity.id().name()) + "()", entity, constants, cascade);
        }
        builder.endControlFlow();
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            builder.addStatement("return $L", values);
        }
    }

    public boolean isEntitySequenceParameter(TypeMirror parameterType, TypeMapping<?> entity) {
        TypeMirror elementType = TypeMirrors.sequenceElementType(parameterType);
        return elementType != null && isEntityParameter(elementType, entity);
    }

    public void appendInsertAndChildCascades(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                        Map<String, String> constants, WriteCascade cascade) {
        childCascade.appendCompositionWrites(builder, value, entity, cascade, constants);
        appendRootInsertAndChildInserts(builder, value, entity, constants, cascade);
    }

    private void appendRootInsertAndChildInserts(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                                 Map<String, String> constants, WriteCascade cascade) {
        CodeBlock coercion = GeneratedKeyCoercion.coercion(
                typeMappingBuilder.memberType(entity.typeName(), entity.id().name()), "keys");
        if (coercion == null) {
            // An id type no GeneratedKeyHolder key can produce (String, UUID, ...) is
            // application-assigned: the identity column joins the INSERT and binds from the entity, so
            // there is no keyholder roundtrip for a value already known before the insert.
            appendRootInsertWithId(builder, value, entity, constants);
        } else {
            // includeId=false: the generated key is read back through GeneratedKeyHolder, so the identity
            // column must be omitted from the INSERT (H2 and most databases reject an explicit NULL).
            rowWrites.appendRowInsert(builder, value, entity, constants, "SQL_INSERT", false, "keys");
            builder.beginControlFlow("if (keys.getKey() != null)");
            builder.addStatement("$L.$L($L)",
                    value, MemberNames.setter(entity.id().name()), coercion);
            builder.endControlFlow();
        }

        for (Prop.ElementCollection collection : entity.elementCollections()) {
            childCascade.appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
        // the root INSERT cascades each composition entity collection — the child's own
        // generated-key machinery assigns its id, the inverse fk is bound from the ROOT id.
        for (EntityRelationship relationship : cascade.collections()) {
            childCascade.appendEntityCollectionInsert(builder, value, entity, relationship, constants);
        }
    }

    private void appendRootUpsert(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                  Map<String, String> constants) {
        String constant = SqlConstants.register(constants, "SQL_UPSERT",
                new SqlBuilder(entity, entity).dialect(sqlDialect).upsert());
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        rowWrites.appendAllColumnParams(sql, entity, value);
        sql.add(".update()");
        builder.addStatement("$L", sql.build());
    }

    public void appendUpdateStatements(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                        Map<String, String> constants, WriteCascade cascade) {
        childCascade.appendCompositionWrites(builder, value, entity, cascade, constants);
        builder.addStatement("$L", rootUpdate(value, entity, constants));
        childCascade.appendChildSyncStatements(builder, value, entity, constants, cascade);
    }

    private CodeBlock rootUpdate(String value, TypeMapping<?> entity, Map<String, String> constants) {
        return rowWrites.rowUpdate(value, entity, constants, "SQL_UPDATE");
    }

    private void appendRootInsertWithId(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                        Map<String, String> constants) {
        String constant = SqlConstants.register(constants, "SQL_INSERT",
                new SqlBuilder(entity, entity).dialect(sqlDialect).insert(true));
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        sql.add(".param($S, $L.$L())", SqlBuilder.paramName(entity.id().name()),
                value, MemberNames.getter(entity.id().name()));
        rowWrites.appendColumnParams(sql, entity, value);
        sql.add(".update()");
        builder.addStatement("$L", sql.build());
    }

    private void appendInsertWithIdStatements(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                              Map<String, String> constants, WriteCascade cascade) {
        appendRootInsertWithId(builder, value, entity, constants);
        for (Prop.ElementCollection collection : entity.elementCollections()) {
            childCascade.appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
        for (EntityRelationship relationship : cascade.collections()) {
            childCascade.appendEntityCollectionInsert(builder, value, entity, relationship, constants);
        }
    }

    public void emitBatch(MethodSpec.Builder builder, ExecutableElement method, VariableElement parameter,
                           TypeMapping<?> entity, String delegate) {
        TypeVariableName element = method.getTypeParameters().isEmpty()
                ? TypeVariableName.get("S", typeMappingBuilder.classNameFor(entity.typeName()))
                : TypeVariableName.get(method.getTypeParameters().getFirst().getSimpleName().toString(),
                        typeMappingBuilder.classNameFor(entity.typeName()));
        builder.addStatement("$T<$T> result = new $T<>()", List.class, element, ArrayList.class);
        builder.beginControlFlow("for ($T entity : $L)", element, MemberNames.parameterName(parameter, "entities"));
        builder.addStatement("result.add($L(entity))", delegate);
        builder.endControlFlow();
        builder.addStatement("return result");
    }

    public void emitDeleteById(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                Map<String, String> constants, WriteCascade cascade) {
        // Children first, then the parent: the child table has no FK constraint to lean on, but the
        // order keeps the child rows from outliving their parent even for a single autocommit delete.
        // Cascade over EVERY collection (save inserts all of them), not just the gate-passed
        // hydrated ones — a child whose VO fails the mapper gate would otherwise orphan rows. The child
        // DELETE is plain SQL keyed on the child table/FK, so it needs no VO mapper (see childDeleteConstant).
        for (Prop.ElementCollection collection : entity.elementCollections()) {
            String childConstant = childCascade.childDeleteConstant(constants, collection);
            builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", childConstant,
                    collection.parentFkColumn(), value);
        }
        // the composition lifecycle is bound to the root — entity-collection children are
        // deleted from the CHILD's own table by the inverse fk, same children-first order.
        for (EntityRelationship relationship : cascade.collections()) {
            Prop.EntityCollection list = (Prop.EntityCollection) relationship.attribute();
            String childConstant = childCascade.entityChildDeleteConstant(constants, list);
            builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", childConstant,
                    list.inverseFkColumn(), value);
        }
        String constant = SqlConstants.register(constants, "SQL_DELETE",
                new SqlBuilder(entity).dialect(sqlDialect).deleteById());
        builder.addStatement("jdbcClient.sql($L).param($S, $L).update()", constant, MemberNames.ID_PARAM, value);
    }

    public boolean isEntityParameter(TypeMirror parameterType, TypeMapping<?> entity) {
        return TypeMirrors.isEntity(types, elements, parameterType, entity);
    }
}
