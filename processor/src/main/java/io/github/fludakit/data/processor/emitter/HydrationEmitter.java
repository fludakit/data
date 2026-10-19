package io.github.fludakit.data.processor.emitter;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import io.github.fludakit.data.metadata.TypeDefinition;
import io.github.fludakit.data.processor.context.RepositoryContext;
import io.github.fludakit.data.sql.SqlDialect;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates hydration methods for loading to-many collections on aggregate root entities.
 *
 * <p>After the main query returns parent entities, hydration methods execute separate queries
 * for each {@link RelationKind#TO_MANY} and {@link RelationKind#ELEMENT_COLLECTION},
 * group results by parent FK, and set them on the parent entity.</p>
 *
 * <p>Generated methods:</p>
 * <ul>
 *   <li>{@code hydrate(Entity entity)} — umbrella method that calls per-collection loaders</li>
 *   <li>{@code hydrateList(List<Entity> entities)} — batch hydration for list results</li>
 *   <li>{@code loadCollectionName(Entity entity)} — per-collection loader (private)</li>
 * </ul>
 */
public final class HydrationEmitter {

    private final SqlDialect dialect;

    public HydrationEmitter(SqlDialect dialect) {
        this.dialect = dialect;
    }

    public HydrationEmitter() {
        this(io.github.fludakit.data.sql.H2Dialect.INSTANCE);
    }

    /**
     * Returns whether the entity has any to-many collections or composing associations that need hydration.
     * Note: ElementCollections are NOT hydrated separately - they're loaded inline with the parent.
     */
    public boolean hasCollectionsToHydrate(TypeDefinition entity) {
        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection) {
                return true;
            }
            // ElementCollections don't need separate hydration - they use inline loading
            if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.composition()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Emits the {@code hydrate(Entity entity)} umbrella method that calls all per-collection loaders.
     * Returns null if there are no to-many collections.
     */
    public MethodSpec emitHydrate(RepositoryContext ctx) {
        TypeDefinition entity = ctx.entity();
        if (!hasCollectionsToHydrate(entity)) {
            return null;
        }

        ClassName entityClass = ClassName.bestGuess(entity.typeName());
        MethodSpec.Builder builder = MethodSpec.methodBuilder("hydrate")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("if (entity == null) return;\n");

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection ec) {
                body.add("load$L(entity);\n", capitalize(ec.name()));
            } else if (prop instanceof TypeDefinition.Prop.ElementCollection ec) {
                body.add("load$L(entity);\n", capitalize(ec.name()));
            } else if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.composition()) {
                body.add("load$L(entity);\n", capitalize(assoc.name()));
            }
        }

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits the {@code hydrateList(List<Entity> entities)} method for batch hydration.
     * Returns null if there are no to-many collections.
     */
    public MethodSpec emitHydrateList(RepositoryContext ctx) {
        TypeDefinition entity = ctx.entity();
        if (!hasCollectionsToHydrate(entity)) {
            return null;
        }

        ClassName entityClass = ClassName.bestGuess(entity.typeName());
        MethodSpec.Builder builder = MethodSpec.methodBuilder("hydrateList")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), entityClass), "entities")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("if (entities == null || entities.isEmpty()) return;\n");
        body.add("for ($T entity : entities) {\n", entityClass);
        body.add("    hydrate(entity);\n");
        body.add("}");

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits per-collection loader methods for each to-many collection.
     * Returns a list of method specs (may be empty).
     * Note: ElementCollections are NOT loaded separately - they use inline queries.
     */
    public List<MethodSpec> emitCollectionLoaders(RepositoryContext ctx) {
        List<MethodSpec> methods = new ArrayList<>();
        TypeDefinition entity = ctx.entity();

        for (TypeDefinition.Prop prop : entity.properties()) {
            if (prop instanceof TypeDefinition.Prop.EntityCollection ec) {
                MethodSpec loader = emitEntityCollectionLoader(ctx, ec);
                if (loader != null) {
                    methods.add(loader);
                }
            }
            // Skip ElementCollection - these don't get separate hydration loaders
            else if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.composition()) {
                MethodSpec loader = emitCompositionAssociationLoader(ctx, assoc);
                if (loader != null) {
                    methods.add(loader);
                }
            }
        }

        return methods;
    }

    /**
     * Emits a loader method for an EntityCollection: queries the child table by parent FK,
     * maps results, and sets them on the parent entity.
     */
    private MethodSpec emitEntityCollectionLoader(RepositoryContext ctx,
                                                   TypeDefinition.Prop.EntityCollection ec) {
        if (!(ec.target() instanceof TypeDefinition.Entity childEntity)) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        ClassName childClass = ClassName.bestGuess(childEntity.typeName());
        String methodName = "load" + capitalize(ec.name());

        // Build SELECT SQL for children by parent FK
        String childTable = dialect.quoteIdentifier(childEntity.tableName());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());

        // Build column list from child mapping - include scalar columns AND ASSOCIATION FK columns
        // ASSOCIATION relations on entity collection elements are materialized as id-only partials
        List<String> columns = new ArrayList<>();
        TypeDefinition.Prop.Id childIdProp = idProp(childEntity);
        if (childIdProp != null) {
            columns.add(dialect.quoteIdentifier(childIdProp.columnName()) + " AS "
                    + dialect.quoteIdentifier(childIdProp.name()));
        }
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.columnName()) + " AS "
                        + dialect.quoteIdentifier(s.name()));
            }
        }
        // Include ASSOCIATION FK columns so row mapper can create id-only partials for back-references
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association assoc && assoc.fkColumn() != null) {
                // Use column name as alias (not property name) so RowMapper can find it
                columns.add(dialect.quoteIdentifier(assoc.fkColumn()) + " AS "
                        + dialect.quoteIdentifier(assoc.fkColumn()));
            }
        }

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + fkColumn + " = :parentId";
        String constName = ctx.registerConstant("HYDRATE_" + ec.name().toUpperCase(), selectSql);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        // Determine the collection type (List<X>)
        String setterName = "set" + Character.toUpperCase(ec.name().charAt(0)) + ec.name().substring(1);

        // Derive child row mapper class name
        ClassName childRowMapper = deriveRowMapperType(childEntity.typeName());

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("$T<$T> items = jdbcClient\n", List.class, childClass);
        body.add("    .sql($L)\n", constName);
        body.add("    .param($S, entity.getId())\n", "parentId");
        body.add("    .query(new $T())\n", childRowMapper);
        body.add("    .list();\n");
        body.add("entity.$L(items);", setterName);

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits a loader method for an ElementCollection: queries the child table by parent FK,
     * maps results to value objects, and sets them on the parent entity.
     */
    private MethodSpec emitElementCollectionLoader(RepositoryContext ctx,
                                                    TypeDefinition.Prop.ElementCollection ec) {
        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        String methodName = "load" + capitalize(ec.name());

        // Build SELECT SQL for elements by parent FK
        String childTable = dialect.quoteIdentifier(ec.childTable());
        String fkColumn = dialect.quoteIdentifier(ec.fkColumn());

        // Select all columns from the element collection table
        String selectSql = "SELECT * FROM " + childTable
                + " WHERE " + fkColumn + " = :parentId";
        String constName = ctx.registerConstant("HYDRATE_" + ec.name().toUpperCase(), selectSql);

        // Determine element type
        String elementFqn = ec.elementType();
        ClassName elementClass = ClassName.bestGuess(elementFqn);

        String setterName = "set" + Character.toUpperCase(ec.name().charAt(0)) + ec.name().substring(1);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        
        // For primitive/wrapper types, map directly from the first column
        boolean isPrimitive = isPrimitiveOrWrapper(elementFqn);
        if (isPrimitive) {
            body.add("$T<$T> items = jdbcClient\n", List.class, elementClass);
            body.add("    .sql($L)\n", constName);
            body.add("    .param($S, entity.getId())\n", "parentId");
            body.add("    .map(row -> row.get(0, $T.class))\n", elementClass);
            body.add("    .list();\n");
        } else {
            // For value objects, we cannot use a RowMapper (VOs don't get them generated).
            // Instead, map each column by name matching the VO's property names.
            // This assumes the SELECT * returns columns matching VO property names.
            body.add("$T<$T> items = jdbcClient\n", List.class, elementClass);
            body.add("    .sql($L)\n", constName);
            body.add("    .param($S, entity.getId())\n", "parentId");
            body.add("    .map(row -> {\n");
            body.add("        $T item = new $T();\n", elementClass, elementClass);
            // TODO: Map individual columns based on VO metadata
            // For now, this is a placeholder - real implementation would iterate VO properties
            body.add("        return item;\n");
            body.add("    })\n");
            body.add("    .list();\n");
        }
        body.add("entity.$L(items);", setterName);

        builder.addCode(body.build());
        return builder.build();
    }

    /**
     * Emits a loader method for a composing Association: loads the full child entity by ID
     * from the child table, replacing the id-only partial set by the row mapper.
     */
    private MethodSpec emitCompositionAssociationLoader(RepositoryContext ctx,
                                                       TypeDefinition.Prop.Association assoc) {
        if (!(assoc.target() instanceof TypeDefinition.Entity childEntity)) return null;
        // Only proceed if it's an aggregate root (has tableName and idColumn)
        if (childEntity.tableName() == null || childEntity.idColumn() == null) return null;

        ClassName entityClass = ClassName.bestGuess(ctx.entity().typeName());
        ClassName childClass = ClassName.bestGuess(childEntity.typeName());
        String methodName = "load" + capitalize(assoc.name());

        // Build SELECT SQL for the child by ID
        String childTable = dialect.quoteIdentifier(childEntity.tableName());
        String idCol = childEntity.idColumn();
        String idColumn = dialect.quoteIdentifier(idCol);

        // Build column list from child mapping
        java.util.List<String> columns = new java.util.ArrayList<>();
        TypeDefinition.Prop.Id childIdProp = idProp(childEntity);
        if (childIdProp != null) {
            columns.add(dialect.quoteIdentifier(childIdProp.columnName()) + " AS "
                    + dialect.quoteIdentifier(childIdProp.name()));
        }
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Scalar s) {
                columns.add(dialect.quoteIdentifier(s.columnName()) + " AS "
                        + dialect.quoteIdentifier(s.name()));
            }
        }
        for (TypeDefinition.Prop prop : childEntity.properties()) {
            if (prop instanceof TypeDefinition.Prop.Association childAssoc && childAssoc.fkColumn() != null) {
                columns.add(dialect.quoteIdentifier(childAssoc.fkColumn()) + " AS "
                        + dialect.quoteIdentifier(childAssoc.name()));
            }
        }

        String selectSql = "SELECT " + String.join(", ", columns)
                + " FROM " + childTable
                + " WHERE " + idColumn + " = :childId";
        String constName = ctx.registerConstant("HYDRATE_" + assoc.name().toUpperCase(), selectSql);

        // Derive child row mapper class name
        ClassName childRowMapper = deriveRowMapperType(childEntity.typeName());

        String getter = "get" + Character.toUpperCase(assoc.name().charAt(0)) + assoc.name().substring(1);
        String setter = "set" + Character.toUpperCase(assoc.name().charAt(0)) + assoc.name().substring(1);

        MethodSpec.Builder builder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PRIVATE)
                .addParameter(entityClass, "entity")
                .returns(void.class);

        CodeBlock.Builder body = CodeBlock.builder();
        body.add("if (entity.$L() != null && entity.$L().getId() != null) {\n", getter, getter);
        body.add("    $T full = jdbcClient\n", childClass);
        body.add("        .sql($L)\n", constName);
        body.add("        .param($S, entity.$L().getId())\n", "childId", getter);
        body.add("        .query(new $T())\n", childRowMapper);
        body.add("        .single();\n");
        body.add("    entity.$L(full);\n", setter);
        body.add("}");

        builder.addCode(body.build());
        return builder.build();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Returns the id Prop from a TypeDefinition, or null if none. */
    private static TypeDefinition.Prop.Id idProp(TypeDefinition def) {
        return switch (def) {
            case TypeDefinition.Entity e -> e.id();
            case TypeDefinition.Aggregate a -> a.id();
            default -> null;
        };
    }

    /**
     * Derives the row mapper class name for a given entity/value-object type.
     * Convention: {@code <package>.<SimpleName>RowMapper}.
     */
    private ClassName deriveRowMapperType(String typeFqn) {
        int lastDot = typeFqn.lastIndexOf('.');
        String pkg = lastDot > 0 ? typeFqn.substring(0, lastDot) : "";
        String simple = lastDot > 0 ? typeFqn.substring(lastDot + 1) : typeFqn;
        return ClassName.get(pkg, simple + "RowMapper");
    }

    /**
     * Checks if a type is a primitive or wrapper type that can be directly constructed from a single column value.
     */
    private boolean isPrimitiveOrWrapper(String typeFqn) {
        return switch (typeFqn) {
            case "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                 "java.lang.Float", "java.lang.Boolean", "java.lang.Short", "java.lang.Byte",
                 "java.math.BigDecimal", "java.time.LocalDate", "java.time.LocalDateTime",
                 "java.time.Instant", "java.util.Date" -> true;
            default -> false;
        };
    }
}
