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
import io.github.fludakit.data.metadata.TypeMapping;
import io.github.fludakit.data.metadata.TypeMapping.Prop;
import io.github.fludakit.data.metadata.UnknownAttributePathException;
import io.github.fludakit.data.metadata.ValueObjectMapping;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code XXXTypeModel} classes that implement {@link TypeMapping} and
 * {@link io.github.fludakit.data.metadata.ValueAccess} for each entity type.
 *
 * <p>The generated class is the runtime descriptor {@code SqlBuilder} uses for
 * path-to-column resolution. It exposes the entity's table name, column map,
 * {@link Prop} descriptors, and a {@code resolve(path)} switch that returns
 * {@link ColumnPlacement} instances directly — no runtime walk needed.</p>
 *
 * <p>Naming: entity {@code Customer} produces {@code CustomerTypeModel} in the same package.</p>
 */
public final class TypeModelEmitter {

    private static final String METADATA_PACKAGE = "io.github.fludakit.data.metadata";

    private static final ClassName TYPE_MAPPING =
            ClassName.get(METADATA_PACKAGE, "TypeMapping");
    private static final ClassName VALUE_ACCESS =
            ClassName.get(METADATA_PACKAGE, "ValueAccess");
    private static final ClassName PROP =
            ClassName.get(METADATA_PACKAGE, "TypeMapping", "Prop");
    private static final ClassName PROP_ID = PROP.nestedClass("Id");
    private static final ClassName PROP_SCALAR = PROP.nestedClass("Scalar");
    private static final ClassName PROP_EMBEDDED = PROP.nestedClass("Embedded");
    private static final ClassName PROP_REFERENCE = PROP.nestedClass("Reference");
    private static final ClassName PROP_ENTITY_COLLECTION = PROP.nestedClass("EntityCollection");
    private static final ClassName PROP_ELEMENT_COLLECTION = PROP.nestedClass("ElementCollection");
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
    public void emit(TypeMapping<?> entity) {
        String typeFqn = entity.typeName();
        String packageName = packageNameOf(typeFqn);
        String simpleName = simpleNameOf(typeFqn);
        String typeModelName = simpleName + "TypeModel";

        ClassName entityType = ClassName.bestGuess(typeFqn);
        ClassName typeModelType = ClassName.get(packageName, typeModelName);

        ParameterizedTypeName typeMappingParam = ParameterizedTypeName.get(TYPE_MAPPING, entityType);
        ParameterizedTypeName valueAccessParam = ParameterizedTypeName.get(VALUE_ACCESS, entityType);

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(typeModelName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(typeMappingParam)
                .addSuperinterface(valueAccessParam);

        // Singleton INSTANCE
        classBuilder.addField(FieldSpec.builder(typeModelType, "INSTANCE",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $T()", typeModelType)
                .build());

        // typeName()
        classBuilder.addMethod(MethodSpec.methodBuilder("typeName")
                .addAnnotation(Override.class)
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

        // id()
        classBuilder.addMethod(buildIdMethod(entity));

        // props()
        classBuilder.addMethod(buildPropsMethod(entity));

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

    // ── id() ──────────────────────────────────────────────────────────────

    private MethodSpec buildIdMethod(TypeMapping<?> entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("id")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(PROP_ID);

        if (entity.id() != null) {
            Prop.Id id = entity.id();
            method.addStatement("return new $T($S, $S, $S)",
                    PROP_ID, id.name(), id.column(), id.typeFqn());
        } else {
            method.addStatement("return null");
        }
        return method.build();
    }

    // ── props() ───────────────────────────────────────────────────────────

    private MethodSpec buildPropsMethod(TypeMapping<?> entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("props")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(LIST, PROP));

        List<Prop> props = entity.props();
        if (props.isEmpty()) {
            method.addStatement("return $T.of()", LIST);
            return method.build();
        }

        method.addCode("return $T.of(\n", LIST);
        for (int i = 0; i < props.size(); i++) {
            Prop prop = props.get(i);
            String trailing = (i < props.size() - 1) ? "," : "";
            method.addCode("    $L$L\n", propConstructorCode(prop), trailing);
        }
        method.addCode(");\n");
        return method.build();
    }

    private CodeBlock propConstructorCode(Prop prop) {
        if (prop instanceof Prop.Id id) {
            return CodeBlock.of("new $T($S, $S, $S)",
                    PROP_ID, id.name(), id.column(), id.typeFqn());
        } else if (prop instanceof Prop.Scalar scalar) {
            return CodeBlock.of("new $T($S, $S, $S)",
                    PROP_SCALAR, scalar.name(), scalar.column(), scalar.typeFqn());
        } else if (prop instanceof Prop.Embedded embedded) {
            // Target is null in generated descriptors
            return CodeBlock.of("new $T($S, null)",
                    PROP_EMBEDDED, embedded.name());
        } else if (prop instanceof Prop.Reference ref) {
            // Target is null in generated descriptors
            return CodeBlock.of("new $T($S, $S, $S, null, $L, $L)",
                    PROP_REFERENCE, ref.name(), ref.fkColumn(),
                    ref.targetTypeName(), ref.composition(), ref.backReference());
        } else if (prop instanceof Prop.EntityCollection ec) {
            // Target is null in generated descriptors, but targetTypeName is preserved
            return CodeBlock.of("new $T($S, $S, null, $S, $S)",
                    PROP_ENTITY_COLLECTION, ec.name(),
                    ec.targetTypeName() == null ? "" : ec.targetTypeName(),
                    ec.inverseName() == null ? "" : ec.inverseName(),
                    ec.inverseFkColumn() == null ? "" : ec.inverseFkColumn());
        } else if (prop instanceof Prop.ElementCollection ec) {
            // Target is null in generated descriptors
            return CodeBlock.of("new $T($S, null)",
                    PROP_ELEMENT_COLLECTION, ec.name());
        }
        throw new IllegalStateException("Unknown Prop type: " + prop.getClass());
    }

    // ── columns() ─────────────────────────────────────────────────────────

    private MethodSpec buildColumnsMethod(TypeMapping<?> entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("columns")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(LIST, COLUMN_REF));

        String tableName = entity.tableName();
        List<ColumnRef> cols = flattenColumns(entity.id(), entity.props(), tableName);

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
     * Flattens physical columns in declaration order, matching {@link TypeMapping#flattenColumns}.
     */
    private List<ColumnRef> flattenColumns(Prop.Id id, List<Prop> props, String tableName) {
        List<ColumnRef> out = new ArrayList<>();
        if (id != null) {
            out.add(new ColumnRef.Simple(id.column(), tableName));
        }
        for (Prop p : props) {
            if (p instanceof Prop.Scalar scalar) {
                out.add(new ColumnRef.Simple(scalar.column(), tableName));
            } else if (p instanceof Prop.Reference reference) {
                out.add(new ColumnRef.Simple(reference.fkColumn(), tableName));
            } else if (p instanceof Prop.Embedded embedded && embedded.target() != null) {
                String prefix = io.github.fludakit.data.Names.columnBase(embedded.name()) + "_";
                flattenEmbeddedColumns(embedded.target(), prefix, tableName, out);
            }
            // EntityCollection / ElementCollection carry no column on this table
        }
        return out;
    }

    private void flattenEmbeddedColumns(ValueObjectMapping<?> vo, String columnPrefix,
                                         String tableName, List<ColumnRef> out) {
        for (Prop p : vo.props()) {
            if (p instanceof Prop.Scalar scalar) {
                out.add(new ColumnRef.Simple(columnPrefix + scalar.column(), tableName));
            } else if (p instanceof Prop.Embedded embedded && embedded.target() != null) {
                String nestedPrefix = columnPrefix + io.github.fludakit.data.Names.columnBase(embedded.name()) + "_";
                flattenEmbeddedColumns(embedded.target(), nestedPrefix, tableName, out);
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

    private MethodSpec buildColumnRefsMethod(TypeMapping<?> entity) {
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
     * Computes the local column refs map: id, scalars, forward FK references, and flattened
     * embedded leaves. Back-references are excluded (they are write-only).
     */
    private Map<String, ColumnRef> computeLocalColumnRefs(TypeMapping<?> entity) {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        String tableName = entity.tableName();
        addLocalRefs(refs, tableName, entity.id(), entity.props(), "", "");
        return refs;
    }

    private void addLocalRefs(Map<String, ColumnRef> refs, String tableName,
                               Prop.Id id, List<Prop> props,
                               String pathPrefix, String columnPrefix) {
        if (id != null) {
            refs.put(pathPrefix + id.name(),
                    new ColumnRef.Simple(columnPrefix + id.column(), tableName));
        }
        for (Prop prop : props) {
            if (prop instanceof Prop.Scalar scalar) {
                refs.put(pathPrefix + scalar.name(),
                        new ColumnRef.Simple(columnPrefix + scalar.column(), tableName));
            } else if (prop instanceof Prop.Reference reference) {
                if (!reference.backReference()) {
                    refs.put(pathPrefix + reference.name(),
                            new ColumnRef.ForeignKey(columnPrefix + reference.fkColumn(), tableName));
                }
            } else if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
                addLocalRefs(refs, tableName, null, embedded.target().props(),
                        pathPrefix + embedded.name() + ".",
                        columnPrefix + io.github.fludakit.data.Names.columnBase(embedded.name()) + "_");
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

    private MethodSpec buildResolveMethod(TypeMapping<?> entity, String typeFqn) {
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
     * 1. Local paths (id, scalars, FK refs, embedded leaves) — no edges
     * 2. Reference traversals (one level) — JoinEdge.ToOne
     * 3. EntityCollection traversals (one level) — JoinEdge.ToMany
     * 4. ElementCollection traversals (one level) — JoinEdge.ToMany
     */
    private Map<String, ColumnPlacement> collectResolvablePaths(TypeMapping<?> entity) {
        Map<String, ColumnPlacement> paths = new LinkedHashMap<>();
        String tableName = entity.tableName();

        // 1. Local paths
        Map<String, ColumnRef> localRefs = computeLocalColumnRefs(entity);
        for (Map.Entry<String, ColumnRef> entry : localRefs.entrySet()) {
            paths.put(entry.getKey(), new ColumnPlacement(entry.getValue()));
        }

        // 2. Reference traversals (one level into target)
        for (Prop prop : entity.props()) {
            if (prop instanceof Prop.Reference ref && !ref.backReference() && ref.target() != null) {
                JoinEdge.ToOne edge = buildToOneEdge(entity, ref);
                if (edge == null) continue;
                TypeMapping<?> target = ref.target();
                Map<String, ColumnRef> targetRefs = computeLocalColumnRefs(target);
                for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                    String fullPath = ref.name() + "." + targetEntry.getKey();
                    paths.put(fullPath, new ColumnPlacement(
                            targetEntry.getValue(), List.of(edge)));
                }
            }
        }

        // 3. EntityCollection traversals (one level into target)
        for (Prop prop : entity.props()) {
            if (prop instanceof Prop.EntityCollection ec && ec.target() != null) {
                JoinEdge.ToMany edge = buildToManyEdgeForCollection(entity, ec);
                if (edge == null) continue;
                TypeMapping<?> target = ec.target();
                Map<String, ColumnRef> targetRefs = computeLocalColumnRefs(target);
                for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                    String fullPath = ec.name() + "." + targetEntry.getKey();
                    paths.put(fullPath, new ColumnPlacement(
                            targetEntry.getValue(), List.of(edge)));
                }
            }
        }

        // 4. ElementCollection traversals (one level into target VO)
        for (Prop prop : entity.props()) {
            if (prop instanceof Prop.ElementCollection ec && ec.target() != null) {
                JoinEdge.ToMany edge = buildToManyEdgeForElementCollection(entity, ec);
                if (edge == null) continue;
                Map<String, ColumnRef> targetRefs = computeVoLocalRefs(
                        ec.target(), ec.target().tableName());
                for (Map.Entry<String, ColumnRef> targetEntry : targetRefs.entrySet()) {
                    String fullPath = ec.name() + "." + targetEntry.getKey();
                    paths.put(fullPath, new ColumnPlacement(
                            targetEntry.getValue(), List.of(edge)));
                }
            }
        }

        return paths;
    }

    private JoinEdge.ToOne buildToOneEdge(TypeMapping<?> owner, Prop.Reference ref) {
        if (ref.targetTable() == null || ref.targetIdColumn() == null) return null;
        return new JoinEdge.ToOne(
                new ColumnRef.ForeignKey(ref.fkColumn(), owner.tableName()),
                new ColumnRef.Simple(ref.targetIdColumn(), ref.targetTable()));
    }

    private JoinEdge.ToMany buildToManyEdgeForCollection(TypeMapping<?> owner, Prop.EntityCollection ec) {
        if (ec.targetTable() == null || ec.inverseFkColumn() == null || owner.idColumn() == null) {
            return null;
        }
        return new JoinEdge.ToMany(
                new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                new ColumnRef.ForeignKey(ec.inverseFkColumn(), ec.targetTable()));
    }

    private JoinEdge.ToMany buildToManyEdgeForElementCollection(TypeMapping<?> owner, Prop.ElementCollection ec) {
        if (ec.childTable() == null || ec.parentFkColumn() == null || owner.idColumn() == null) {
            return null;
        }
        return new JoinEdge.ToMany(
                new ColumnRef.Simple(owner.idColumn(), owner.tableName()),
                new ColumnRef.ForeignKey(ec.parentFkColumn(), ec.childTable()));
    }

    /**
     * Computes local column refs for a value object mapping (no id).
     */
    private Map<String, ColumnRef> computeVoLocalRefs(ValueObjectMapping<?> vo, String tableName) {
        Map<String, ColumnRef> refs = new LinkedHashMap<>();
        if (tableName != null) {
            addLocalRefs(refs, tableName, null, vo.props(), "", "");
        }
        return refs;
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

    private MethodSpec buildIsToManyPathMethod(TypeMapping<?> entity) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("isToManyPath")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(String.class, "path");

        // Collect to-many prop names
        List<String> toManyNames = new ArrayList<>();
        for (Prop prop : entity.props()) {
            if (prop instanceof Prop.EntityCollection || prop instanceof Prop.ElementCollection) {
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

    private MethodSpec buildValueMethod(TypeMapping<?> entity, ClassName entityType, String typeFqn) {
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
     * Covers local paths (id, scalar, embedded leaves) and one-level reference traversals.
     */
    private Map<String, List<String>> collectValuePaths(TypeMapping<?> entity, String rootVar) {
        Map<String, List<String>> paths = new LinkedHashMap<>();

        // Local paths
        if (entity.id() != null) {
            paths.put(entity.id().name(), List.of(rootVar + "." + getter(entity.id().name()) + "()"));
        }
        for (Prop prop : entity.props()) {
            collectLocalValuePaths(paths, prop, rootVar, "");
        }

        // Reference traversals (one level)
        for (Prop prop : entity.props()) {
            if (prop instanceof Prop.Reference ref && !ref.backReference() && ref.target() != null) {
                String refGetter = rootVar + "." + getter(ref.name()) + "()";
                if (ref.target().id() != null) {
                    String fullPath = ref.name() + "." + ref.target().id().name();
                    paths.put(fullPath, List.of(refGetter + "." + getter(ref.target().id().name()) + "()"));
                }
                for (Prop targetProp : ref.target().props()) {
                    collectLocalValuePaths(paths, targetProp, refGetter, ref.name() + ".");
                }
            }
        }

        return paths;
    }

    private void collectLocalValuePaths(Map<String, List<String>> paths, Prop prop,
                                         String accessor, String pathPrefix) {
        if (prop instanceof Prop.Scalar scalar) {
            paths.put(pathPrefix + scalar.name(),
                    List.of(accessor + "." + getter(scalar.name()) + "()"));
        } else if (prop instanceof Prop.Embedded embedded && embedded.target() != null) {
            String nestedAccessor = accessor + "." + getter(embedded.name()) + "()";
            String nestedPrefix = pathPrefix + embedded.name() + ".";
            for (Prop voProp : embedded.target().props()) {
                collectLocalValuePaths(paths, voProp, nestedAccessor, nestedPrefix);
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
