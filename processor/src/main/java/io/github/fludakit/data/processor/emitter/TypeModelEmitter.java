package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import io.github.fludakit.data.metadata.ColumnPlacement;
import io.github.fludakit.data.metadata.ColumnRef;
import io.github.fludakit.data.metadata.JoinEdge;
import io.github.fludakit.data.metadata.TypeDefinition;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code XXXTypeModel} classes that implement {@link io.github.fludakit.data.metadata.TableSchema} and
 * {@link io.github.fludakit.data.metadata.ColumnResolver} and
 * {@link io.github.fludakit.data.metadata.ValueAccess} for each entity type.
 *
 * <p>The generated class is the runtime descriptor {@code SqlBuilder} uses for
 * path-to-column resolution. It exposes the entity's table name, column map,
 * and a {@code resolve(path)} switch that returns
 * {@link ColumnPlacement} instances directly — no runtime walk needed.</p>
 *
 * <p>Naming: entity {@code Customer} produces {@code CustomerTypeModel} in the same package.</p>
 */
public final class TypeModelEmitter {

    private static final String METADATA_PACKAGE = "io.github.fludakit.data.metadata";

    private static final ClassName TABLE_SCHEMA =
            ClassName.get(METADATA_PACKAGE, "TableSchema");
    private static final ClassName COLUMN_RESOLVER =
            ClassName.get(METADATA_PACKAGE, "ColumnResolver");
    private static final ClassName TYPE_DEFINITION =
            ClassName.get(METADATA_PACKAGE, "TypeDefinition");
    private static final ClassName VALUE_ACCESS =
            ClassName.get(METADATA_PACKAGE, "ValueAccess");
    private static final ClassName COLUMN_REF =
            ClassName.get(METADATA_PACKAGE, "ColumnRef");
    private static final ClassName COLUMN_REF_SIMPLE = COLUMN_REF.nestedClass("Simple");
    private static final ClassName COLUMN_REF_FK = COLUMN_REF.nestedClass("ForeignKey");
    private static final ClassName COLUMN_PLACEMENT =
            ClassName.get(METADATA_PACKAGE, "ColumnPlacement");
    private static final ClassName JOIN_EDGE =
            ClassName.get(METADATA_PACKAGE, "JoinEdge");
    private static final ClassName JOIN_EDGE_TO_ONE = JOIN_EDGE.nestedClass("ToOne");
    private static final ClassName JOIN_EDGE_TO_MANY = JOIN_EDGE.nestedClass("ToMany");
    private static final ClassName UNKNOWN_PATH =
            ClassName.get(METADATA_PACKAGE, "UnknownAttributePathException");
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName MAP = ClassName.get(Map.class);

    private final Filer filer;

    public TypeModelEmitter(Filer filer) {
        this.filer = filer;
    }

    /**
     * Generates the {@code XXXTypeModel} class for the given entity type mapping.
     *
     * @param entity the entity type mapping
     */
    public void emit(TypeDefinition entity) {
        String typeFqn = entity.typeName();
        String packageName = packageNameOf(typeFqn);
        String simpleName = simpleNameOf(typeFqn);
        String typeModelName = simpleName + "TypeModel";

        ClassName entityType = ClassName.bestGuess(typeFqn);
        ClassName typeModelType = ClassName.get(packageName, typeModelName);

        ParameterizedTypeName valueAccessParam = ParameterizedTypeName.get(VALUE_ACCESS, entityType);

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(typeModelName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(TABLE_SCHEMA)
                .addSuperinterface(COLUMN_RESOLVER)
                .addSuperinterface(valueAccessParam);

        // Singleton INSTANCE
        classBuilder.addField(FieldSpec.builder(typeModelType, "INSTANCE",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", typeModelType)
                .build());

        // typeName()
        classBuilder.addMethod(MethodSpec.methodBuilder("typeName")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", typeFqn)
                .build());

        // tableName()
        classBuilder.addMethod(MethodSpec.methodBuilder("tableName")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", entity.tableName())
                .build());

        // idColumn()
        classBuilder.addMethod(MethodSpec.methodBuilder("idColumn")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S", entity.idColumn())
                .build());

        // definition() — returns a TypeDefinition
        classBuilder.addMethod(buildDefinitionMethod(entity));

        // columns()
        classBuilder.addMethod(buildColumnsMethod(entity));

        // columnRefs()
        classBuilder.addMethod(buildColumnRefsMethod(entity));

        // resolve(path)
        classBuilder.addMethod(buildResolveMethod(entity, typeFqn));

        // isToManyPath(path)
        classBuilder.addMethod(buildIsToManyPathMethod(entity));

        // value(path, root)
        classBuilder.addMethod(buildValueMethod(entity, entityType, typeFqn));

        // Write file
        try {
            JavaFile.builder(packageName, classBuilder.build())
                    .build()
                    .writeTo(filer);
        } catch (IOException e) {
            // Filer will report the error; silently skip
        }
    }

    // ── definition() ──────────────────────────────────────────────────────

    private MethodSpec buildDefinitionMethod(TypeDefinition entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("definition")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TYPE_DEFINITION);

        // Collect Props by type
        List<TypeDefinition.Prop.Scalar> scalars = new ArrayList<>();
        TypeDefinition.Prop.Id idProp = null;
        TypeDefinition.Prop.Version versionProp = null;
        List<TypeDefinition.Prop.Embedded> embedded = new ArrayList<>();
        List<TypeDefinition.Prop.Association> associations = new ArrayList<>();
        List<TypeDefinition.Prop.EntityCollection> entityCollections = new ArrayList<>();
        List<TypeDefinition.Prop.ElementCollection> elementCollections = new ArrayList<>();

        for (TypeDefinition.Prop prop : entity.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Scalar s -> scalars.add(s);
                case TypeDefinition.Prop.Id id -> idProp = id;
                case TypeDefinition.Prop.Version v -> versionProp = v;
                case TypeDefinition.Prop.Embedded e -> embedded.add(e);
                case TypeDefinition.Prop.Association a -> associations.add(a);
                case TypeDefinition.Prop.EntityCollection ec -> entityCollections.add(ec);
                case TypeDefinition.Prop.ElementCollection ec -> elementCollections.add(ec);
                default -> {}
            }
        }

        // Build properties list for generated code
        if (entity.properties().isEmpty()) {
            method.addStatement("$T<$T> props = $T.of()", LIST, ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop"), LIST);
        } else {
            CodeBlock.Builder propsBuilder = CodeBlock.builder();
            propsBuilder.add("$T<$T> props = $T.of(\n", LIST, ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop"), LIST);
            List<TypeDefinition.Prop> allProps = new ArrayList<>();
            if (idProp != null) allProps.add(idProp);
            if (versionProp != null) allProps.add(versionProp);
            allProps.addAll(scalars);
            allProps.addAll(embedded);
            allProps.addAll(associations);
            allProps.addAll(entityCollections);
            allProps.addAll(elementCollections);

            for (int i = 0; i < allProps.size(); i++) {
                TypeDefinition.Prop prop = allProps.get(i);
                propsBuilder.add(propLiteral(prop));
                if (i < allProps.size() - 1) {
                    propsBuilder.add(",");
                }
                propsBuilder.add("\n");
            }
            propsBuilder.add(")");
            method.addCode(propsBuilder.build());
            method.addCode(";\n");
        }

        String tableName = entity.tableName();
        String idCol = idProp != null ? idProp.columnName() : null;
        boolean isAggregate = entity instanceof TypeDefinition.Aggregate;

        if (entity instanceof TypeDefinition.Entity) {
            method.addCode("return new $T.Entity($S, $S, ", TYPE_DEFINITION, entity.typeName(), tableName);
            addIdOrVersion(method, idProp, "Id");
            method.addCode(", ");
            addIdOrVersion(method, versionProp, "Version");
            method.addCode(", ");
            addPropList(method, scalars, "Scalar");
            method.addCode(", ");
            addPropList(method, embedded, "Embedded");
            method.addCode(", ");
            addPropList(method, associations, "Association");
            method.addCode(", ");
            addPropList(method, entityCollections, "EntityCollection");
            method.addCode(", ");
            addPropList(method, elementCollections, "ElementCollection");
            method.addStatement(")");
        } else if (entity instanceof TypeDefinition.Aggregate agg) {
            method.addCode("return new $T.Aggregate($S, $S, ", TYPE_DEFINITION, entity.typeName(), tableName);
            addIdOrVersion(method, idProp, "Id");
            method.addCode(", ");
            addIdOrVersion(method, versionProp, "Version");
            method.addCode(", ");
            addPropList(method, scalars, "Scalar");
            method.addCode(", ");
            addPropList(method, embedded, "Embedded");
            method.addCode(", ");
            addPropList(method, associations, "Association");
            method.addCode(", ");
            addPropList(method, entityCollections, "EntityCollection");
            method.addCode(", ");
            addPropList(method, elementCollections, "ElementCollection");
            method.addStatement(")");
        } else {
            method.addCode("return new $T.ValueObject($S, ", TYPE_DEFINITION, entity.typeName());
            addPropList(method, scalars, "Scalar");
            method.addCode(", ");
            addPropList(method, embedded, "Embedded");
            method.addCode(", ");
            addPropList(method, elementCollections, "ElementCollection");
            method.addStatement(")");
        }
        return method.build();
    }

    private void addIdOrVersion(MethodSpec.Builder method, TypeDefinition.Prop prop, String typeName) {
        if (prop == null) {
            method.addCode("null");
        } else {
            method.addCode("$L", propLiteral(prop));
        }
    }

    private void addPropList(MethodSpec.Builder method, List<? extends TypeDefinition.Prop> props, String typeName) {
        if (props.isEmpty()) {
            method.addCode("$T.of()", LIST);
        } else {
            method.addCode("$T.of(\n", LIST);
            for (int i = 0; i < props.size(); i++) {
                method.addCode("    $L", propLiteral(props.get(i)));
                if (i < props.size() - 1) {
                    method.addCode(",");
                }
                method.addCode("\n");
            }
            method.addCode("  )");
        }
    }

    private CodeBlock entityTargetLiteral(TypeDefinition.Entity entity) {
        // Reference the target entity's TypeModel.definition() method and cast to Entity
        String typeName = entity.typeName();
        String packageName = typeName.substring(0, typeName.lastIndexOf('.'));
        String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
        ClassName typeModelClass = ClassName.get(packageName, simpleName + "TypeModel");
        return CodeBlock.of("($T.Entity)$L.INSTANCE.definition()", TYPE_DEFINITION, typeModelClass);
    }

    private CodeBlock propLiteral(TypeDefinition.Prop prop) {
        return switch (prop) {
            case TypeDefinition.Prop.Scalar s -> CodeBlock.of("new $T($S, $S, $S, $L)",
                    ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Scalar"),
                    s.name(), s.javaType(), s.columnName(), s.nullable());
            case TypeDefinition.Prop.Id id -> CodeBlock.of("new $T($S, $S, $S, $T.IDENTITY)",
                    ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Id"),
                    id.name(), id.javaType(), id.columnName(), ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Id", "GeneratedType"));
            case TypeDefinition.Prop.Version v -> CodeBlock.of("new $T($S, $S, $S)",
                    ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Version"),
                    v.name(), v.javaType(), v.columnName());
            case TypeDefinition.Prop.Embedded e -> CodeBlock.of("new $T($S, $S, null, $S)",
                    ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Embedded"),
                    e.name(), e.javaType(), e.prefix());
            case TypeDefinition.Prop.Association a -> {
                CodeBlock targetLiteral = a.definition() != null 
                    ? entityTargetLiteral(a.definition())
                    : CodeBlock.of("null");
                yield CodeBlock.of("new $T($S, $S, $L, $S, $L, $L, $L, $T.$L)",
                        ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Association"),
                        a.name(), a.javaType(), targetLiteral, a.fkColumn(), a.backReference(), a.composition(),
                        a.cascade() != null ? CodeBlock.of("$T.$L", ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "Association", "Cascade"), a.cascade()) : CodeBlock.of("null"),
                        ClassName.get(METADATA_PACKAGE, "LoadStrategy"), a.loadStrategy());
            }
            case TypeDefinition.Prop.EntityCollection ec -> {
                CodeBlock targetLiteral = ec.definition() != null 
                    ? entityTargetLiteral(ec.definition())
                    : CodeBlock.of("null");
                yield CodeBlock.of("new $T($S, $S, $L, $S, null, $T.$L)",
                        ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "EntityCollection"),
                        ec.name(), ec.javaType(), targetLiteral, ec.fkColumn(),
                        ClassName.get(METADATA_PACKAGE, "LoadStrategy"), ec.loadStrategy());
            }
            case TypeDefinition.Prop.ElementCollection ec -> CodeBlock.of("new $T($S, $S, null, $S, $S, $T.$L)",
                    ClassName.get(METADATA_PACKAGE, "TypeDefinition", "Prop", "ElementCollection"),
                    ec.name(), ec.javaType(), ec.childTable(), ec.fkColumn(),
                    ClassName.get(METADATA_PACKAGE, "LoadStrategy"), ec.loadStrategy());
            default -> throw new IllegalStateException("Unexpected prop: " + prop);
        };
    }

    // ── columns() ─────────────────────────────────────────────────────────

    private MethodSpec buildColumnsMethod(TypeDefinition entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("columns")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(LIST, COLUMN_REF));

        String tableName = entity.tableName();
        List<ColumnRef> cols = flattenColumns(entity, tableName);

        if (cols.isEmpty()) {
            method.addStatement("return $T.of()", LIST);
            return method.build();
        }

        method.addCode("return $T.of(\n", LIST);
        for (int i = 0; i < cols.size(); i++) {
            ColumnRef col = cols.get(i);
            String trailing = (i < cols.size() - 1) ? "," : "";
            method.addCode("    $L$L\n", columnRefLiteral(col, tableName), trailing);
        }
        method.addCode(");\n");
        return method.build();
    }

    /**
     * Flattens physical columns: scalar columns, TO_ONE FK columns, and recursively
     * embedded VO columns with prefix.
     */
    private List<ColumnRef> flattenColumns(TypeDefinition def, String tableName) {
        List<ColumnRef> out = new ArrayList<>();
        for (TypeDefinition.Prop prop : def.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Scalar s -> out.add(new ColumnRef.Simple(s.columnName(), tableName));
                case TypeDefinition.Prop.Id id -> out.add(new ColumnRef.Simple(id.columnName(), tableName));
                case TypeDefinition.Prop.Version v -> out.add(new ColumnRef.Simple(v.columnName(), tableName));
                case TypeDefinition.Prop.Association a -> {
                    if (a.fkColumn() != null) {
                        out.add(new ColumnRef.Simple(a.fkColumn(), tableName));
                    }
                }
                case TypeDefinition.Prop.Embedded e -> {
                    String prefix = e.name() + "_";
                    flattenEmbeddedColumns(e.definition(), prefix, tableName, out);
                }
                default -> {}
            }
        }
        return out;
    }

    private void flattenEmbeddedColumns(TypeDefinition embedded, String columnPrefix,
                                         String tableName, List<ColumnRef> out) {
        for (TypeDefinition.Prop prop : embedded.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Scalar s -> out.add(new ColumnRef.Simple(columnPrefix + s.columnName(), tableName));
                case TypeDefinition.Prop.Id id -> out.add(new ColumnRef.Simple(columnPrefix + id.columnName(), tableName));
                case TypeDefinition.Prop.Version v -> out.add(new ColumnRef.Simple(columnPrefix + v.columnName(), tableName));
                case TypeDefinition.Prop.Embedded e -> {
                    String nestedPrefix = columnPrefix + e.name() + "_";
                    flattenEmbeddedColumns(e.definition(), nestedPrefix, tableName, out);
                }
                default -> {}
            }
        }
    }

    /**
     * Returns the ColumnRef literal for a physical column (always Simple for columns()).
     */
    private CodeBlock columnRefLiteral(ColumnRef ref, String ownerTable) {
        return CodeBlock.of("new $T($S, $S)", COLUMN_REF_SIMPLE, ref.column(), ownerTable);
    }

    // ── columnRefs() ──────────────────────────────────────────────────────

    private MethodSpec buildColumnRefsMethod(TypeDefinition entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("columnRefs")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(MAP,
                        ClassName.get(String.class), COLUMN_REF));

        Map<String, ColumnRef> refs = computeLocalColumnRefs(entity);

        if (refs.isEmpty()) {
            method.addStatement("return $T.of()", MAP);
            return method.build();
        }

        List<Map.Entry<String, ColumnRef>> entries = new ArrayList<>(refs.entrySet());
        if (entries.size() <= 10) {
            // Use Map.of(k1, v1, k2, v2, ...)
            method.addCode("return $T.of(\n", MAP);
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, ColumnRef> entry = entries.get(i);
                String trailing = (i < entries.size() - 1) ? "," : "";
                method.addCode("    $S, $L$L\n", entry.getKey(),
                        columnRefLiteralForRefs(entry.getValue()), trailing);
            }
            method.addCode(");\n");
        } else {
            // Use Map.ofEntries(Map.entry(k1, v1), ...)
            method.addCode("return $T.ofEntries(\n", MAP);
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, ColumnRef> entry = entries.get(i);
                String trailing = (i < entries.size() - 1) ? "," : "";
                method.addCode("    $T.entry($S, $L)$L\n",
                        MAP, entry.getKey(), columnRefLiteralForRefs(entry.getValue()), trailing);
            }
            method.addCode(");\n");
        }
        return method.build();
    }

    /**
     * Computes the local column refs map: scalars, forward FK references, and flattened
     * embedded leaves.
     */
    private Map<String, ColumnRef> computeLocalColumnRefs(TypeDefinition entity) {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        String tableName = entity.tableName();
        addLocalRefs(refs, tableName, entity, "", "");
        return refs;
    }

    private void addLocalRefs(Map<String, ColumnRef> refs, String tableName,
                               TypeDefinition def,
                               String pathPrefix, String columnPrefix) {
        for (TypeDefinition.Prop prop : def.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Scalar s -> refs.put(pathPrefix + s.name(),
                        new ColumnRef.Simple(columnPrefix + s.columnName(), tableName));
                case TypeDefinition.Prop.Id id -> refs.put(pathPrefix + id.name(),
                        new ColumnRef.Simple(columnPrefix + id.columnName(), tableName));
                case TypeDefinition.Prop.Version v -> refs.put(pathPrefix + v.name(),
                        new ColumnRef.Simple(columnPrefix + v.columnName(), tableName));
                case TypeDefinition.Prop.Association a -> {
                    if (a.fkColumn() != null) {
                        refs.put(pathPrefix + a.name(),
                                new ColumnRef.ForeignKey(columnPrefix + a.fkColumn(), tableName));
                    }
                }
                case TypeDefinition.Prop.Embedded e -> addLocalRefs(refs, tableName, e.definition(),
                        pathPrefix + e.name() + ".",
                        columnPrefix + e.name() + "_");
                default -> {}
            }
        }
    }

    /**
     * Returns the ColumnRef literal for columnRefs() — preserves Simple vs ForeignKey distinction.
     */
    private CodeBlock columnRefLiteralForRefs(ColumnRef ref) {
        if (ref instanceof ColumnRef.Simple simple) {
            return CodeBlock.of("new $T($S, $S)", COLUMN_REF_SIMPLE, simple.column(), simple.table());
        } else if (ref instanceof ColumnRef.ForeignKey fk) {
            return CodeBlock.of("new $T($S, $S)", COLUMN_REF_FK, fk.column(), fk.table());
        }
        throw new IllegalStateException("Unknown ColumnRef type: " + ref.getClass());
    }

    // ── resolve(path) ─────────────────────────────────────────────────────

    private MethodSpec buildResolveMethod(TypeDefinition entity, String typeFqn) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("resolve")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(COLUMN_PLACEMENT)
                .addParameter(String.class, "path");

        // Collect all resolvable paths
        Map<String, ColumnPlacement> allPaths = collectResolvablePaths(entity);

        if (allPaths.isEmpty()) {
            method.addStatement("throw new $T(path, $S)", UNKNOWN_PATH, typeFqn);
            return method.build();
        }

        method.addCode("return switch (path) {\n");
        for (Map.Entry<String, ColumnPlacement> entry : allPaths.entrySet()) {
            method.addCode("    case $S -> $L;\n",
                    entry.getKey(), placementCode(entry.getValue()));
        }
        method.addCode("    default -> throw new $T(path, $S);\n", UNKNOWN_PATH, typeFqn);
        method.addCode("};\n");

        return method.build();
    }

    /**
     * Collects all resolvable paths and their placements:
     * 1. Local paths (scalars, FK refs, embedded leaves) — no edges
     * 2. Reference traversals (one level) — JoinEdge.ToOne
     * 3. EntityCollection traversals (one level) — JoinEdge.ToMany
     * 4. ElementCollection traversals (one level) — JoinEdge.ToMany
     */
    private Map<String, ColumnPlacement> collectResolvablePaths(TypeDefinition entity) {
        Map<String, ColumnPlacement> paths = new LinkedHashMap<>();
        String tableName = entity.tableName();

        // 1. Local paths
        Map<String, ColumnRef> localRefs = computeLocalColumnRefs(entity);
        for (Map.Entry<String, ColumnRef> entry : localRefs.entrySet()) {
            paths.put(entry.getKey(), new ColumnPlacement(entry.getValue()));
        }

        // 2-4. Relation traversals (one level into definition)
        for (TypeDefinition.Prop prop : entity.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Association assoc -> {
                    if (assoc.definition() != null) {
                        JoinEdge.ToOne edge = buildAssociationEdge(entity, assoc);
                        if (edge == null) continue;
                        TypeDefinition target = assoc.definition();
                        Map<String, ColumnRef> targetRefs = computeLocalColumnRefs(target);
                        for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                            String fullPath = assoc.name() + "." + targetEntry.getKey();
                            paths.put(fullPath, new ColumnPlacement(
                                    targetEntry.getValue(), List.of(edge)));
                        }
                    }
                }
                case TypeDefinition.Prop.EntityCollection ec -> {
                    if (ec.definition() != null) {
                        JoinEdge.ToMany edge = buildEntityCollectionEdge(entity, ec);
                        if (edge == null) continue;
                        TypeDefinition target = ec.definition();
                        Map<String, ColumnRef> targetRefs = computeLocalColumnRefs(target);
                        for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                            String fullPath = ec.name() + "." + targetEntry.getKey();
                            paths.put(fullPath, new ColumnPlacement(
                                    targetEntry.getValue(), List.of(edge)));
                        }
                    }
                }
                case TypeDefinition.Prop.ElementCollection ec -> {
                    // ElementCollection stores primitives - no nested path resolution
                    // The collection itself is resolvable via the FK column
                }
                default -> {}
            }
        }

        return paths;
    }

    private JoinEdge.ToOne buildAssociationEdge(TypeDefinition owner, TypeDefinition.Prop.Association assoc) {
        if (assoc.definition() == null || assoc.definition().tableName() == null
                || assoc.definition().idColumn() == null || assoc.fkColumn() == null) return null;
        return new JoinEdge.ToOne(
                new ColumnRef.ForeignKey(assoc.fkColumn(), owner.tableName()),
                new ColumnRef.Simple(assoc.definition().idColumn(), assoc.definition().tableName()));
    }

    private JoinEdge.ToMany buildEntityCollectionEdge(TypeDefinition owner, TypeDefinition.Prop.EntityCollection ec) {
        if (ec.definition() == null || ec.definition().tableName() == null
                || ec.fkColumn() == null || owner.idColumn() == null) {
            return null;
        }
        return new JoinEdge.ToMany(
                new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                new ColumnRef.ForeignKey(ec.fkColumn(), ec.definition().tableName()));
    }

    private JoinEdge.ToMany buildToManyEdgeForElementCollection(TypeDefinition owner, TypeDefinition.Prop.ElementCollection ec) {
        String childTable = ec.childTable();
        if (childTable == null || ec.fkColumn() == null || owner.idColumn() == null) {
            return null;
        }
        return new JoinEdge.ToMany(
                new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                new ColumnRef.ForeignKey(ec.fkColumn(), childTable));
    }

    private CodeBlock placementCode(ColumnPlacement placement) {
        CodeBlock leafCode = columnRefLiteralForRefs(placement.leaf());
        if (placement.edges().isEmpty()) {
            return CodeBlock.of("new $T($L)", COLUMN_PLACEMENT, leafCode);
        }
        CodeBlock.Builder edgesBuilder = CodeBlock.builder();
        edgesBuilder.add("$T.of(", LIST);
        for (int i = 0; i < placement.edges().size(); i++) {
            if (i > 0) edgesBuilder.add(", ");
            edgesBuilder.add(edgeCode(placement.edges().get(i)));
        }
        edgesBuilder.add(")");
        return CodeBlock.of("new $T($L, $L)", COLUMN_PLACEMENT, leafCode, edgesBuilder.build());
    }

    private CodeBlock edgeCode(JoinEdge edge) {
        if (edge instanceof JoinEdge.ToOne toOne) {
            CodeBlock owner = CodeBlock.of("new $T($S, $S)",
                    COLUMN_REF_FK, toOne.owner().column(), toOne.owner().table());
            CodeBlock child = CodeBlock.of("new $T($S, $S)",
                    COLUMN_REF_SIMPLE, toOne.child().column(), toOne.child().table());
            return CodeBlock.of("new $T($L, $L)", JOIN_EDGE_TO_ONE, owner, child);
        } else if (edge instanceof JoinEdge.ToMany toMany) {
            CodeBlock owner = CodeBlock.of("new $T($S, $S)",
                    COLUMN_REF_SIMPLE, toMany.owner().column(), toMany.owner().table());
            CodeBlock child = CodeBlock.of("new $T($S, $S)",
                    COLUMN_REF_FK, toMany.child().column(), toMany.child().table());
            return CodeBlock.of("new $T($L, $L)", JOIN_EDGE_TO_MANY, owner, child);
        }
        throw new IllegalStateException("Unknown JoinEdge type: " + edge.getClass());
    }

    // ── isToManyPath(path) ────────────────────────────────────────────────

    private MethodSpec buildIsToManyPathMethod(TypeDefinition entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("isToManyPath")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(String.class, "path");

        // Collect to-many relation names
        List<String> toManyNames = new ArrayList<>();
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection || prop instanceof TypeDefinition.Prop.ElementCollection) {
                toManyNames.add(prop.name());
            }
        }

        if (toManyNames.isEmpty()) {
            method.addStatement("return false");
            return method.build();
        }

        // Extract first segment and switch
        method.addStatement("int dot = path.indexOf('.')");
        method.addStatement("String head = dot < 0 ? path : path.substring(0, dot)");
        method.addCode("return switch (head) {\n");
        method.addCode("    case ");
        for (int i = 0; i < toManyNames.size(); i++) {
            if (i > 0) method.addCode(", ");
            method.addCode("$S", toManyNames.get(i));
        }
        method.addCode(" -> true;\n");
        method.addCode("    default -> false;\n");
        method.addCode("};\n");

        return method.build();
    }

    // ── value(path, root) ─────────────────────────────────────────────────

    private MethodSpec buildValueMethod(TypeDefinition entity, ClassName entityType, String typeFqn) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("value")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Object.class)
                .addParameter(String.class, "path")
                .addParameter(entityType, "root");

        // Collect value paths: local paths and reference traversals
        Map<String, List<String>> valuePaths = collectValuePaths(entity, "root");

        if (valuePaths.isEmpty()) {
            method.addStatement("throw new $T(path, $S)", UNKNOWN_PATH, typeFqn);
            return method.build();
        }

        method.addCode("return switch (path) {\n");
        for (Map.Entry<String, List<String>> entry : valuePaths.entrySet()) {
            String getterChain = String.join(".", entry.getValue());
            method.addCode("    case $S -> $L;\n", entry.getKey(), getterChain);
        }
        method.addCode("    default -> throw new $T(path, $S);\n", UNKNOWN_PATH, typeFqn);
        method.addCode("};\n");

        return method.build();
    }

    /**
     * Collects paths and their getter chains for the value() method.
     * Covers local paths (scalars, embedded leaves) and one-level reference traversals.
     */
    private Map<String, List<String>> collectValuePaths(TypeDefinition entity, String rootVar) {
        Map<String, List<String>> paths = new LinkedHashMap<>();

        // Local paths (scalars and embedded leaves)
        for (TypeDefinition.Prop prop : entity.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Scalar s -> paths.put(s.name(),
                        List.of(rootVar + "." + getter(s.name()) + "()"));
                case TypeDefinition.Prop.Id id -> paths.put(id.name(),
                        List.of(rootVar + "." + getter(id.name()) + "()"));
                case TypeDefinition.Prop.Version v -> paths.put(v.name(),
                        List.of(rootVar + "." + getter(v.name()) + "()"));
                case TypeDefinition.Prop.Embedded e -> {
                    if (e.definition() != null) {
                        collectEmbeddedValuePaths(paths, e.definition(),
                                rootVar + "." + getter(e.name()) + "()",
                                e.name() + ".");
                    }
                }
                default -> {}
            }
        }

        // Reference traversals (one level)
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.definition() != null) {
                String refGetter = rootVar + "." + getter(assoc.name()) + "()";
                TypeDefinition target = assoc.definition();
                // Target's own columns
                for (TypeDefinition.Prop targetProp : target.properties()) {
                    switch (targetProp) {
                        case TypeDefinition.Prop.Scalar s -> {
                            String fullPath = assoc.name() + "." + s.name();
                            paths.put(fullPath,
                                    List.of(refGetter + "." + getter(s.name()) + "()"));
                        }
                        case TypeDefinition.Prop.Id id -> {
                            String fullPath = assoc.name() + "." + id.name();
                            paths.put(fullPath,
                                    List.of(refGetter + "." + getter(id.name()) + "()"));
                        }
                        case TypeDefinition.Prop.Version v -> {
                            String fullPath = assoc.name() + "." + v.name();
                            paths.put(fullPath,
                                    List.of(refGetter + "." + getter(v.name()) + "()"));
                        }
                        case TypeDefinition.Prop.Embedded e -> {
                            if (e.definition() != null) {
                                collectEmbeddedValuePaths(paths, e.definition(),
                                        refGetter + "." + getter(e.name()) + "()",
                                        assoc.name() + "." + e.name() + ".");
                            }
                        }
                        default -> {}
                    }
                }
            }
        }

        return paths;
    }

    private void collectEmbeddedValuePaths(Map<String, List<String>> paths,
                                            TypeDefinition embeddedDef,
                                            String accessor, String pathPrefix) {
        for (TypeDefinition.Prop prop : embeddedDef.properties()) {
            switch (prop) {
                case TypeDefinition.Prop.Scalar s -> paths.put(pathPrefix + s.name(),
                        List.of(accessor + "." + getter(s.name()) + "()"));
                case TypeDefinition.Prop.Id id -> paths.put(pathPrefix + id.name(),
                        List.of(accessor + "." + getter(id.name()) + "()"));
                case TypeDefinition.Prop.Version v -> paths.put(pathPrefix + v.name(),
                        List.of(accessor + "." + getter(v.name()) + "()"));
                case TypeDefinition.Prop.Embedded e -> {
                    if (e.definition() != null) {
                        String nestedAccessor = accessor + "." + getter(e.name()) + "()";
                        String nestedPrefix = pathPrefix + e.name() + ".";
                        collectEmbeddedValuePaths(paths, e.definition(),
                                nestedAccessor, nestedPrefix);
                    }
                }
                default -> {}
            }
        }
    }

    private static String getter(String propertyName) {
        return "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    }

    // ── Naming utilities ─────────────────────────────────────────────────

    private static String packageNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(0, lastDot) : "";
    }

    private static String simpleNameOf(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot > 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
