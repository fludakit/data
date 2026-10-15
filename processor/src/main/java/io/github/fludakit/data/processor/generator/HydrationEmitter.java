package io.github.fludakit.data.processor.generator;

import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import io.github.fludakit.data.Names;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.processor.analysis.AttributeTypes;
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.processor.analysis.MappedTypeBuilder;
import io.github.fludakit.data.processor.analysis.EntityRelationship;
import io.github.fludakit.data.processor.support.MemberNames;
import io.github.fludakit.data.sql.SqlDialect;
import io.github.fludakit.jdbc.JdbcClient;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

/**
 * Emits the depth-1 hydration loaders of a {@code <Repository>Impl}: the {@code hydrateAll}
 * umbrella plus one {@code hydrate<Field>} loader per hydrated element collection and per hydrated
 * one-to-many relationship.
 *
 * <p>Hydration is the READ side of the aggregate: a parent row is fetched first and its children are
 * re-read in one grouped {@code WHERE <fk> IN (...)} query afterwards, so the parent's own SELECT
 * stays a single row per entity and cannot disturb {@code LIMIT} or {@code COUNT}. A composing
 * to-one relation is NOT hydrated here — it is JOIN-projected onto the parent's own row by
 * {@link RepositoryMethodCompiler} and needs no loader.</p>
 *
 * <p>Constructed after {@code init()} has settled the dialect, which is the only state it holds: the
 * loader column lists are quoted through it. It emits no source file — the {@link MethodSpec}s it
 * builds are handed back to the caller, which passes them to {@link RepositoryShellEmitter}.</p>
 */
public final class HydrationEmitter {

    private final SqlDialect sqlDialect;
    private final AttributeTypes attributeTypes;
    private final MappedTypeBuilder typeMappingBuilder;

    /**
     * @param attributeTypes the one lookup this emitter needs: the element type of a collection, which
     *                       is the only fact on a {@code Prop.ElementCollection} that is an FQN and
     *                       therefore not a JavaPoet name
     */
    public HydrationEmitter(SqlDialect sqlDialect, AttributeTypes attributeTypes,
                            MappedTypeBuilder typeMappingBuilder) {
        this.sqlDialect = sqlDialect;
        this.attributeTypes = attributeTypes;
        this.typeMappingBuilder = typeMappingBuilder;
    }

    /**
     * True iff the repository generates hydration: a reflection-free row mapper was emitted for the
     * entity AND at least one hydrator cleared its gate — an element collection past the VO row-mapper
     * gate, or an entity relationship past the managed-target gate. The relationship eligibility
     * (managed target + generated target mapper + resolved inverse) is decided by the caller; this
     * predicate only joins the lists.
     */
    public boolean hasGeneratedHydration(ClassName mapperType,
                                  List<Prop.ElementCollection> hydratedCollections,
                                  List<EntityRelationship> hydratedRelationships) {
        return mapperType != null && (!hydratedCollections.isEmpty() || !hydratedRelationships.isEmpty());
    }

    /**
     * Emits the per-entity umbrella: {@code private void hydrateAll(Collection<Entity> parents)} calling
     * each emitted {@code hydrate<Field>(parents)} loader. One method reached from every read shape keeps
     * the wiring sites free of per-collection / per-relationship detail; the relationship loaders
     * (many-to-one, one-to-many) join the same dispatch.
     */
    public MethodSpec hydrationUmbrella(TypeMapping<?> entity, List<Prop.ElementCollection> hydrated,
                                 List<EntityRelationship> relationships) {
        MethodSpec.Builder umbrella = MethodSpec.methodBuilder("hydrateAll")
                .addJavadoc("Dispatches every generated hydration loader over the given parents. "
                        + "Hydration is depth-1: entities produced by a loader are not themselves hydrated.\n")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Collection.class),
                        typeMappingBuilder.classNameFor(entity.typeName())), "parents");
        for (Prop.ElementCollection c : hydrated) {
            umbrella.addStatement("hydrate$L(parents)", MemberNames.capitalize(c.name()));
        }
        for (EntityRelationship r : relationships) {
            umbrella.addStatement("hydrate$L(parents)", MemberNames.capitalize(r.attribute().name()));
        }
        return umbrella.build();
    }

    /**
     * Emits one element-collection loader: {@code private void hydrate<Field>(Collection<Entity>
     * parents)}, selecting the VO's columns from the child table WHERE the parent fk IN the parent ids.
     * Delegates the whole shape to {@link #appendGroupedKeyHydration}, which the one-to-many loader
     * reuses; the emission is textually the original element-collection body, so a VO corpus's generated impl stays identical.
     */
    public MethodSpec appendCollectionHydration(Map<String, String> constants,
                                         TypeMapping<?> entity, Prop.ElementCollection collection,
                                         ClassName voMapper) {
        String selectConst = SqlConstants.register(constants, "SQL_SELECT_" + MemberNames.upperSnake(collection.name()),
                "SELECT " + quotedColumnList(collection) + " FROM "
                        + sqlDialect.quoteIdentifier(collection.childTable())
                        + " WHERE " + sqlDialect.quoteIdentifier(collection.parentFkColumn()) + " IN (");
        return appendGroupedKeyHydration(constants, entity, collection.name(), selectConst,
                collection.parentFkColumn(), attributeTypes.classArgOf(collection.elementTypeName()),
                voMapper);
    }

    /**
     * Emits one one-to-many loader (the child's OWN table): {@code parents}' ids group the
     * TARGET's own physical columns — INCLUDING the inverse fk, which the anonymous pairing reads by
     * label — WHERE {@code <inverse>_id IN (...)}. The delegate row mapper is the target entity's
     * generated {@code <Target>RowMapper} (no VO involved), and the grouped list is assigned to the
     * parent's collection setter. The target's own relationships are NOT hydrated here (depth-1);
     * non-composing back-references on children materialized by this loader carry an id-only partial
     * when the child's row mapper resolves the FK, and composing references stay loader-filled.
     */
    public MethodSpec appendOneToManyHydration(Map<String, String> constants, TypeMapping<?> entity,
                                        EntityRelationship relationship) {
        Prop.EntityCollection a = (Prop.EntityCollection) relationship.attribute();
        TypeMapping<?> targetModel = relationship.targetModel();
        String inverseFk = Names.fkColumn(a.inverseName());
        String selectConst = SqlConstants.register(constants, "SQL_SELECT_" + MemberNames.upperSnake(a.name()),
                "SELECT " + entityColumnList(targetModel) + " FROM "
                        + sqlDialect.quoteIdentifier(targetModel.tableName())
                        + " WHERE " + sqlDialect.quoteIdentifier(inverseFk) + " IN (");
        return appendGroupedKeyHydration(constants, entity, a.name(), selectConst, inverseFk,
                typeMappingBuilder.classNameFor(targetModel.typeName()), relationship.targetMapper());
    }

    /**
     * The one grouping loader shape shared by element-collection and one-to-many
     * hydration. It gathers the parents' ids, builds a {@code WHERE <fk> IN (:key0, ...)} at runtime
     * (JdbcClient has no collection-parameter expansion), and reads each child row as an
     * {@code Object[]} pair of {@code [<fk>, <Child>]} via an anonymous {@code RowMapper} — the row
     * mapper is row-scoped and has no client access, so the pairing is assembled here — then groups the
     * children by fk and assigns each parent's collection. The pair's second element delegates to
     * {@code childMapper} (a {@code <VO>RowMapper} for element collections, the target entity's
     * {@code <Entity>RowMapper} for one-to-many). FK-only child columns are never selected by the
     * element-collection column list (see {@link #quotedColumnList}); the association targets stay
     * {@code null}.
     */
    private MethodSpec appendGroupedKeyHydration(Map<String, String> constants, TypeMapping<?> entity,
                                                 String field, String selectConst, String fkLabel,
                                                 TypeName childType, ClassName childMapper) {
        ClassName sqlSpec = ClassName.get(JdbcClient.class).nestedClass("SqlSpec");
        TypeName entityType = typeMappingBuilder.classNameFor(entity.typeName());
        // The parent-key / child-FK column shares the entity id's JDBC type, so derive it from
        // the id rather than hardcoding Long — an entity with an Integer (or other JDBC-safe) id would
        // otherwise emit a loader whose $T casts/getObject calls do not compile in the consumer build.
        // box() normalizes a primitive id (long -> java.lang.Long) so a Long-id corpus stays byte-identical.
        TypeName keyType = typeMappingBuilder.memberType(entity.typeName(), entity.id().name()).box();
        TypeName parentKeyList = ParameterizedTypeName.get(ClassName.get(java.util.List.class), keyType);
        TypeName rowArrayType = ArrayTypeName.of(TypeName.OBJECT);
        TypeName rowsType = ParameterizedTypeName.get(ClassName.get(java.util.List.class), rowArrayType);
        TypeName groupedType = ParameterizedTypeName.get(ClassName.get(java.util.Map.class), keyType,
                ParameterizedTypeName.get(ClassName.get(java.util.List.class), childType));

        MethodSpec.Builder loader = MethodSpec.methodBuilder("hydrate" + MemberNames.capitalize(field))
                .addJavadoc("Hydrates the $L collection over the given parents. Depth-1: the loaded "
                        + "children's own relationships are not hydrated.\n", field)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(java.util.Collection.class), entityType), "parents");
        loader.beginControlFlow("if (parents.isEmpty())").addStatement("return").endControlFlow();
        loader.addStatement("$T keys = new $T<>()", parentKeyList, java.util.ArrayList.class);
        loader.beginControlFlow("for ($T parent : parents)", entityType);
        loader.addStatement("keys.add(parent.$L())", MemberNames.getter(entity.id().name()));
        loader.endControlFlow();

        loader.addStatement("$T sql = new $T($L)", StringBuilder.class, StringBuilder.class, selectConst);
        loader.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        loader.beginControlFlow("if (i > 0)");
        loader.addStatement("sql.append($S)", ", ");
        loader.endControlFlow();
        loader.addStatement("sql.append($S).append(i)", ":key");
        loader.endControlFlow();
        loader.addStatement("sql.append($S)", ")");
        loader.addStatement("$T spec = jdbcClient.sql(sql.toString())", sqlSpec);
        loader.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        loader.addStatement("spec = spec.param($S + i, keys.get(i))", "key");
        loader.endControlFlow();
        loader.addStatement("$T rows = spec.query((rs, rowNum) -> new $T[] { rs.getObject($S, $T.class), "
                        + "new $T().mapRow(rs, rowNum) }).list()",
                rowsType, Object.class, fkLabel, keyType, childMapper);

        loader.addStatement("$T grouped = new $T<>()", groupedType, java.util.HashMap.class);
        loader.beginControlFlow("for ($T row : rows)", rowArrayType);
        loader.addStatement("grouped.computeIfAbsent(($T) row[0], k -> new $T<>()).add(($T) row[1])",
                keyType, java.util.ArrayList.class, childType);
        loader.endControlFlow();
        loader.beginControlFlow("for ($T parent : parents)", entityType);
        loader.addStatement("parent.$L(grouped.getOrDefault(parent.$L(), new $T<>()))",
                MemberNames.setter(field), MemberNames.getter(entity.id().name()), java.util.ArrayList.class);
        loader.endControlFlow();

        return loader.build();
    }

    /**
     * The physical column list of an ENTITY (not a VO): every {@code metadata().columns()} entry,
     * quoted and comma-joined — id first, then scalars, many-to-one/association FKs and flattened
     * embedded leaves. Unlike {@link #quotedColumnList} (element-collection children, whose FK-only
     * columns are never selected) a one-to-many/many-to-one SELECT must carry the target's own FK
     * columns so its row mapper and, for one-to-many, the inverse grouping label are readable.
     */
    private String entityColumnList(TypeMapping<?> entity) {
        StringBuilder sb = new StringBuilder();
        for (ColumnRef column : entity.columns()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(sqlDialect.quoteIdentifier(column.column()));
        }
        return sb.toString();
    }

    private String quotedColumnList(Prop.ElementCollection collection) {
        StringBuilder sb = new StringBuilder(sqlDialect.quoteIdentifier(collection.parentFkColumn()));
        for (Prop a : collection.target().props()) {
            if (a instanceof Prop.Id id) {
                sb.append(", ").append(sqlDialect.quoteIdentifier(id.column()));
            } else if (a instanceof Prop.Scalar scalar) {
                sb.append(", ").append(sqlDialect.quoteIdentifier(scalar.column()));
            } else if (a instanceof Prop.Embedded embedded) {
                if (embedded.target() == null) {
                    continue;
                }
                for (ColumnRef leaf : embedded.target().columns()) {
                    sb.append(", ").append(sqlDialect.quoteIdentifier(Names.columnBase(embedded.name()) + "_" + leaf.column()));
                }
            }
            // Reference / EntityCollection / ElementCollection: no read column on this child table.
        }
        return sb.toString();
    }
}
