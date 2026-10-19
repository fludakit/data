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
import io.github.fludakit.data.metadata.UnknownAttributePathException;

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

        // Build columnMappings list
        if (entity.columnMappings().isEmpty()) {
            method.addStatement("$T<$T> columns = $T.of()", LIST, COLUMN_MAPPING, LIST);
        } else {
            CodeBlock.Builder columnsBuilder = CodeBlock.builder();
            columnsBuilder.add("$T<$T> columns = $T.of(\n", LIST, COLUMN_MAPPING, LIST);
            for (ColumnMapping col : entity.columnMappings()) {
                columnsBuilder.add("    new $T($S, $S, $S, $L, $L)",
                        COLUMN_MAPPING, col.propertyName(), col.columnName(),
                        col.typeFqn(), col.isId(), col.isVersion());
                if (entity.columnMappings().indexOf(col) < entity.columnMappings().size() - 1) {
                    columnsBuilder.add(",");
                }
                columnsBuilder.add("\n");
            }
            columnsBuilder.add(")");
            method.addCode(columnsBuilder.build());
            method.addCode(";\n");
        }

        // Build relationMappings list
        if (entity.relationMappings().isEmpty()) {
            method.addStatement("$T<$T> relations = $T.of()", LIST, RELATION_MAPPING, LIST);
        } else {
            CodeBlock.Builder relationsBuilder = CodeBlock.builder();
            relationsBuilder.add("$T<$T> relations = $T.of(\n", LIST, RELATION_MAPPING, LIST);
            List<RelationMapping> rels = entity.relationMappings();
            for (int i = 0; i < rels.size(); i++) {
                RelationMapping rel = rels.get(i);
                String fkCol = rel.fkColumn();
                String childTbl = rel.childTable();
                // Always use null for target in generated code since nested VOs don't have TypeModels
                relationsBuilder.add("    new $T($S, $T.$L, $S, ",
                        RELATION_MAPPING,
                        rel.propertyName(),
                        RELATION_KIND, rel.kind(),
                        rel.targetTypeName());
                if (fkCol != null) {
                    relationsBuilder.add("$S, ", fkCol);
                } else {
                    relationsBuilder.add("null, ");
                }
                if (childTbl != null) {
                    relationsBuilder.add("$S, ", childTbl);
                } else {
                    relationsBuilder.add("null, ");
                }
                relationsBuilder.add("$L, $L, null)",
                        rel.isComposition(),
                        rel.isEmbedded());
                if (i < rels.size() - 1) {
                    relationsBuilder.add(",");
                }
                relationsBuilder.add("\n");
            }
            relationsBuilder.add(")");
            method.addCode(relationsBuilder.build());
            method.addCode(";\n");
        }

        String idCol = entity.idColumn();
        method.addStatement("return new $T($S, $S, $S, $L, columns, relations)",
                TYPE_DEFINITION, entity.typeName(), entity.tableName(),
                idCol, entity.isAggregate());
        return method.build();
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
        for (ColumnMapping col : def.columnMappings()) {
            out.add(new ColumnRef.Simple(col.columnName(), tableName));
        }
        for (RelationMapping rel : def.relationMappings()) {
            if (rel.kind() == RelationKind.TO_ONE && rel.fkColumn() != null) {
                out.add(new ColumnRef.Simple(rel.fkColumn(), tableName));
            } else if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                String prefix = rel.propertyName() + "_";
                flattenEmbeddedColumns(rel.target(), prefix, tableName, out);
            }
        }
        return out;
    }

    private void flattenEmbeddedColumns(TypeDefinition embedded, String columnPrefix,
                                         String tableName, List<ColumnRef> out) {
        for (ColumnMapping col : embedded.columnMappings()) {
            out.add(new ColumnRef.Simple(columnPrefix + col.columnName(), tableName));
        }
        for (RelationMapping rel : embedded.relationMappings()) {
            if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                String nestedPrefix = columnPrefix + rel.propertyName() + "_";
                flattenEmbeddedColumns(rel.target(), nestedPrefix, tableName, out);
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
        for (ColumnMapping col : def.columnMappings()) {
            refs.put(pathPrefix + col.propertyName(),
                    new ColumnRef.Simple(columnPrefix + col.columnName(), tableName));
        }
        for (RelationMapping rel : def.relationMappings()) {
            if (rel.kind() == RelationKind.TO_ONE && rel.fkColumn() != null) {
                refs.put(pathPrefix + rel.propertyName(),
                        new ColumnRef.ForeignKey(columnPrefix + rel.fkColumn(), tableName));
            } else if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                addLocalRefs(refs, tableName, rel.target(),
                        pathPrefix + rel.propertyName() + ".",
                        columnPrefix + rel.propertyName() + "_");
            }
        }
    }

    /**
     * Computes local column refs for a target definition using an explicit table name.
     * Used for element collection targets where the target's own tableName may be null.
     */
    private Map<String, ColumnRef> computeLocalColumnRefsWithTable(TypeDefinition def, String tableName) {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        addLocalRefs(refs, tableName, def, "", "");
        return refs;
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

        // 2-4. Relation traversals (one level into target)
        for (RelationMapping rel : entity.relationMappings()) {
            if (rel.kind() == RelationKind.TO_ONE && rel.target() != null) {
                JoinEdge.ToOne edge = buildToOneEdge(entity, rel);
                if (edge == null) continue;
                TypeDefinition target = rel.target();
                Map<String, ColumnRef> targetRefs = computeLocalColumnRefs(target);
                for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                    String fullPath = rel.propertyName() + "." + targetEntry.getKey();
                    paths.put(fullPath, new ColumnPlacement(
                            targetEntry.getValue(), List.of(edge)));
                }
            } else if (rel.kind() == RelationKind.TO_MANY && rel.target() != null) {
                JoinEdge.ToMany edge = buildToManyEdgeForCollection(entity, rel);
                if (edge == null) continue;
                TypeDefinition target = rel.target();
                Map<String, ColumnRef> targetRefs = computeLocalColumnRefs(target);
                for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                    String fullPath = rel.propertyName() + "." + targetEntry.getKey();
                    paths.put(fullPath, new ColumnPlacement(
                            targetEntry.getValue(), List.of(edge)));
                }
            } else if (rel.kind() == RelationKind.ELEMENT_COLLECTION && rel.target() != null) {
                JoinEdge.ToMany edge = buildToManyEdgeForElementCollection(entity, rel);
                if (edge == null) continue;
                String childTable = rel.childTable() != null ? rel.childTable() : rel.target().tableName();
                Map<String, ColumnRef> targetRefs = computeLocalColumnRefsWithTable(rel.target(), childTable);
                for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                    String fullPath = rel.propertyName() + "." + targetEntry.getKey();
                    paths.put(fullPath, new ColumnPlacement(
                            targetEntry.getValue(), List.of(edge)));
                }
            }
        }

        return paths;
    }

    private JoinEdge.ToOne buildToOneEdge(TypeDefinition owner, RelationMapping ref) {
        if (ref.target() == null || ref.target().tableName() == null
                || ref.target().idColumn() == null || ref.fkColumn() == null) return null;
        return new JoinEdge.ToOne(
                new ColumnRef.ForeignKey(ref.fkColumn(), owner.tableName()),
                new ColumnRef.Simple(ref.target().idColumn(), ref.target().tableName()));
    }

    private JoinEdge.ToMany buildToManyEdgeForCollection(TypeDefinition owner, RelationMapping ec) {
        if (ec.target() == null || ec.target().tableName() == null
                || ec.fkColumn() == null || owner.idColumn() == null) {
            return null;
        }
        return new JoinEdge.ToMany(
                new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                new ColumnRef.ForeignKey(ec.fkColumn(), ec.target().tableName()));
    }

    private JoinEdge.ToMany buildToManyEdgeForElementCollection(TypeDefinition owner, RelationMapping ec) {
        String childTable = ec.childTable() != null ? ec.childTable()
                : (ec.target() != null ? ec.target().tableName() : null);
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
        for (RelationMapping rel : entity.relationMappings()) {
            if (rel.kind() == RelationKind.TO_MANY || rel.kind() == RelationKind.ELEMENT_COLLECTION) {
                toManyNames.add(rel.propertyName());
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
        for (ColumnMapping col : entity.columnMappings()) {
            paths.put(col.propertyName(),
                    List.of(rootVar + "." + getter(col.propertyName()) + "()"));
        }
        for (RelationMapping rel : entity.relationMappings()) {
            if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                collectEmbeddedValuePaths(paths, rel.target(), rel.target().columnMappings(),
                        rel.target().relationMappings(),
                        rootVar + "." + getter(rel.propertyName()) + "()",
                        rel.propertyName() + ".");
            }
        }

        // Reference traversals (one level)
        for (RelationMapping rel : entity.relationMappings()) {
            if (rel.kind() == RelationKind.TO_ONE && rel.target() != null) {
                String refGetter = rootVar + "." + getter(rel.propertyName()) + "()";
                TypeDefinition target = rel.target();
                // Target's own columns
                for (ColumnMapping col : target.columnMappings()) {
                    String fullPath = rel.propertyName() + "." + col.propertyName();
                    paths.put(fullPath,
                            List.of(refGetter + "." + getter(col.propertyName()) + "()"));
                }
                // Target's embedded leaves
                for (RelationMapping targetRel : target.relationMappings()) {
                    if (targetRel.kind() == RelationKind.EMBEDDED && targetRel.target() != null) {
                        collectEmbeddedValuePaths(paths, targetRel.target(),
                                targetRel.target().columnMappings(),
                                targetRel.target().relationMappings(),
                                refGetter + "." + getter(targetRel.propertyName()) + "()",
                                rel.propertyName() + "." + targetRel.propertyName() + ".");
                    }
                }
            }
        }

        return paths;
    }

    private void collectEmbeddedValuePaths(Map<String, List<String>> paths,
                                            TypeDefinition embeddedDef,
                                            List<ColumnMapping> columns,
                                            List<RelationMapping> relations,
                                            String accessor, String pathPrefix) {
        for (ColumnMapping col : columns) {
            paths.put(pathPrefix + col.propertyName(),
                    List.of(accessor + "." + getter(col.propertyName()) + "()"));
        }
        for (RelationMapping rel : relations) {
            if (rel.kind() == RelationKind.EMBEDDED && rel.target() != null) {
                String nestedAccessor = accessor + "." + getter(rel.propertyName()) + "()";
                String nestedPrefix = pathPrefix + rel.propertyName() + ".";
                collectEmbeddedValuePaths(paths, rel.target(),
                        rel.target().columnMappings(),
                        rel.target().relationMappings(),
                        nestedAccessor, nestedPrefix);
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
