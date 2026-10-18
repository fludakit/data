package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.processor.analysis.TypeModelResolver;
import io.github.fludakit.data.processor.model.EntityRelationship;
import io.github.fludakit.data.processor.model.WriteCascade;
import io.github.fludakit.data.processor.support.GeneratedKeyCoercion;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.sql.SqlBuilder;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.jdbc.support.GeneratedKeyHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The statements a write of the ROOT owes to its CHILDREN: the element-collection child tables, the
 * owned entity collections, and the composition targets the root row holds a foreign key to.
 *
 * <p>Split from {@link WriteMethodEmitter} because the two halves answer different questions and grow
 * for different reasons:</p>
 *
 * <ul>
 *   <li>a ROOT statement is one INSERT / UPSERT / UPDATE of the entity's own table;</li>
 *   <li>a CHILD statement is a SET — element collections are replace-all (delete the rows for the
 *       parent, then insert the current ones), owned entity collections are a diff (upsert each, then
 *       delete the orphans), and a composition target is written BEFORE the root because the root row
 *       carries its foreign key.</li>
 * </ul>
 *
 * <p>Both halves build their statements from {@link RowWrites}, so a child row and a root row are
 * written by the same code.</p>
 */
public final class ChildCascadeEmitter {

    private final SqlDialect sqlDialect;
    private final RowWrites rowWrites;
    private final AttributeTypes attributeTypes;
    private final TypeModelResolver typeMappingBuilder;

    /**
     * No {@code Elements} and no {@code Types} of its own: every member this emitter writes was
     * already resolved into the {@link TypeMapping} or the {@link Prop.ElementCollection} prop it is
     * handed — the child tables come from {@link Names#childTable(String, String)} and the target
     * models from {@link WriteCascade#compositions()}.
     *
     * <p>The one thing it does look up is the element type of a collection, and that is because an
     * FQN cannot be turned back into a JavaPoet name without the round: {@link AttributeTypes} is the
     * collaborator that owns that lookup, so this class still holds no environment service.</p>
     */
    public ChildCascadeEmitter(SqlDialect sqlDialect, RowWrites rowWrites,
                               AttributeTypes attributeTypes, TypeModelResolver typeMappingBuilder) {
        this.sqlDialect = sqlDialect;
        this.rowWrites = rowWrites;
        this.attributeTypes = attributeTypes;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    /** The element type of a {@code List<@ValueObject>} collection: what the generated loop declares. */
    private TypeName elementTypeOf(Prop.ElementCollection collection) {
        return attributeTypes.classArgOf(collection.elementTypeName());
    }

    public void appendCompositionWrites(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                         WriteCascade cascade, Map<String, String> constants) {
        for (EntityRelationship relationship : cascade.compositions()) {
            Prop.Reference reference = (Prop.Reference) relationship.attribute();
            TypeMapping<?> target = relationship.targetModel();
            String base = "SQL_" + MemberNames.upperSnake(reference.name());
            String targetVar = "target_" + reference.name();
            builder.addStatement("$T $L = $L.$L()", typeMappingBuilder.classNameFor(target.typeName()), targetVar, value,
                    MemberNames.getter(reference.name()));
            builder.beginControlFlow("if ($L != null)", targetVar);
            rowWrites.appendRowWrite(builder, targetVar, target, constants, base + "_INSERT", base + "_UPSERT",
                    base + "_UPDATE", "keys_" + reference.name());
            builder.endControlFlow();
        }
    }

    public void appendEntityCollectionInsert(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                              EntityRelationship relationship, Map<String, String> constants) {
        Prop.EntityCollection list = (Prop.EntityCollection) relationship.attribute();
        TypeMapping<?> target = relationship.targetModel();
        TypeMapping<?> mapping = target;
        String inverseFk = list.inverseFkColumn();
        String constant = SqlConstants.register(constants, "SQL_INSERT_" + MemberNames.upperSnake(list.name()),
                new SqlBuilder(mapping, mapping).dialect(sqlDialect).insert(false));
        builder.beginControlFlow("for ($T item : $L.$L())", typeMappingBuilder.classNameFor(target.typeName()),
                value, MemberNames.getter(list.name()));
        builder.beginControlFlow("if (item != null)");
        builder.addStatement("$T childKeys = new $T()", GeneratedKeyHolder.class, GeneratedKeyHolder.class);
        CodeBlock.Builder sql = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        for (Map.Entry<String, ColumnRef> entry : mapping.writeColumnRefs().entrySet()) {
            ColumnRef ref = entry.getValue();
            if (rowWrites.isIdentifierColumn(mapping, ref)) {
                continue;
            }
            if (ref.column().equals(inverseFk)) {
                sql.add(".param($S, $L.$L())", SqlBuilder.paramName(entry.getKey()),
                        value, MemberNames.getter(entity.id().name()));
            } else {
                sql.add(".param($S, $L)", SqlBuilder.paramName(entry.getKey()),
                        rowWrites.boundValue(mapping, "item", entry.getKey(), ref));
            }
        }
        sql.add(".update(childKeys)");
        builder.addStatement("$L", sql.build());
        builder.beginControlFlow("if (childKeys.getKey() != null)");
        builder.addStatement("item.$L($L)", MemberNames.setter(target.id().name()),
                GeneratedKeyCoercion.coercion(typeMappingBuilder.memberType(target.typeName(), target.id().name()), "childKeys"));
        builder.endControlFlow();
        builder.endControlFlow();
        builder.endControlFlow();
    }

    public String entityChildDeleteConstant(Map<String, String> constants, Prop.EntityCollection list) {
        return SqlConstants.register(constants, "SQL_DELETE_" + MemberNames.upperSnake(list.name()),
                childDeleteSql(list.targetTable(), list.inverseFkColumn()));
    }

    public void appendEntityCollectionDelete(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                              EntityRelationship relationship, Map<String, String> constants) {
        Prop.EntityCollection list = (Prop.EntityCollection) relationship.attribute();
        appendChildDelete(builder, value, entity,
                entityChildDeleteConstant(constants, list), list.inverseFkColumn());
    }

    public void appendChildDelete(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                   String constant, String fkColumn) {
        builder.addStatement("jdbcClient.sql($L).param($S, $L.$L()).update()", constant, fkColumn,
                value, MemberNames.getter(entity.id().name()));
    }

    public void appendElementCollectionInsert(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                               Prop.ElementCollection collection,
                                               Map<String, String> constants) {
        // the child-table column list must iterate the SAME flattened descriptor columns the
        // params below bind. The old list walked {@code collection.rowProps()} while the params
        // walked {@code descriptor().columns()}; the two diverge for a collection VO that embeds
        // another VO (one column per property vs one per flattened leaf). Iterating the descriptor
        // here keeps columns and params aligned. Byte-stable for a flat VO (no nested embedded).
        List<String> columns = childColumnNames(collection);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(sqlDialect.quoteIdentifier(collection.childTable()))
                .append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(sqlDialect.quoteIdentifier(columns.get(i)));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(':').append(columns.get(i));
        }
        sql.append(')');
        String constant = SqlConstants.register(constants, "SQL_INSERT_" + MemberNames.upperSnake(collection.name()), sql.toString());

        builder.beginControlFlow("for ($T item : $L.$L())", elementTypeOf(collection), value,
                MemberNames.getter(collection.name()));
        builder.beginControlFlow("if (item != null)");
        CodeBlock.Builder insert = CodeBlock.builder().add("jdbcClient.sql($L)", constant);
        insert.add(".param($S, $L.$L())", collection.parentFkColumn(), value, MemberNames.getter(entity.id().name()));
        // Child-table placeholders stay COLUMN-named: the hand-rendered INSERT above lists the
        // element-collection columns (:product_id, :quantity), so the params must match them. The
        // authoritative flat child-column list is {@link Prop.ElementCollection#elementColumns()} — one
        // source of truth — while the value still walks the descriptor path through boundValue so
        // fk-bearing children bind their id exactly like the previous property-driven loop did.
        for (Map.Entry<String, ColumnRef> entry : collection.target().localColumnRefs().entrySet()) {
            insert.add(".param($S, $L)", entry.getValue().column(),
                    rowWrites.boundValue(collection.target(), "item", entry.getKey(), entry.getValue()));
        }
        insert.add(".update()");
        builder.addStatement("$L", insert.build());
        builder.endControlFlow();
        builder.endControlFlow();
    }

    public List<String> childColumnNames(Prop.ElementCollection collection) {
        List<String> columns = new ArrayList<>();
        columns.add(collection.parentFkColumn());
        for (ColumnRef ref : collection.elementColumns()) {
            columns.add(ref.column());
        }
        return columns;
    }

    public void appendChildSyncStatements(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                           Map<String, String> constants, WriteCascade cascade) {
        for (Prop.ElementCollection collection : entity.elementCollections()) {
            appendElementCollectionDelete(builder, value, entity, collection, constants);
            appendElementCollectionInsert(builder, value, entity, collection, constants);
        }
        for (EntityRelationship relationship : cascade.collections()) {
            if (rowWrites.upsertsInOneStatement()) {
                appendEntityCollectionUpsert(builder, value, entity, relationship, constants);
            } else {
                // No one-statement upsert: delete the parent's whole child set by the inverse fk and
                // re-insert it. Child ids are REGENERATED, which is what replace-all has always meant —
                // without an upsert there is no way to write a row under a known key.
                appendEntityCollectionDelete(builder, value, entity, relationship, constants);
                appendEntityCollectionInsert(builder, value, entity, relationship, constants);
            }
        }
    }

    public void appendEntityCollectionUpsert(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                              EntityRelationship relationship, Map<String, String> constants) {
        Prop.EntityCollection list = (Prop.EntityCollection) relationship.attribute();
        TypeMapping<?> target = relationship.targetModel();
        TypeMapping<?> mapping = target;
        String inverseFk = list.inverseFkColumn();
        String base = MemberNames.upperSnake(list.name());
        TypeName idType = typeMappingBuilder.memberType(target.typeName(), target.id().name()).box();
        String keys = "keys_" + list.name();

        String idsSelect = SqlConstants.register(constants, "SQL_SELECT_IDS_" + base,
                childIdsSelectSql(list.targetTable(), inverseFk, mapping.idColumn()));
        String orphanDelete = SqlConstants.register(constants, "SQL_DELETE_ORPHAN_" + base,
                orphanChildDeleteSql(list.targetTable(), inverseFk, mapping.idColumn()));
        String upsert = SqlConstants.register(constants, "SQL_UPSERT_" + base,
                new SqlBuilder(mapping, mapping).dialect(sqlDialect).upsert());
        String insert = SqlConstants.register(constants, "SQL_INSERT_" + base,
                new SqlBuilder(mapping, mapping).dialect(sqlDialect).insert(false));

        CodeBlock.Builder select = CodeBlock.builder().add("jdbcClient.sql($L)", idsSelect);
        select.add(".param($S, $L.$L()).query($T.class).list()", inverseFk, value,
                MemberNames.getter(entity.id().name()), idType);
        builder.addStatement("$T<$T> $L = new $T<>($L)", List.class, idType, keys, ArrayList.class,
                select.build());

        builder.beginControlFlow("for ($T item : $L.$L())", typeMappingBuilder.classNameFor(target.typeName()),
                value, MemberNames.getter(list.name()));
        builder.beginControlFlow("if (item != null)");
        builder.beginControlFlow("if (item.$L() != null)", MemberNames.getter(target.id().name()));
        CodeBlock.Builder upsertSql = CodeBlock.builder().add("jdbcClient.sql($L)", upsert);
        rowWrites.appendChildColumnParams(upsertSql, mapping, "item", inverseFk, value, entity, true);
        upsertSql.add(".update()");
        builder.addStatement("$L", upsertSql.build());
        builder.nextControlFlow("else");
        // A brand-new child: INSERT with the identity column omitted and the generated key read back
        // onto the item, so a later orphan pass recognises it as kept. The inverse fk is bound from
        // the ROOT, never from the child — a freshly built child does not point back at its parent.
        builder.addStatement("$T childKeys = new $T()", GeneratedKeyHolder.class, GeneratedKeyHolder.class);
        CodeBlock.Builder insertSql = CodeBlock.builder().add("jdbcClient.sql($L)", insert);
        rowWrites.appendChildColumnParams(insertSql, mapping, "item", inverseFk, value, entity, false);
        insertSql.add(".update(childKeys)");
        builder.addStatement("$L", insertSql.build());
        builder.beginControlFlow("if (childKeys.getKey() != null)");
        builder.addStatement("item.$L($L)", MemberNames.setter(target.id().name()),
                GeneratedKeyCoercion.coercion(typeMappingBuilder.memberType(target.typeName(), target.id().name()), "childKeys"));
        builder.endControlFlow();
        builder.endControlFlow();
        // A child the upsert/insert gave a key is not an orphan — remove it from the doomed set.
        builder.beginControlFlow("if (item.$L() != null)", MemberNames.getter(target.id().name()));
        builder.addStatement("$L.remove(item.$L())", keys, MemberNames.getter(target.id().name()));
        builder.endControlFlow();
        builder.endControlFlow();
        builder.endControlFlow();

        // Whatever is left was a child of this parent that the in-memory collection no longer names.
        // An emptied collection removes nothing from the set, so the whole child set goes — which is
        // exactly what "the collection is now empty" has to mean.
        builder.beginControlFlow("for ($T orphan : $L)", idType, keys);
        builder.addStatement("jdbcClient.sql($L).param($S, $L.$L()).param($S, $L).update()", orphanDelete,
                inverseFk, value, MemberNames.getter(entity.id().name()), mapping.idColumn(), "orphan");
        builder.endControlFlow();
    }

    public String childIdsSelectSql(String childTable, String fkColumn, String idColumn) {
        return "SELECT " + sqlDialect.quoteIdentifier(idColumn) + " FROM " + sqlDialect.quoteIdentifier(childTable)
                + " WHERE " + sqlDialect.quoteIdentifier(fkColumn) + " = :" + fkColumn;
    }

    public String orphanChildDeleteSql(String childTable, String fkColumn, String idColumn) {
        return "DELETE FROM " + sqlDialect.quoteIdentifier(childTable)
                + " WHERE " + sqlDialect.quoteIdentifier(fkColumn) + " = :" + fkColumn
                + " AND " + sqlDialect.quoteIdentifier(idColumn) + " = :" + idColumn;
    }

    public String childDeleteConstant(Map<String, String> constants, Prop.ElementCollection collection) {
        return SqlConstants.register(constants, "SQL_DELETE_" + MemberNames.upperSnake(collection.name()),
                childDeleteSql(collection.childTable(), collection.parentFkColumn()));
    }

    public String childDeleteSql(String childTable, String fkColumn) {
        return "DELETE FROM " + sqlDialect.quoteIdentifier(childTable)
                + " WHERE " + sqlDialect.quoteIdentifier(fkColumn) + " = :" + fkColumn;
    }

    public void appendElementCollectionDelete(MethodSpec.Builder builder, String value, TypeMapping<?> entity,
                                               Prop.ElementCollection collection,
                                               Map<String, String> constants) {
        appendChildDelete(builder, value, entity,
                childDeleteConstant(constants, collection), collection.parentFkColumn());
    }
}
